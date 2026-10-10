import Foundation
import Security
import RemoteCodexCore

actor KeychainCredentials: CredentialStore {
    private let service = "dev.codexops.client.ios.transport"
    private let account = "grace"

    func getToken() async throws -> String? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw KeychainFailure(status: status) }
        return String(data: data, encoding: .utf8)
    }

    func setToken(_ value: String?) async throws {
        let query = baseQuery
        guard let value else {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainFailure(status: status) }
            return
        }
        let attributes: [String: Any] = [kSecValueData as String: Data(value.utf8)]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var insert = query
            insert[kSecValueData as String] = Data(value.utf8)
            insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            let inserted = SecItemAdd(insert as CFDictionary, nil)
            guard inserted == errSecSuccess else { throw KeychainFailure(status: inserted) }
        } else if status != errSecSuccess {
            throw KeychainFailure(status: status)
        }
    }

    private var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }
}

private struct KeychainFailure: LocalizedError {
    let status: OSStatus
    var errorDescription: String? { "The connection token could not be accessed in Keychain (\(status))." }
}
