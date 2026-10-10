import XCTest
@testable import RemoteCodexCore

final class TimelineTests: XCTestCase {
    func testHydrationMergesBufferedDeltaWithoutDoublingSnapshot() throws {
        var timeline = TimelineState()
        let turn = try timelineJSON(#"{"id":"turn","status":"inProgress","items":[{"id":"item","type":"agentMessage","text":"Hello"}]}"#)
        let event = try timelineJSON(#"{"method":"item/agentMessage/delta","params":{"turnId":"turn","itemId":"item","delta":"Hello world"}}"#)
        timeline.hydrate(turns: [turn], bufferedEvents: [event])
        XCTAssertEqual(timeline.entries.map(\.text), ["Hello world"])
        XCTAssertEqual(timeline.activeTurnID, "turn")
    }

    func testCompletedSnapshotSupersedesStreamAndLatePlanDelta() throws {
        var timeline = TimelineState()
        timeline.apply(method: "item/plan/delta", params: try timelineJSON(#"{"turnId":"t","itemId":"p","delta":"partial"}"#))
        timeline.apply(method: "item/completed", params: try timelineJSON(#"{"turnId":"t","item":{"id":"p","type":"plan","text":"Final plan"}}"#))
        timeline.apply(method: "item/plan/delta", params: try timelineJSON(#"{"turnId":"t","itemId":"p","delta":"duplicate"}"#))
        XCTAssertEqual(timeline.entries.map(\.text), ["Final plan"])
        XCTAssertTrue(timeline.entries[0].completed)
    }

    func testResumedActiveAssistantSnapshotContinuesStreaming() throws {
        var timeline = TimelineState()
        timeline.snapshot(turns: [try timelineJSON(#"{"id":"t","status":"inProgress","items":[{"id":"a","type":"agentMessage","text":"first"}]}"#)])
        timeline.apply(method: "item/agentMessage/delta", params: try timelineJSON(#"{"turnId":"t","itemId":"a","delta":" second"}"#))
        XCTAssertEqual(timeline.entries.map(\.text), ["first second"])
        XCTAssertEqual(timeline.activeTurnID, "t")
    }

    func testOlderPageCannotOverwriteLiveOutcomeOrDuplicateEntries() throws {
        var timeline = TimelineState()
        timeline.apply(method: "turn/started", params: try timelineJSON(#"{"turn":{"id":"t"}}"#))
        timeline.apply(method: "item/agentMessage/delta", params: try timelineJSON(#"{"turnId":"t","itemId":"a","delta":"live"}"#))
        timeline.apply(method: "turn/completed", params: try timelineJSON(#"{"turn":{"id":"t","status":"interrupted"}}"#))
        let page = try timelineJSON(#"{"id":"t","status":"inProgress","items":[{"id":"a","type":"agentMessage","text":"stale"}]}"#)
        timeline.snapshot(turns: [page], prepend: true)
        XCTAssertEqual(timeline.entries.map(\.text), ["live"])
        XCTAssertEqual(timeline.turnStatuses["t"], "interrupted")
        XCTAssertNil(timeline.activeTurnID)
    }

    func testResumeUsesRecentTurnAndServerCursor() throws {
        var timeline = TimelineState()
        timeline.hydrate(resume: try timelineJSON(#"{"thread":{"id":"chat"},"initialTurnsPage":{"data":[{"id":"t","status":"completed","items":[{"id":"a","type":"agentMessage","text":"recent"}]}],"nextCursor":"opaque:cursor"}}"#))
        XCTAssertEqual(timeline.entries.map(\.text), ["recent"])
        XCTAssertEqual(timeline.historyCursor, "opaque:cursor")
        XCTAssertEqual(TimelineState.olderParameters(threadID: "chat", cursor: timeline.historyCursor)["cursor"], .string("opaque:cursor"))
        XCTAssertEqual(TimelineState.resumeParameters(threadID: "chat")["excludeTurns"], .bool(true))
    }

    func testToolProgressSurvivesSnapshotAndTerminalSummaryIgnoresLateDeltas() throws {
        var timeline = TimelineState()
        timeline.apply(method: "item/mcpToolCall/progress", params: try timelineJSON(#"{"turnId":"t","itemId":"tool","message":"Searching"}"#))
        timeline.snapshot(turns: [try timelineJSON(#"{"id":"t","status":"completed","items":[{"id":"tool","type":"mcpToolCall","tool":"lookup"},{"id":"r","type":"reasoning","summary":["Done"]}]}"#)])
        timeline.apply(method: "item/reasoning/summaryTextDelta", params: try timelineJSON(#"{"turnId":"t","itemId":"r","summaryIndex":0,"delta":"stale"}"#))
        XCTAssertEqual(timeline.entries[0].progressMessage, "Searching")
        XCTAssertEqual(timeline.entries[1].summaries, ["Done"])
    }
}

private func timelineJSON(_ source: String) throws -> JSONValue { try JSONDecoder().decode(JSONValue.self, from: Data(source.utf8)) }
