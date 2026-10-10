import Foundation

public struct TimelineEntry: Identifiable, Sendable, Equatable {
    public let turnID: String
    public var raw: JSONValue
    public var progressMessage: String
    public var summaryFinal: Bool

    public init(turnID: String, raw: JSONValue, progressMessage: String = "", summaryFinal: Bool = false) {
        self.turnID = turnID
        self.raw = raw
        self.progressMessage = progressMessage
        self.summaryFinal = summaryFinal
    }

    public var itemID: String { raw["id"]?.stringValue ?? "" }
    public var id: String { "\(turnID)/\(itemID)" }
    public var kind: String { raw["type"]?.stringValue ?? "" }
    public var status: String { raw["status"]?.stringValue ?? "" }
    public var completed: Bool { raw["_completed"]?.boolValue == true }
    public var summaries: [String] { (raw["summary"]?.arrayValue ?? []).compactMap(\.stringValue) }
    public var text: String {
        switch kind {
        case "userMessage":
            return (raw["content"]?.arrayValue ?? []).compactMap { part in
                part["type"]?.stringValue == "text" ? part["text"]?.stringValue : nil
            }.joined(separator: "\n")
        case "commandExecution":
            return [raw["command"]?.stringValue, raw["aggregatedOutput"]?.stringValue, raw["status"]?.stringValue]
                .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "\n\n")
        case "fileChange":
            return (raw["changes"]?.arrayValue ?? []).map {
                ($0["path"]?.stringValue ?? "") + "\n" + ($0["diff"]?.stringValue ?? "")
            }.joined(separator: "\n\n")
        case "reasoning": return summaries.isEmpty ? "Working…" : summaries.joined(separator: "\n")
        case "mcpToolCall": return progressMessage.isEmpty ? (raw["tool"]?.stringValue ?? "Tool activity") : progressMessage
        default: return raw["text"]?.stringValue ?? ""
        }
    }
}

/// A value reducer. The caller guards chat selection and connection generation before delivery.
public struct TimelineState: Sendable, Equatable {
    private var order: [String] = []
    private var items: [String: TimelineEntry] = [:]
    private var progress: [String: String] = [:]
    public private(set) var turnStatuses: [String: String] = [:]
    public private(set) var activeTurnID: String?
    public var historyCursor: String?
    public var entries: [TimelineEntry] { order.compactMap { items[$0] } }

    public init() {}

    public mutating func clear() { self = TimelineState() }

    public static func resumeParameters(threadID: String) -> JSONValue {
        .object(["threadId": .string(threadID), "excludeTurns": .bool(true),
                 "initialTurnsPage": .object(["limit": .integer(1), "itemsView": .string("full"), "sortDirection": .string("desc")])])
    }

    public static func olderParameters(threadID: String, cursor: String?) -> JSONValue {
        var parameters: [String: JSONValue] = ["threadId": .string(threadID), "limit": .integer(1),
                                             "itemsView": .string("full"), "sortDirection": .string("desc")]
        if let cursor { parameters["cursor"] = .string(cursor) }
        return .object(parameters)
    }

    public mutating func hydrate(resume: JSONValue, bufferedEvents: [JSONValue] = []) {
        let page = resume["initialTurnsPage"]
        let turns = page?["data"]?.arrayValue ?? resume["thread"]?["turns"]?.arrayValue ?? []
        historyCursor = page?["nextCursor"]?.stringValue
        hydrate(turns: turns, bufferedEvents: bufferedEvents)
    }

