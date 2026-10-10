import XCTest
@testable import RemoteCodexCore

final class StoreTests: XCTestCase {
    private func temporaryURL() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("remote-codex-store-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent("records.sqlite3")
    }

    func testRecordsSurviveReopeningAndCanBeRemoved() async throws {
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let credentials = MemoryCredentialStore()
        let first = try SQLiteClientStore(url: url, credentials: credentials)
        try await first.put(Data("fixture draft".utf8), for: "grace/draft/thread-1")
        let second = try SQLiteClientStore(url: url, credentials: credentials)
        let loaded = try await second.get("grace/draft/thread-1")
        XCTAssertEqual(loaded, Data("fixture draft".utf8))
        try await second.remove("grace/draft/thread-1")
        let removed = try await first.get("grace/draft/thread-1")
        XCTAssertNil(removed)
    }

    func testTokenDoesNotEnterDatabaseAndCanBeForgotten() async throws {
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = try SQLiteClientStore(url: url, credentials: MemoryCredentialStore())
        let token = "fixture-credential-never-in-sqlite"
        try await store.saveToken(token)
        let loaded = try await store.token()
        XCTAssertEqual(loaded, token)
        let bytes = try Data(contentsOf: url)
        XCTAssertNil(bytes.range(of: Data(token.utf8)))
        try await store.saveToken(nil)
        let forgotten = try await store.token()
        XCTAssertNil(forgotten)
    }

    func testUpsertSupportsEmptyAndBinaryData() async throws {
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = try SQLiteClientStore(url: url, credentials: MemoryCredentialStore())
        try await store.put(Data(), for: "journal")
        let empty = try await store.get("journal")
        XCTAssertEqual(empty, Data())
        let journal = Data([0, 255, 13, 10, 0])
        try await store.put(journal, for: "journal")
        let loaded = try await store.get("journal")
        XCTAssertEqual(loaded, journal)
    }

    func testRecordsArePrivateAndInvalidKeysFailClosed() async throws {
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = try SQLiteClientStore(url: url, credentials: MemoryCredentialStore())
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        XCTAssertEqual((attributes[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        do {
            try await store.put(Data("unused".utf8), for: "bad\0key")
            XCTFail("Invalid record key was accepted")
        } catch StoreFailure.invalidKey { }
        let missing = try await store.get("bad")
        XCTAssertNil(missing)
    }
}
