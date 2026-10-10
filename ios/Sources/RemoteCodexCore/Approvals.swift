import Foundation

public enum ApprovalFailure: Error, Equatable, Sendable {
    case staleRequest, invalidResponse, missingFileContext, confirmationRequired
}

public struct ApprovalChoice: Sendable, Equatable {
    public let value: JSONValue
    public let label: String
    public let consequence: String
    public let grantsAccess: Bool
    public var result: JSONValue { .object(["decision": value]) }
}

public struct ApprovalRequest: Identifiable, Sendable, Equatable {
    public let requestID: JSONValue
    public let method: String
    public let params: JSONValue
    public let generation: UInt64
    /// String and integer RPC IDs intentionally occupy different identities.
    public var id: String { approvalID(requestID) }
    public var threadID: String { params["threadId"]?.stringValue ?? "" }
    public var blocksUser: Bool { method != "item/tool/requestUserInput" || params["isBlocking"]?.boolValue != false }

    public init(id: JSONValue, method: String, params: JSONValue, generation: UInt64) {
        self.requestID = id; self.method = method; self.params = params; self.generation = generation
    }

    public var choices: [ApprovalChoice] {
        let file = method == "item/fileChange/requestApproval"
        guard file || method == "item/commandExecution/requestApproval" else { return [] }
        let advertised = params["availableDecisions"]
        let values: [JSONValue]
        if advertised == nil || advertised == .null {
            values = (file ? ["accept", "acceptForSession", "decline", "cancel"] : ["accept", "decline"]).map(JSONValue.string)
        } else { values = advertised?.arrayValue ?? [] }
        var seen: [JSONValue] = []
        return values.compactMap { value in
            guard !seen.contains(value) else { return nil }; seen.append(value)
            if let name = value.stringValue {
                switch name {
                case "accept": return ApprovalChoice(value: value, label: "Approve once", consequence: "", grantsAccess: true)
                case "decline": return ApprovalChoice(value: value, label: "Decline", consequence: "", grantsAccess: false)
                case "cancel": return ApprovalChoice(value: value, label: "Deny and stop turn", consequence: "", grantsAccess: false)
                case "acceptForSession": return ApprovalChoice(value: value, label: "Approve for session", consequence: file ? "Future changes to the same files can run without prompting for this session." : "Future matching approvals can run without prompting for this session.", grantsAccess: true)
                default: return nil
                }
            }
            guard !file, let object = value.objectValue, object.count == 1 else { return nil }
            if let amendment = object["acceptWithExecpolicyAmendment"]?.objectValue,
               Set(amendment.keys) == ["execpolicy_amendment"], let prefix = amendment["execpolicy_amendment"]?.arrayValue,
               prefix.allSatisfy({ $0.stringValue != nil }) {
                let command = prefix.compactMap(\.stringValue).joined(separator: " ")
                return ApprovalChoice(value: value, label: "Approve and save command rule", consequence: "Future commands matching this prefix can run without prompting beyond this turn: \(command)" + (prefix.isEmpty ? "\nAn empty prefix can match broadly." : ""), grantsAccess: true)
            }
            if let amendment = object["applyNetworkPolicyAmendment"]?.objectValue,
               Set(amendment.keys) == ["network_policy_amendment"], let rule = amendment["network_policy_amendment"]?.objectValue,
               Set(rule.keys) == ["host", "action"], let host = rule["host"]?.stringValue, !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
               let action = rule["action"]?.stringValue, ["allow", "deny"].contains(action) {
                return ApprovalChoice(value: value, label: action == "allow" ? "Save network allow rule" : "Save network deny rule",
                                      consequence: "\(action == "allow" ? "Allow" : "Deny") future network requests for \(host). This rule persists beyond this turn.", grantsAccess: action == "allow")
            }
            return nil
        }
    }

    public var questions: [JSONValue] { params["questions"]?.arrayValue ?? [] }
    public func questionAnswers(selections: [String: String], notes: [String: String]) -> JSONValue {
        var answers: [String: JSONValue] = [:]
        for question in questions {
            guard let id = question["id"]?.stringValue, !id.isEmpty else { continue }
            var values: [JSONValue] = []
            if let selection = selections[id] { values.append(.string(selection)) }
            if let note = notes[id], !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                values.append(.string(question["isSecret"]?.boolValue == true ? note : note.trimmingCharacters(in: .whitespacesAndNewlines)))
            }
            answers[id] = .object(["answers": .array(values)])
        }
        return .object(["answers": .object(answers)])
    }
}

public struct PermissionOption: Identifiable, Sendable, Equatable {
    public let id: String
    public let label: String
    public let value: JSONValue
}

/// Copies only requested permissions. Deny entries and scan restrictions survive every subset.
public struct PermissionSelection: Sendable {
    private let profile: JSONValue
    private let fileSystem: [String: JSONValue]?
    public let options: [PermissionOption]

