import XCTest

/// UI tests that capture App Store screenshots via fastlane snapshot.
///
/// All screenshots: Korean language, dark mode.
/// Run via: `cd iosApp && fastlane screenshots`
@MainActor
class ScreenshotTests: XCTestCase {

    let app = XCUIApplication()

    override func setUpWithError() throws {
        continueAfterFailure = false

        app.launchArguments = ["-AppleLanguages", "(ko)"]
        setupSnapshot(app)

        // Force dark system appearance (ThemeMode.SYSTEM resolves to dark)
        XCUIDevice.shared.appearance = .dark

        // Never grant location (map fallback screenshot)
        app.resetAuthorizationStatus(for: .location)
        app.launch()

        sleep(6) // wait for Supabase data load

        // Toggle Settings UI to ensure Korean + Dark mode
        switchToKoreanDarkMode()

        // Clear any persisted bookmarks from previous test runs
        clearAllBookmarks()
    }

    // MARK: - Screenshot Sequence

    /// 1. Featured screen landing page (Korean, dark mode)
    func test01_Featured() {
        snapshot("01_Featured")
    }

    /// 2. 전체 전시 > 대한민국 > 전체 > 이번 주 오픈 (Korean, dark mode)
    func test02_ListOpeningThisWeek() {
        navigateToList()
        tapElement("전체 전시")
        sleep(1)
        tapElement("이번 주 오픈")
        sleep(1)
        snapshot("02_List_OpeningThisWeek")
    }

    /// 3. 전체 전시 > 대한민국 > 전체 > 이번 주 종료 (Korean, dark mode)
    func test03_ListClosingThisWeek() {
        navigateToList()
        tapElement("전체 전시")
        sleep(1)
        tapElement("이번 주 종료")
        sleep(1)
        snapshot("03_List_ClosingThisWeek")
    }

    /// 4. 지도 > 전체 — fallback view when no location access (Korean, dark mode)
    func test04_MapAll() {
        navigateToMap()
        tapMapTab("전체")
        sleep(2)
        dismissLocationAlert()
        sleep(1)
        snapshot("04_Map_All")
    }

    /// 5. 지도 > 내 전시 — with bookmarked exhibition pins (Korean, dark mode)
    func test05_MapMyList() {
        bookmarkVisibleExhibitions(count: 6)

        navigateToMap()
        tapMapTab("내 전시")
        sleep(3)
        dismissLocationAlert()
        sleep(1)
        snapshot("05_Map_MyList")
    }

    // MARK: - Settings: Korean + Dark Mode

    /// Open Settings menu, switch to Korean (if English), then cycle theme to Dark.
    private func switchToKoreanDarkMode() {
        openSettingsMenu()

        // If language is English, switch to Korean (menu auto-closes on language tap)
        let englishLabel = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label BEGINSWITH 'Language:'")).firstMatch
        if englishLabel.waitForExistence(timeout: 2) {
            englishLabel.tap()
            sleep(2)
            openSettingsMenu()
        }

        // Cycle theme to Dark. Only the active theme is shown in the menu.
        // Cycle: SYSTEM → LIGHT → DARK → SYSTEM. Menu stays open after theme taps.
        for _ in 0..<3 {
            let darkKo = app.descendants(matching: .any)
                .matching(NSPredicate(format: "label CONTAINS '테마: 다크'")).firstMatch
            let darkEn = app.descendants(matching: .any)
                .matching(NSPredicate(format: "label CONTAINS 'Theme: Dark'")).firstMatch
            if darkKo.waitForExistence(timeout: 1) || darkEn.exists {
                break
            }
            let themeItem = app.descendants(matching: .any)
                .matching(NSPredicate(format: "label CONTAINS '테마:' OR label CONTAINS 'Theme:'")).firstMatch
            guard themeItem.waitForExistence(timeout: 1) else { break }
            themeItem.tap()
            sleep(1)
        }

        // Dismiss menu by tapping outside
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8)).tap()
        sleep(1)
    }

    private func openSettingsMenu() {
        let settingsBtn = app.buttons["Settings"]
        if settingsBtn.waitForExistence(timeout: 3) {
            settingsBtn.tap()
            sleep(1)
        }
    }

    // MARK: - Navigation Helpers

    private func navigateToList() {
        // "목록" is the List tab — use buttons to target nav bar specifically
        let listTab = app.buttons["목록"]
        if listTab.waitForExistence(timeout: 3) {
            listTab.tap()
        }
        sleep(2)
    }

    private func navigateToMap() {
        let mapTab = app.buttons["지도"]
        if mapTab.waitForExistence(timeout: 3) {
            mapTab.tap()
        }
        sleep(3)
    }

    private func tapMapTab(_ label: String) {
        let text = app.staticTexts[label]
        if text.waitForExistence(timeout: 3) {
            text.tap()
            return
        }
        let btn = app.buttons[label]
        if btn.waitForExistence(timeout: 2) {
            btn.tap()
            return
        }
        let pred = NSPredicate(format: "label == %@", label)
        let match = app.descendants(matching: .any).matching(pred).firstMatch
        if match.waitForExistence(timeout: 2) {
            match.tap()
        }
    }

    // MARK: - Bookmark Helpers

    /// Bookmark up to `count` exhibitions from the Featured screen.
    /// Featured cards have "Add bookmark" buttons visible without scrolling.
    private func bookmarkVisibleExhibitions(count: Int) {
        // We're on the Featured tab already. Tap "Add bookmark" buttons one by one.
        var bookmarked = 0
        for _ in 0..<(count + 5) {
            guard bookmarked < count else { break }
            let btn = app.buttons.matching(identifier: "Add bookmark").firstMatch
            if btn.waitForExistence(timeout: 2) && btn.isHittable {
                btn.tap()
                sleep(1)
                bookmarked += 1
            } else {
                // Scroll down to reveal more cards
                app.swipeUp()
                sleep(1)
            }
        }
    }

    /// Remove all existing bookmarks.
    private func clearAllBookmarks() {
        for _ in 0..<20 {
            let btn = app.buttons.matching(identifier: "Remove bookmark").element(boundBy: 0)
            guard btn.waitForExistence(timeout: 1) else { break }
            btn.tap()
            sleep(1)
        }
    }

    /// Dismiss iOS location permission alert.
    private func dismissLocationAlert() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let dontAllow = springboard.buttons["허용 안 함"]
        if dontAllow.waitForExistence(timeout: 3) {
            dontAllow.tap()
            sleep(1)
            return
        }
        let dontAllowEn = springboard.buttons["Don't Allow"]
        if dontAllowEn.waitForExistence(timeout: 1) {
            dontAllowEn.tap()
            sleep(1)
        }
    }

    // MARK: - General UI Helpers

    /// Tap an element by label — tries StaticText, then Button, then any descendant.
    private func tapElement(_ label: String) {
        let text = app.staticTexts[label]
        if text.waitForExistence(timeout: 3) {
            text.tap()
            return
        }
        let btn = app.buttons[label]
        if btn.waitForExistence(timeout: 2) {
            btn.tap()
            return
        }
        let pred = NSPredicate(format: "label == %@", label)
        let match = app.descendants(matching: .any).matching(pred).firstMatch
        if match.waitForExistence(timeout: 2) {
            match.tap()
        }
    }
}

