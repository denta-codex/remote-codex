import Foundation
import SwiftUI
import RemoteCodexCore

struct ChatRow: Identifiable {
    let id: String
    let title: String
    let preview: String
}
struct QueueRow: Identifiable {
    let id: String
    let text: String
    let clientID: String
}
private struct AppOperation: Codable {
    let id: String
    var method: String
    var params: JSONValue
    let draft: String
    var threadID: String?
    var directory: String?
    var attempted = false
}
private actor FixtureCredentials: CredentialStore {
    private var value: String?
    func getToken() async throws -> String? { value }
    func setToken(_ token: String?) async throws { value = token }
}

@MainActor
final class AppController: ObservableObject {
    static let endpoint = URL(string: "wss://grace.taila198f.ts.net/codex/rpc")!
    @Published var connected = false
    @Published var connecting = false
    @Published var account = ""
    @Published var chats: [ChatRow] = []
    @Published var selectedID: String?
    @Published var selectedTitle = "New chat"
    @Published var timeline = TimelineState()
    @Published var approvals = ApprovalState()
    @Published var queue: [QueueRow] = []
    @Published var draft = ""
    @Published var error: String?
    @Published var busy = false
    @Published var loading = false
    @Published var historyNeedsRecovery = false
    @Published var hasMoreHistory = false
    @Published var listCursor: String?
    @Published var pendingOperation = false
    @Published var tokenInput = ""
    let fixture = ProcessInfo.processInfo.arguments.contains("--fixture")
    private let session = StockRemoteSession()
    private var store: SQLiteClientStore?
    private var generation: UInt64 = 0
    private var selection: UInt64 = 0
    private var streamTask: Task<Void, Never>?
    private var operation: AppOperation?
    private var recovery: SmallerHistoryRecovery?
    private var started = false
    private var buffered: [JSONValue] = []

