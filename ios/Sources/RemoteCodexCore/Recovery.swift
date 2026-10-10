import Foundation

public enum RecoveryFailure: Error, Equatable, Sendable {
    case invalidPage, repeatedCursor, noMoreHistory, noTurnToSkip, mutationReplayBlocked, invalidTransition
}

public enum OperationKind: String, Codable, Sendable {
    case createChat, sendMessage, queueMessage, removeQueued, startQueued, steerQueued, stopTurn, approval
}
public enum OperationStatus: String, Codable, Sendable {
    case prepared, attempted, acknowledged, uncertain
}
public enum OperationStage: String, Codable, Sendable, Hashable {
    case workspacePreparation, threadCreation, inputSubmission, queueAddition, queueRemoval, queueStart, queueSteer, interruption, approvalResponse
}

/// Persist before each dispatch; immutable input remains available for explicit review.
/// A process restart while `attempted` is an uncertain outcome, never permission to replay.
public struct OperationRecord: Codable, Sendable, Equatable, Identifiable {
    public let id: String
    public let hostID: String
    public let kind: OperationKind
    public let input: JSONValue
    public let workingDirectory: String?
    public private(set) var threadID: String?
    public private(set) var status: OperationStatus
    public private(set) var stage: OperationStage?
    public private(set) var acknowledgedStages: Set<OperationStage>
    public var clientUserMessageID: String { id }
    public var requiresReview: Bool { status == .attempted || status == .uncertain }

    public init(id: String = UUID().uuidString.lowercased(), hostID: String, kind: OperationKind, input: JSONValue, threadID: String? = nil, workingDirectory: String? = nil) {
        self.id = id; self.hostID = hostID; self.kind = kind; self.input = input
        self.threadID = threadID; self.workingDirectory = workingDirectory
        status = .prepared; stage = nil; acknowledgedStages = []
    }

    public func mayDispatch(stage: OperationStage) -> Bool {
        !requiresReview && !acknowledgedStages.contains(stage)
    }

    public mutating func markAttempted(stage: OperationStage) throws {
        guard mayDispatch(stage: stage) else { throw RecoveryFailure.mutationReplayBlocked }
        self.stage = stage; status = .attempted
    }

    public mutating func acknowledge(stage: OperationStage, threadID: String? = nil) throws {
        guard self.stage == stage, status == .attempted else { throw RecoveryFailure.invalidTransition }
        if let threadID {
            guard !threadID.isEmpty, self.threadID == nil || self.threadID == threadID else { throw RecoveryFailure.invalidTransition }
            self.threadID = threadID
        }
        acknowledgedStages.insert(stage); status = .acknowledged
    }

    public mutating func markUncertain() {
        if status == .attempted { status = .uncertain }
    }

    /// Call only after read-only evidence establishes that this precise stage completed.
    /// This records the authoritative outcome; it does not execute or repeat a mutation.
    public mutating func reconcileAcknowledged(stage: OperationStage, threadID: String? = nil) throws {
        guard self.stage == stage, requiresReview else { throw RecoveryFailure.invalidTransition }
        if let threadID {
            guard !threadID.isEmpty, self.threadID == nil || self.threadID == threadID else { throw RecoveryFailure.invalidTransition }
            self.threadID = threadID
        }
        acknowledgedStages.insert(stage); status = .acknowledged
    }
}

/// Explicit, session-only recovery with unmodified stock cursors. No automatic downshift ladder.
public struct SmallerHistoryRecovery: Sendable {
    public let threadID: String
    public private(set) var itemLimit = 20
    public private(set) var turn: JSONValue?
    public private(set) var hasMore = true
    public private(set) var readingItems = false
    private var turnCursor: String?
    private var itemCursor: String?
    private var turnCursors: Set<String> = []
    private var itemCursors: Set<String> = []

    public init(threadID: String) { self.threadID = threadID }
    public mutating func smallerPages() { itemLimit = 1 }
    public mutating func resuming() { readingItems = false }

    public mutating func skipTurn() throws -> String {
        guard let id = turn?["id"]?.stringValue else { throw RecoveryFailure.noTurnToSkip }
        finishTurn(); readingItems = false; return id
    }

    /// At most one summary request and one item page. Failed item reads retain their cursor.
    public mutating func next(call: @Sendable (String, JSONValue) async throws -> JSONValue) async throws -> [JSONValue] {
        guard hasMore else { throw RecoveryFailure.noMoreHistory }
        readingItems = false
        if turn == nil {
            var parameters: [String: JSONValue] = ["threadId": .string(threadID), "limit": .integer(1), "itemsView": .string("summary"), "sortDirection": .string("desc")]
            if let turnCursor { parameters["cursor"] = .string(turnCursor) }
            let page = try await call("thread/turns/list", .object(parameters))
            try Task.checkCancellation()
            guard let turns = page["data"]?.arrayValue, turns.count <= 1,
                  turns.allSatisfy({ !($0["id"]?.stringValue ?? "").isEmpty }) else { throw RecoveryFailure.invalidPage }
            let next = try validatedCursor(page)
            guard next == nil || (next != turnCursor && !turnCursors.contains(next!)) else { throw RecoveryFailure.repeatedCursor }
            if let next { turnCursors.insert(next) }; turnCursor = next
            if let first = turns.first, var summary = first.objectValue { summary.removeValue(forKey: "items"); turn = .object(summary) }
            else { hasMore = next != nil; return [] }
        }
        guard let selected = turn, let turnID = selected["id"]?.stringValue else { throw RecoveryFailure.invalidPage }
        readingItems = true
        var parameters: [String: JSONValue] = ["threadId": .string(threadID), "turnId": .string(turnID), "limit": .integer(Int64(itemLimit)), "sortDirection": .string("desc")]
        if let itemCursor { parameters["cursor"] = .string(itemCursor) }
        let page = try await call("thread/items/list", .object(parameters))
        try Task.checkCancellation()
        guard let rows = page["data"]?.arrayValue, rows.count <= itemLimit,
              rows.allSatisfy({ $0["turnId"]?.stringValue == turnID && !($0["item"]?["id"]?.stringValue ?? "").isEmpty }) else { throw RecoveryFailure.invalidPage }
        let next = try validatedCursor(page)
        guard next == nil || (next != itemCursor && !itemCursors.contains(next!)) else { throw RecoveryFailure.repeatedCursor }
        if let next { itemCursors.insert(next) }; itemCursor = next
        var result = selected.objectValue ?? [:]
        result["items"] = .array(rows.reversed().compactMap { $0["item"] })
        if next == nil { finishTurn() }
        readingItems = false
        return [.object(result)]
    }

    private mutating func finishTurn() { turn = nil; itemCursor = nil; itemCursors.removeAll(); hasMore = turnCursor != nil }
}

private func validatedCursor(_ page: JSONValue) throws -> String? {
    guard let value = page["nextCursor"], value != .null else { return nil }
    guard let cursor = value.stringValue, !cursor.isEmpty else { throw RecoveryFailure.invalidPage }
    return cursor
}