/// Uses the visible catalogue; changes no account data and never chooses a recipient.
@MainActor
class SharePreviewTests: XCTestCase {
    func testPreviewCanShareAgainAfterOutsideDismissal() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launch()
        let exhibition = app.buttons.matching(NSPredicate(format: "label CONTAINS ' – '")).firstMatch
        XCTAssertTrue(exhibition.waitForExistence(timeout: 20))
        exhibition.tap()

        let openShare = app.buttons.matching(
            NSPredicate(format: "label == %@ OR label == %@", "전시 공유", "Share exhibition")
        ).firstMatch
        XCTAssertTrue(openShare.waitForExistence(timeout: 5))
        openShare.tap()
        let share = app.buttons.matching(
            NSPredicate(format: "label == %@ OR label == %@", "공유하기", "Share")
        ).firstMatch
        XCTAssertTrue(share.waitForExistence(timeout: 5))
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in share.isEnabled }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)

        for attempt in 1...2 {
            share.tap()
            let presenting = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in !share.isEnabled }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [presenting], timeout: 5), .completed)
            let attachment = XCTAttachment(screenshot: app.screenshot())
            attachment.name = "share-sheet-\(attempt)"
            attachment.lifetime = .keepAlways
            add(attachment)
            // Outside both the compact phone sheet and the centered iPad popover.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.25)).tap()
            let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in share.isEnabled }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 5), .completed)
        }
        let back = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@ OR label BEGINSWITH %@", "뒤로", "Back")
        ).firstMatch
        back.tap()
        XCTAssertTrue(openShare.waitForExistence(timeout: 5))
    }

    func testPreviewThemeAndLanguageMatrix() {
        continueAfterFailure = false
        let app = XCUIApplication()
        defer { XCUIDevice.shared.appearance = .light }
        app.launch()
        // Apply after launch so the app observes the active simulator trait change.
        XCUIDevice.shared.appearance = .dark

        func button(_ prefixes: [String]) -> XCUIElement {
            app.buttons.matching(NSCompoundPredicate(orPredicateWithSubpredicates:
                prefixes.map { NSPredicate(format: "label BEGINSWITH %@", $0) }
            )).firstMatch
        }
        func tap(_ prefixes: [String]) {
            let target = button(prefixes)
            XCTAssertTrue(target.waitForExistence(timeout: 10))
            target.tap()
        }
        func setPreferences(language: String, theme: String) {
            tap(["MY"])
            tap(["Settings", "설정"])
            tap(["Language", "언어"])
            tap([language == "ko" ? "한국어" : "English"])
            tap(["Appearance", "화면 모드"])
            let labels = ["System": ["System", "시스템"], "Light": ["Light", "라이트"], "Dark": ["Dark", "다크"]]
            tap(labels[theme]!)
            tap(["Back", "뒤로"])
            tap(["FEATURED", "추천"])
        }
        func capturePreview(_ name: String) {
            let exhibition = app.buttons.matching(NSPredicate(format: "label CONTAINS ' – '")).firstMatch
            XCTAssertTrue(exhibition.waitForExistence(timeout: 20))
            exhibition.tap()
            tap(["Share exhibition", "전시 공유"])
            let share = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "공유하기", "Share")).firstMatch
            XCTAssertTrue(share.waitForExistence(timeout: 5))
            let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in share.isEnabled }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
            let attachment = XCTAttachment(screenshot: app.screenshot())
            attachment.name = name
            attachment.lifetime = .keepAlways
            add(attachment)
            tap(["Back", "뒤로"])
            tap(["←"])
        }

        for language in ["ko", "en"] {
            for theme in ["System", "Light", "Dark"] {
                setPreferences(language: language, theme: theme)
                capturePreview("preview-\(language)-\(theme)-os-dark")
            }
        }
        setPreferences(language: "ko", theme: "System")
        XCUIDevice.shared.appearance = .light
        capturePreview("preview-ko-System-os-light")
    }
}
