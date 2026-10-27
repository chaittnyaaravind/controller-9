import XCTest

/// Turns Gyro aim on through Settings (the setting persists), for the hand-held aim test on a device:
/// the player then turns the phone and the host's trace shows the right stick moving.
final class GyroSetupUITests: XCTestCase {
    func testTurnOnGyroAim() {
        let app = XCUIApplication()
        app.launchArguments = ["-screen", "settings"]
        app.launch()
        let toggle = app.switches["Gyro aim"].firstMatch
        XCTAssertTrue(toggle.waitForExistence(timeout: 10), "Gyro aim switch missing")
        if (toggle.value as? String) != "1" { toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap() }
        XCTAssertEqual(toggle.value as? String, "1", "Gyro aim did not turn on")
    }
}
