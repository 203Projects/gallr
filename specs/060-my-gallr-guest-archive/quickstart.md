# Quickstart: My Gallr guest archive

## Automated verification

```bash
./gradlew shared:allTests
./gradlew composeApp:allTests
./gradlew shared:ktlintCheck composeApp:ktlintCheck
```

## Manual Android/iOS verification

1. Start with an anonymous session and open the fourth tab.
2. Confirm My Gallr appears instead of Sign In.
3. Choose Add past visits, search by exhibition and venue, select three entries, and save once.
4. Confirm three distinct archive cards appear and no account prompt is shown.
5. Restart the app and confirm the same snapshot content remains.
6. Reopen Add past visits and confirm archived exhibitions cannot be duplicated.
7. Open Account and confirm the existing sign-in screen appears; return to My Gallr.
8. Sign in and confirm My Gallr remains the fourth-tab default while Account opens the existing member profile.
9. Verify Korean and English copy, dark mode, reduced-width devices, and screen-reader labels.
