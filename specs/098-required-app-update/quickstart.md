# Quickstart: verifying the required app update

## Automated

```bash
node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
./gradlew shared:allTests --tests "com.gallr.shared.app.*" --tests "com.gallr.shared.repository.AppUpdate*"
./gradlew shared:ktlintCheck shared:allTests composeApp:ktlintCheck composeApp:allTests
./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug
cd admin && npx -y -p node@22.23.1 -- sh -c 'npm run typecheck && npm test && npm run build'
```

pgTAP suite `068_app_version_policy.test.sql` in the isolated scratch workdir (see supabase/AGENTS.md).

## Manual (Android emulator and iOS simulator against a local stack)

1. Fresh install at 1.14.0 with both policy rows at 1.13.1: the app opens as today; Settings shows 1.14.0.
2. Set Android's minimum to 1.15.0 (SQL editor or Admin): bring the app to the foreground after a minute, or relaunch:
   the full-screen notice shows; back does nothing; 업데이트 opens the Play listing (the emulator may show the web
   page); iOS is unaffected until its own row changes.
3. Turn the network off and relaunch: the notice is still there (remembered answer).
4. Lower the minimum to 1.13.1 and relaunch online: the app opens normally.
5. Set recommended to 1.15.0 with minimum 1.13.1: Home shows the nudge; 나중에 hides it; relaunch the same day: hidden;
   change the device date to tomorrow and relaunch: shown again.
6. Switch to English: both screens in English.
7. Admin: open 앱 버전, enter `1.14` in a field: refused with a message; enter 1.15.0 as minimum with last released
   1.14.0: inline warning; save as a non-staff account: the RPC error shows and the value stays.
