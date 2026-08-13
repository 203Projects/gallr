# gallr 1.8.2 — Release Notes

**Version:** 1.8.2

**Android versionCode:** 28

**Android target API:** 36 (Android 16)

**iOS build:** 23

**Date:** 2026-08-13

**Reader source for release artifacts:** `canonical-v2`

---

## Store “What’s New”

### Korean (`ko-KR`)

```text
지도를 축소했을 때 전시 위치를 더 편하게 살펴볼 수 있습니다.
• 가까운 위치는 개수가 표시된 핀으로 깔끔하게 묶입니다
• 한 장소와 여러 장소의 핀을 쉽게 구분할 수 있습니다
• 겹치던 전시 제목을 정리해 지도를 읽기 쉬워졌습니다
```

### English (`en-US`)

```text
Exhibition locations are easier to explore when the map is zoomed out.
• Nearby locations collapse into clean counted pins
• Single and grouped locations are easier to distinguish
• Overlapping exhibition titles are reduced for a clearer map
```

---

## Full change summary

### Fixed

- Restored the 1.8.0 screen-space proximity grouping behavior at zoomed-out map levels.
- Restored the 1.8.0 two-pin group marker geometry and added a compact exhibition count badge.
- Corrected MapLibre anchor compensation so each white backing layer forms an outline instead of a displaced shadow.
- Kept singleton captions to one collision-aware line to prevent the 1.8.1 zoomed-out title overlap regression.

### Release boundary

- This release replaces the pending 1.8.1 submissions before review.
- Android and iOS release artifacts continue to use the reviewed Seoul Supabase project and `canonical-v2` catalogue source.
- No database migration or production configuration change is required.

---

## Pre-submission checklist

- [x] Rebase the map fix onto the latest `origin/develop`.
- [x] Bump Android to **1.8.2 (28)**.
- [x] Bump iOS to **1.8.2 (23)** for Debug and Release configurations.
- [x] Run the complete mobile verification gates.
- [x] Build and verify the signed Android App Bundle.
- [x] Archive, export, and verify the signed iOS App Store IPA.
- [x] Remove the pending 1.8.1 submissions from review.
- [x] Upload Android 1.8.2 (28) and update its store release notes.
- [x] Upload iOS 1.8.2 (23), attach it to the 1.8.2 App Store version, and update “What’s New”.
- [x] Submit the corrected mobile versions for review.
