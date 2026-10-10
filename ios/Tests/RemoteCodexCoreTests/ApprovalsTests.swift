import XCTest
@testable import RemoteCodexCore

final class ApprovalsTests: XCTestCase {
    func testTypedIDsAndOneAttemptPerRequest() throws {
        var state = ApprovalState(); state.select(threadID: "chat", generation: 7)
        let numeric = approvalRequest(id: .integer(1), generation: 7)
        let string = approvalRequest(id: .string("1"), generation: 7)
        state.receive(numeric); state.receive(numeric); state.receive(string)
        XCTAssertEqual(state.requests.count, 2)
        _ = try state.consumeResponse(for: numeric, result: .object(["decision": .string("accept")]), generation: 7)
        XCTAssertThrowsError(try state.consumeResponse(for: numeric, result: .object(["decision": .string("accept")]), generation: 7)) { XCTAssertEqual($0 as? ApprovalFailure, .staleRequest) }
        XCTAssertEqual(state.requests.map(\.requestID), [.string("1")])
    }

    func testDisconnectInvalidatesOldApprovalAndDesktopResolutionIsTerminal() throws {
        var state = ApprovalState(); state.select(threadID: "chat", generation: 1)
        let old = approvalRequest(generation: 1); state.receive(old)
        state.select(threadID: "chat", generation: 2)
        XCTAssertThrowsError(try state.consumeResponse(for: old, result: .object(["decision": .string("accept")]), generation: 2))
        let fresh = approvalRequest(generation: 2); state.receive(fresh)
        state.apply(method: "serverRequest/resolved", params: .object(["requestId": fresh.requestID]), generation: 2)
        state.receive(fresh)
        XCTAssertTrue(state.requests.isEmpty)
    }

