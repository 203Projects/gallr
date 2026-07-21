# gallr 1.7.1 — Release Notes

**Version:** 1.7.1
**Android versionCode:** 18
**iOS build:** 14
**Date:** 2026-06-09
**Submission artifact (Android):** `release-artifacts/1.7.1/android/gallr-1.7.1-vc18.aab` (signed AAB)
**Submission artifact (iOS):** `release-artifacts/1.7.1/ios/export/iosApp.ipa` (App Store export)

---

## Play Store "What's new" (copy-paste into Play Console → release notes)

### Korean (`ko-KR`)

```
이벤트 기능 안정성을 개선했습니다.
• 목록 탭에서 이벤트 필터를 하나씩 개별 선택할 수 있어요
• 이벤트 상세에서 참여 전시가 누락되던 문제를 수정했습니다
• 지도에서 여러 이벤트의 컬러 핀이 함께 표시됩니다
• 이미지 로딩 방식을 바꿔 서비스 사용량을 줄였습니다
```

### English (`en-US`)

```
Event discovery is more reliable.
• Pick active event filters one at a time in the List tab
• Event detail pages now show participant exhibitions reliably
• Map pins preserve colors for multiple active events
• Image loading now avoids Supabase transformation quota usage
```

> Both are under Google Play's 500-character limit.

### App Store "What's New" (iOS)

Use the English copy above. iOS ships the same 1.7.1 fixes.

---

## Full change summary

### Fixed
- **Independent active-event filters.** The List tab now tracks the selected event id instead of a shared "events only" Boolean, so active events are discoverable independently.
- **Participant exhibitions for later-entered events.** Event detail pages load exhibitions by the requested event id, and the Apps Script exhibition sync now upserts before deleting stale ids to avoid empty windows during sync.
- **Multiple event map pins.** Grouped map locations preserve colored pins for every active event represented there.
- **General event wording.** Active event copy now says "이벤트" / "Events" instead of art-fair-specific wording.

### Changed
- **Native image sizing.** Supabase Storage public object URLs are passed directly to the native image loaders. This avoids `/storage/v1/render/image` calls and reduces Storage Image Transformations quota usage.

---

## Pre-submission checklist

- [x] Android `versionCode` 17 → **18**, `versionName` → **1.7.1**
- [x] iOS `MARKETING_VERSION` → **1.7.1**, `CURRENT_PROJECT_VERSION` 13 → **14** (Debug + Release)
- [x] `./gradlew :shared:test :composeApp:test :composeApp:compileKotlinIosSimulatorArm64`
- [x] `node web/tests/gas-sync-status.test.js`
- [x] `git diff --check`
- [x] Build signed release AAB: `release-artifacts/1.7.1/android/gallr-1.7.1-vc18.aab`
- [x] Verify signed AAB with `jarsigner`
- [x] Archive + export iOS build 14 (`.ipa`) via `xcodebuild`
- [x] Verify exported IPA reports version **1.7.1** / build **14**
- [x] Store artifacts under `release-artifacts/1.7.1/`
- [ ] Upload `gallr-1.7.1-vc18.aab` to Play Console
- [ ] Upload iOS build 14 to App Store Connect
- [ ] Deploy updated `gas/SyncExhibitions.gs` to Apps Script before relying on the sync fix in production