    public init(request: ApprovalRequest) {
        profile = request.params["permissions"] ?? .object([:])
        fileSystem = profile["fileSystem"]?.objectValue
        var result: [PermissionOption] = []
        if let network = profile["network"]?.objectValue, Set(network.keys) == ["enabled"], network["enabled"]?.boolValue == true {
            result.append(PermissionOption(id: "network", label: "Network access", value: .object(network)))
        }
        if let fs = fileSystem, Set(fs.keys).isSubset(of: ["entries", "read", "write", "globScanMaxDepth"]),
           ["entries", "read", "write"].allSatisfy({ field in
               guard let value = fs[field], value != .null else { return true }
               guard let array = value.arrayValue else { return false }
               return array.allSatisfy { entry in
                   if field != "entries" { return entry.stringValue != nil }
                   guard let object = entry.objectValue, Set(object.keys) == ["access", "path"],
                         ["read", "write", "deny"].contains(object["access"]?.stringValue ?? "") else { return false }
                   return knownPermissionPath(object["path"])
               }
           }) {
            for field in ["entries", "read", "write"] {
                for (index, value) in (fs[field]?.arrayValue ?? []).enumerated() {
                    if field == "entries" {
                        guard let access = value["access"]?.stringValue, ["read", "write"].contains(access) else { continue }
                        result.append(PermissionOption(id: "\(field)/\(index)", label: "\(access): \(permissionPathLabel(value["path"]))", value: value))
                    } else if let path = value.stringValue { result.append(PermissionOption(id: "\(field)/\(index)", label: "\(field): \(path)", value: value)) }
                }
            }
        }
        options = result
    }

    public func result(selected: Set<String>, scope: String = "turn") throws -> JSONValue {
        guard ["turn", "session"].contains(scope), selected.isSubset(of: Set(options.map(\.id))) else { throw ApprovalFailure.invalidResponse }
        var grant: [String: JSONValue] = [:]
        if selected.contains("network"), let option = options.first(where: { $0.id == "network" }) { grant["network"] = option.value }
        if selected.contains(where: { $0 != "network" }), let fs = fileSystem {
            var subset: [String: JSONValue] = [:]
            if let depth = fs["globScanMaxDepth"] { subset["globScanMaxDepth"] = depth }
            for field in ["entries", "read", "write"] {
                let values = (fs[field]?.arrayValue ?? []).enumerated().filter {
                    selected.contains("\(field)/\($0.offset)") || (field == "entries" && $0.element["access"]?.stringValue == "deny")
                }.map(\.element)
                if !values.isEmpty { subset[field] = .array(values) }
            }
            grant["fileSystem"] = .object(subset)
        }
        return .object(["permissions": .object(grant), "scope": .string(grant.isEmpty ? "turn" : scope)])
    }

    public func validResult(_ response: JSONValue) -> Bool {
        guard let object = response.objectValue, Set(object.keys) == ["permissions", "scope"],
              let scope = response["scope"]?.stringValue, let grant = response["permissions"]?.objectValue else { return false }
        var selected: Set<String> = []
        if let network = options.first(where: { $0.id == "network" }), grant["network"] == network.value { selected.insert("network") }
        for field in ["entries", "read", "write"] {
            let requested = fileSystem?[field]?.arrayValue ?? []
            var next = 0
            for value in grant["fileSystem"]?[field]?.arrayValue ?? [] {
                while next < requested.count && requested[next] != value { next += 1 }
                if next < requested.count {
                    let id = "\(field)/\(next)"; if options.contains(where: { $0.id == id }) { selected.insert(id) }; next += 1
                }
            }
        }
        return (try? result(selected: selected, scope: scope)) == response
    }
}

public struct ApprovalState: Sendable {
    private var threadID: String?
    private var generation: UInt64 = 0
    private var pending: [ApprovalRequest] = []
    private var terminalIDs: Set<String> = []
    private var contexts: [String: String] = [:]
    public var requests: [ApprovalRequest] { pending }
    public init() {}

    public mutating func select(threadID: String?, generation: UInt64) {
        guard self.threadID != threadID || self.generation != generation else { return }
        self.threadID = threadID; self.generation = generation; pending.removeAll(); terminalIDs.removeAll(); contexts.removeAll()
    }

    public mutating func receive(_ request: ApprovalRequest) {
        guard request.generation == generation, request.threadID == threadID,
              Self.supportedMethods.contains(request.method), !terminalIDs.contains(request.id), !pending.contains(where: { $0.id == request.id }) else { return }
        switch request.requestID { case .string, .integer: pending.append(request); default: return }
    }

    public static let supportedMethods: Set<String> = ["item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/permissions/requestApproval", "item/tool/requestUserInput"]