    public mutating func snapshot(turns: [JSONValue], prepend: Bool = false) {
        var pageOrder: [String] = []
        var pageItems: [String: TimelineEntry] = [:]
        for turn in turns {
            guard let turnID = turn["id"]?.stringValue, !turnID.isEmpty else { continue }
            let status = turn["status"]?.stringValue ?? ""
            if !prepend || turnStatuses[turnID] == nil { turnStatuses[turnID] = status }
            if turnStatuses[turnID] == "inProgress" { activeTurnID = turnID }
            else if activeTurnID == turnID { activeTurnID = nil }
            for item in turn["items"]?.arrayValue ?? [] {
                guard let itemID = item["id"]?.stringValue, !itemID.isEmpty, var raw = item.objectValue else { continue }
                raw["_completed"] = .bool(true)
                let key = "\(turnID)/\(itemID)"
                let entry = TimelineEntry(turnID: turnID, raw: .object(raw), progressMessage: progress[key] ?? "",
                                          summaryFinal: item["type"]?.stringValue == "reasoning" && status != "inProgress")
                if pageItems[key] == nil { pageOrder.append(key) }
                pageItems[key] = entry
            }
        }
        if prepend {
            order = pageOrder.filter { items[$0] == nil } + order
            for (key, entry) in pageItems where items[key] == nil { items[key] = entry }
        } else {
            for key in pageOrder { if items[key] == nil { order.append(key) }; items[key] = pageItems[key] }
        }
    }

    public mutating func hydrate(turns: [JSONValue], bufferedEvents: [JSONValue]) {
        snapshot(turns: turns)
        var live = TimelineState()
        var completed: Set<String> = []
        for event in bufferedEvents {
            let method = event["method"]?.stringValue ?? ""
            let params = event["params"] ?? .object([:])
            if method == "item/completed", let turnID = params["turnId"]?.stringValue, let itemID = params["item"]?["id"]?.stringValue {
                completed.insert("\(turnID)/\(itemID)")
            }
            live.apply(method: method, params: params)
        }
        for entry in live.entries {
            guard let old = items[entry.id], !completed.contains(entry.id) else { put(entry); continue }
            if old.kind == "plan" && old.completed { continue }
            var raw = old.raw.objectValue ?? [:]
            for (key, value) in entry.raw.objectValue ?? [:] where !["text", "aggregatedOutput", "summary", "_completed"].contains(key) { raw[key] = value }
            for field in ["text", "aggregatedOutput"] {
                let value = mergeTimelineStream(old.raw[field]?.stringValue ?? "", entry.raw[field]?.stringValue ?? "")
                if !value.isEmpty { raw[field] = .string(value) }
            }
            if old.kind == "reasoning" && !old.summaryFinal {
                raw["summary"] = .array((0..<max(old.summaries.count, entry.summaries.count)).map { index in
                    .string(mergeTimelineStream(index < old.summaries.count ? old.summaries[index] : "", index < entry.summaries.count ? entry.summaries[index] : ""))
                })
            }
            put(TimelineEntry(turnID: entry.turnID, raw: .object(raw), progressMessage: entry.progressMessage.isEmpty ? old.progressMessage : entry.progressMessage,
                              summaryFinal: old.summaryFinal || entry.summaryFinal))
        }
        for event in bufferedEvents where event["method"]?.stringValue?.hasPrefix("turn/") == true {
            apply(method: event["method"]?.stringValue ?? "", params: event["params"] ?? .object([:]))
        }
    }

