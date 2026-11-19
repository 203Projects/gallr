import XCTest

@MainActor
final class NativeNavigationTests: XCTestCase {
    func testTabTrailSurvivesSwitchAndRotationThenRetapReturnsToRoot() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--native-fixture"]
        app.launch()
        dismissFirstRun(on: app)
        defer { XCUIDevice.shared.orientation = .portrait }

        let myTab = app.buttons["gallr-tab-my"]
        let homeTab = app.buttons["gallr-tab-home"]
        XCTAssertTrue(myTab.waitForExistence(timeout: 30))
        myTab.tap()

        let settings = app.buttons["gallr-settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 15))
        settings.tap()
        let back = app.buttons["gallr-back"]
        XCTAssertTrue(back.waitForExistence(timeout: 15))

        homeTab.tap()
        XCTAssertTrue(back.waitForNonExistence(timeout: 5))
        myTab.tap()
        XCTAssertTrue(back.waitForExistence(timeout: 10))

        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(back.waitForExistence(timeout: 10))
        myTab.tap()
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        XCTAssertTrue(back.waitForNonExistence(timeout: 5))
    }
    private func dismissFirstRun(on app: XCUIApplication) {
        let skip = app.buttons.matching(NSPredicate(format: "label IN %@", ["건너뛰기", "Skip"])).firstMatch
        if skip.waitForExistence(timeout: 3), skip.isHittable {
            skip.tap()
        }
    }

    func testCompactAndExpandedLayoutKeepTheSameCatalogueSelection() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--native-fixture", "--native-adaptive-fixture"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        guard app.frame.width >= 650 else { throw XCTSkip("A wide Simulator window is required.") }
        dismissFirstRun(on: app)

        let listTab = app.buttons["gallr-tab-list"]
        let exhibition = app.buttons["exhibition.row.native-fixture"].firstMatch
        let title = app.staticTexts["exhibition.title.native-fixture"].firstMatch
        let resize = app.buttons["gallr-fixture-resize"]

        XCTAssertTrue(listTab.waitForExistence(timeout: 30))
        listTab.tap()
        XCTAssertTrue(exhibition.waitForExistence(timeout: 15))
        exhibition.tap()
        XCTAssertTrue(title.waitForExistence(timeout: 15))
        XCTAssertTrue(exhibition.waitForNonExistence(timeout: 5))

        resize.tap()
        XCTAssertTrue(exhibition.waitForExistence(timeout: 15))
        XCTAssertTrue(exhibition.isHittable)
        XCTAssertTrue(title.exists)
        XCTAssertGreaterThan(title.frame.minX, exhibition.frame.maxX)

        resize.tap()
        XCTAssertTrue(title.exists)
        XCTAssertTrue(exhibition.waitForNonExistence(timeout: 5))
    }

    func testCatalogueSelectionSurvivesWindowRotationAndTabChanges() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--native-fixture"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        dismissFirstRun(on: app)
        defer { XCUIDevice.shared.orientation = .portrait }

        let listTab = app.buttons["gallr-tab-list"]
        let homeTab = app.buttons["gallr-tab-home"]
        let exhibition = app.buttons["exhibition.row.native-fixture"].firstMatch
        let title = app.staticTexts["exhibition.title.native-fixture"].firstMatch
        XCTAssertTrue(listTab.waitForExistence(timeout: 30))
        listTab.tap()
        XCTAssertTrue(exhibition.waitForExistence(timeout: 15))
        exhibition.tap()
        XCTAssertTrue(title.waitForExistence(timeout: 15))

        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(title.waitForExistence(timeout: 15))
        let back = app.buttons["gallr-back"]
        homeTab.tap()
        XCTAssertTrue(back.waitForNonExistence(timeout: 5))
        XCTAssertTrue(homeTab.isSelected)
        listTab.tap()
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        XCTAssertTrue(listTab.isSelected)
        XCTAssertTrue(back.isHittable)
        back.tap()
        XCTAssertTrue(back.waitForNonExistence(timeout: 5))
        XCTAssertTrue(exhibition.waitForExistence(timeout: 15))
        XCTAssertTrue(title.waitForNonExistence(timeout: 5))
    }
}

extension NativeNavigationTests {
    func testDraftReopensAfterLeavingRouteComposer() {
        let app = XCUIApplication()
        app.launchArguments = ["--native-fixture"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()

        let myTab = app.buttons["gallr-tab-my"]
        XCTAssertTrue(myTab.waitForExistence(timeout: 30))
        myTab.tap()
        let routes = app.buttons["gallr-my-routes"]
        XCTAssertTrue(routes.waitForExistence(timeout: 10))
        routes.tap()
        let makeRoute = app.buttons["native-route-new"]
        XCTAssertTrue(makeRoute.waitForExistence(timeout: 10))
        makeRoute.tap()

        let replaceDraft = app.buttons.matching(
            NSPredicate(format: "label IN %@", ["현재 초안 바꾸기", "REPLACE DRAFT"])
        ).firstMatch
        if replaceDraft.waitForExistence(timeout: 2) { replaceDraft.tap() }

        let name = app.textFields["native-route-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.tap()
        name.typeText("Native route draft")
        let addStops = app.buttons["native-route-add-stops"]
        if !addStops.isHittable { app.swipeUp() }
        XCTAssertTrue(addStops.waitForExistence(timeout: 10))
        addStops.tap()
        let stop = app.buttons["native-route-stop.native-fixture"]
        XCTAssertTrue(stop.waitForExistence(timeout: 10))
        stop.tap()
        app.buttons["native-route-confirm-stops"].tap()

        let back = app.buttons["gallr-back"]
        XCTAssertTrue(back.waitForExistence(timeout: 10))
        back.tap()
        let draft = app.buttons["native-route-draft"]
        XCTAssertTrue(draft.waitForExistence(timeout: 10))
        draft.tap()
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        XCTAssertEqual(name.value as? String, "Native route draft")
    }
}
