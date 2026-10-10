import Foundation

public enum WorkspaceFailure: Error, Equatable, Sendable { case invalidOperationIdentity }

/// Parameter construction only. The app journals and dispatches each host mutation separately.
public struct ProjectlessWorkspace: Sendable, Equatable {
    public let operationID: String
    public let root = "/home/agent/Documents/RemoteCodex"
    public var directory: String { "\(root)/\(operationID)" }

    public init(operationID: String) throws {
        guard UUID(uuidString: operationID) != nil, operationID == operationID.lowercased(),
              operationID.count == 36 else { throw WorkspaceFailure.invalidOperationIdentity }
        self.operationID = operationID
    }

    public var prepareParameters: JSONValue {
        .object([
            "command": .array(["mkdir", "-p", "-m", "0700", "--", directory].map(JSONValue.string)),
            "cwd": .string(root),
            "sandboxPolicy": .object(["type": .string("workspaceWrite"), "writableRoots": .array([.string(root)]), "networkAccess": .bool(false)]),
            "timeoutMs": .integer(10_000), "outputBytesCap": .integer(2_048)
        ])
    }

    public var threadStartParameters: JSONValue {
        .object(["cwd": .string(directory), "historyMode": .string("paginated"), "ephemeral": .bool(false),
                 "threadSource": .string("agent_created_thread"), "projectId": .null])
    }

    public var inspectParameters: JSONValue { .object(["path": .string(directory)]) }

    public static func isDirectory(_ metadata: JSONValue) -> Bool {
        let value = metadata["metadata"] ?? metadata
        return value["isDirectory"]?.boolValue == true || (value["type"]?.stringValue ?? value["kind"]?.stringValue ?? "").lowercased() == "directory"
    }

    public static func textInput(_ text: String) -> JSONValue { .array([.object(["type": .string("text"), "text": .string(text)])]) }

    public static func turnStartParameters(threadID: String, input: JSONValue, clientUserMessageID: String) -> JSONValue {
        .object(["threadId": .string(threadID), "input": input, "clientUserMessageId": .string(clientUserMessageID)])
    }

    public static func queueAddParameters(threadID: String, input: JSONValue, clientUserMessageID: String) -> JSONValue {
        .object(["threadId": .string(threadID), "input": input, "clientUserMessageId": .string(clientUserMessageID)])
    }
}
