import XCTest

/// Device captures for the store artwork. Fastlane supplies the locale and consumes the snapshot names.
@MainActor
final class ScreenshotTests: XCTestCase {
    private let app = XCUIApplication()

    private var isEnglish: Bool { Snapshot.deviceLanguage.hasPrefix("en") }

    override func setUpWithError() throws {
        continueAfterFailure = false
        setupSnapshot(app)
        app.launchArguments = Self.withoutSystemLocale(app.launchArguments)
        XCUIDevice.shared.appearance = .light
        app.launch()
        XCTAssertTrue(app.buttons["gallr-tab-home"].waitForExistence(timeout: 45))
        dismissTasteIfPresented()
        selectAppLanguage()
    }

    func testStoreCaptures() throws {
        selectTab("home")
        _ = try requireExhibition(prefix: "exhibition.row.", on: "Home")
        dismissTasteIfPresented(wait: 0)
        snapshot("01_Home")

        selectTab("list")
        chooseClosingFilter()
        _ = try requireExhibition(prefix: "exhibition.row.", on: "Closing this week")
        snapshot("02_ClosingThisWeek")

        resetList()
        saveExhibitions(count: 4)
        selectTab("map")
        requireMapWithSaves()
        snapshot("03_Map")

        selectTab("list")
        resetList()
        tap("list.mode.saved")
        _ = try requireExhibition(prefix: "exhibition.row.", on: "My list")
        snapshot("06_My")

        selectTab("home")
        try openEditorNote()
        snapshot("05_CuratorNote")
    }

    func testPreviewFlow() throws {
        selectTab("list")
        resetList()
        saveExhibitions(count: 3)

        selectTab("home")
        _ = try requireExhibition(prefix: "exhibition.row.", on: "Home")
        mark("home")
        app.swipeUp(velocity: .slow)
        try openEditorNote()
        mark("note")
        waitForPreviewShot()
        tap("gallr-back")

        selectTab("list")
        resetList()
        mark("list")
        chooseClosingFilter()
        _ = try requireExhibition(prefix: "exhibition.row.", on: "Closing this week")
        app.swipeUp(velocity: .slow)

        selectTab("map")
        requireMapWithSaves()
        mark("map")
        waitForPreviewShot()
        mark("end")
    }

    private struct MissingCaptureContent: Error {}

