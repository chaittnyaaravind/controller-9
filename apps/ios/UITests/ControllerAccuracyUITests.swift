import XCTest

/// Presses every control of the Xbox-style layout on a real device through the real touch pipeline.
/// The host's `--trace-input` log is the oracle: each step must send its own input and nothing else
/// (tools/ios-ci/check-trace.py). Steps are printed as "STEP <name>" so the trace can be matched.
final class ControllerAccuracyUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments = ["-layout", "pad:xbox", "-screen", "controller"]
        app.launch()
        XCTAssertTrue(app.buttons["A button"].waitForExistence(timeout: 10), "controller screen did not appear")
        sleep(3) // connect to the host
    }

    private func step(_ name: String, _ action: () -> Void) {
        print("STEP \(name) \(Date().timeIntervalSince1970)")
        action()
        usleep(450_000)
    }

    func testEveryControlSendsOnlyItself() {
        for b in ["A", "B", "X", "Y", "LB", "RB", "View", "Menu", "Guide", "LS", "RS"] {
            let el = app.buttons["\(b) button"]
            XCTAssertTrue(el.exists, "\(b) missing")
            step(b) { el.press(forDuration: 0.6) }
        }
        for t in ["LT", "RT"] { step(t) { app.buttons["\(t) trigger"].press(forDuration: 0.6) } }

        let pad = app.buttons["Direction pad"]
        // Thumb positions as people really land: left aimed slightly high, the others a little off-axis.
        for (name, x, y) in [("dpad-left-high", 0.14, 0.40), ("dpad-up", 0.55, 0.14), ("dpad-right", 0.86, 0.56), ("dpad-down", 0.45, 0.86)] {
            step(name) { pad.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)).press(forDuration: 0.6) }
        }

        // The gap between the top and right face buttons belongs to neither.
        let yb = app.buttons["Y button"].frame, bb = app.buttons["B button"].frame
        let gap = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: (yb.maxX + bb.minX) / 2, dy: (yb.maxY + bb.minY) / 2))
        step("face-gap") { gap.press(forDuration: 0.6) }

        for (name, label) in [("stick-left-right", "left stick"), ("stick-right-right", "right stick")] {
            let s = app.buttons[label]
            step(name) {
                s.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
                    .press(forDuration: 0.1, thenDragTo: s.coordinate(withNormalizedOffset: CGVector(dx: 1.0, dy: 0.5)), withVelocity: .default, thenHoldForDuration: 0.6)
            }
        }
        print("STEP done \(Date().timeIntervalSince1970)")
    }
}
