import XCTest

/// Turbo end to end: set A to Turbo in the layout's button settings, then hold A on the controller.
/// The host's `--trace-input` log is the oracle (tools/ios-ci/check-turbo.py counts the A presses).
final class TurboUITests: XCTestCase {
    func testHoldingTurboButtonFiresRepeatedly() {
        let setup = XCUIApplication()
        setup.launchArguments = ["-screen", "profiles"]
        setup.launch()
        let link = setup.buttons["Turbo and toggle buttons"].firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 10))
        link.tap()
        let a = setup.buttons.matching(NSPredicate(format: "label BEGINSWITH 'A'")).firstMatch
        XCTAssertTrue(a.waitForExistence(timeout: 5), "A row missing")
        a.tap()
        let turbo = setup.buttons["Turbo"].firstMatch
        XCTAssertTrue(turbo.waitForExistence(timeout: 5), "Turbo option missing")
        turbo.tap()
        setup.terminate()

        let app = XCUIApplication()
        app.launchArguments = ["-layout", "pad:xbox", "-screen", "controller"]
        app.launch()
        XCTAssertTrue(app.buttons["A button"].waitForExistence(timeout: 10))
        sleep(3) // connect to the host
        print("STEP turbo-hold \(Date().timeIntervalSince1970)")
        app.buttons["A button"].press(forDuration: 1.5)
        usleep(400_000)
        print("STEP done \(Date().timeIntervalSince1970)")
    }
}