    private func requireExhibition(prefix: String, on surface: String) throws -> XCUIElement {
        let cards = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix))
        let deadline = Date().addingTimeInterval(35)
        var swipes = 0
        while Date() < deadline {
            dismissTasteIfPresented(wait: 0)
            let visible = cards.allElementsBoundByIndex.first(where: { $0.isHittable })
            if let visible { return visible }
            if cards.firstMatch.exists, swipes < 5 {
                app.swipeUp(velocity: .slow)
                swipes += 1
            } else {
                RunLoop.current.run(until: Date().addingTimeInterval(0.35))
            }
        }
        XCTFail("\(surface) did not show a visible exhibition card.")
        throw MissingCaptureContent()
    }

    private func openEditorNote() throws {
        let stories = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "home.editor-note."))
        for _ in 0..<10 {
            if let story = stories.allElementsBoundByIndex.first(where: { $0.isHittable }) {
                story.tap()
                let note = element("exhibition.editor-note")
                if note.waitForExistence(timeout: 10), note.isHittable { return }
                for _ in 0..<8 {
                    app.swipeUp(velocity: .slow)
                    if note.exists, note.isHittable { return }
                }
                break
            }
            app.swipeUp(velocity: .slow)
        }
        XCTFail("The Home story did not show a visible editor’s note.")
        throw MissingCaptureContent()
    }

    private func requireMapWithSaves() {
        let map = element("map.control.plus")
        let legend = element("map.legend.saved")
        XCTAssertTrue(map.waitForExistence(timeout: 20) && map.isHittable)
        XCTAssertTrue(legend.waitForExistence(timeout: 20) && legend.isHittable)
        let pins = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@ OR identifier == %@", "map.pin.", "map.cluster"))
        XCTAssertTrue(pins.firstMatch.waitForExistence(timeout: 20), "The Map did not place exhibition pins.")
        XCTAssertTrue(pins.allElementsBoundByIndex.contains(where: { $0.isHittable }), "No map pins are visible.")
    }

    private func chooseClosingFilter() {
        scrollListToTop()
        let filter = app.buttons["list.filter.closing"]
        let rail = app.scrollViews["list.filters"]
        if !filter.exists || !filter.isHittable {
            if rail.exists { rail.swipeLeft(velocity: .slow) }
        }
        tap("list.filter.closing")
        XCTAssertTrue(filter.isSelected)
    }

    private func resetList() {
        scrollListToTop()
        tap("list.mode.all")
        if let clear = visibleButton(["필터 초기화", "Clear filters"]) { clear.tap() }
        let allCities = app.buttons["list.filter.all-cities"]
        if allCities.exists, allCities.isHittable { allCities.tap() }
    }

    private func saveExhibitions(count: Int) {
        let addLabels = ["북마크 추가", "Add bookmark"]
        var saved = 0
        for _ in 0..<(count + 8) where saved < count {
            if let button = visibleButton(addLabels, prefix: "exhibition.bookmark.", fullyVisible: true) {
                let bookmark = app.buttons[button.identifier]
                bookmark.tap()
                dismissNotificationPrompt()
                dismissAccountNudge()
                let savedLabel = NSPredicate(format: "label IN %@", ["북마크 삭제", "Remove bookmark"])
                let savedResult = XCTNSPredicateExpectation(predicate: savedLabel, object: bookmark)
                XCTAssertEqual(XCTWaiter.wait(for: [savedResult], timeout: 5), .completed)
                saved += 1
            } else if !dismissAccountNudge() {
                app.swipeUp(velocity: .slow)
                RunLoop.current.run(until: Date().addingTimeInterval(0.35))
            }
        }
        XCTAssertEqual(saved, count, "The catalogue did not provide enough visible exhibitions to save.")
    }

    private func scrollListToTop() {
        let search = app.textFields["list.search"]
        for _ in 0..<10 {
            if search.exists, search.isHittable { break }
            app.swipeDown(velocity: .fast)
        }
    }

    private func selectAppLanguage() {
        let current = app.buttons["gallr-tab-home"]
        guard current.label == (isEnglish ? "홈" : "HOME") else { return }
        selectTab("my")
        tap("gallr-settings")
        let languages = app.buttons.matching(NSPredicate(
            format: "label BEGINSWITH %@ OR label BEGINSWITH %@", "언어", "Language"
        ))
        let picker = languages.firstMatch
        XCTAssertTrue(picker.waitForExistence(timeout: 10))
        picker.tap()
        let value = app.buttons[isEnglish ? "English" : "한국어"].firstMatch
        XCTAssertTrue(value.waitForExistence(timeout: 5))
        value.tap()
        selectTab("home")
        let translated = NSPredicate(format: "label == %@", isEnglish ? "HOME" : "홈")
        let translation = XCTNSPredicateExpectation(predicate: translated, object: current)
        XCTAssertEqual(XCTWaiter.wait(for: [translation], timeout: 5), .completed)
    }

    private func dismissTasteIfPresented(wait: TimeInterval = 3) {
        let skip = app.buttons.matching(NSPredicate(format: "label IN %@", ["건너뛰기", "Skip"])).firstMatch
        if (wait == 0 ? skip.exists : skip.waitForExistence(timeout: wait)), skip.isHittable { skip.tap() }
    }

    private func dismissNotificationPrompt() {
        let later = app.alerts.buttons.matching(NSPredicate(format: "label IN %@", ["나중에", "Not now"])).firstMatch
        if later.waitForExistence(timeout: 1), later.isHittable { later.tap() }
    }

    @discardableResult
    private func dismissAccountNudge() -> Bool {
        let title = app.staticTexts.matching(NSPredicate(
            format: "label IN %@", ["전시 5개를 저장했어요.", "You’ve saved 5 exhibitions."]
        )).firstMatch
        guard title.exists, let later = visibleButton(["나중에", "Not now"]) else { return false }
        later.tap()
        return true
    }

    private func selectTab(_ name: String) {
        tap("gallr-tab-\(name)")
    }

    private func tap(_ identifier: String) {
        let target = element(identifier)
        XCTAssertTrue(target.waitForExistence(timeout: 12) && target.isHittable, "Missing visible control \(identifier).")
        target.tap()
    }

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    private func visibleButton(
        _ labels: [String],
        prefix: String? = nil,
        fullyVisible: Bool = false
    ) -> XCUIElement? {
        let usable = fullyVisible ? app.windows.firstMatch.frame.insetBy(dx: 8, dy: 104) : .null
        let buttons = app.buttons.matching(NSPredicate(format: "label IN %@", labels)).allElementsBoundByIndex
        return buttons.first { element in
            element.isHittable && (prefix.map { element.identifier.hasPrefix($0) } ?? true)
                && (!fullyVisible || usable.contains(element.frame))
        }
    }

    private func mark(_ shot: String) {
        print("PREVIEW_MARK \(shot) \(Date().timeIntervalSince1970)")
    }

    private func waitForPreviewShot() {
        RunLoop.current.run(until: Date().addingTimeInterval(2))
    }

    private static func withoutSystemLocale(_ arguments: [String]) -> [String] {
        var kept: [String] = []
        var skipNext = false
        for argument in arguments {
            if skipNext { skipNext = false; continue }
            if argument == "-AppleLanguages" || argument == "-AppleLocale" { skipNext = true; continue }
            kept.append(argument)
        }
        return kept
    }
}
