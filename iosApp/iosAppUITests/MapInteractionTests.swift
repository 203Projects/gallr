import XCTest

@MainActor
final class MapInteractionTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUpWithError() throws {
        continueAfterFailure = false
        app.launchArguments = ["-AppleLanguages", "(ko)"]
        app.resetAuthorizationStatus(for: .location)
        app.launch()
        sleep(6)
    }

    /// A pinch that begins on a grouped exhibition pin must remain a map gesture instead of
    /// opening the overlap sheet.
    ///
    /// This asserts only that the sheet stays closed. XCUITest keeps both fingers inside the pin's
    /// 44pt target, and the map engine treats two touches as a pinch only when they are about 6 mm
    /// apart and moving quickly, so a pinch that narrow may legitimately leave the camera still.
    /// XCUITest has no wider two-finger gesture that starts on a chosen point, so check by hand
    /// after a map engine upgrade that a normal pinch starting on a pin zooms the map.
    func testPinOriginPinchArbitration() throws {
        returnToRootNavigationIfNeeded()
        navigateToMap()
        dismissLocationAlert()

        let groupPinPredicate = NSPredicate(format: "label CONTAINS '그룹. 목록 열기'")
        let overlapSheetPredicate = NSPredicate(format: "label BEGINSWITH '이 주변 전시'")

        for attempt in 1...5 {
            let groupPin = app.buttons.matching(groupPinPredicate).firstMatch
            XCTAssertTrue(
                groupPin.waitForExistence(timeout: 3) && groupPin.isHittable,
                "Grouped pin must be available before pinch attempt \(attempt)"
            )

            groupPin.pinch(withScale: 0.5, velocity: -5.0)
            sleep(1)

            let overlapSheet = app.staticTexts.matching(overlapSheetPredicate).firstMatch
            XCTAssertFalse(overlapSheet.exists, "Pinch attempt \(attempt) opened the overlap sheet")
        }
    }

    private func returnToRootNavigationIfNeeded() {
        for _ in 0..<4 {
            if app.buttons["지도"].exists {
                return
            }
            let backButton = app.buttons["←"]
            guard backButton.waitForExistence(timeout: 1) else { break }
            backButton.tap()
            sleep(1)
        }
        XCTAssertTrue(app.buttons["지도"].exists, "Root navigation must be available")
    }

    private func navigateToMap() {
        let mapTab = app.buttons["지도"]
        XCTAssertTrue(mapTab.waitForExistence(timeout: 3), "Map tab must be available")
        mapTab.tap()
        sleep(3)
    }

    private func dismissLocationAlert() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let dontAllow = springboard.buttons["허용 안 함"]
        if dontAllow.waitForExistence(timeout: 3) {
            dontAllow.tap()
            sleep(1)
            return
        }
        let dontAllowEnglish = springboard.buttons["Don't Allow"]
        if dontAllowEnglish.waitForExistence(timeout: 1) {
            dontAllowEnglish.tap()
            sleep(1)
        }
    }
}
