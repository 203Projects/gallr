import XCTest

@MainActor
final class NativeMyGallrTests: XCTestCase {
    func testEditedAccountProfileAppearsInMyAndClearsOnSignOut() throws {
        let app = launchFixture(arguments: ["--native-signed-in-fixture"])
        app.buttons["gallr-tab-my"].tap()
        XCTAssertTrue(app.staticTexts["Fixture Profile Name"].waitForExistence(timeout: 10))
        app.buttons["gallr-my-account"].tap()
        XCTAssertTrue(app.staticTexts["Fixture Profile Name"].waitForExistence(timeout: 10))
        app.buttons.matching(NSPredicate(format: "label IN %@", ["프로필 편집", "Edit profile"])).firstMatch.tap()

        let name = app.textFields["gallr-profile-display-name"]
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.tap()
        name.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "Fixture Profile Name".count))
        name.typeText("Edited Fixture Profile")
        app.buttons["gallr-profile-save"].tap()
        XCTAssertTrue(app.staticTexts["Edited Fixture Profile"].waitForExistence(timeout: 10))
        app.buttons["gallr-back"].tap()
        XCTAssertTrue(app.staticTexts["Edited Fixture Profile"].waitForExistence(timeout: 10))
        app.buttons["gallr-tab-home"].tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(
            format: "label CONTAINS %@", "Edited Fixture Profile"
        )).firstMatch.waitForExistence(timeout: 10))
        app.buttons["gallr-tab-my"].tap()

        app.buttons["gallr-settings"].tap()
        let signOut = app.buttons.matching(NSPredicate(format: "label IN %@", ["로그아웃", "Sign out"]))
        reveal(signOut.firstMatch, in: app)
        signOut.firstMatch.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(
            format: "label IN %@", ["로그아웃할까요?", "Sign out?"]
        )).firstMatch.waitForExistence(timeout: 10))
        let confirmation = try XCTUnwrap(signOut.allElementsBoundByIndex.first(where: \.isHittable))
        confirmation.tap()
        let account = app.buttons.matching(NSPredicate(
            format: "identifier == %@ AND label IN %@", "gallr-my-account", ["로그인", "Sign in"]
        )).firstMatch
        XCTAssertTrue(account.waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["Edited Fixture Profile"].exists)
    }

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
        let app = launchFixture()
        app.buttons["gallr-tab-my"].tap()
        return app
    }

    private func launchFixture(arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--native-fixture"] + arguments
        XCUIDevice.shared.orientation = .portrait
        app.launch()

        let skip = app.buttons.matching(NSPredicate(format: "label IN %@", ["건너뛰기", "Skip"])).firstMatch
        if skip.waitForExistence(timeout: 3), skip.isHittable { skip.tap() }
        XCTAssertTrue(app.buttons["gallr-tab-my"].waitForExistence(timeout: 20))
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
