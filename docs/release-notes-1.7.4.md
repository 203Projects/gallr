# gallr 1.7.4 — Release Notes

**Version:** 1.7.4

**Android versionCode:** 21

**Android target API:** 36 (Android 16)

**iOS build:** 16

**Date:** 2026-07-30

**Reader source for release artifacts:** `canonical-v2`

---

## Play Store “What’s new”

### Korean (`ko-KR`)

```text
전시 데이터가 더 빠르고 안정적으로 업데이트됩니다.
• 최신 전시 정보가 새 카탈로그에서 직접 제공됩니다
• 목록, 상세, 지도, 이벤트 화면의 데이터 일관성을 강화했습니다
• 저장한 전시와 기존 사용 방식은 그대로 유지됩니다
```

### English (`en-US`)

```text
Exhibition data is now delivered more quickly and reliably.
• Current exhibition information comes directly from the new catalog
• Improved consistency across lists, details, maps, and events
• Saved exhibitions and existing app behavior remain unchanged
```

---

## Full change summary

### Changed

- Android and iOS release artifacts use the published-only canonical exhibition catalog.
- Android release artifacts compile against and target Android 16 (API level 36), satisfying the Google Play app-update requirement effective August 31, 2026.
- Catalog loading verifies exact row membership on both platforms and content integrity on the canonical reader before accepting a paginated result.
- Featured, List, Map, Event, and Editor surfaces continue to share the same exhibition repository and visibility rules.

### Rollback boundary

- The checked-in source default remains `legacy`; release artifacts select `canonical-v2` explicitly at build time.
- The production legacy table, integrity RPC, and canonical-to-legacy mirror remain available for supported installed clients and a reader-only rollback.
- This release does not remove legacy data, change editorial ownership, or restart the retired Sheet writer.

---

## Required release commands

Android:

```bash
./gradlew \
  -Pexhibition.catalog.source=canonical-v2 \
  :shared:allTests \
  :composeApp:allTests \
  :composeApp:bundleRelease
```

iOS:

```bash
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Release \
  GALLR_EXHIBITION_CATALOG_SOURCE=canonical-v2 \
  archive
```

An artifact is invalid for this rollout unless its generated Android
`EXHIBITION_CATALOG_SOURCE` or packaged iOS
`GallrExhibitionCatalogSource` equals `canonical-v2`.

---

## Pre-submission checklist

- [x] Base release preparation on reviewed production commit `04b0d22dc56d7b00ca292c8d89912ea532109a3d`.
- [x] Bump Android to **1.7.4 (21)**.
- [x] Compile against and target Android 16 (**API level 36**).
- [x] Bump iOS to **1.7.4 (16)** for Debug and Release configurations.
- [x] Run `:shared:allTests` and `:composeApp:allTests` with the `canonical-v2` override.
- [x] Build and inspect an Android debug canary containing `canonical-v2`.
- [x] Smoke-test a fresh install on an API 36 Pixel emulator, including location denial and Seoul fallback, canonical map markers, marker details, and Android back handling.
- [x] Build, inspect, and launch an iOS simulator canary containing `canonical-v2`.
- [x] Build and verify the signed Android App Bundle.
- [ ] Archive, export, and verify the signed iOS App Store IPA.
- [ ] Upload the Android bundle to Play Console.
- [ ] Upload the iOS build to App Store Connect.
- [ ] Start controlled store rollout only under separate production authorization.

The current Naver Maps Android SDK emits D8 stack-map warnings during the
API 36 release build and non-fatal style/telemetry warnings at runtime.
Map tiles, canonical markers, marker details, and back handling passed on
the API 36 emulator; upgrading the Maps SDK remains a separate follow-up.

No store upload or mobile production traffic change is authorized by this
release-preparation pull request.
