import XCTest

final class RemoteCodexUITests: XCTestCase {
    private func screenshot(_ app: XCUIApplication, name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
    override func setUpWithError() throws { continueAfterFailure = false }
    func testFixtureChatAndSend() {
        let app = XCUIApplication()
        app.launchArguments = ["--fixture", "--reset-fixture"]
        app.launch()
        let chat = app.buttons["chat-fixture-chat"]
        XCTAssertTrue(chat.waitForExistence(timeout: 10))
        chat.tap()
        XCTAssertTrue(app.staticTexts["This conversation contains synthetic content only."].waitForExistence(timeout: 10))
        let draft = app.textFields["message-draft"]
        XCTAssertTrue(draft.waitForExistence(timeout: 5))
        draft.tap(); draft.typeText("Hello iPhone")
        app.buttons["send-message"].tap()
        XCTAssertTrue(app.staticTexts["Fixture reply: Hello iPhone"].waitForExistence(timeout: 10))
        screenshot(app, name: "fixture-chat")
    }
    func testFixtureSettingsNeverOffersLiveConnection() {
        let app = XCUIApplication()
        app.launchArguments = ["--fixture", "--reset-fixture"]
        app.launch()
        app.buttons["settings"].tap()
        XCTAssertTrue(app.staticTexts["Fixture mode uses synthetic content and never connects to a server."].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Save and connect"].exists)
        screenshot(app, name: "fixture-settings")
    }
    func testDraftSurvivesRestart() {
        let app = XCUIApplication()
        app.launchArguments = ["--fixture", "--reset-fixture"]
        app.launch()
        let chat = app.buttons["chat-fixture-chat"]
        XCTAssertTrue(chat.waitForExistence(timeout: 10)); chat.tap()
        let draft = app.textFields["message-draft"]
        XCTAssertTrue(draft.waitForExistence(timeout: 5))
        draft.tap()
        if let existing = draft.value as? String, existing != "Message", !existing.isEmpty {
            draft.press(forDuration: 1.2)
            if app.menuItems["Select All"].exists { app.menuItems["Select All"].tap(); draft.typeText(XCUIKeyboardKey.delete.rawValue) }
        }
        draft.typeText("Saved fixture draft")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.terminate()
        app.launchArguments = ["--fixture"]
        app.launch()
        XCTAssertTrue(chat.waitForExistence(timeout: 10)); chat.tap()
        XCTAssertTrue(draft.waitForExistence(timeout: 5))
        XCTAssertTrue((draft.value as? String)?.contains("Saved fixture draft") == true)
        screenshot(app, name: "fixture-draft")
    }
}
