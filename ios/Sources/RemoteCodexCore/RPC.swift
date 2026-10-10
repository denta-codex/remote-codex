import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum RPCFailure: Error, Sendable, Equatable, LocalizedError {
    case notConnected, invalidEndpoint, unauthorized, transport
    case rejected(code: Int, message: String)
    case responseTimedOut, messageTooLarge, staleGeneration, alreadyResponded

    public var errorDescription: String? {
        switch self {
        case .notConnected: return "Connect to Grace first."
        case .invalidEndpoint: return "A secure WSS endpoint is required."
        case .unauthorized: return "Grace rejected the connection credential. Scan the setup QR again."
        case .transport: return "Connection lost. A submitted operation may have been accepted; it was not replayed."
        case .rejected(_, let message): return message
        case .responseTimedOut: return "The server did not confirm the operation. It may have been accepted; it was not replayed."
        case .messageTooLarge: return "The message exceeded the connection limit. Drafts are retained; submitted operations were not replayed."
        case .staleGeneration: return "This request belongs to a previous connection."
        case .alreadyResponded: return "This request is resolved or a response has already been attempted."
        }
    }
}

public struct SessionEvent: Sendable, Equatable {
    public let generation: UInt64
    public let message: JSONValue
    public init(generation: UInt64, message: JSONValue) { self.generation = generation; self.message = message }
}

public protocol RemoteSession: Sendable {
    func connect(endpoint: URL, token: String) async throws -> JSONValue
    func call(_ method: String, params: JSONValue) async throws -> JSONValue
    func respond(id: JSONValue, result: JSONValue, generation: UInt64) async throws
    func respondError(id: JSONValue, code: Int, message: String, generation: UInt64) async throws
    func disconnect() async
    func currentGeneration() async -> UInt64
    func events() async -> AsyncStream<SessionEvent>
}

/// Injection is restricted to construction. Production uses the system WebSocket implementation.
public protocol RPCWebSocketTransport: Sendable {
    func start() async throws
    func send(_ text: String) async throws
    func receive() async throws -> String
    func close() async
}

private final class WebSocketDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    // Refuse redirects rather than forward a bearer credential to a different host.
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

private final class SystemWebSocket: RPCWebSocketTransport, @unchecked Sendable {
    private let session: URLSession
    private let task: URLSessionWebSocketTask

    init(request: URLRequest, maximumMessageSize: Int) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 15
        session = URLSession(configuration: configuration, delegate: WebSocketDelegate(), delegateQueue: nil)
        task = session.webSocketTask(with: request)
        // Apple documents this as cumulative across continuation frames, before a complete message is returned.
        task.maximumMessageSize = maximumMessageSize
    }
    func start() async throws { task.resume() }
    func send(_ text: String) async throws {
        do { try await task.send(.string(text)) } catch { throw sanitized(error) }
    }
    func receive() async throws -> String {
        do {
            switch try await task.receive() {
            case .string(let text): return text
            case .data: throw RPCFailure.transport
            @unknown default: throw RPCFailure.transport
            }
        } catch { throw sanitized(error) }
    }
    func close() async { task.cancel(with: .goingAway, reason: nil); session.invalidateAndCancel() }
    private func sanitized(_ error: Error) -> RPCFailure {
        if let status = (task.response as? HTTPURLResponse)?.statusCode, status == 401 || status == 403 { return .unauthorized }
        if task.closeCode == .messageTooBig || (error as? URLError)?.code == .dataLengthExceedsMaximum { return .messageTooLarge }
        return (error as? RPCFailure) ?? .transport
    }
}

