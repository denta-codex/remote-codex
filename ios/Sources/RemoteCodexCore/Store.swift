import Foundation
import CSQLite

/// Device records and credentials are separate so credentials never enter SQLite.
public protocol ClientStore: Sendable {
    func get(_ key: String) async throws -> Data?
    func put(_ value: Data, for key: String) async throws
    func remove(_ key: String) async throws
    func token() async throws -> String?
    func saveToken(_ value: String?) async throws
}

public protocol CredentialStore: Sendable {
    func getToken() async throws -> String?
    func setToken(_ token: String?) async throws
}

public enum StoreFailure: Error, LocalizedError {
    case databaseUnavailable, invalidKey, recordUnavailable

    public var errorDescription: String? {
        switch self {
        case .databaseUnavailable: return "Device storage could not be opened."
        case .invalidKey: return "The device record identifier is invalid."
        case .recordUnavailable: return "The device record could not be read or saved."
        }
    }
}

/// Each operation commits before returning. FULL sync makes a saved pre-send
/// journal a durable boundary, rather than a best-effort in-memory preference.
public actor SQLiteClientStore: ClientStore {
    private let database: OpaquePointer
    private let credentials: any CredentialStore

    public init(url: URL, credentials: any CredentialStore) throws {
        guard url.isFileURL else { throw StoreFailure.databaseUnavailable }
        self.credentials = credentials
        let manager = FileManager.default
        let parent = url.deletingLastPathComponent()
        try manager.createDirectory(at: parent, withIntermediateDirectories: true,
                                    attributes: [.posixPermissions: 0o700])
        var opened: OpaquePointer?
        let result = sqlite3_open_v2(url.path, &opened,
            SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil)
        guard result == SQLITE_OK, let connection = opened else {
            if let opened { sqlite3_close(opened) }
            throw StoreFailure.databaseUnavailable
        }
        do {
            try manager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
            #if os(iOS)
            try manager.setAttributes([.protectionKey: FileProtectionType.complete],
                                      ofItemAtPath: parent.path)
            try manager.setAttributes([.protectionKey: FileProtectionType.complete],
                                      ofItemAtPath: url.path)
            #endif
            sqlite3_busy_timeout(connection, 5_000)
            guard sqlite3_exec(connection,
                "PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL; CREATE TABLE IF NOT EXISTS records (key TEXT PRIMARY KEY NOT NULL, value BLOB NOT NULL);",
                nil, nil, nil) == SQLITE_OK else { throw StoreFailure.databaseUnavailable }
            self.database = connection
        } catch {
            sqlite3_close(connection)
            throw error
        }
    }

    deinit { sqlite3_close(database) }

    public func get(_ key: String) throws -> Data? {
        let statement = try prepare("SELECT value FROM records WHERE key = ?", key: key)
        defer { sqlite3_finalize(statement) }
        let result = sqlite3_step(statement)
        if result == SQLITE_DONE { return nil }
        guard result == SQLITE_ROW else { throw StoreFailure.recordUnavailable }
        let count = Int(sqlite3_column_bytes(statement, 0))
        guard count > 0 else { return Data() }
        guard let bytes = sqlite3_column_blob(statement, 0) else { throw StoreFailure.recordUnavailable }
        return Data(bytes: bytes, count: count)
    }

    public func put(_ value: Data, for key: String) throws {
        let statement = try prepare(
            "INSERT INTO records (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            key: key)
        defer { sqlite3_finalize(statement) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        let bound = value.isEmpty ? sqlite3_bind_zeroblob(statement, 2, 0) :
            value.withUnsafeBytes { sqlite3_bind_blob(statement, 2, $0.baseAddress, Int32($0.count), transient) }
        guard bound == SQLITE_OK, sqlite3_step(statement) == SQLITE_DONE else {
            throw StoreFailure.recordUnavailable
        }
    }

    public func remove(_ key: String) throws {
        let statement = try prepare("DELETE FROM records WHERE key = ?", key: key)
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_DONE else { throw StoreFailure.recordUnavailable }
    }

    public func token() async throws -> String? { try await credentials.getToken() }
    public func saveToken(_ value: String?) async throws { try await credentials.setToken(value) }

    private func prepare(_ sql: String, key: String) throws -> OpaquePointer {
        guard !key.isEmpty, key.utf8.count <= 512, !key.contains("\0") else {
            throw StoreFailure.invalidKey
        }
        var prepared: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &prepared, nil) == SQLITE_OK,
              let statement = prepared else { throw StoreFailure.recordUnavailable }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        guard sqlite3_bind_text(statement, 1, key, -1, transient) == SQLITE_OK else {
            sqlite3_finalize(statement)
            throw StoreFailure.recordUnavailable
        }
        return statement
    }
}

/// Disposable fixture credentials. Production uses the app's Keychain adapter.
public actor MemoryCredentialStore: CredentialStore {
    private var value: String?
    public init(token: String? = nil) { value = token }
    public func getToken() -> String? { value }
    public func setToken(_ token: String?) { value = token }
}
