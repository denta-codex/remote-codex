import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import RemoteCodexCore
#if canImport(Darwin) && canImport(CryptoKit)
import Darwin
import CryptoKit
#endif

private actor FixtureSocket: RPCWebSocketTransport {
    private var incoming: [String] = []
    private var reader: CheckedContinuation<String, Error>?
    private var closed = false
    private var sent: [JSONValue] = []
    private var watchers: [String: [CheckedContinuation<JSONValue, Never>]] = [:]
    private var failResponse = false
    func start() async throws { closed = false }
    func send(_ text: String) async throws {
        guard !closed else { throw RPCFailure.transport }
        let message = try JSONDecoder().decode(JSONValue.self, from: Data(text.utf8))
        sent.append(message)
        if let method = message["method"]?.stringValue {
            for watcher in watchers.removeValue(forKey: method) ?? [] { watcher.resume(returning: message) }
            if method == "initialize" {
                try push(.object(["id": message["id"]!, "result": .object(["codexHome": .string("/fixture")])]))
            }
        } else if failResponse { throw RPCFailure.transport }
    }
    func receive() async throws -> String {
        if !incoming.isEmpty { return incoming.removeFirst() }
        guard !closed else { throw RPCFailure.transport }
        return try await withCheckedThrowingContinuation { reader = $0 }
    }
    func close() async { closed = true; reader?.resume(throwing: RPCFailure.transport); reader = nil }
    func push(_ value: JSONValue) throws { pushText(String(decoding: try JSONEncoder().encode(value), as: UTF8.self)) }
    func pushText(_ value: String) {
        if let reader { self.reader = nil; reader.resume(returning: value) }
        else { incoming.append(value) }
    }
    func waitForMethod(_ method: String) async -> JSONValue {
        if let match = sent.last(where: { $0["method"]?.stringValue == method }) { return match }
        return await withCheckedContinuation { watchers[method, default: []].append($0) }
    }
    func sentMessages() -> [JSONValue] { sent }
    func failReplies() { failResponse = true }
}

#if canImport(Darwin) && canImport(CryptoKit)
/// Disposable RFC 6455 peer exercising the actual URLSession path and continuation buffering.
/// No credentials, RPC content, or handshake headers are printed.
private final class LoopbackWebSocket: @unchecked Sendable {
    enum Mode { case response(bytes: Int, finalFrame: Bool), outbound(bytes: Int), lostMutationReply, unauthorized }
    enum Failure: Error { case socket, peerClosed, malformed }
    let endpoint: URL
    let completion = XCTestExpectation(description: "Loopback peer completed")
    private let listener: Int32
    private let mode: Mode
    private let lock = NSLock()
    private var peer: Int32 = -1
    private var stopped = false
    private var failure: Error?
    private var lastReceivedBytes = 0

