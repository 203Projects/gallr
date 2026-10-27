# Quickstart: Taste listening and reliable catalogue data

## Owner steps before code (the Assignment)

1. Watch three people (one new, two who have saved something) open Home and 내 취향 and say why each card is there.
   Write down the first surprise.
2. Tag ten running exhibitions in Admin → Art / 작품 정보 with today's vocabulary. Write down every term you wished
   existed; that list finalises the vocabulary migration.
3. Label fifteen scenarios for the eval: a set of picks or saves, and the three exhibitions you expect on top.

## Verify Stage 1 (shared and app)

```bash
./gradlew shared:allTests --tests '*Taste*' --tests '*LocalExhibitionRecommender*'
./gradlew shared:ktlintCheck shared:allTests
./gradlew composeApp:ktlintCheck composeApp:allTests
./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug
./gradlew composeApp:iosSimulatorArm64Test
```

In a worktree, prefix Gradle with `ANDROID_HOME=$HOME/Library/Android/sdk`.

Manual pass on Android and iOS, Korean and English:

1. Fresh install → Home → the taste sheet opens; start stays disabled until 3; pick 3 → 내 취향 rail, every card's
   first reason starts with 내가 고른 or 저장한.
2. Reinstall → skip → prompt card; reopen the app → no sheet.
3. Tap a chip → sheet with picks selected.
4. 덜 보기 on a card → snackbar → 되돌리기 restores; 덜 보기 again, then save it → it returns; its term left the chips and
   is in the sheet's 덜 보기 row.
5. Open a card from 내 취향 → reason under the title; open the same exhibition from List → no reason.
6. Set a minimum version above the build (staging Admin → 앱 버전) on a fresh install → update screen shows, no sheet.

## Verify Stage 0 (database, Admin, desk)

```bash
node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
supabase test db   # in an isolated scratch workdir, see the local verification memory
(cd admin && npm test)
(cd gallery && npm test)
(cd scripts/tag-exhibition && deno task test)
node --test scripts/catalogue-coverage/coverage.test.mjs
```

Coverage before the release PR (owner runs; read-only):

```bash
supabase db query --linked --project-ref oqrvbstopuppznxqoonp -f scripts/catalogue-coverage/coverage.sql < /dev/null | node scripts/catalogue-coverage/coverage.mjs --release 1.15.0
```
