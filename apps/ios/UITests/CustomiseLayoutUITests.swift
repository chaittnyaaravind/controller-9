import XCTest

/// "Customise positions and sizes" on a controller layout opens the layout editor with every control of
/// that layout, each one movable on its own (the most requested feature in competitors' reviews).
final class CustomiseLayoutUITests: XCTestCase {
    func testCustomiseOpensEditorWithEveryControl() {
        let app = XCUIApplication()
        app.launchArguments = ["-screen", "profiles"]
        app.launch()
        let customise = app.buttons["Customise positions and sizes"].firstMatch
        XCTAssertTrue(customise.waitForExistence(timeout: 10), "Customise button missing")
        customise.tap()
        XCTAssertTrue(app.buttons["Save"].waitForExistence(timeout: 10), "editor did not open")
        // Xbox-style has 16 working controls: 2 triggers, 2 bumpers, 4 face buttons, 2 sticks, D-pad,
        // 2 stick presses, View, Guide, Menu (Share sends nothing). Each is its own draggable control.
        let draggable = app.descendants(matching: .any).matching(NSPredicate(format: "label ENDSWITH 'Drag to move.'"))
        XCTAssertEqual(draggable.count, 16, "controls in the editor: \(draggable.allElementsBoundByIndex.map(\.label))")
    }
}