    init(mode: Mode) throws {
        self.mode = mode
        let descriptor = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard descriptor >= 0 else { throw Failure.socket }
        listener = descriptor
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0
        address.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
        let bound = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(descriptor, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0, Darwin.listen(descriptor, 1) == 0 else { Darwin.close(descriptor); throw Failure.socket }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(descriptor, $0, &length) }
        }
        guard named == 0 else { Darwin.close(descriptor); throw Failure.socket }
        endpoint = URL(string: "ws://127.0.0.1:\(UInt16(bigEndian: address.sin_port))/codex/rpc")!
        DispatchQueue.global().async { [self] in
            defer { completion.fulfill() }
            do { try run() } catch { lock.lock(); failure = error; lock.unlock() }
        }
    }
    func stop() {
        lock.lock()
        if stopped { lock.unlock(); return }
        stopped = true
        let activePeer = peer
        lock.unlock()
        if activePeer >= 0 { Darwin.shutdown(activePeer, SHUT_RDWR) }
        Darwin.shutdown(listener, SHUT_RDWR)
        Darwin.close(listener)
    }
    func serverFailure() -> Error? { lock.lock(); defer { lock.unlock() }; return failure }
    private func run() throws {
        let descriptor = Darwin.accept(listener, nil, nil)
        guard descriptor >= 0 else { throw Failure.socket }
        lock.lock(); peer = descriptor; lock.unlock()
        defer { lock.lock(); peer = -1; lock.unlock(); Darwin.close(descriptor) }
        var noSignal: Int32 = 1
        setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, &noSignal, socklen_t(MemoryLayout<Int32>.size))
        var timeout = timeval(tv_sec: 10, tv_usec: 0)
        setsockopt(descriptor, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(descriptor, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        var handshake = Data()
        while !handshake.suffix(4).elementsEqual([13, 10, 13, 10]) {
            guard handshake.count < 16 * 1024 else { throw Failure.malformed }
            handshake.append(try read(descriptor, count: 1))
        }
        if case .unauthorized = mode {
            try write(descriptor, data: Data("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8))
            return
        }
        let lines = String(decoding: handshake, as: UTF8.self).components(separatedBy: "\r\n")
        guard let keyLine = lines.first(where: { $0.lowercased().hasPrefix("sec-websocket-key:") }),
              lines.contains(where: { $0.lowercased() == "authorization: bearer fixture-token" }) else { throw Failure.malformed }
        let key = keyLine.dropFirst("sec-websocket-key:".count).trimmingCharacters(in: .whitespaces)
        let hash = Insecure.SHA1.hash(data: Data((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").utf8))
        let accept = Data(hash).base64EncodedString()
        try write(descriptor, data: Data("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: \(accept)\r\n\r\n".utf8))
        let initialize = try receiveJSON(descriptor)
        guard initialize["method"] == .string("initialize"), let initID = initialize["id"] else { throw Failure.malformed }
        try frame(descriptor, opcode: 1, final: true, data: JSONEncoder().encode(JSONValue.object([
            "id": initID, "result": .object(["codexHome": .string("/fixture")])
        ])))
        let initialized = try receiveJSON(descriptor)
        guard initialized["method"] == .string("initialized") else { throw Failure.malformed }
        let request = try receiveJSON(descriptor, maximumBytes: StockRemoteSession.outboundMaximumBytes)
        if case .outbound(let expectedBytes) = mode {
            guard request["method"] == .string("fixture/send"), let requestID = request["id"], lastReceivedBytes == expectedBytes else { throw Failure.malformed }
            try frame(descriptor, opcode: 1, final: true, data: JSONEncoder().encode(JSONValue.object([
                "id": requestID, "result": .object(["bytes": .integer(Int64(lastReceivedBytes))])
            ])))
            _ = try? read(descriptor, count: 1)
            return
        }
        if case .lostMutationReply = mode {
            guard request["method"] == .string("turn/start") else { throw Failure.malformed }
            return // Accepted exactly one message, then connection ends without its acknowledgement.
        }
        guard request["method"] == .string("fixture/read"), let requestID = request["id"] else { throw Failure.malformed }
        guard case .response(let bytes, let finalFrame) = mode else { throw Failure.malformed }
        let encodedID = String(decoding: try JSONEncoder().encode(requestID), as: UTF8.self)
        let prefix = Data("{\"id\":\(encodedID),\"result\":\"".utf8)
        let suffix = Data("\"}".utf8)
        var payload = prefix
        payload.append(Data(repeating: 97, count: bytes - prefix.count - suffix.count))
        payload.append(suffix)
        let boundary = payload.count / 2
        try frame(descriptor, opcode: 1, final: false, data: payload.prefix(boundary))
        try frame(descriptor, opcode: 0, final: finalFrame, data: payload.suffix(payload.count - boundary))
        // Keep the socket open until the client closes; the unfinished oversize case must fail before FIN.
        _ = try? read(descriptor, count: 1)
    }
    private func receiveJSON(_ descriptor: Int32, maximumBytes: Int = 1024 * 1024) throws -> JSONValue {
        var message = Data()
        while true {
            let header = try read(descriptor, count: 2)
            let opcode = header[0] & 15
            if opcode == 8 { throw Failure.peerClosed }
            var length = UInt64(header[1] & 127)
            if length == 126 { length = try read(descriptor, count: 2).reduce(0) { ($0 << 8) | UInt64($1) } }
            else if length == 127 { length = try read(descriptor, count: 8).reduce(0) { ($0 << 8) | UInt64($1) } }
            guard length <= UInt64(maximumBytes), message.count <= maximumBytes - Int(length) else { throw Failure.malformed }
            let masked = (header[1] & 128) != 0
            let mask = masked ? try read(descriptor, count: 4) : Data()
            var payload = try read(descriptor, count: Int(length))
            if masked {
                let maskBytes = Array(mask)
                payload.withUnsafeMutableBytes { (buffer: UnsafeMutableRawBufferPointer) in
                    // Avoid Data indexing overhead while unmasking the production 100 MiB boundary.
                    let wordMask = UInt64(maskBytes[0]) | UInt64(maskBytes[1]) << 8 | UInt64(maskBytes[2]) << 16 | UInt64(maskBytes[3]) << 24
                    let repeatedMask = (wordMask | wordMask << 32).littleEndian
                    var offset = 0
                    while offset + 8 <= buffer.count {
                        let word = buffer.loadUnaligned(fromByteOffset: offset, as: UInt64.self)
                        buffer.storeBytes(of: word ^ repeatedMask, toByteOffset: offset, as: UInt64.self)
                        offset += 8
                    }
                    while offset < buffer.count { buffer[offset] ^= maskBytes[offset % 4]; offset += 1 }
                }
            }
            if opcode == 9 { try frame(descriptor, opcode: 10, final: true, data: payload); continue }
            guard opcode == 0 || opcode == 1 else { throw Failure.malformed }
            message.append(payload)
            if header[0] & 128 != 0 {
                lastReceivedBytes = message.count
                return try JSONDecoder().decode(JSONValue.self, from: message)
            }
        }
    }
    private func frame(_ descriptor: Int32, opcode: UInt8, final: Bool, data: Data) throws {
        var header = Data([opcode | (final ? 128 : 0)])
        if data.count < 126 { header.append(UInt8(data.count)) }
        else if data.count <= Int(UInt16.max) {
            header.append(126); var count = UInt16(data.count).bigEndian
            withUnsafeBytes(of: &count) { header.append(contentsOf: $0) }
        } else {
            header.append(127); var count = UInt64(data.count).bigEndian
            withUnsafeBytes(of: &count) { header.append(contentsOf: $0) }
        }
        try write(descriptor, data: header)
        try write(descriptor, data: data)
    }
    private func read(_ descriptor: Int32, count: Int) throws -> Data {
        var data = Data(count: count)
        try data.withUnsafeMutableBytes { buffer in
            var offset = 0
            while offset < count {
                let size = Darwin.recv(descriptor, buffer.baseAddress!.advanced(by: offset), count - offset, 0)
                guard size > 0 else { throw Failure.peerClosed }
                offset += size
            }
        }
        return data
    }
    private func write(_ descriptor: Int32, data: Data) throws {
        try data.withUnsafeBytes { buffer in
            var offset = 0
            while offset < data.count {
                let size = Darwin.send(descriptor, buffer.baseAddress!.advanced(by: offset), data.count - offset, 0)
                guard size > 0 else { throw Failure.peerClosed }
                offset += size
            }
        }
    }
}

extension RPCTests {
    func testRealWebSocketOutboundAtProductionLimitAndRejectsOneByteOver() async throws {
        let server = try LoopbackWebSocket(mode: .outbound(bytes: StockRemoteSession.outboundMaximumBytes))
        defer { server.stop() }
        let session = StockRemoteSession(allowLoopbackTest: true, responseTimeout: .seconds(60))
        _ = try await session.connect(endpoint: server.endpoint, token: "fixture-token")
        let emptyMessage: JSONValue = .object(["id": .integer(2), "method": .string("fixture/send"), "params": .object(["text": .string("")])])
        let overhead = try JSONEncoder().encode(emptyMessage).count
        let text = String(repeating: "a", count: StockRemoteSession.outboundMaximumBytes - overhead)
        // A rejected call consumes ID 2 but transmits nothing; ID 3 has identical encoded length.
        await assertFailure(.messageTooLarge) {
            _ = try await session.call("fixture/send", params: .object(["text": .string(text + "a")]))
        }
        let result = try await session.call("fixture/send", params: .object(["text": .string(text)]))
        XCTAssertEqual(result["bytes"], .integer(Int64(StockRemoteSession.outboundMaximumBytes)))
        await session.disconnect()
        await fulfillment(of: [server.completion], timeout: 12)
        XCTAssertNil(server.serverFailure())
    }
    func testRealWebSocketMutationWithLostReplyIsNeverReplayed() async throws {
        let server = try LoopbackWebSocket(mode: .lostMutationReply)
        defer { server.stop() }
        let session = StockRemoteSession(allowLoopbackTest: true)
        _ = try await session.connect(endpoint: server.endpoint, token: "fixture-token")
        await assertFailure(.transport) { _ = try await session.call("turn/start", params: .object([:])) }
        await assertFailure(.notConnected) { _ = try await session.call("turn/start", params: .object([:])) }
        await session.disconnect()
        await fulfillment(of: [server.completion], timeout: 12)
        XCTAssertNil(server.serverFailure())
    }
    func testRealWebSocketFragmentedMessageAtProductionLimit() async throws {
        let server = try LoopbackWebSocket(mode: .response(bytes: StockRemoteSession.inboundMaximumBytes, finalFrame: true))
        defer { server.stop() }
        let session = StockRemoteSession(allowLoopbackTest: true)
        _ = try await session.connect(endpoint: server.endpoint, token: "fixture-token")
        let result = try await session.call("fixture/read", params: .object([:]))
        XCTAssertGreaterThan(result.stringValue?.utf8.count ?? 0, 31 * 1024 * 1024)
        await session.disconnect()
        await fulfillment(of: [server.completion], timeout: 12)
        XCTAssertNil(server.serverFailure())
    }
    func testRealWebSocketRejectsOversizedContinuationBeforeFinalFrame() async throws {
        let server = try LoopbackWebSocket(mode: .response(bytes: StockRemoteSession.inboundMaximumBytes + 1, finalFrame: false))
        defer { server.stop() }
        let session = StockRemoteSession(allowLoopbackTest: true, responseTimeout: .seconds(12))
        _ = try await session.connect(endpoint: server.endpoint, token: "fixture-token")
        // Receiving must fail before the peer sends FIN; a timeout would expose a broken cumulative limit.
        await assertFailure(.messageTooLarge) { _ = try await session.call("fixture/read", params: .object([:])) }
        await session.disconnect()
        await fulfillment(of: [server.completion], timeout: 12)
    }
    func testRealWebSocketUnauthorizedHandshake() async throws {
        let server = try LoopbackWebSocket(mode: .unauthorized)
        defer { server.stop() }
        let session = StockRemoteSession(allowLoopbackTest: true)
        await assertFailure(.unauthorized) { _ = try await session.connect(endpoint: server.endpoint, token: "fixture-token") }
        await session.disconnect()
        await fulfillment(of: [server.completion], timeout: 12)
    }
}
#endif

private final class RequestCapture: @unchecked Sendable {
    private let lock = NSLock()
    private var request: URLRequest?
    private var maximum: Int?
    func record(_ value: URLRequest, limit: Int) { lock.lock(); defer { lock.unlock() }; request = value; maximum = limit }
    func snapshot() -> (URLRequest?, Int?) { lock.lock(); defer { lock.unlock() }; return (request, maximum) }
}

final class RPCTests: XCTestCase {
    private let endpoint = URL(string: "wss://fixture.invalid/codex/rpc")!
    private func session(_ socket: FixtureSocket, timeout: Duration = .seconds(2), limit: Int = StockRemoteSession.inboundMaximumBytes,
                         outbound: Int = StockRemoteSession.outboundMaximumBytes, capacity: Int = 1024) -> StockRemoteSession {
        StockRemoteSession(allowLoopbackTest: true, inboundMaximumBytes: limit, outboundMaximumBytes: outbound,
                           responseTimeout: timeout, eventCapacity: capacity, transportFactory: { _, _ in socket })
    }
    private func assertFailure(_ expected: RPCFailure, operation: () async throws -> Void,
                               file: StaticString = #filePath, line: UInt = #line) async {
        do { try await operation(); XCTFail("Expected RPCFailure", file: file, line: line) }
        catch { XCTAssertEqual(error as? RPCFailure, expected, file: file, line: line) }
    }
    func testInitializeContractAndAuthorizationHeader() async throws {
        let socket = FixtureSocket()
        let capture = RequestCapture()
        let session = StockRemoteSession(clientVersion: "0.1.7", transportFactory: { request, maximum in
            capture.record(request, limit: maximum); return socket
        })
        let result = try await session.connect(endpoint: endpoint, token: "fixture-token")
        XCTAssertEqual(result["codexHome"], .string("/fixture"))
        let initialize = await socket.waitForMethod("initialize")
        XCTAssertEqual(initialize["params"]?["clientInfo"]?["version"], .string("0.1.7"))
        XCTAssertEqual(initialize["params"]?["capabilities"]?["experimentalApi"], .bool(true))
        _ = await socket.waitForMethod("initialized")
        let snapshot = capture.snapshot()
        XCTAssertEqual(snapshot.0?.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-token")
        XCTAssertEqual(snapshot.1, 32 * 1024 * 1024)
        await session.disconnect()
    }
    func testWSSRequiredAndFixtureLoopbackIsExplicit() async {
        let session = StockRemoteSession()
        for value in ["ws://127.0.0.1:9123", "ws://example.com", "wss://user:secret@example.com", "wss://example.com/#fragment"] {
            await assertFailure(.invalidEndpoint) { _ = try await session.connect(endpoint: URL(string: value)!, token: "fixture") }
        }
    }
    func testServerRequestIDCollisionCannotCompleteClientCall() async throws {
        let socket = FixtureSocket(); let session = session(socket)
        let stream = await session.events(); var events = stream.makeAsyncIterator()
        _ = try await session.connect(endpoint: endpoint, token: "fixture")
        let call = Task { try await session.call("turn/start", params: .object([:])) }
        let sent = await socket.waitForMethod("turn/start")
        let request: JSONValue = .object(["id": sent["id"]!, "method": .string("item/commandExecution/requestApproval"), "params": .object([:])])
        try await socket.push(request)
        let event = await events.next()
        XCTAssertEqual(event?.message, request)
        try await socket.push(.object(["id": sent["id"]!, "result": .object(["turn": .string("accepted")])]))
        let result = try await call.value
        XCTAssertEqual(result["turn"], .string("accepted"))
        try await session.respond(id: sent["id"]!, result: .object(["decision": .string("accept")]), generation: event!.generation)
        await assertFailure(.alreadyResponded) { try await session.respond(id: sent["id"]!, result: .object([:]), generation: event!.generation) }
        await session.disconnect()
    }
    func testStringIDDoesNotCompleteNumericCall() async throws {
        let socket = FixtureSocket(); let session = session(socket)
        _ = try await session.connect(endpoint: endpoint, token: "fixture")
        let call = Task { try await session.call("thread/list", params: .object([:])) }
        let sent = await socket.waitForMethod("thread/list")
        try await socket.push(.object(["id": .string(String(sent["id"]!.integerValue!)), "result": .string("wrong")]))
        try await socket.push(.object(["id": sent["id"]!, "result": .string("right")]))
        let value = try await call.value
        XCTAssertEqual(value, .string("right"))
        await session.disconnect()
    }
    func testResolvedAndStaleApprovalsCannotBeAnswered() async throws {
        let socket = FixtureSocket(); let session = session(socket)
        var events = await session.events().makeAsyncIterator()
        _ = try await session.connect(endpoint: endpoint, token: "fixture")
        let id = JSONValue.string("approval-one")
        try await socket.push(.object(["id": id, "method": .string("item/permissions/requestApproval"), "params": .object([:])]))
        let request = await events.next()!
        try await socket.push(.object(["method": .string("serverRequest/resolved"), "params": .object(["requestId": id])]))
        _ = await events.next()
        await assertFailure(.alreadyResponded) { try await session.respond(id: id, result: .object([:]), generation: request.generation) }
        await session.disconnect()
        await assertFailure(.staleGeneration) { try await session.respond(id: id, result: .object([:]), generation: request.generation) }
    }
    func testFailedApprovalReplyIsAttemptedOnlyOnce() async throws {
        let socket = FixtureSocket(); let session = session(socket)
        var events = await session.events().makeAsyncIterator()
        _ = try await session.connect(endpoint: endpoint, token: "fixture")
        let id = JSONValue.integer(44)
        try await socket.push(.object(["id": id, "method": .string("item/commandExecution/requestApproval"), "params": .object([:])]))
        let request = await events.next()!
        await socket.failReplies()
        await assertFailure(.transport) { try await session.respond(id: id, result: .object([:]), generation: request.generation) }
        await assertFailure(.staleGeneration) { try await session.respond(id: id, result: .object([:]), generation: request.generation) }
        let replies = await socket.sentMessages().filter { $0["id"] == id && $0["method"] == nil }
        XCTAssertEqual(replies.count, 1)
    }
    func testUnconfirmedMutationTimesOutWithoutReplay() async throws {
        let socket = FixtureSocket(); let session = session(socket, timeout: .milliseconds(40))
        _ = try await session.connect(endpoint: endpoint, token: "fixture")
        await assertFailure(.responseTimedOut) { _ = try await session.call("turn/start", params: .object([:])) }
        let sent = await socket.sentMessages().filter { $0["method"] == .string("turn/start") }
        XCTAssertEqual(sent.count, 1)
        await session.disconnect()
    }
    func testInboundLimitInvalidatesSessionAndPendingCall() async throws {
        let socket = FixtureSocket(); let session = session(socket, limit: 256)
        _ = try await session.connect(endpoint: endpoint, token: "fixture")
        let call = Task { try await session.call("thread/read", params: .object([:])) }
        _ = await socket.waitForMethod("thread/read")
        await socket.pushText(String(repeating: "x", count: 257))
        await assertFailure(.messageTooLarge) { _ = try await call.value }
        await assertFailure(.notConnected) { _ = try await session.call("thread/list", params: .object([:])) }
    }
    func testOutboundLimitRejectsBeforeTransmission() async throws {
        let socket = FixtureSocket(); let session = session(socket, outbound: 256)
        _ = try await session.connect(endpoint: endpoint, token: "fixture")
        await assertFailure(.messageTooLarge) { _ = try await session.call("turn/start", params: .object(["text": .string(String(repeating: "💬", count: 100))])) }
        let sent = await socket.sentMessages().filter { $0["method"] == .string("turn/start") }
        XCTAssertTrue(sent.isEmpty)
        await session.disconnect()
    }
    func testEventOverflowClosesInsteadOfContinuingWithMissingEvents() async throws {
        let socket = FixtureSocket(); let session = session(socket, capacity: 1)
        let stream = await session.events()
        _ = try await session.connect(endpoint: endpoint, token: "fixture")
        let call = Task { try await session.call("thread/read", params: .object([:])) }
        _ = await socket.waitForMethod("thread/read")
        try await socket.push(.object(["method": .string("turn/started")]))
        try await socket.push(.object(["method": .string("turn/completed")]))
        await assertFailure(.transport) { _ = try await call.value }
        var events = stream.makeAsyncIterator()
        let first = await events.next(); let end = await events.next()
        XCTAssertEqual(first?.message["method"], .string("turn/started"))
        XCTAssertNil(end)
    }
}
