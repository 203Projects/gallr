import XCTest

/// Raw UI captures for the App Store set (spec docs/superpowers/specs/2026-10-14-store-creatives-refresh-design.md).
///
/// Light appearance and production data. The app keeps its own language setting, so an `en-US` run
/// switches it through MY → Settings → Language before capturing. The captures are composed into store
/// layouts by `store-assets/render.mjs`; run via `cd iosApp && fastlane screenshots`.
@MainActor
class ScreenshotTests: XCTestCase {

    let app = XCUIApplication()

    private var isEnglish: Bool { Snapshot.deviceLanguage.hasPrefix("en") }

    override func setUpWithError() throws {
        continueAfterFailure = false
        setupSnapshot(app)
        // The app keeps its own language (Korean by default) and ignores the system's. Dropping the
        // system language SnapshotHelper sets keeps the keyboard Latin, so search queries type as written.
        app.launchArguments = Self.withoutSystemLocale(app.launchArguments)
        XCUIDevice.shared.appearance = .light
        app.launch()
        XCTAssertTrue(waitForLabel(ko: "홈", en: "HOME", timeout: 60), "the tab bar never appeared")
        selectAppLanguage()
        clearBookmarks()
    }

    /// One pass through the five captured screens (the Home capture fills the first two frames). A single
    /// launch keeps the language switch and the saved exhibitions in place for the later screens.
    func testStoreCaptures() {
        // 1. Home: the featured pager and 에디터의 시선.
        tapTab(ko: "홈", en: "HOME")
        waitForContent("the Home pager", { $0.hasPrefix("01 / ") })
        settle(3)
        snapshot("01_Home")

        // 2. List, closing this week.
        tapTab(ko: "목록", en: "LIST")
        tapLabel(ko: "이번 주 종료", en: "CLOSING THIS WEEK")
        waitForContent("closing-this-week cards", Self.isResultCard)
        settle(2)
        snapshot("02_ClosingThisWeek")

        // Save a few from the full list: they become the orange pins on the Map and fill 내 전시.
        tapLabel(ko: "이번 주 종료", en: "CLOSING THIS WEEK")
        settle(2)
        selectAllRegions()
        saveExhibitions(count: 4)

        // 3. Map, all exhibitions around the simulated location.
        tapTab(ko: "지도", en: "MAP")
        settle(8)
        snapshot("03_Map")

        // 4. The saved list: 목록 → 내 전시.
        tapTab(ko: "목록", en: "LIST")
        tapAny([isEnglish ? "My List" : "내 전시"])
        settle(2)
        selectAllRegions()
        waitForContent("saved exhibitions", Self.isResultCard)
        settle(2)
        snapshot("06_My")

        // 5. Exhibition detail at the curator's note, opened from Home's 에디터의 시선 story card.
        tapTab(ko: "홈", en: "HOME")
        settle(2)
        let cardPrefix = isEnglish ? "Editor's eye, " : "에디터의 시선, "
        for _ in 0..<8 where onScreen({ $0.hasPrefix(cardPrefix) }) == nil {
            app.swipeUp()
            settle(1)
        }
        if openStoryCard(prefix: cardPrefix) {
            _ = scrollTo(ko: "에디터의 시선", en: "EDITOR'S EYE", maxSwipes: 12)
            // Drag the note's label up under the top bar so its paragraphs fill the frame.
            if let label = onScreen([isEnglish ? "EDITOR'S EYE" : "에디터의 시선"]) {
                label.press(
                    forDuration: 0.1,
                    thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.24)),
                    withVelocity: .slow,
                    thenHoldForDuration: 0.5,
                )
            }
            settle(3)
            snapshot("05_CuratorNote")
        } else {
            XCTFail("05_CuratorNote: no story card opened an exhibition\n\(app.debugDescription)")
        }
    }

    /// The app preview storyboard (store-assets/README.md). store-assets/record-preview.sh records the
    /// screen while this runs and cuts it at the PREVIEW_MARK lines; setup between marks is cut away.
    func testPreviewFlow() {
        // Off camera: saved exhibitions for the map's orange pins.
        tapTab(ko: "목록", en: "LIST")
        selectAllRegions()
        saveExhibitions(count: 3)
        tapTab(ko: "홈", en: "HOME")
        tapTab(ko: "홈", en: "HOME")
        settle(4)

        // 1. Home, drifting down to 에디터의 시선.
        mark("home")
        settle(1)
        app.swipeUp(velocity: .slow)
        settle(2)

        // 2. A curator's note.
        let cardPrefix = isEnglish ? "Editor's eye, " : "에디터의 시선, "
        for _ in 0..<6 where onScreen({ $0.hasPrefix(cardPrefix) }) == nil {
            app.swipeUp(velocity: .slow)
        }
        if openStoryCard(prefix: cardPrefix) {
            mark("note")
            _ = scrollTo(ko: "에디터의 시선", en: "EDITOR'S EYE", maxSwipes: 12)
            if let label = onScreen([isEnglish ? "EDITOR'S EYE" : "에디터의 시선"]) {
                label.press(
                    forDuration: 0.1,
                    thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.24)),
                    withVelocity: .slow,
                    thenHoldForDuration: 0.3,
                )
            }
            settle(3)
            goBack()
        }

        // 3. Closing this week, from the top of the list the setup scrolled.
        tapTab(ko: "목록", en: "LIST")
        tapAny([isEnglish ? "All Exhibitions" : "전체 전시"])
        scrollListToTop()
        mark("list")
        tapLabel(ko: "이번 주 종료", en: "CLOSING THIS WEEK")
        settle(2)
        app.swipeUp(velocity: .slow)
        settle(2)

        // 4. The map, saved exhibitions in orange.
        tapTab(ko: "지도", en: "MAP")
        mark("map")
        settle(5)

        // The preview ends on the map (store-assets/video.mjs SHOTS).
        mark("end")
    }

    /// Taps the first on-screen story card and waits for its exhibition page (its back button). A tap
    /// that lands while the pager settles does nothing, so it is retried once.
    private func openStoryCard(prefix: String) -> Bool {
        for _ in 0..<2 {
            guard let card = onScreen({ $0.hasPrefix(prefix) }) else { return false }
            card.tap()
            if element(["뒤로", "Back"]).waitForExistence(timeout: 6) {
                settle(3)
                return true
            }
        }
        return false
    }

    private func mark(_ shot: String) {
        print("PREVIEW_MARK \(shot) \(Date().timeIntervalSince1970)")
    }

    // MARK: - Setup helpers

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

    private func selectAppLanguage() {
        let wantEnglish = isEnglish
        let alreadyEnglish = app.buttons["HOME"].exists || app.staticTexts["HOME"].exists
        guard wantEnglish != alreadyEnglish else { return }
        tapTab(ko: "MY", en: "MY")
        tapAny(["설정", "Settings"])
        tapAny(["언어", "Language"])
        tapAny([wantEnglish ? "English" : "한국어"])
        settle(2)
        goBack()
        tapTab(ko: "홈", en: "HOME")
    }

    private func clearBookmarks() {
        for _ in 0..<20 {
            guard let saved = onScreen({ $0.hasPrefix("북마크 삭제") || $0.hasPrefix("Remove bookmark") }) else { break }
            saved.tap()
            settle(1)
        }
    }

    private func saveExhibitions(count: Int) {
        var saved = 0
        for _ in 0..<(count + 6) where saved < count {
            if let button = onScreen({ $0.hasPrefix("북마크 추가") || $0.hasPrefix("Add bookmark") }, below: 0.4) {
                button.tap()
                dismissNotificationPrompt()
                saved += 1
                settle(1)
            } else {
                app.swipeUp()
                // Let the list stop coasting; a tap read from a moving list lands on another row.
                settle(2)
            }
        }
    }

    /// Selects the whole-country region chip, so no region filter hides exhibitions.
    private func selectAllRegions() {
        if let allRegions = onScreen({ $0.hasPrefix(self.isEnglish ? "All (" : "전체 (") }) {
            allRegions.tap()
            settle(1)
        }
    }

    /// An exhibition card's label: "title, venue, dates" with "2026.09.02 – 2026.10.31" or
    /// "Sep 2 – Oct 31, 2026". Gallery rows and chips carry no date range.
    private static func isResultCard(_ label: String) -> Bool {
        label.contains(" – ") && label.range(of: #"20\d\d"#, options: .regularExpression) != nil
    }

    /// Waits until real content is on screen, so a capture never shows a loading state. A screen that
    /// never fills fails the test rather than producing a frame.
    private func waitForContent(_ what: String, _ matches: (String) -> Bool, timeout: TimeInterval = 30) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if onScreen(matches) != nil { return }
            settle(1)
        }
        XCTFail("\(what) never appeared\n\(app.debugDescription)")
    }

    /// The first save asks to turn on saved-exhibition alerts; the store set shows the app without it.
    private func dismissNotificationPrompt() {
        let later = element(["다음에", "Not now", "NOT NOW", "Later"])
        if later.waitForExistence(timeout: 2) {
            later.tap()
            settle(1)
        }
    }

    // MARK: - Navigation helpers

    private func tapTab(ko: String, en: String) {
        let label = isEnglish ? en : ko
        let tab = app.buttons[label]
        if tab.waitForExistence(timeout: 5) {
            tab.tap()
        } else {
            tapLabel(ko: ko, en: en)
        }
        settle(2)
    }

    private func tapLabel(ko: String, en: String) {
        tapAny([isEnglish ? en : ko])
    }

    private func tapAny(_ labels: [String]) {
        let target = element(labels)
        XCTAssertTrue(target.waitForExistence(timeout: 10), "missing \(labels)\n\(app.debugDescription)")
        target.tap()
        settle(1)
    }

    private func element(_ labels: [String]) -> XCUIElement {
        let predicate = NSPredicate(format: "label IN %@", labels)
        return app.descendants(matching: .any).matching(predicate).firstMatch
    }

    /// A tap point for the first element with one of the labels whose centre lies inside the window,
    /// clear of the status and tab bars. It reads one snapshot of the tree: pager cards off to the side have no hit point, and
    /// live queries go stale while Compose recomposes after a tap.
    private func onScreen(_ labels: [String]) -> XCUICoordinate? {
        onScreen({ labels.contains($0) })
    }

    /// `below` (0…1) skips matches above that fraction of the window: list rows scrolled under the app
    /// bar still report frames there, and a tap would land on the bar instead.
    private func onScreen(_ matches: (String) -> Bool, preferLast: Bool = false, below: CGFloat = 0) -> XCUICoordinate? {
        guard let root = try? app.snapshot() else { return nil }
        let window = root.frame
        // Keep clear of the status bar and the tab bar so a tap never lands on either.
        let usable = window.insetBy(dx: 0, dy: window.height * 0.08)
        var stack: [XCUIElementSnapshot] = [root]
        var found: [CGRect] = []
        while let node = stack.popLast() {
            // The tap goes to the centre, so the centre must be clear of the bars; on iPad a story card
            // is taller than the clear band and never fits it whole.
            let centre = CGPoint(x: node.frame.midX, y: node.frame.midY)
            if matches(node.label), !node.frame.isEmpty, usable.contains(centre),
               node.frame.minY >= window.height * below {
                found.append(node.frame)
            }
            stack.append(contentsOf: node.children.reversed())
        }
        let ordered = found.sorted { $0.minY < $1.minY }
        guard let frame = preferLast ? ordered.last : ordered.first else { return nil }
        return app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.midX, dy: frame.midY))
    }

    private func waitForLabel(ko: String, en: String, timeout: TimeInterval) -> Bool {
        element([ko, en]).waitForExistence(timeout: timeout)
    }

    @discardableResult
    private func scrollTo(ko: String, en: String, maxSwipes: Int = 10) -> Bool {
        for _ in 0..<maxSwipes {
            if onScreen([isEnglish ? en : ko]) != nil { return true }
            app.swipeUp()
            settle(1)
        }
        return onScreen([isEnglish ? en : ko]) != nil
    }

    private func goBack() {
        let back = element(["뒤로", "Back"])
        if back.waitForExistence(timeout: 3) {
            back.tap()
        }
        settle(2)
    }

    /// The list keeps its scroll position across tabs; the search box and filter chips sit at its top.
    private func scrollListToTop() {
        // At the top the search box sits just under the tabs (y ≈ 174 pt); scrolled, it is above the bar.
        // Its frame is read rather than `isHittable`, which throws for an element off screen.
        for _ in 0..<4 {
            let field = app.textViews.firstMatch
            if field.exists, field.frame.minY > 120 { return }
            app.swipeDown()
            settle(1)
        }
    }

    private func settle(_ seconds: UInt32) {
        sleep(seconds)
    }
}
