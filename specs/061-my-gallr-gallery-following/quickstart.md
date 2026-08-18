# Quickstart: My Gallr gallery following

## Automated verification

```bash
./gradlew shared:allTests composeApp:allTests
./gradlew shared:ktlintCheck composeApp:ktlintCheck androidApp:ktlintCheck
./gradlew androidApp:lintDebug androidApp:assembleDebug composeApp:linkDebugFrameworkIosSimulatorArm64
```

## Manual verification

1. Open My Gallr anonymously and select Following.
2. Add two galleries by Korean and English search; confirm no account or permission prompt appears.
3. Restart and confirm both galleries remain.
4. Confirm exhibitions present at follow time are not marked new.
5. Inject a later catalogue exhibition for a followed venue and confirm `NEW` appears.
6. Open that gallery, confirm the exhibition opens, then return and confirm `NEW` is cleared.
7. Unfollow one gallery with confirmation and verify Visits is unchanged.
8. Verify Korean/English, dark/light, narrow-screen, and accessibility presentation.