public actor StockRemoteSession: RemoteSession {
    public static let inboundMaximumBytes = 32 * 1024 * 1024
    public static let outboundMaximumBytes = 100 * 1024 * 1024
    public typealias TransportFactory = @Sendable (URLRequest, Int) -> any RPCWebSocketTransport

    private struct Pending {
        let continuation: CheckedContinuation<JSONValue, Error>
        let timeout: Task<Void, Never>
    }
    private let clientVersion: String
    private let allowLoopbackTest: Bool
    private let inboundLimit: Int
    private let outboundLimit: Int
    private let responseTimeout: Duration
    private let factory: TransportFactory
    private var transport: (any RPCWebSocketTransport)?
    private var receiver: Task<Void, Never>?
    private var generation: UInt64 = 0
    private var nextID: Int64 = 0
    private var initialized = false
    private var pending: [JSONValue: Pending] = [:]
    private var serverRequests: [JSONValue: Bool] = [:] // true once resolved or any reply was attempted
    private var eventStream: AsyncStream<SessionEvent>
    private var eventContinuation: AsyncStream<SessionEvent>.Continuation
    private var streamFinished = false
    private let eventCapacity: Int

    public init(clientVersion: String = "0.1.0", allowLoopbackTest: Bool = false,
                inboundMaximumBytes: Int = StockRemoteSession.inboundMaximumBytes,
                outboundMaximumBytes: Int = StockRemoteSession.outboundMaximumBytes,
                responseTimeout: Duration = .seconds(30), eventCapacity: Int = 1024,
                transportFactory: TransportFactory? = nil) {
        precondition(inboundMaximumBytes > 0 && outboundMaximumBytes > 0 && eventCapacity > 0)
        precondition(allowLoopbackTest || (inboundMaximumBytes == Self.inboundMaximumBytes && outboundMaximumBytes == Self.outboundMaximumBytes))
        self.clientVersion = clientVersion
        self.allowLoopbackTest = allowLoopbackTest
        self.inboundLimit = inboundMaximumBytes
        self.outboundLimit = outboundMaximumBytes
        self.responseTimeout = responseTimeout
        self.eventCapacity = eventCapacity
        self.factory = transportFactory ?? { SystemWebSocket(request: $0, maximumMessageSize: $1) }
        let stream = AsyncStream<SessionEvent>.makeStream(bufferingPolicy: .bufferingOldest(eventCapacity))
        self.eventStream = stream.stream
        self.eventContinuation = stream.continuation
    }

    deinit {
        receiver?.cancel()
        eventContinuation.finish()
        let activeSocket = transport
        Task { await activeSocket?.close() }
    }

    public func events() -> AsyncStream<SessionEvent> {
        renewEventsIfNeeded()
        return eventStream
    }
    public func currentGeneration() -> UInt64 { generation }

    public func connect(endpoint: URL, token: String) async throws -> JSONValue {
        guard let components = URLComponents(url: endpoint, resolvingAgainstBaseURL: false),
              components.user == nil, components.password == nil, components.fragment == nil,
              let host = components.host, !host.isEmpty,
              components.scheme == "wss" || (allowLoopbackTest && components.scheme == "ws" && host == "127.0.0.1"),
              !token.isEmpty, !token.contains("\r"), !token.contains("\n") else { throw RPCFailure.invalidEndpoint }
        // Preserve a pre-connect subscription on the first connection.
        if transport != nil {
            let previousGeneration = generation
            await invalidate(.notConnected, expectedGeneration: previousGeneration)
            guard generation == previousGeneration &+ 1, transport == nil else { throw RPCFailure.staleGeneration }
        }
        else { generation &+= 1 }
        renewEventsIfNeeded()
        let epoch = generation
        var request = URLRequest(url: endpoint)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let socket = factory(request, inboundLimit)
        transport = socket
        do {
            try await socket.start()
            guard generation == epoch else { throw RPCFailure.staleGeneration }
            receiver = Task { [weak self] in
                do {
                    while !Task.isCancelled {
                        let text = try await socket.receive()
                        guard let self else { await socket.close(); return }
                        guard try await self.receiveMessage(text, epoch: epoch) else { return }
                    }
                } catch {
                    await self?.invalidate((error as? RPCFailure) ?? .transport, expectedGeneration: epoch)
                }
            }
            let result = try await performCall("initialize", params: .object([
                "clientInfo": .object(["name": .string("remote-codex"), "version": .string(clientVersion)]),
                "capabilities": .object(["experimentalApi": .bool(true)])
            ]), timeout: .seconds(15))
            guard generation == epoch else { throw RPCFailure.staleGeneration }
            try await send(.object(["method": .string("initialized"), "params": .object([:])]), epoch: epoch)
            guard generation == epoch else { throw RPCFailure.staleGeneration }
            initialized = true
            return result
        } catch {
            await invalidate((error as? RPCFailure) ?? .transport, expectedGeneration: epoch)
            throw error
        }
    }

    public func call(_ method: String, params: JSONValue = .object([:])) async throws -> JSONValue {
        guard initialized else { throw RPCFailure.notConnected }
        return try await performCall(method, params: params, timeout: responseTimeout)
    }

    private func performCall(_ method: String, params: JSONValue, timeout: Duration) async throws -> JSONValue {
        guard transport != nil else { throw RPCFailure.notConnected }
        guard nextID < Int64.max else { throw RPCFailure.transport }
        nextID += 1
        let id = JSONValue.integer(nextID)
        let epoch = generation
        let message: JSONValue = .object(["id": id, "method": .string(method), "params": params])
        let text = try encode(message)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                guard !Task.isCancelled else { continuation.resume(throwing: CancellationError()); return }
                let timer = Task { [weak self] in
                    do { try await Task.sleep(for: timeout) } catch { return }
                    await self?.finish(id: id, epoch: epoch, result: .failure(RPCFailure.responseTimedOut))
                }
                pending[id] = Pending(continuation: continuation, timeout: timer)
                Task { [weak self] in await self?.sendRegistered(text, id: id, epoch: epoch) }
            }
        } onCancel: { Task { await self.finish(id: id, epoch: epoch, result: .failure(CancellationError())) } }
    }

    private func sendRegistered(_ text: String, id: JSONValue, epoch: UInt64) async {
        guard generation == epoch, pending[id] != nil, let socket = transport else { return }
        do {
            try await socket.send(text)
            guard generation == epoch else { throw RPCFailure.staleGeneration }
        }
        catch { await invalidate((error as? RPCFailure) ?? .transport, expectedGeneration: epoch) }
    }

    public func respond(id: JSONValue, result: JSONValue, generation: UInt64) async throws {
        try await respondOnce(id: id, message: .object(["id": id, "result": result]), epoch: generation)
    }
    public func respondError(id: JSONValue, code: Int, message: String, generation: UInt64) async throws {
        try await respondOnce(id: id, message: .object(["id": id, "error": .object([
            "code": .integer(Int64(code)), "message": .string(message)
        ])]), epoch: generation)
    }
    private func respondOnce(id: JSONValue, message: JSONValue, epoch: UInt64) async throws {
        guard generation == epoch else { throw RPCFailure.staleGeneration }
        guard serverRequests[id] == false else { throw RPCFailure.alreadyResponded }
        // Reserve before encoding/sending. Delivery can be uncertain after any failed write.
        serverRequests[id] = true
        try await send(message, epoch: epoch)
    }

    private func send(_ message: JSONValue, epoch: UInt64) async throws {
        guard generation == epoch else { throw RPCFailure.staleGeneration }
        guard let socket = transport else { throw RPCFailure.notConnected }
        let text = try encode(message)
        do {
            try await socket.send(text)
            guard generation == epoch else { throw RPCFailure.staleGeneration }
        }
        catch {
            await invalidate((error as? RPCFailure) ?? .transport, expectedGeneration: epoch)
            throw (error as? RPCFailure) ?? RPCFailure.transport
        }
    }
    private func encode(_ message: JSONValue) throws -> String {
        let data = try JSONEncoder().encode(message)
        guard data.count <= outboundLimit else { throw RPCFailure.messageTooLarge }
        return String(decoding: data, as: UTF8.self)
    }

    private func receiveMessage(_ text: String, epoch: UInt64) throws -> Bool {
        guard generation == epoch else { return false }
        guard text.utf8.count <= inboundLimit else { throw RPCFailure.messageTooLarge }
        let message = try JSONDecoder().decode(JSONValue.self, from: Data(text.utf8))
        guard message.objectValue != nil else { throw RPCFailure.transport }
        try dispatch(message, epoch: epoch)
        return true
    }

    private func dispatch(_ message: JSONValue, epoch: UInt64) throws {
        if let method = message["method"]?.stringValue, !method.isEmpty {
            if let id = message["id"] {
                guard id.stringValue != nil || id.integerValue != nil else { throw RPCFailure.transport }
                if serverRequests[id] != nil { return }
                serverRequests[id] = false
            } else if method == "serverRequest/resolved", let id = message["params"]?["requestId"] {
                serverRequests[id] = true
            }
            switch eventContinuation.yield(SessionEvent(generation: epoch, message: message)) {
            case .enqueued: break
            case .dropped, .terminated: throw RPCFailure.transport
            @unknown default: throw RPCFailure.transport
            }
        } else if let id = message["id"] {
            if let error = message["error"]?.objectValue {
                finish(id: id, epoch: epoch, result: .failure(RPCFailure.rejected(
                    code: Int(error["code"]?.integerValue ?? -1), message: error["message"]?.stringValue ?? "Server rejected the request.")))
            } else if let result = message["result"] {
                finish(id: id, epoch: epoch, result: .success(result))
            } else { throw RPCFailure.transport }
        } else { throw RPCFailure.transport }
    }

    private func finish(id: JSONValue, epoch: UInt64, result: Result<JSONValue, Error>) {
        guard generation == epoch, let waiter = pending.removeValue(forKey: id) else { return }
        waiter.timeout.cancel()
        waiter.continuation.resume(with: result)
    }
    public func disconnect() async { await invalidate(.notConnected, expectedGeneration: generation) }

    private func invalidate(_ failure: RPCFailure, expectedGeneration: UInt64) async {
        guard generation == expectedGeneration else { return }
        generation &+= 1
        initialized = false
        let socket = transport
        transport = nil
        receiver?.cancel()
        receiver = nil
        let waiters = pending.values
        pending.removeAll()
        serverRequests.removeAll()
        for waiter in waiters { waiter.timeout.cancel(); waiter.continuation.resume(throwing: failure) }
        if !streamFinished {
            let kind: String = failure == .messageTooLarge ? "messageTooLarge" : (failure == .unauthorized ? "unauthorized" : "disconnected")
            eventContinuation.yield(SessionEvent(generation: expectedGeneration, message: .object([
                "method": .string("connection/closed"), "params": .object(["kind": .string(kind)])
            ])))
            eventContinuation.finish()
            streamFinished = true
        }
        await socket?.close()
    }
    private func renewEventsIfNeeded() {
        guard streamFinished else { return }
        let stream = AsyncStream<SessionEvent>.makeStream(bufferingPolicy: .bufferingOldest(eventCapacity))
        eventStream = stream.stream
        eventContinuation = stream.continuation
        streamFinished = false
    }
}