    func start() async {
        guard !started else { return }
        started = true
        do {
            let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent(fixture ? "RemoteCodexFixture" : "RemoteCodex", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let credentials: any CredentialStore = fixture ? FixtureCredentials() : KeychainCredentials()
            store = try SQLiteClientStore(url: directory.appendingPathComponent("client.sqlite"), credentials: credentials)
            if let data = try await store?.get("operation") {
                operation = try JSONDecoder().decode(AppOperation.self, from: data)
                pendingOperation = true
            }
            draft = await readDraft("new")
            if fixture {
                connected = true; account = "Fixture account"
                chats = [ChatRow(id: "fixture-chat", title: "Fixture conversation", preview: "Synthetic chat for local testing")]
            } else if let token = try await store?.token() {
                await connect(token: token, save: false)
            }
        } catch { self.error = "Local storage could not be opened. \(error.localizedDescription)" }
    }

    func setup(_ value: String) async {
        guard store != nil else { error = "Local storage is unavailable. Restart before pairing."; return }
        let candidate = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let token = Pairing.parseSetupQR(candidate) ?? candidate
        guard token.count == 64, token.allSatisfy({ "0123456789abcdef".contains($0) }) else {
            error = "Use the setup QR or its 64-character transport token."; return
        }
        await connect(token: token, save: true)
        if connected { tokenInput = "" }
    }

    private func connect(token: String, save: Bool) async {
        guard !fixture, !connecting else { return }
        connecting = true; connected = false; error = nil
        streamTask?.cancel()
        do {
            _ = try await session.connect(endpoint: Self.endpoint, token: token)
            generation = await session.currentGeneration()
            let response = try await session.call("account/read", params: .object(["refreshToken": .bool(false)]))
            guard let accountData = response["account"], accountData != .null else {
                throw ClientError("Sign in to Codex on Grace before connecting.")
            }
            account = accountData["email"]?.stringValue ?? accountData["type"]?.stringValue ?? "Connected account"
            if save { try await store?.saveToken(token) }
            connected = true
            approvals.select(threadID: selectedID, generation: generation)
            let events = await session.events()
            streamTask = Task { [weak self] in
                for await event in events { await self?.receive(event) }
            }
            await refreshChats()
            if let selectedID { await openChat(selectedID, title: selectedTitle) }
            await reconcile()
        } catch {
            await session.disconnect()
            self.error = "Connection failed. \(error.localizedDescription)"
        }
        connecting = false
    }

    func disconnect() async {
        streamTask?.cancel(); await session.disconnect()
        connected = false; account = ""; approvals = ApprovalState()
    }
    func forgetToken() async {
        await disconnect()
        do { try await store?.saveToken(nil) } catch { self.error = error.localizedDescription }
    }
    func reconnect() async {
        do {
            guard let token = try await store?.token() else { return }
            await connect(token: token, save: false)
        } catch { self.error = error.localizedDescription }
    }

    func refreshChats(more: Bool = false) async {
        guard connected, !fixture, !loading else { return }
        let captured = generation
        loading = true; defer { loading = false }
        do {
            var params: [String: JSONValue] = ["limit": .integer(30), "archived": .bool(false),
                "modelProviders": .array([]), "sortKey": .string("updated_at"), "sortDirection": .string("desc")]
            if more, let listCursor { params["cursor"] = .string(listCursor) }
            let page = try await session.call("thread/list", params: .object(params))
            guard captured == generation else { return }
            let rows = (page["data"]?.arrayValue ?? []).compactMap { row -> ChatRow? in
                guard let id = row["id"]?.stringValue else { return nil }
                let preview = row["preview"]?.stringValue ?? ""
                return ChatRow(id: id, title: row["name"]?.stringValue ?? String(preview.prefix(70)), preview: preview)
            }
            chats = more ? chats + rows.filter { next in !chats.contains { $0.id == next.id } } : rows
            let cursor = page["nextCursor"]?.stringValue
            if more, cursor != nil, cursor == listCursor { throw ClientError("The server repeated a page. Refresh chats to continue.") }
            listCursor = cursor
        } catch { self.error = error.localizedDescription }
    }

    func newChat() async {
        await persistDraft()
        selection &+= 1; selectedID = nil; selectedTitle = "New chat"
        timeline.clear(); queue = []; hasMoreHistory = false; historyNeedsRecovery = false
        approvals.select(threadID: nil, generation: generation)
        draft = await readDraft("new")
    }

    func openChat(_ id: String, title: String) async {
        await persistDraft()
        selection &+= 1
        let revision = selection, captured = generation
        selectedID = id; selectedTitle = title; timeline.clear(); queue = []
        approvals.select(threadID: id, generation: generation)
        draft = await readDraft(id); buffered = []; loading = true
        historyNeedsRecovery = false; hasMoreHistory = false
        if fixture {
            timeline.hydrate(turns: [.object(["id": .string("fixture-turn"), "status": .string("completed"),
                "items": .array([.object(["id": .string("fixture-message"), "type": .string("agentMessage"),
                    "text": .string("This conversation contains synthetic content only.")])])])], bufferedEvents: [])
            loading = false; return
        }
        guard connected else { loading = false; return }
        do {
            let response = try await session.call("thread/resume", params: TimelineState.resumeParameters(threadID: id))
            guard revision == selection, captured == generation else { return }
            approvals.recordResume(response, generation: captured)
            let page: JSONValue
            if let initial = response["initialTurnsPage"] { page = initial }
            else { page = try await session.call("thread/turns/list", params: TimelineState.olderParameters(threadID: id, cursor: nil)) }
            guard revision == selection, captured == generation else { return }
            timeline.hydrate(turns: (page["data"]?.arrayValue ?? []).reversed(), bufferedEvents: buffered)
            timeline.historyCursor = page["nextCursor"]?.stringValue
            hasMoreHistory = timeline.historyCursor != nil
            recovery = nil
            await refreshQueue()
        } catch {
            if revision == selection {
                historyNeedsRecovery = true
                recovery = SmallerHistoryRecovery(threadID: id)
                self.error = "History could not be loaded. Use smaller history pages to recover."
            }
        }
        if revision == selection { loading = false; buffered = [] }
    }

    func loadHistory(smaller: Bool = false) async {
        guard let id = selectedID, connected, !loading, !fixture else { return }
        loading = true; defer { loading = false }
        let revision = selection, captured = generation
        do {
            if smaller || recovery != nil {
                if recovery == nil { recovery = SmallerHistoryRecovery(threadID: id) }
                var reader = recovery!
                if smaller { reader.smallerPages() }
                let rpc = session
                let turns = try await reader.next { method, params in try await rpc.call(method, params: params) }
                guard revision == selection, captured == generation else { return }
                recovery = reader; timeline.snapshot(turns: turns, prepend: true)
                hasMoreHistory = reader.hasMore; historyNeedsRecovery = false
            } else {
                guard let cursor = timeline.historyCursor else { return }
                let page = try await session.call("thread/turns/list", params: .object(["threadId": .string(id),
                    "limit": .integer(10), "itemsView": .string("full"), "sortDirection": .string("desc"), "cursor": .string(cursor)]))
                guard revision == selection, captured == generation else { return }
                timeline.snapshot(turns: (page["data"]?.arrayValue ?? []).reversed(), prepend: true)
                let next = page["nextCursor"]?.stringValue
                guard next == nil || next != cursor else { throw ClientError("The server repeated a history page.") }
                timeline.historyCursor = next; hasMoreHistory = next != nil
            }
        } catch { historyNeedsRecovery = true; self.error = error.localizedDescription }
    }

    func persistDraft() async {
        do { try await store?.put(Data(draft.utf8), for: "draft/\(selectedID ?? "new")") }
        catch { self.error = "Draft could not be saved. \(error.localizedDescription)" }
    }
    private func readDraft(_ key: String) async -> String {
        do { return try await store?.get("draft/\(key)").flatMap { String(data: $0, encoding: .utf8) } ?? "" }
        catch { self.error = error.localizedDescription; return "" }
    }

    func send() async {
        guard connected, !busy, !pendingOperation, !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        busy = true; defer { busy = false }
        await persistDraft()
        if fixture {
            if selectedID == nil { selectedID = "fixture-chat"; selectedTitle = "Fixture conversation" }
            let text = draft; draft = ""; await persistDraft()
            let turnID = UUID().uuidString
            timeline.snapshot(turns: [.object(["id": .string(turnID), "status": .string("completed"), "items": .array([
                .object(["id": .string("user-\(turnID)"), "type": .string("userMessage"), "content": .array([.object(["type": .string("text"), "text": .string(text)])])]),
                .object(["id": .string("agent-\(turnID)"), "type": .string("agentMessage"), "text": .string("Fixture reply: \(text)")])])])])
            return
        }
        do {
            let operationID = UUID().uuidString.lowercased()
            if selectedID == nil {
                let workspace = try ProjectlessWorkspace(operationID: operationID)
                var preparation = AppOperation(id: operationID, method: "command/exec", params: workspace.prepareParameters,
                    draft: draft, directory: workspace.directory)
                let prepared = try await dispatch(&preparation)
                guard prepared["exitCode"]?.integerValue == 0 else {
                    try await finishOperation()
                    throw ClientError("The conversation directory could not be prepared.")
                }
                preparation.method = "thread/start"; preparation.params = workspace.threadStartParameters; preparation.attempted = false
                let result = try await dispatch(&preparation)
                guard let id = result["thread"]?["id"]?.stringValue else { throw ClientError("New chat did not include its ID.") }
                selectedID = id; selectedTitle = String(draft.prefix(70)); preparation.threadID = id
                approvals.select(threadID: id, generation: generation)
                try await store?.put(Data(draft.utf8), for: "draft/\(id)")
            }
            guard let id = selectedID else { return }
            let input: JSONValue = .array([.object(["type": .string("text"), "text": .string(draft), "text_elements": .array([])])])
            let queued = timeline.activeTurnID != nil || !queue.isEmpty
            var sending = AppOperation(id: operationID, method: queued ? "thread/queue/add" : "turn/start",
                params: .object(["threadId": .string(id), "input": input, "clientUserMessageId": .string(operationID)]),
                draft: draft, threadID: id)
            let result = try await dispatch(&sending)
            if let turn = result["turn"] { timeline.snapshot(turns: [turn]) }
            try await finishOperation()
            draft = ""; await persistDraft(); try await store?.remove("draft/new")
            await refreshQueue(); await refreshChats()
        } catch { self.error = "The operation may have reached Grace. Reconcile before sending again. \(error.localizedDescription)" }
    }

    private func dispatch(_ next: inout AppOperation) async throws -> JSONValue {
        guard let store else { throw ClientError("Local storage is unavailable; nothing was sent.") }
        guard !next.attempted else { throw ClientError("This operation has already been attempted.") }
        next.attempted = true; operation = next; pendingOperation = true
        try await store.put(try JSONEncoder().encode(next), for: "operation")
        let captured = generation
        let result = try await session.call(next.method, params: next.params)
        guard captured == generation else { throw ClientError("Connection changed before confirmation.") }
        return result
    }
    private func finishOperation() async throws {
        guard let store else { throw ClientError("Local storage is unavailable.") }
        try await store.remove("operation"); operation = nil; pendingOperation = false
    }
    func abandonOperation() async {
        do { try await finishOperation() } catch { self.error = error.localizedDescription }
    }
    func reconcile() async {
        guard let operation, connected, !fixture else { return }
        do {
            if operation.method == "command/exec" || operation.method == "thread/start" {
                if let cwd = operation.directory {
                    let page = try await session.call("thread/list", params: .object(["cwd": .string(cwd), "limit": .integer(100), "modelProviders": .array([])]))
                    let matches = page["data"]?.arrayValue ?? []
                    if matches.count == 1, let id = matches[0]["id"]?.stringValue {
                        try await store?.put(Data(operation.draft.utf8), for: "draft/\(id)")
                        await openChat(id, title: "Recovered chat")
                        try await finishOperation(); error = "Chat recovered. Your draft is retained; review it before sending."
                        return
                    }
                }
            } else if let id = operation.threadID {
                var accepted = false
                if operation.method == "thread/queue/delete" || operation.method == "thread/queue/add" {
                    var cursor: String?, seen = Set<String>(), data: [JSONValue] = []
                    repeat {
                        var params: [String: JSONValue] = ["threadId": .string(id), "limit": .integer(100)]
                        if let cursor { params["cursor"] = .string(cursor) }
                        let page = try await session.call("thread/queue/list", params: .object(params))
                        guard let rows = page["data"]?.arrayValue else { throw ClientError("Queue response was incomplete.") }
                        data += rows; cursor = page["nextCursor"]?.stringValue
                        if let cursor, !seen.insert(cursor).inserted { throw ClientError("The server repeated a queue page.") }
                    } while cursor != nil
                    accepted = operation.method == "thread/queue/delete"
                        ? !data.contains { $0["id"] == operation.params["queuedSubmissionId"] }
                        : data.contains { containsClientID($0, operation.id) }
                }
                if !accepted && operation.method != "thread/queue/delete" {
                    let page = try await session.call("thread/turns/list", params: .object(["threadId": .string(id), "limit": .integer(100), "sortDirection": .string("desc"), "itemsView": .string("full")]))
                    let data = page["data"]?.arrayValue ?? []
                    if operation.method == "turn/interrupt" {
                        accepted = data.contains { $0["id"] == operation.params["turnId"] && $0["status"]?.stringValue != nil && $0["status"]?.stringValue != "inProgress" }
                    } else { accepted = data.contains { containsClientID($0, operation.id) } }
                }
                if accepted {
                    try await finishOperation()
                    if draft == operation.draft && ["turn/start", "thread/queue/add"].contains(operation.method) { draft = ""; await persistDraft() }
                    await openChat(id, title: selectedTitle)
                    return
                }
            }
            error = "Grace has not confirmed this operation. Your draft is retained. No operation was replayed."
        } catch { self.error = "Could not reconcile the operation. \(error.localizedDescription)" }
    }
    private func containsClientID(_ value: JSONValue, _ id: String) -> Bool {
        if value["clientUserMessageId"]?.stringValue == id { return true }
        if let values = value.arrayValue { return values.contains { containsClientID($0, id) } }
        if let values = value.objectValue { return values.values.contains { containsClientID($0, id) } }
        return false
    }

    func stop() async {
        guard let thread = selectedID, let turn = timeline.activeTurnID, !busy, !pendingOperation else { return }
        busy = true; defer { busy = false }
        do {
            var record = AppOperation(id: UUID().uuidString, method: "turn/interrupt",
                params: .object(["threadId": .string(thread), "turnId": .string(turn)]), draft: draft, threadID: thread)
            _ = try await dispatch(&record); try await finishOperation()
        } catch { self.error = "Stop could not be confirmed. Reconcile before trying again." }
    }
    func refreshQueue() async {
        guard let id = selectedID, connected, !fixture else { return }
        let captured = generation, revision = selection
        do {
            var cursor: String?, seen = Set<String>(), rows: [QueueRow] = []
            repeat {
                var params: [String: JSONValue] = ["threadId": .string(id), "limit": .integer(100)]
                if let cursor { params["cursor"] = .string(cursor) }
                let page = try await session.call("thread/queue/list", params: .object(params))
                rows += (page["data"]?.arrayValue ?? []).compactMap { row in
                    guard let id = row["id"]?.stringValue else { return nil }
                    return QueueRow(id: id, text: (row["input"]?.arrayValue ?? []).compactMap { $0["text"]?.stringValue }.joined(separator: "\n"), clientID: row["clientUserMessageId"]?.stringValue ?? "")
                }
                cursor = page["nextCursor"]?.stringValue
                if let cursor, !seen.insert(cursor).inserted { throw ClientError("The server repeated a queue page.") }
            } while cursor != nil
            guard captured == generation, revision == selection else { return }
            queue = rows
        } catch { self.error = "Queue could not be refreshed. \(error.localizedDescription)" }
    }
    func queueAction(_ item: QueueRow, start: Bool) async {
        guard let id = selectedID, connected, !busy, !pendingOperation, !start || timeline.activeTurnID == nil else { return }
        busy = true; defer { busy = false }
        do {
            var record = AppOperation(id: start ? item.clientID : UUID().uuidString, method: start ? "thread/queue/start" : "thread/queue/delete",
                params: .object(["threadId": .string(id), "queuedSubmissionId": .string(item.id)]), draft: draft, threadID: id)
            _ = try await dispatch(&record); try await finishOperation(); await refreshQueue()
        } catch { self.error = "Queue operation could not be confirmed. Reconcile before trying again." }
    }

    func answer(_ request: ApprovalRequest, result: JSONValue, broaderScope: Bool = false) async {
        guard connected, request.generation == generation, let store else { return }
        do {
            let key = "request/\(request.threadID)/\(request.params["turnId"]?.stringValue ?? "")/\(request.id)"
            guard try await store.get(key) == nil else {
                throw ClientError("This response was already attempted. Reconnect to inspect the server request.")
            }
            var consumed = approvals
            let safe = try consumed.consumeResponse(for: request, result: result, generation: generation, confirmBroaderScope: broaderScope)
            // A durable tombstone contains identity only; secret question answers never enter device records.
            try await store.put(Data("attempted".utf8), for: key)
            approvals = consumed
            try await session.respond(id: request.requestID, result: safe, generation: generation)
        } catch { self.error = "Response could not be confirmed. \(error.localizedDescription)" }
    }
    private func receive(_ event: SessionEvent) async {
        guard event.generation == generation else { return }
        let message = event.message
        guard let method = message["method"]?.stringValue else { return }
        let params = message["params"] ?? .object([:])
        if method == "connection/closed" || method == "connection/lost" {
            connected = false; approvals.select(threadID: selectedID, generation: generation &+ 1)
            error = "Disconnected. Reconnect to recover current server state."
            return
        }
        if let id = message["id"] {
            let supported = ["item/commandExecution/requestApproval", "item/fileChange/requestApproval",
                "item/permissions/requestApproval", "item/tool/requestUserInput"]
            guard supported.contains(method) else {
                try? await session.respondError(id: id, code: -32601, message: "This iOS client does not support this request.", generation: generation)
                return
            }
            approvals.receive(ApprovalRequest(id: id, method: method, params: params, generation: generation))
        } else {
            approvals.apply(method: method, params: params, generation: generation)
            if params["threadId"]?.stringValue == selectedID {
                if loading { buffered.append(message) } else { timeline.apply(method: method, params: params) }
                if method == "thread/queue/changed" { await refreshQueue() }
            }
        }
    }
}
private struct ClientError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