    public func fileContext(for request: ApprovalRequest) -> String? {
        guard request.method == "item/fileChange/requestApproval", request.generation == generation, request.threadID == threadID else { return nil }
        return contexts[contextKey(request.params["turnId"]?.stringValue ?? "", request.params["itemId"]?.stringValue ?? "")].flatMap { $0.isEmpty ? nil : $0 }
    }

    public mutating func apply(method: String, params: JSONValue, generation: UInt64) {
        guard generation == self.generation else { return }
        if method == "serverRequest/resolved", let id = params["requestId"] {
            let key = approvalID(id); pending.removeAll { $0.id == key }; terminalIDs.insert(key); return
        }
        guard params["threadId"]?.stringValue == threadID, let turnID = params["turnId"]?.stringValue else { return }
        if ["item/started", "item/completed"].contains(method), let item = params["item"] { record(turnID: turnID, item: item, replace: true) }
        if method == "item/fileChange/patchUpdated", let itemID = params["itemId"] {
            record(turnID: turnID, item: .object(["id": itemID, "type": .string("fileChange"), "changes": params["changes"] ?? .array([])]), replace: true)
        }
    }

    public mutating func recordResume(_ response: JSONValue, generation: UInt64) {
        guard self.generation == generation, response["thread"]?["id"]?.stringValue == threadID else { return }
        for turn in response["initialTurnsPage"]?["data"]?.arrayValue ?? [] {
            guard let id = turn["id"]?.stringValue else { continue }
            for item in turn["items"]?.arrayValue ?? [] { record(turnID: id, item: item, replace: false) }
        }
    }

    /// Consume before transport dispatch. A lost reply must never re-enable this request.
    public mutating func consumeResponse(for request: ApprovalRequest, result: JSONValue, generation: UInt64, confirmBroaderScope: Bool = false) throws -> JSONValue {
        guard generation == self.generation, request.generation == generation, pending.contains(request), !terminalIDs.contains(request.id) else { throw ApprovalFailure.staleRequest }
        if request.method == "item/tool/requestUserInput" {
            guard let response = result.objectValue, Set(response.keys) == ["answers"], let answers = result["answers"]?.objectValue,
                  Set(answers.keys) == Set(request.questions.compactMap { $0["id"]?.stringValue }),
                  answers.values.allSatisfy({ value in value.objectValue?.count == 1 && value["answers"]?.arrayValue?.allSatisfy({ $0.stringValue != nil }) == true }) else { throw ApprovalFailure.invalidResponse }
        } else if request.method == "item/permissions/requestApproval" {
            guard PermissionSelection(request: request).validResult(result) else { throw ApprovalFailure.invalidResponse }
            if result["scope"]?.stringValue == "session", !confirmBroaderScope { throw ApprovalFailure.confirmationRequired }
        } else {
            guard let choice = request.choices.first(where: { $0.result == result }) else { throw ApprovalFailure.invalidResponse }
            if request.method == "item/fileChange/requestApproval", choice.grantsAccess, fileContext(for: request) == nil { throw ApprovalFailure.missingFileContext }
            if !choice.consequence.isEmpty, !confirmBroaderScope { throw ApprovalFailure.confirmationRequired }
        }
        terminalIDs.insert(request.id); pending.removeAll { $0.id == request.id }; return result
    }

    private mutating func record(turnID: String, item: JSONValue, replace: Bool) {
        guard !turnID.isEmpty, item["type"]?.stringValue == "fileChange", let itemID = item["id"]?.stringValue, !itemID.isEmpty else { return }
        let changes = item["changes"]?.arrayValue ?? []
        let valid = !changes.isEmpty && changes.allSatisfy { !($0["path"]?.stringValue ?? "").isEmpty }
        let key = contextKey(turnID, itemID)
        if replace || contexts[key] == nil { contexts[key] = valid ? TimelineEntry(turnID: turnID, raw: item).text : "" }
    }
}

private func approvalID(_ value: JSONValue) -> String {
    switch value { case .string(let value): return "string:\(value)"; case .integer(let value): return "integer:\(value)"; default: return "invalid" }
}
private func contextKey(_ turnID: String, _ itemID: String) -> String { "\(turnID.utf8.count):\(turnID)\(itemID)" }
private func knownPermissionPath(_ value: JSONValue?) -> Bool {
    guard let object = value?.objectValue, let type = object["type"]?.stringValue else { return false }
    switch type {
    case "path": return Set(object.keys) == ["type", "path"] && object["path"]?.stringValue != nil
    case "glob_pattern": return Set(object.keys) == ["type", "pattern"] && object["pattern"]?.stringValue != nil
    case "special": return ["root", "minimal", "project_roots", "tmpdir", "slash_tmp", "unknown"].contains(object["value"]?["kind"]?.stringValue ?? "")
    default: return false
    }
}
private func permissionPathLabel(_ value: JSONValue?) -> String {
    value?["path"]?.stringValue ?? value?["pattern"]?.stringValue ?? value?["value"]?["kind"]?.stringValue ?? "Requested path"
}
