import Foundation
import XCTest
@testable import HelloWorldFixtureCore

final class FixtureStoreTests: XCTestCase {
    private var directory: URL!
    private var store: FixtureStore!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        store = FixtureStore(fileURL: directory.appendingPathComponent("fixture/state.json"))
    }

    override func tearDownWithError() throws {
        if FileManager.default.fileExists(atPath: directory.path) {
            try FileManager.default.removeItem(at: directory)
        }
    }

    func testFreshInstallStartsEmpty() throws {
        XCTAssertEqual(try store.load(), FixtureState())
    }

    func testSavedNoteAndCounterSurviveNewStoreInstance() throws {
        let state = FixtureState(tapCount: 3, savedNote: "Hello iPhone 👋\nSecond line")
        try store.save(state)
        let reopened = FixtureStore(fileURL: directory.appendingPathComponent("fixture/state.json"))
        XCTAssertEqual(try reopened.load(), state)
    }

    func testSavingCounterPreservesNote() throws {
        try store.save(FixtureState(savedNote: "Keep this note"))
        var state = try store.load()
        state.tapCount += 1
        try store.save(state)
        XCTAssertEqual(try store.load(), FixtureState(tapCount: 1, savedNote: "Keep this note"))
    }

    func testMalformedStorageIsReportedInsteadOfSilentlyReset() throws {
        try store.save(FixtureState(savedNote: "Existing note"))
        try Data("invalid JSON".utf8).write(to: directory.appendingPathComponent("fixture/state.json"))
        XCTAssertThrowsError(try store.load())
    }

    func testResetPreservesOtherFiles() throws {
        try store.save(FixtureState(tapCount: 4, savedNote: "Synthetic note"))
        let sibling = directory.appendingPathComponent("fixture/other.json")
        try Data("keep".utf8).write(to: sibling)
        try store.reset()
        XCTAssertEqual(try store.load(), FixtureState())
        XCTAssertEqual(try Data(contentsOf: sibling), Data("keep".utf8))
    }

    func testResetBeforeFirstSaveIsHarmless() throws {
        try store.reset()
        XCTAssertEqual(try store.load(), FixtureState())
    }
}