    func testFileGrantsRequireMatchingLiveContextButDeclineDoesNot() throws {
        var state = ApprovalState(); state.select(threadID: "chat", generation: 1)
        let request = ApprovalRequest(id: .integer(1), method: "item/fileChange/requestApproval",
                                      params: try approvalJSON(#"{"threadId":"chat","turnId":"t","itemId":"f"}"#), generation: 1)
        state.receive(request)
        XCTAssertThrowsError(try state.consumeResponse(for: request, result: .object(["decision": .string("accept")]), generation: 1)) { XCTAssertEqual($0 as? ApprovalFailure, .missingFileContext) }
        state.apply(method: "item/started", params: try approvalJSON(#"{"threadId":"chat","turnId":"t","item":{"id":"f","type":"fileChange","changes":[{"path":"a.swift","diff":"+ change"}]}}"#), generation: 1)
        XCTAssertTrue(state.fileContext(for: request)?.contains("a.swift") == true)
        _ = try state.consumeResponse(for: request, result: .object(["decision": .string("accept")]), generation: 1)
        state.select(threadID: "chat", generation: 2)
        let decline = ApprovalRequest(id: .integer(1), method: request.method, params: request.params, generation: 2)
        state.receive(decline)
        _ = try state.consumeResponse(for: decline, result: .object(["decision": .string("decline")]), generation: 2)
    }

    func testResumeContextCannotCreateRequestAndBufferedEventWins() throws {
        var state = ApprovalState(); state.select(threadID: "chat", generation: 3)
        state.apply(method: "item/started", params: try approvalJSON(#"{"threadId":"chat","turnId":"t","item":{"id":"f","type":"fileChange","changes":[{"path":"a","diff":"new"}]}}"#), generation: 3)
        state.recordResume(try approvalJSON(#"{"thread":{"id":"chat"},"initialTurnsPage":{"data":[{"id":"t","items":[{"id":"f","type":"fileChange","changes":[{"path":"a","diff":"old"}]}]}]}}"#), generation: 3)
        XCTAssertTrue(state.requests.isEmpty)
        let request = ApprovalRequest(id: .string("file"), method: "item/fileChange/requestApproval", params: try approvalJSON(#"{"threadId":"chat","turnId":"t","itemId":"f"}"#), generation: 3)
        XCTAssertEqual(state.fileContext(for: request), "a\nnew")
    }

    func testAdvertisedAmendmentCopiedExactlyAndRequiresConfirmation() throws {
        let params = try approvalJSON(#"{"threadId":"chat","availableDecisions":[{"acceptWithExecpolicyAmendment":{"execpolicy_amendment":["git","status"]}},"futureUnknown"]}"#)
        let request = ApprovalRequest(id: .integer(2), method: "item/commandExecution/requestApproval", params: params, generation: 1)
        XCTAssertEqual(request.choices.count, 1)
        XCTAssertEqual(request.choices[0].value, params["availableDecisions"]?.arrayValue?.first)
        var state = ApprovalState(); state.select(threadID: "chat", generation: 1); state.receive(request)
        XCTAssertThrowsError(try state.consumeResponse(for: request, result: request.choices[0].result, generation: 1)) { XCTAssertEqual($0 as? ApprovalFailure, .confirmationRequired) }
        _ = try state.consumeResponse(for: request, result: request.choices[0].result, generation: 1, confirmBroaderScope: true)
    }

    func testPermissionSubsetRetainsDenyAndRejectsInventedGrant() throws {
        let request = ApprovalRequest(id: .integer(1), method: "item/permissions/requestApproval", params: try approvalJSON(#"{"threadId":"chat","permissions":{"network":{"enabled":true},"fileSystem":{"globScanMaxDepth":2,"entries":[{"access":"write","path":{"type":"path","path":"/allowed"}},{"access":"deny","path":{"type":"path","path":"/denied"}}]}}}"#), generation: 1)
        let selection = PermissionSelection(request: request)
        let result = try selection.result(selected: ["entries/0"])
        XCTAssertEqual(result["scope"], .string("turn"))
        XCTAssertEqual(result["permissions"]?["fileSystem"]?["entries"]?.arrayValue?.count, 2)
        XCTAssertEqual(result["permissions"]?["fileSystem"]?["globScanMaxDepth"], .integer(2))
        XCTAssertTrue(selection.validResult(result))
        XCTAssertFalse(selection.validResult(try approvalJSON(#"{"permissions":{"network":{"enabled":true},"other":"invented"},"scope":"session"}"#)))
        XCTAssertThrowsError(try selection.result(selected: ["entries/99"]))
    }

    func testQuestionSecretWhitespaceAndExplicitEmptyAnswerArePreserved() throws {
        let request = ApprovalRequest(id: .integer(1), method: "item/tool/requestUserInput", params: try approvalJSON(#"{"threadId":"chat","questions":[{"id":"secret","isSecret":true},{"id":"choice"},{"id":"empty"}]}"#), generation: 1)
        let result = request.questionAnswers(selections: ["choice": "Option"], notes: ["secret": " value ", "choice": " details ", "empty": "  ", "stale": "ignored"])
        XCTAssertEqual(result["answers"]?["secret"]?["answers"], .array([.string(" value ")]))
        XCTAssertEqual(result["answers"]?["choice"]?["answers"], .array([.string("Option"), .string("details")]))
        XCTAssertEqual(result["answers"]?["empty"]?["answers"], .array([]))
        var state = ApprovalState(); state.select(threadID: "chat", generation: 1); state.receive(request)
        _ = try state.consumeResponse(for: request, result: result, generation: 1)
        XCTAssertFalse(ApprovalState.supportedMethods.contains("mcpServer/elicitation/request"))
    }
}

private func approvalJSON(_ source: String) throws -> JSONValue { try JSONDecoder().decode(JSONValue.self, from: Data(source.utf8)) }
private func approvalRequest(id: JSONValue = .integer(1), generation: UInt64) -> ApprovalRequest {
    ApprovalRequest(id: id, method: "item/commandExecution/requestApproval", params: .object(["threadId": .string("chat")]), generation: generation)
}
