import XCTest

final class RemoteCodexUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private func launchFresh() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--fixture", "--ui-testing", "--reset-fixture"]
        app.launch()
        XCTAssertTrue(app.staticTexts["hello-world"].waitForExistence(timeout: 10))
        return app
    }

    private func screenshot(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testHelloWorldLaunch() {
        let app = launchFresh()
        XCTAssertEqual(app.staticTexts["hello-world"].label, "Hello, world!")
        XCTAssertEqual(app.staticTexts["fixture-status"].label, "Offline testing fixture")
        XCTAssertEqual(app.staticTexts["tap-count"].label, "Tap count: 0")
        XCTAssertTrue(app.staticTexts["build-info"].exists)
        XCTAssertFalse(app.staticTexts["storage-error"].exists)
        screenshot(app, name: "fixture-launch")
    }

    func testTapAndSaveNote() {
        let app = launchFresh()
        app.buttons["increment-button"].tap()
        app.buttons["increment-button"].tap()
        XCTAssertEqual(app.staticTexts["tap-count"].label, "Tap count: 2")
        let note = app.textFields["note-input"]
        XCTAssertTrue(note.waitForExistence(timeout: 5))
        note.tap()
        note.typeText("Hello iPhone")
        app.buttons["save-note"].tap()
        XCTAssertEqual(app.staticTexts["saved-note"].label, "Saved note: Hello iPhone")
        XCTAssertFalse(app.staticTexts["storage-error"].exists)
        screenshot(app, name: "fixture-interaction")
    }

    func testStateSurvivesRestart() {
        executionTimeAllowance = 120
        print("Fixture phase: persistence fresh launch")
        let app = launchFresh()
        print("Fixture phase: persistence increment")
        app.buttons["increment-button"].tap()
        let note = app.textFields["note-input"]
        XCTAssertTrue(note.waitForExistence(timeout: 5))
        print("Fixture phase: persistence note entry")
        note.tap()
        note.typeText("Saved fixture note")
        print("Fixture phase: persistence save")
        app.buttons["save-note"].tap()
        XCTAssertEqual(app.staticTexts["saved-note"].label, "Saved note: Saved fixture note")
        print("Fixture phase: persistence terminate")
        app.terminate()
        app.launchArguments = ["--fixture"]
        print("Fixture phase: persistence relaunch")
        app.launch()
        print("Fixture phase: persistence verify")
        XCTAssertTrue(app.staticTexts["hello-world"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["tap-count"].label, "Tap count: 1")
        XCTAssertEqual(app.staticTexts["saved-note"].label, "Saved note: Saved fixture note")
        XCTAssertFalse(app.staticTexts["storage-error"].exists)
        screenshot(app, name: "fixture-persistence")
    }
}
