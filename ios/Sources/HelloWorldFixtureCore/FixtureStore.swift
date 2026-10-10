import Foundation

public struct FixtureState: Codable, Equatable {
    public var tapCount: Int
    public var savedNote: String

    public init(tapCount: Int = 0, savedNote: String = "") {
        self.tapCount = tapCount
        self.savedNote = savedNote
    }
}

/// Stores synthetic testing state only. The file survives an in-place app upgrade.
public struct FixtureStore {
    private let fileURL: URL

    public init(fileURL: URL) {
        self.fileURL = fileURL
    }

    public func load() throws -> FixtureState {
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            return FixtureState()
        }
        return try JSONDecoder().decode(FixtureState.self, from: Data(contentsOf: fileURL))
    }

    public func save(_ state: FixtureState) throws {
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        try JSONEncoder().encode(state).write(to: fileURL, options: .atomic)
    }

    /// UI-test isolation removes only this fixture file, never the app container.
    public func reset() throws {
        if FileManager.default.fileExists(atPath: fileURL.path) {
            try FileManager.default.removeItem(at: fileURL)
        }
    }
}
