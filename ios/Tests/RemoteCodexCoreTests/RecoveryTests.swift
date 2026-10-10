import XCTest
@testable import RemoteCodexCore

final class RecoveryTests: XCTestCase {
    func testJournalSurvivesRestartAndBlocksUnknownAttemptReplay() throws {
        var record = OperationRecord(hostID: "fixture", kind: .createChat, input: .string("immutable reviewed input"))
        try record.markAttempted(stage: .threadCreation)
        let restored = try JSONDecoder().decode(OperationRecord.self, from: JSONEncoder().encode(record))
        XCTAssertEqual(restored, record)
        XCTAssertTrue(restored.requiresReview)
        XCTAssertFalse(restored.mayDispatch(stage: .threadCreation))
        var recovered = restored; recovered.markUncertain()
        XCTAssertThrowsError(try recovered.markAttempted(stage: .threadCreation))
        try recovered.reconcileAcknowledged(stage: .threadCreation, threadID: "authoritative")
        XCTAssertEqual(recovered.input, .string("immutable reviewed input"))
        XCTAssertFalse(recovered.mayDispatch(stage: .threadCreation))
        XCTAssertTrue(recovered.mayDispatch(stage: .inputSubmission))
    }

    func testAcknowledgementCannotOverwriteAuthoritativeThreadOrSkipAttempt() throws {
        var record = OperationRecord(hostID: "fixture", kind: .sendMessage, input: .array([]), threadID: "chat")
        XCTAssertThrowsError(try record.acknowledge(stage: .inputSubmission))
        try record.markAttempted(stage: .inputSubmission)
        XCTAssertThrowsError(try record.acknowledge(stage: .inputSubmission, threadID: "other"))
        try record.acknowledge(stage: .inputSubmission, threadID: "chat")
        XCTAssertFalse(record.requiresReview)
        XCTAssertFalse(record.mayDispatch(stage: .inputSubmission))
    }

    func testRecoveryMakesOneSummaryAndOnePageInChronologicalItemOrder() async throws {
        let script = RecoveryScript([.page(try recoveryJSON(#"{"data":[{"id":"t","status":"completed","items":[{"id":"ignored"}]}],"nextCursor":"next-turn"}"#)), .page(try recoveryJSON(#"{"data":[{"turnId":"t","item":{"id":"new","type":"agentMessage","text":"new"}},{"turnId":"t","item":{"id":"old","type":"agentMessage","text":"old"}}],"nextCursor":"next-item"}"#))])
        var recovery = SmallerHistoryRecovery(threadID: "chat")
        let result = try await recovery.next { method, params in try await script.call(method, params) }
        XCTAssertEqual(result[0]["items"]?.arrayValue?.compactMap { $0["id"]?.stringValue }, ["old", "new"])
        let calls = await script.calls
        XCTAssertEqual(calls.map(\.0), ["thread/turns/list", "thread/items/list"])
        XCTAssertEqual(calls[0].1["itemsView"], .string("summary"))
        XCTAssertEqual(calls[1].1["limit"], .integer(20))
        XCTAssertTrue(recovery.hasMore)
    }

    func testFailedPageRetainsCursorAndExplicitDownshiftChangesOnlyLimit() async throws {
        let script = RecoveryScript([.page(try recoveryJSON(#"{"data":[{"id":"t"}],"nextCursor":null}"#)), .page(try recoveryJSON(#"{"data":[{"turnId":"t","item":{"id":"one"}}],"nextCursor":"opaque"}"#)), .failure,
                                     .page(try recoveryJSON(#"{"data":[{"turnId":"t","item":{"id":"two"}}],"nextCursor":null}"#))])
        var recovery = SmallerHistoryRecovery(threadID: "chat")
        _ = try await recovery.next { try await script.call($0, $1) }
        do { _ = try await recovery.next { try await script.call($0, $1) }; XCTFail("Expected fixture failure") } catch {}
        XCTAssertTrue(recovery.readingItems)
        recovery.smallerPages()
        _ = try await recovery.next { try await script.call($0, $1) }
        let calls = await script.calls
        XCTAssertEqual(calls[2].1["cursor"], .string("opaque"))
        XCTAssertEqual(calls[3].1["cursor"], .string("opaque"))
        XCTAssertEqual(calls[3].1["limit"], .integer(1))
        XCTAssertFalse(recovery.hasMore)
    }

    func testWrongTurnPageCannotAdvanceAndSkipUsesKnownTurnCursor() async throws {
        let script = RecoveryScript([.page(try recoveryJSON(#"{"data":[{"id":"t"}],"nextCursor":"next-turn"}"#)), .page(try recoveryJSON(#"{"data":[{"turnId":"wrong","item":{"id":"one"}}],"nextCursor":"bad"}"#)), .page(try recoveryJSON(#"{"data":[],"nextCursor":null}"#))])
        var recovery = SmallerHistoryRecovery(threadID: "chat")
        do { _ = try await recovery.next { try await script.call($0, $1) }; XCTFail("Expected invalid ownership") } catch { XCTAssertEqual(error as? RecoveryFailure, .invalidPage) }
        XCTAssertEqual(try recovery.skipTurn(), "t")
        _ = try await recovery.next { try await script.call($0, $1) }
        let calls = await script.calls
        XCTAssertEqual(calls.last?.1["cursor"], .string("next-turn"))
        XCTAssertThrowsError(try recovery.skipTurn())
    }

    func testNonAdjacentItemCursorCycleIsRejected() async throws {
        let script = RecoveryScript([.page(try recoveryJSON(#"{"data":[{"id":"t"}]}"#)), .page(try recoveryJSON(#"{"data":[],"nextCursor":"a"}"#)), .page(try recoveryJSON(#"{"data":[],"nextCursor":"b"}"#)), .page(try recoveryJSON(#"{"data":[],"nextCursor":"a"}"#))])
        var recovery = SmallerHistoryRecovery(threadID: "chat")
        _ = try await recovery.next { try await script.call($0, $1) }
        _ = try await recovery.next { try await script.call($0, $1) }
        do { _ = try await recovery.next { try await script.call($0, $1) }; XCTFail("Expected cursor cycle") } catch { XCTAssertEqual(error as? RecoveryFailure, .repeatedCursor) }
    }
}

private func recoveryJSON(_ source: String) throws -> JSONValue { try JSONDecoder().decode(JSONValue.self, from: Data(source.utf8)) }
private actor RecoveryScript {
    enum Step: Sendable { case page(JSONValue), failure }
    private var steps: [Step]
    private(set) var calls: [(String, JSONValue)] = []
    init(_ steps: [Step]) { self.steps = steps }
    func call(_ method: String, _ params: JSONValue) throws -> JSONValue {
        calls.append((method, params))
        guard !steps.isEmpty else { throw RecoveryFailure.invalidPage }
        switch steps.removeFirst() { case .page(let value): return value; case .failure: throw RecoveryFailure.invalidPage }
    }
}
