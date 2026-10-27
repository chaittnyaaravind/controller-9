import XCTest

/// Home Screen quick action: long-press the app icon → "Play <game>" opens that game's controller.
final class QuickActionUITests: XCTestCase {
    func testPlayQuickActionOpensController() throws {
        let app = XCUIApplication()
        app.launch()                       // publishes the quick actions
        XCTAssertTrue(app.buttons["Scan a friend's game"].waitForExistence(timeout: 10))
        XCUIDevice.shared.press(.home)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let icon = springboard.icons["Controller-9"]
        guard icon.waitForExistence(timeout: 5) else { throw XCTSkip("app icon not on the visible Home Screen page") }
        icon.press(forDuration: 1.5)
        let play = springboard.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play '")).firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 5), "quick action missing")
        play.tap()
        XCTAssertTrue(app.buttons["A button"].waitForExistence(timeout: 10), "controller did not open from the quick action")
    }
}
