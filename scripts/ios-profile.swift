#!/usr/bin/env swift
// Validate decoded development profile without printing device/certificate data.
import Foundation
let arguments = CommandLine.arguments
func fail() -> Never { fputs("Development profile does not match the required app, team, devices, or validity.\n", stderr); exit(1) }
guard arguments.count == 4,
      let data = try? Data(contentsOf: URL(fileURLWithPath: arguments[1])),
      let profile = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any],
      let uuid = profile["UUID"] as? String, UUID(uuidString: uuid) != nil,
      let teams = profile["TeamIdentifier"] as? [String], teams == [arguments[2]],
      let expiration = profile["ExpirationDate"] as? Date, expiration > Date(),
      let devices = profile["ProvisionedDevices"] as? [String], !devices.isEmpty,
      profile["ProvisionsAllDevices"] as? Bool != true,
      let certificates = profile["DeveloperCertificates"] as? [Data], !certificates.isEmpty,
      let entitlements = profile["Entitlements"] as? [String: Any],
      entitlements["application-identifier"] as? String == arguments[2] + ".dev.codexops.client.ios",
      entitlements["com.apple.developer.team-identifier"] as? String == arguments[2],
      entitlements["get-task-allow"] as? Bool == true else { fail() }
try (uuid + "\n").write(toFile: arguments[3], atomically: true, encoding: .utf8)
