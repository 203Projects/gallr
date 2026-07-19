# gallr 1.7.3 — Release Notes

**Version:** 1.7.3

**Android versionCode:** 20

**iOS build:** 15

**Date:** 2026-07-19

**Submission artifact (Android):** `release-artifacts/1.7.3/android/gallr-1.7.3-vc20.aab`

**Submission artifact (iOS):** `release-artifacts/1.7.3/ios/export/iosApp.ipa`

---

## Play Store “What’s new”

### Korean (`ko-KR`)

```
전시 탐색이 더 정확하고 매끄러워졌습니다.
• 곧 시작할 전시는 오픈 14일 전부터 모든 화면에 일관되게 표시됩니다
• 종료된 전시와 비어 있는 에디터 큐레이션은 자동으로 정리됩니다
• 목록 끝에서 필터 영역이 튀어 오르던 현상을 수정했습니다
• 시작 화면의 라이트·다크 모드 전환을 자연스럽게 개선했습니다
```

### English (`en-US`)

```
Exhibition discovery is more accurate and polished.
• Upcoming shows now appear consistently from 14 days before opening
• Ended shows and empty editor curations are automatically hidden
• Fixed the filter header bouncing near the end of the List tab
• Smoothed the light and dark launch-screen handoff
```

Both entries are under Google Play’s 500-character limit. Use the English copy for the App Store “What’s New” field.

---

## Full change summary

### Changed
- A shared catalog-visibility rule now governs Featured, List, Map, Event, and Editor surfaces: an exhibition is visible when it has not ended and opens within 14 days.
- Editor tiles, exhibition counts, and details use only catalog-visible exhibitions. Editors with no visible exhibitions are omitted, and banners show the live count without stale date ranges.

### Fixed
- The List tab’s collapsible filters respond only to user drag/fling input, preventing header animation from causing a bounce near the end of the list.
- iOS native launch assets and the Compose splash overlay use matching semantic light/dark colors and logo treatment.
- Long single-exhibition map labels truncate cleanly.
- Editor loading, error, empty, and retry behavior now tracks both editor and exhibition data reliably.

### Behind the scenes
- No database migration, backend deployment, or feature-flag change is required.
- The web rebuild workflow and submission-form regression coverage were strengthened in the same merge window; these changes do not alter the mobile binaries.

### Artifact checksums (SHA-256)
- Android AAB: `bcc8c4b6ae6c34ecfff17987e0a3a974ce416ac639bdc393baf3229434c325bc`
- iOS IPA: `2766bb125797d47f19a23574feaaa815fc15391c3fcc21a6758b1da03153f38f`

---

## Pre-submission checklist

- [x] Base release branch on latest `origin/develop` (`adb03e5`)
- [x] Android `versionCode` 19 → **20**, `versionName` 1.7.2 → **1.7.3**
- [x] iOS `MARKETING_VERSION` 1.7.1 → **1.7.3**, `CURRENT_PROJECT_VERSION` 14 → **15** (Debug + Release)
- [x] Run `:shared:allTests` and `:composeApp:allTests` (127 Gradle tasks)
- [x] Build signed Android App Bundle; verify `jar verified` and embedded version **1.7.3 (20)**
- [x] Archive and export iOS App Store IPA; verify bundle `com.gallr.app`, version **1.7.3 (15)**, arm64, and App Store provisioning
- [ ] Upload Android bundle to Play Console
- [ ] Upload iOS build to App Store Connect
- [ ] Promote `develop` to `main` through a reviewed PR
