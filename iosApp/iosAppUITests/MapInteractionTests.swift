import XCTest

@MainActor
final class MapInteractionTests: XCTestCase {
    func testPinchFromClusterDoesNotOpenSelection() throws {
        let app = XCUIApplication()
        app.launchArguments = ["--native-fixture"]
        app.resetAuthorizationStatus(for: .location)
        app.launch()
        dismissFirstRun(in: app)
        let mapTab = app.buttons["gallr-tab-map"]
        XCTAssertTrue(mapTab.waitForExistence(timeout: 15))
        mapTab.tap()
        denyLocationIfRequested()

        let cluster = app.descendants(matching: .any).matching(identifier: "map.cluster").firstMatch
        XCTAssertTrue(cluster.waitForExistence(timeout: 10))
        XCTAssertTrue(cluster.isHittable)

        cluster.pinch(withScale: 0.5, velocity: -5)
        let sheet = app.staticTexts["map.selection.title"]
        XCTAssertFalse(sheet.waitForExistence(timeout: 1))

        XCTAssertTrue(cluster.waitForExistence(timeout: 10))
        cluster.tap()
        XCTAssertTrue(sheet.waitForExistence(timeout: 5))
        let exhibition = app.buttons["exhibition.row.native-fixture"]
        XCTAssertTrue(exhibition.waitForExistence(timeout: 5))
        exhibition.tap()
        XCTAssertTrue(app.staticTexts["exhibition.title.native-fixture"].waitForExistence(timeout: 5))
    }

    private func dismissFirstRun(in app: XCUIApplication) {
        let skip = app.buttons.matching(NSPredicate(format: "label IN %@", ["건너뛰기", "Skip"])).firstMatch
        if skip.waitForExistence(timeout: 3), skip.isHittable { skip.tap() }
    }

    private func denyLocationIfRequested() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let deny = springboard.buttons.matching(NSPredicate(format: "label IN %@", ["허용 안 함", "Don’t Allow", "Don't Allow"])).firstMatch
        if deny.waitForExistence(timeout: 5) { deny.tap() }
    }
}
