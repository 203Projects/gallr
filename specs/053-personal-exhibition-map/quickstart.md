# Quickstart: Personal Exhibition Map

## 1. Preflight

```bash
node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
node --test scripts/staging-rehearsal/lib/validate-migration-lineage.test.mjs
git diff --check
```

Read `DESIGN.md`, `CLAUDE.md`, and `gas/AGENTS.md` before implementing UI or the catalog/GAS field.
Do not expose, rotate, or add credentials; external navigation requires no embedded map API key.

## 2. Test-first order

For every slice, add the named focused test and observe the expected failure before implementation:

1. Shared country DTO/cache/model compatibility.
2. Shared Haversine, scope resolution, aggregation, projection, snapping, and candidate ranking.
3. Shared local visit storage, cloud merge idempotency, repeat visits, and tombstones.
4. Compose map ViewModel mode/scope/nearby/visit state and renderer hit-test helpers.
5. Android and iOS metadata/sanitization contract tests or deterministic test fixtures.
6. Supabase visit/photo RLS, Storage policies, and catalog-country propagation.

## 3. Fast mobile verification

```bash
./gradlew shared:testDebugUnitTest composeApp:testDebugUnitTest
./gradlew composeApp:assembleDebug
```

If `shared:testDebugUnitTest` is not available for the current plugin task graph, run
`./gradlew shared:allTests` instead.

## 4. Full KMP verification

Open/build `iosApp` in Xcode once if the current branch still contains Naver SPM cinterop, then run:

```bash
./gradlew shared:allTests
./gradlew composeApp:allTests
./gradlew composeApp:linkReleaseFrameworkIosSimulatorArm64
```

After Naver dependencies are removed, verify a clean iOS framework build no longer depends on an
NMapsMap artifact in DerivedData.

## 5. Database verification

```bash
supabase db reset
supabase test db
supabase db lint --local --schema public,content,content_private --fail-on error
```

Also run the focused GAS tests documented in `gas/AGENTS.md` after adding `country_code` to
`KNOWN_COLUMNS`.

## 6. Privacy fixtures

Use committed synthetic image fixtures only—never a personal photo. Cover:

- JPEG with GPS and capture time.
- JPEG with capture time but no GPS.
- JPEG without metadata.
- Unsupported/corrupt data.

For every successful sanitizer result, decode the new file and assert that GPS/location dictionaries
and source identifiers are absent. Verify that logs contain neither coordinates, note content, image
bytes, private object paths, nor signed URLs.

## 7. Manual acceptance

- Korea → Seoul → district/exhibition takes at most three selections.
- Canvas marks and equivalent list expose identical actionable content with TalkBack and VoiceOver.
- Near Me asks for location only after the action, sorts fixed nearby fixtures correctly, and remains
  usable after denial.
- Directions open exact venue coordinates in an installed external map or web fallback.
- Anonymous manual visit appears immediately, persists offline, and survives app restart.
- Re-authentication sync is retryable and creates no duplicate visit.
- Photo matching never confirms automatically; missing/redacted metadata has a manual path.
- Uploaded derivative is private and unreadable by a second test user.
- A visited, later-unpublished exhibition remains readable through its snapshot.
- Dark theme, large text, reduced motion, compact phone, and tablet layouts retain the design hierarchy.