    public mutating func apply(method: String, params: JSONValue) {
        if method == "turn/started" || method == "turn/completed" {
            guard let turn = params["turn"], let id = turn["id"]?.stringValue, !id.isEmpty else { return }
            if method == "turn/started" { activeTurnID = id; turnStatuses[id] = "inProgress" }
            else { turnStatuses[id] = turn["status"]?.stringValue ?? "completed"; if activeTurnID == id { activeTurnID = nil } }
            return
        }
        guard let turnID = params["turnId"]?.stringValue, !turnID.isEmpty else { return }
        if method == "item/started" || method == "item/completed" {
            guard var raw = params["item"]?.objectValue, let itemID = raw["id"]?.stringValue, !itemID.isEmpty else { return }
            let key = "\(turnID)/\(itemID)"
            if method == "item/started", raw["type"]?.stringValue == "reasoning", items[key]?.summaryFinal == true { return }
            raw["_completed"] = .bool(method == "item/completed")
            if raw["type"]?.stringValue == "reasoning", method == "item/started", let old = items[key] {
                let parts = raw["summary"]?.arrayValue?.compactMap(\.stringValue) ?? []
                raw["summary"] = .array((0..<max(parts.count, old.summaries.count)).map { index in
                    .string(mergeTimelineStream(index < parts.count ? parts[index] : "", index < old.summaries.count ? old.summaries[index] : ""))
                })
            }
            put(TimelineEntry(turnID: turnID, raw: .object(raw), progressMessage: progress[key] ?? "",
                              summaryFinal: raw["type"]?.stringValue == "reasoning" && method == "item/completed"))
            return
        }
        guard let itemID = params["itemId"]?.stringValue, !itemID.isEmpty else { return }
        let key = "\(turnID)/\(itemID)"
        if method == "item/mcpToolCall/progress" {
            guard let message = params["message"]?.stringValue, !message.isEmpty else { return }
            let bounded = String(message.suffix(100_000)); progress[key] = bounded
            var entry = items[key] ?? TimelineEntry(turnID: turnID, raw: .object(["id": .string(itemID), "type": .string("mcpToolCall")]))
            entry.progressMessage = bounded; put(entry); return
        }
        if method == "item/reasoning/summaryPartAdded" || method == "item/reasoning/summaryTextDelta" {
            guard let index = params["summaryIndex"]?.integerValue, (0...255).contains(index),
                  !["completed", "failed", "interrupted"].contains(turnStatuses[turnID] ?? "") else { return }
            var entry = items[key] ?? TimelineEntry(turnID: turnID, raw: .object(["id": .string(itemID), "type": .string("reasoning")]))
            guard !entry.summaryFinal else { return }
            var parts = entry.summaries; while parts.count <= Int(index) { parts.append("") }
            if method.hasSuffix("summaryTextDelta") { parts[Int(index)] = String((parts[Int(index)] + (params["delta"]?.stringValue ?? "")).suffix(100_000)) }
            var raw = entry.raw.objectValue ?? [:]; raw["summary"] = .array(parts.map(JSONValue.string)); entry.raw = .object(raw); put(entry); return
        }
        let kind: String; let field: String
        switch method {
        case "item/agentMessage/delta": kind = "agentMessage"; field = "text"
        case "item/plan/delta": kind = "plan"; field = "text"
        case "item/commandExecution/outputDelta": kind = "commandExecution"; field = "aggregatedOutput"
        default: return
        }
        // Resume's live overlay is also marked as a snapshot. Active assistant/command
        // items must continue streaming after hydration; terminal turns and plans win.
        guard !["completed", "failed", "interrupted"].contains(turnStatuses[turnID] ?? ""),
              !(kind == "plan" && items[key]?.completed == true) else { return }
        var raw = items[key]?.raw.objectValue ?? ["id": .string(itemID), "type": .string(kind)]
        raw[field] = .string(String(((raw[field]?.stringValue ?? "") + (params["delta"]?.stringValue ?? "")).suffix(100_000)))
        put(TimelineEntry(turnID: turnID, raw: .object(raw), progressMessage: progress[key] ?? ""))
    }

    private mutating func put(_ entry: TimelineEntry) {
        if items[entry.id] == nil { order.append(entry.id) }
        items[entry.id] = entry
        if !entry.progressMessage.isEmpty { progress[entry.id] = entry.progressMessage }
    }
}

private func mergeTimelineStream(_ snapshot: String, _ stream: String) -> String {
    if stream.isEmpty { return snapshot }
    if snapshot.isEmpty || stream.hasPrefix(snapshot) { return stream }
    if snapshot.hasPrefix(stream) || snapshot.hasSuffix(stream) { return snapshot }
    let a = Array(snapshot), b = Array(stream)
    for count in stride(from: min(a.count, b.count), through: 1, by: -1) where a.suffix(count).elementsEqual(b.prefix(count)) {
        return snapshot + String(b.dropFirst(count))
    }
    return snapshot + stream
}
