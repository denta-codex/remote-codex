import Foundation

public enum Pairing {
    /// The endpoint is trusted application configuration, never supplied by a QR.
    public static func parseSetupQR(_ value: String?) -> String? {
        guard let value, value.hasPrefix("remote-codex-setup-v1:") else { return nil }
        let token = String(value.dropFirst("remote-codex-setup-v1:".count))
        guard token.utf8.count == 64, token.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else { return nil }
        return token
    }
}

public func parseSetupQR(_ value: String?) -> String? { Pairing.parseSetupQR(value) }
