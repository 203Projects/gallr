# Quickstart: Local discovery accuracy fixes

## 1. Generate the catalogue fixture (once, needs approval)

The fixture is a public-catalogue snapshot. Producing it reads the production `exhibition_catalog_v2`
view, so the operator runs or approves this step:

```bash
supabase db query --linked --project-ref oqrvbstopuppznxqoonp -f specs/088-discovery-accuracy-fixes/fixture/export.sql < /dev/null > /tmp/088-export.json
```

```bash
node specs/088-discovery-accuracy-fixes/fixture/to-kotlin.mjs /tmp/088-export.json > shared/src/commonTest/kotlin/com/gallr/shared/fixture/PublishedCatalogueFixture.kt
```

Commit the generated file. Record the hand-checked expected hours for each fixture exhibition in
`shared/src/commonTest/kotlin/com/gallr/shared/fixture/PublishedCatalogueHoursExpectations.kt`.

## 2. Test-first loop

Write each failing test before the code it covers (constitution II):

```bash
./gradlew shared:testAndroidHostTest --tests "com.gallr.shared.hours.*"
```

```bash
./gradlew shared:testAndroidHostTest --tests "com.gallr.shared.recommendation.*" --tests "com.gallr.shared.map.*"
```

```bash
./gradlew composeApp:testAndroidHostTest --tests "com.gallr.app.viewmodel.LocalDiscoveryViewModelTest" --tests "com.gallr.app.ui.route.*" --tests "com.gallr.app.ui.discovery.*"
```

`testAndroidHostTest` is the fast JVM host target; `shared:allTests` also runs the iOS simulator target.

## 3. Fixture assertions (success criteria)

| Test | Asserts |
|---|---|
| `DiscoveryFixtureRouteTest.forYouRouteSucceedsFromMeasuredNeighbourhoods` | SC-001: three-stop For You routes, no history, from Hannam (37.5345, 127.0010), Seongsu (37.5446, 127.0557) and Cheongdam (37.5240, 127.0470) on 2026-10-02 at 11:00 |
| `DiscoveryFixtureRouteTest.noStopClosedOrLateAcrossWeek` | SC-002: every mode, every weekday, start times 10:00–17:00 hourly, no known-closed stop, every known `visitEnd <= closes` |
| `DiscoveryFixtureHoursTest.readsListedHours` | SC-003: all but at most one of the 68 non-blank hours read, each equal to its expectation |
| `DiscoveryFixtureSimilarityTest.textSimilarPairShare` | SC-004: share of pairs over the text threshold is at most 10% |
| `DiscoveryFixtureSimilarityTest.sameArtistShowsAreMostSimilar` | Both Georg Baselitz exhibitions are each other's strongest text match |
| `DiscoveryFixtureTasteTest.*` | SC-005 and SC-006: scenario histories rank the expected match in the top three; reasons name real anchors; no-history scenarios show no taste reason |

## 4. Full verification before handoff

```bash
./gradlew shared:ktlintCheck composeApp:ktlintCheck shared:allTests composeApp:allTests
```

```bash
./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug
```

```bash
./gradlew composeApp:linkReleaseFrameworkIosSimulatorArm64
```

## 5. Manual checks (SC-007, SC-008)

1. On a mid-range Android device and an iPhone, open Map → Route and build For You, Neighborhood and
   Saved routes. Each result should appear without perceptible delay, under one second.
2. Set the device clock to a Monday afternoon (Korea time) and confirm no Monday-closed gallery appears.
3. Confirm the per-stop label reads `HOURS NOT VERIFIED · …` only for stops whose hours could not be
   read, and that the route-level warning disappears when every stop is verified.
4. Confirm the diff adds no network client, permission, persisted key, analytics field or migration:

```bash
git diff develop --stat -- supabase/ shared/src/commonMain/kotlin/com/gallr/shared/analytics shared/src/commonMain/kotlin/com/gallr/shared/data/network androidApp/src/main/AndroidManifest.xml iosApp/
```

The expected output is empty.
