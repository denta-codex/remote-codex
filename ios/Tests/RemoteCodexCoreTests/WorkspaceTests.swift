import XCTest
@testable import RemoteCodexCore

final class WorkspaceTests: XCTestCase {
    func testDeterministicProjectlessPreparationIsArgumentSafeAndNetworkDenied() throws {
        let operation = "01234567-89ab-cdef-0123-456789abcdef"
        let workspace = try ProjectlessWorkspace(operationID: operation)
        XCTAssertEqual(workspace.directory, "/home/agent/Documents/RemoteCodex/" + operation)
        XCTAssertEqual(workspace.prepareParameters["command"], .array(["mkdir", "-p", "-m", "0700", "--", workspace.directory].map(JSONValue.string)))
        XCTAssertEqual(workspace.prepareParameters["sandboxPolicy"]?["type"], .string("workspaceWrite"))
        XCTAssertEqual(workspace.prepareParameters["sandboxPolicy"]?["networkAccess"], .bool(false))
        XCTAssertEqual(workspace.prepareParameters["sandboxPolicy"]?["writableRoots"], .array([.string(workspace.root)]))
        XCTAssertEqual(workspace.threadStartParameters["projectId"], .null)
        XCTAssertNil(workspace.threadStartParameters["model"])
        XCTAssertEqual(workspace.threadStartParameters["historyMode"], .string("paginated"))
    }

    func testOperationIdentityCannotEscapeRoot() {
        for operation in ["../outside", "$(secret)", "01234567-89AB-CDEF-0123-456789ABCDEF", "a\n"] {
            XCTAssertThrowsError(try ProjectlessWorkspace(operationID: operation))
        }
    }

    func testDirectoryMetadataAndInputMatchStockShapes() {
        XCTAssertTrue(ProjectlessWorkspace.isDirectory(.object(["metadata": .object(["type": .string("directory")])])))
        XCTAssertFalse(ProjectlessWorkspace.isDirectory(.object(["type": .string("file")])))
        let input = ProjectlessWorkspace.textInput("literal `value` $HOME")
        let parameters = ProjectlessWorkspace.turnStartParameters(threadID: "chat", input: input, clientUserMessageID: "operation")
        XCTAssertEqual(parameters["clientUserMessageId"], .string("operation"))
        XCTAssertEqual(parameters["input"], input)
        XCTAssertEqual(input.arrayValue?.first?["text"], .string("literal `value` $HOME"))
    }
}
