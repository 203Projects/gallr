# gallr 1.6.4 — Release Notes

**Version:** 1.6.4
**Android versionCode:** 15
**iOS build:** 11
**Date:** 2026-06-03
**Submission artifact (Android):** `gallr-1.6.4-15-release.aab` (signed AAB)

---

## Play Store "What's new" (copy-paste into Play Console → release notes)

### Korean (`ko-KR`) — 162 chars

```
이벤트 화면을 정리했습니다.
• 다가오는 이벤트도 미리 노출 — "곧 시작" 표시 후 시작일에 "지금 진행 중"으로 전환
• 지도 탭 이벤트 버튼이 동그란 이벤트 이미지로 변경
• 이벤트 상세 배너에서 긴 장소명이 잘리지 않도록 수정
• 상단 뒤로가기 버튼이 상태 표시줄에 겹치던 문제 수정
```

### English (`en-US`) — 303 chars

```
We tidied up the events experience.
• Upcoming events now appear early — shown as "COMING SOON," switching to "NOW ON" on the start date
• The map-tab event button is now a circular event image
• The event detail banner no longer clips long venue names
• Fixed the back button overlapping the status bar
```

> Both are well under Google Play's 500-character limit.

### App Store "What's New" (iOS, same release) — English 303 chars

Use the English copy above. (iOS ships the same 1.6.4 changes.)

---

## Full change summary

### New / Changed
- **Promote upcoming events.** An active event now appears across the app (Featured banner, List banner, Map button, exhibition-card ribbon) **before** its start date, so upcoming events can be promoted ahead of opening. The eyebrow is date-aware: **"COMING SOON" / "곧 시작"** before the start date, then **"NOW ON" / "지금 진행 중"** once it's running. Events still retire automatically after they end.
- **Cleaner event labels.** Removed the hardcoded "ART EVENT" / "아트페어" tag that appeared on every event. The detail top bar now reads **"EVENT" / "이벤트"**; the detail banner shows the admin-written location with no inaccurate "CITY-WIDE" / "도시 전역" prefix; the participating section is retitled **"Participants" / "참여"**.
- **Circular event button on the map.** The persistent Map-tab button is now a circular crop of the event's cover image with a brand-color ring, instead of a square with truncated text. Falls back to a solid brand-color circle when there's no cover image.

### Fixed
- **Long venue names no longer clip** in the event detail hero banner — the banner grows to fit its text (e.g. "홍익대학교 | 문헌관, 아트앤디자인밸리").
- **Back button no longer hides under the status bar** on the event detail screen (Android status-bar / camera-cutout inset is now reserved).

### Behind the scenes
- New `events.short_label` column (compact event tag, ≤ 12 chars, e.g. "FLUX 614") for the exhibition-card corner ribbon; falls back to 12-char name truncation when unset. **Requires running migration `018_add_event_short_label.sql` in Supabase and adding the `short_label` column to the events sheet to take effect.**
- iOS build fix: removed a Key-Value-Coding share-sheet "subject" hack that no longer compiles under the Xcode 26 SDK (the image share is unaffected).

---

## Pre-submission checklist

- [x] Android `versionCode` 14 → **15**, `versionName` → **1.6.4**
- [x] iOS `MARKETING_VERSION` → **1.6.4**, `CURRENT_PROJECT_VERSION` 10 → **11** (Debug + Release)
- [x] Signed release AAB built and verified (`jarsigner -verify` → "jar verified")
- [x] iOS device + simulator targets compile (Xcode 26.5 SDK)
- [x] `:shared:testDebugUnitTest` green
- [ ] **Run Supabase migration `018_add_event_short_label.sql`** (else the ribbon uses name-truncation fallback — not a blocker, but `short_label` won't apply until done)
- [ ] Add `short_label` header to the `gallr_events_list` sheet (optional, to populate ribbons)
- [ ] Upload `gallr-1.6.4-15-release.aab` to Play Console
- [ ] Archive + upload iOS build 11 from Xcode
