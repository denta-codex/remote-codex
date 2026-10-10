import Foundation
// Synthetic profiles only. These checks prove rejection, not real Apple signing.
let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: directory) }
let team = "ABCDE12345"
let base: [String: Any] = [
    "UUID": UUID().uuidString, "TeamIdentifier": [team],
    "ExpirationDate": Date().addingTimeInterval(86400),
    "ProvisionedDevices": ["synthetic-device"], "DeveloperCertificates": [Data([1, 2, 3])],
    "Entitlements": ["application-identifier": team + ".dev.codexops.client.ios",
                     "com.apple.developer.team-identifier": team, "get-task-allow": true]
]
var cases: [(String, [String: Any], Bool)] = [("valid synthetic development shape", base, true)]
func changed(_ key: String, _ value: Any) -> [String: Any] { var p = base; p[key] = value; return p }
cases.append(("expired", changed("ExpirationDate", Date().addingTimeInterval(-1)), false))
cases.append(("no devices", changed("ProvisionedDevices", [String]()), false))
cases.append(("enterprise", changed("ProvisionsAllDevices", true), false))
cases.append(("wrong team", changed("TeamIdentifier", ["OTHER12345"]), false))
cases.append(("no certificates", changed("DeveloperCertificates", [Data]()), false))
cases.append(("unsafe UUID", changed("UUID", "../../outside"), false))
for (key, value) in [("application-identifier", team + ".*"),
                     ("application-identifier", team + ".other.app"),
                     ("com.apple.developer.team-identifier", "OTHER12345"),
                     ("get-task-allow", false)] as [(String, Any)] {
    var entitlements = base["Entitlements"] as! [String: Any]
    entitlements[key] = value
    cases.append(("reject entitlement \(key)", changed("Entitlements", entitlements), false))
}
for (name, profile, accepts) in cases {
    let input = directory.appendingPathComponent("profile.plist")
    let output = directory.appendingPathComponent("uuid")
    try? FileManager.default.removeItem(at: output)
    try PropertyListSerialization.data(fromPropertyList: profile, format: .xml, options: 0).write(to: input)
    let process = Process()
    process.executableURL = URL(fileURLWithPath: CommandLine.arguments[1])
    process.arguments = [input.path, team, output.path]
    process.standardOutput = FileHandle.nullDevice
    process.standardError = FileHandle.nullDevice
    try process.run(); process.waitUntilExit()
    guard (process.terminationStatus == 0) == accepts,
          FileManager.default.fileExists(atPath: output.path) == accepts else {
        fputs("Profile validation fixture failed: \(name)\n", stderr); exit(1)
    }
}
print("\(cases.count) synthetic development-profile validation checks passed")
