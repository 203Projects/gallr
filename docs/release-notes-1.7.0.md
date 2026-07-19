# gallr 1.7.0 — Release Notes

**Version:** 1.7.0
**Android versionCode:** 17
**iOS build:** 13
**Date:** 2026-06-08
**Submission artifact (Android):** `gallr-1.7.0-vc17.aab` (signed AAB)

---

## Play Store "What's new" (copy-paste into Play Console → release notes)

### Korean (`ko-KR`)

```
여러 아트페어가 동시에 열려도 모두 보여드립니다.
• 추천 탭: 진행 중인 아트페어 배너가 자동으로 넘어가고, 좌우로 넘겨 볼 수 있어요
• 목록 탭: 상단 배너가 모든 아트페어를 번갈아 표시 — 누르면 상세로 이동
• 지도 탭: 이벤트 버튼이 페어별 이미지로 전환됩니다
• 화면 모션 줄이기 / 스크린 리더 사용 시 자동 전환을 끄도록 개선
```

### English (`en-US`)

```
When several art fairs run at once, you now see all of them.
• Featured tab: the active-fair banner auto-advances and you can swipe between fairs
• List tab: the top banner cycles through every active fair — tap to open its detail
• Map tab: the event button switches between each fair's image
• Respects Reduce Motion / screen readers by pausing auto-advance
```

> Both are well under Google Play's 500-character limit.

### App Store "What's New" (iOS, same release)

Use the English copy above. (iOS ships the same 1.7.0 changes.)

---

## Full change summary

### New / Changed
- **Multiple active art fairs now all appear.** Previously, when two or more events (art fairs) were active at the same time, only the first one showed anywhere in the app — the rest were silently dropped. Every active event is now surfaced:
  - **Featured tab:** an auto-advancing, swipeable hero pager (4s) with a dot indicator. The card wraps to its content height. With one active event it looks exactly as before.
  - **List tab:** the 36dp top banner auto-cycles (3.5s) through all active fairs with a timing bar; swipe to switch, tap to open the fair's detail. One filter chip per fair, each in its brand color.
  - **Map tab:** the floating event button cross-fades its cover image and brand color across the active fairs; tap opens the currently-shown one.
- **Respects reduced motion.** When the device has Reduce Motion or a screen reader enabled, the auto-advancing carousels stop animating on their own — every event still renders and stays reachable by swipe/tap, and the surfaces carry proper accessibility labels.

### Fixed
- **Second and third active events are no longer invisible.** The root cause (`TabsViewModel` keeping only the first active event) is fixed; all active events are retained and displayed.

### Behind the scenes
- New shared cycling driver (`rememberCyclingIndex` → `CyclingState` with `.index` + `.advance()`); the `advance()` path keeps the List banner manually navigable even when auto-advance is gated off for accessibility.
- `expect/actual isReduceMotionOrScreenReaderActive()` (Android: touch-exploration / animator scale; iOS: Reduce Motion / VoiceOver).
- Featured pager uses Compose Multiplatform 1.8.0 Foundation `HorizontalPager` (stable).
- DESIGN.md updated to sanction functional motion (crossfade, timing cues, gated auto-cycling).
- 49 unit tests pass (new `CyclingIndexTest` + `TabsViewModelActiveEventsTest`); Android + iOS compile clean; on-device emulator QA done (multi-event pager/banner/FAB verified by seeding a 2nd active event).

> **No Supabase migration or schema change required for this release** — the fix was purely in the app's ViewModel; the data layer already returned all active events.

### Web (not part of the app binary)
The same merge window also shipped web-only changes to gallrmap.com (exhibition submit-form fixes, footer link, About-page CTA, sitemap/robots). These deploy with the static site via Vercel and have **no effect on the Android/iOS app build**.

---

## Pre-submission checklist

- [x] Android `versionCode` 16 → **17**, `versionName` → **1.7.0**
- [x] iOS `MARKETING_VERSION` → **1.7.0**, `CURRENT_PROJECT_VERSION` 12 → **13** (Debug + Release)
- [x] `:composeApp:testDebugUnitTest` + `:shared:testDebugUnitTest` green (49 tests)
- [x] Android + iOS targets compile
- [x] On-device emulator QA (multi-event surfaces verified)
- [x] develop → main promotion PR opened (#85)
- [ ] **No Supabase migration needed** (none this release)
- [ ] Build signed release AAB (`gallr-1.7.0-vc17.aab`) and verify (`jarsigner -verify` → "jar verified")
- [ ] Archive + export iOS build 13 (`.ipa`) via Xcode / `xcodebuild`
- [ ] Stage artifacts under `release-artifacts/1.7.0/`
- [ ] Upload `gallr-1.7.0-vc17.aab` to Play Console
- [ ] Upload iOS build 13 to App Store Connect
