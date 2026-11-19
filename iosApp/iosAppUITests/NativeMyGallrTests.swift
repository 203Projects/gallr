import XCTest

@MainActor
final class NativeMyGallrTests: XCTestCase {
    func testPreferencesAndAnonymousSignInValidation() {
        let app = openMy()
        app.buttons["gallr-settings"].tap()

        let language = app.descendants(matching: .any)["gallr-settings-language"].firstMatch
        XCTAssertTrue(language.waitForExistence(timeout: 10))
        language.tap()
        app.buttons["English"].firstMatch.tap()
        let listTab = app.buttons["gallr-tab-list"]
        XCTAssertTrue(listTab.waitForExistence(timeout: 10))
        XCTAssertEqual(listTab.label, "LIST")

        let appearance = app.descendants(matching: .any)["gallr-settings-appearance"].firstMatch
        XCTAssertTrue(appearance.waitForExistence(timeout: 10))
        appearance.tap()
        app.buttons["Dark"].firstMatch.tap()
        XCTAssertTrue(appearance.label.contains("Dark") || (appearance.value as? String) == "Dark")

        appearance.tap()
        app.buttons["System"].firstMatch.tap()
        language.tap()
        app.buttons["한국어"].firstMatch.tap()
        app.buttons["gallr-back"].tap()

        let account = app.buttons["gallr-my-account"]
        XCTAssertTrue(account.waitForExistence(timeout: 10))
        account.tap()
        let email = app.textFields["gallr-sign-in-email"]
        XCTAssertTrue(email.waitForExistence(timeout: 10))
        email.tap()
        email.typeText("invalid")

        let submit = app.buttons["gallr-sign-in-submit"]
        reveal(submit, in: app)
        submit.tap()
        XCTAssertTrue(app.staticTexts["gallr-sign-in-error"].waitForExistence(timeout: 10))
    }

    func testDiaryVisitAndPrivateNotePersistAcrossLaunches() {
        let app = openMy()
        let fixtureEntry = app.buttons["gallr-diary-entry-native-fixture"]
        if !fixtureEntry.exists {
            let add = app.buttons["gallr-diary-add"]
            reveal(add, in: app)
            add.tap()
            let exhibition = app.buttons["gallr-diary-add-native-fixture"]
            XCTAssertTrue(exhibition.waitForExistence(timeout: 10))
            exhibition.tap()
            let saveArchive = app.buttons["gallr-archive-save"]
            reveal(saveArchive, in: app)
            saveArchive.tap()
        }
        reveal(fixtureEntry, in: app)
        fixtureEntry.tap()

        let note = app.textViews["gallr-diary-note"]
        reveal(note, in: app)
        note.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.35)).tap()
        let content = "Quiet space " + UUID().uuidString
        note.typeText(content)

        let saveDiary = app.buttons["gallr-diary-save"]
        reveal(saveDiary, in: app)
        saveDiary.tap()
        XCTAssertTrue(fixtureEntry.waitForExistence(timeout: 10))

        app.terminate()
        let reopened = openMy()
        let savedEntry = reopened.buttons["gallr-diary-entry-native-fixture"]
        reveal(savedEntry, in: reopened)
        savedEntry.tap()
        let reopenedNote = reopened.textViews["gallr-diary-note"]
        reveal(reopenedNote, in: reopened)
        XCTAssertTrue((reopenedNote.value as? String)?.contains(content) == true)
    }

    private func openMy() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--native-fixture"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()

        let skip = app.buttons.matching(NSPredicate(format: "label IN %@", ["건너뛰기", "Skip"])).firstMatch
        if skip.waitForExistence(timeout: 3), skip.isHittable { skip.tap() }
        let my = app.buttons["gallr-tab-my"]
        XCTAssertTrue(my.waitForExistence(timeout: 20))
        my.tap()
        return app
    }

    private func reveal(_ element: XCUIElement, in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        _ = element.waitForExistence(timeout: 10)
        for _ in 0..<8 {
            if element.isHittable, element.frame.midY > app.frame.minY + 65,
               element.frame.midY < app.frame.maxY - 120 { return }
            app.swipeUp(velocity: .slow)
        }
        XCTAssertTrue(element.isHittable, file: file, line: line)
    }
}
