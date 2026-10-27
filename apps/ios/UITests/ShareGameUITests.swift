import XCTest

/// Long-press a game on Home → "Share by QR code" shows its QR code; "Scan a game" is offered beside it.
final class ShareGameUITests: XCTestCase {
    func testShareShowsQRCode() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.buttons["Scan a friend's game"].waitForExistence(timeout: 10), "Scan a game card missing")
        let card = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Play '")).firstMatch
        XCTAssertTrue(card.exists)
        card.press(forDuration: 1.2)
        let share = app.buttons["Share by QR code"]
        XCTAssertTrue(share.waitForExistence(timeout: 5), "context menu missing")
        share.tap()
        XCTAssertTrue(app.images.matching(NSPredicate(format: "label BEGINSWITH 'QR code for'")).firstMatch.waitForExistence(timeout: 5), "QR code not shown")
    }
}
