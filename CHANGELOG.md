# Changelog

All notable changes to gallr will be documented in this file.

## [1.6.0] - 2026-05-12

### Added
- **Editor hub.** A single "Editors" filter chip replaces both "Editor's Picks" and "[Name]'s Picks". Tapping it opens a tile selector showing the gallr team's house picks alongside every active and past guest editor. Tapping a tile lands on a dedicated editor detail page with the editor's banner and their curated exhibitions.
- **Past editors browsable.** Inactive editors and their curated exhibitions are preserved in the new "Past editors" section of the selector — every editor's contribution stays attributable over time.
- **Multiple simultaneous active guest editors.** The single-banner constraint is gone; the selector cleanly shows multiple active editors as peers in the "Currently curating" section.

### Changed
- The `Editor's Picks` and `[Name]'s Picks` filter chips no longer exist. Both concepts collapse into the unified `Editors ›` portal chip.
- Editor banners no longer appear inline on the List tab. Banners now live on the dedicated editor detail page.

### Infrastructure
- New `editors` table (renamed from `guest_editors`), with a hardcoded `gallr-editors` seed row representing the gallr team's house identity.
- New `exhibitions.editor_id` foreign key column replaces both `is_editors_pick` (Boolean) and `guest_editor_id` (FK). Migration `017_unify_editors.sql` performs the rename, seeds the house editor, backfills existing flagged exhibitions, and drops the legacy columns.
- Apps Script sync: `KNOWN_COLUMNS` updated; FK validation renames from `guest_editor_id` to `editor_id`; the `is_editors_pick` Boolean branch is removed.
- Admin sheet workflow change: previously `is_editors_pick = TRUE` rows now type `gallr-editors` into the new `editor_id` column. See `gas/README.md` for the bulk-replace ARRAYFORMULA tip.

## [1.5.1] - 2026-05-12

### Fixed
- **Past reception labels no longer linger.** The exhibition detail page used to show "Opening Apr 5, 5 PM" the day after a reception ended, making it read like an upcoming event. Now the reception label and its inline opening time hide starting the calendar day after the reception date — both Korean ("오프닝 4월 5일, 5 PM") and English variants. Boundary is calendar-date based in the device's timezone, not a 24-hour window.

## [1.5.0] - 2026-05-12

### Added
- **Guest Editor curation.** A partner curator's exhibition list now surfaces in the app as a leftmost filter chip on the List tab — "[Name]'s Picks" in English, "[이름]의 픽" in Korean. Tap the chip and an editorial banner slides down (~250 ms vertical expand) above the filtered results: a small "GUEST EDITOR" label, the editor's bilingual name in display weight, their title/institution, and a short bio in italic — left-border accent layout consistent with gallr's monochrome aesthetic.
- **Mutual-exclusive editorial filter.** Tapping the guest-editor chip clears every other active filter; tapping any other chip clears the guest pick. The screen always belongs to one editorial voice at a time.
- **Bilingual editor data with fallback.** Editor name, title, and bio are stored in both Korean and English. English falls back to Korean if the English field is empty. When a guest editor is active but has no tagged exhibitions, the list shows "No exhibitions in this list" / "선택된 전시가 없습니다".
- **Past editors preserved for history.** Each guest editor row has `active_from` / `active_to` dates. Past editors and their tagged exhibitions stay in the database for future browsing surfaces.

### Infrastructure
- New `guest_editors` Supabase table with row-level security scoped to active rows only (anon key cannot read draft / inactive editor bios). Admin populates via Supabase Studio.
- New `guest_editor_id` foreign key column on `exhibitions`, nullable, set-null on parent delete. Indexed for efficient guest-editor filtering.
- Exhibition sync (`gas/SyncExhibitions.gs`) validates the new `guest_editor_id` slug against the `guest_editors` table before insert — the existing `event_id` FK validation pattern extended to guest editors. Bad slugs in the sheet are skipped with a clear log message.
- Active-editor query pinned to Asia/Seoul timezone and honors `active_from` as well as `active_to` — future-scheduled editors do not activate prematurely.

## [1.4.0] - 2026-04-25

### Added
- **Splash screen on cold launch.** Branded launch experience with the arch-pin gallr logo centered on a theme-aware background (white in light mode, `#121212` in dark mode). A native platform splash appears instantly (Android `SplashScreen` API, iOS `LaunchScreen.storyboard`) and hands off seamlessly to a Compose overlay that holds for a 1.5s minimum brand moment, dismisses once exhibition data has loaded, and is capped at 3s regardless of network state. Cold-launch only — no splash on background restore.
- **Local push notifications for bookmarked exhibitions.** On-device reminders surface time-sensitive moments without any backend:
  - **Closing soon** — 3 days before a bookmarked exhibition's closing date.
  - **Opening soon** — 3 days before a bookmarked exhibition's opening date.
  - **Reception reminder** — morning of a bookmarked exhibition's reception day.
  - **My List inactivity** — 7 days after the user's last bookmark add or remove.
- All notification copy is bilingual (Korean / English) and respects the in-app language setting. Tapping a notification deep-links to the relevant exhibition or list. A contextual permission prompt appears on first bookmark — never as a cold prompt on app open.

## [1.3.0] - 2026-04-24

### Added
- **City-wide art event support (Phase 1).** A new Featured-tab promoted card and dedicated Event Detail screen surface active city-wide events (launch event: Loop Lab Busan 2025). Participating galleries and linked exhibitions are discoverable from a single entry point. Backed by a new `events` table, an `exhibitions.event_id` foreign key, and a new `gas/SyncEvents.gs` sync pipeline.
- **Hero image on the Featured event card (Phase 2a).** Event cards now render a cover image with a dark scrim and overlaid text, using a new `events.cover_image_url` column. Falls back gracefully to the flat brand color when no image is present.
- **List-tab surface treatments (Phase 2b).** Three new surfaces on the List tab appear automatically when a city-wide event is active:
  - A slim pinned banner above the tab row showing the event name and "NOW ON" label; tap opens Event Detail. Visible on both All Exhibitions and My List sub-tabs.
  - A brand-colored filter chip leading the flags row that filters the list to event-linked exhibitions.
  - A small corner label on event-linked exhibition cards for at-a-glance identification.
- **Map-tab event treatments (Phase 2c).** When a city-wide event is active, exhibition pins linked to the event are recolored in the event's brand color, and a brand-colored FAB anchored to the bottom-right opens Event Detail.
- All event surfaces collapse to zero footprint when no event is active, and auto-reset if an active event expires mid-session.

### Changed
- Events sync switched from delete-all-then-insert to upsert + diff-delete, eliminating the FK orphan window that previously caused linked exhibitions to briefly appear unlinked after each events-sheet edit.
- Featured event card now sizes to its content with current padding values instead of a fixed 140dp height.
- Event Detail's exhibitions list reuses the standard exhibition card (cover image, bookmark heart, status label) instead of a stripped-down variant.

### Removed
- The accent color treatment on event names (last-token tint in Featured banner, List banner, and Event Detail header). The effect added visual noise without aiding scanability; event names now render in solid white across all three surfaces.

## [0.0.4.0] - 2026-04-16

### Added
- Profile photo crop & resize screen with pan/pinch-to-zoom and circle overlay. Users can frame their photo before uploading.
- Skeleton placeholders on Profile tab while data loads, eliminating flash of default username/avatar.
- Keyboard dismiss on tap outside text fields in Edit Profile screen.

### Changed
- Image picker now returns raw bytes; compression happens after cropping for better quality.
- Crop overlay renders at app level with proper z-ordering on both iOS and Android.

## [0.0.3.0] - 2026-04-15

### Changed
- App now defaults to Korean on first launch, regardless of device locale. Existing users with a saved language preference are unaffected.
- Profile photo change button ("사진 변경") is now the sole tap target for the photo picker. The profile photo circle is display-only.

### Fixed
- Removed camera emoji overlay from profile photo circle, consistent with the Reductionist design system.
- Photo change button now uses Material3 TextButton with proper ripple, touch target, and disabled state dimming.

## [0.0.2.0] - 2026-04-09

### Added
- City filter chips now sorted by exhibition count (most exhibitions first). Each chip shows the count, e.g. "Seoul (42)".
- Region sub-filter chips appear below city chips when a city is selected. Multi-select support lets you combine regions (e.g. Gangnam-gu + Jongno-gu). Includes "All" chip for quick region reset.
- `CityWithCount` and `RegionWithCount` data classes for type-safe city/region filter data.
- 8 unit tests covering city sort-by-count, region grouping, active-only counting, and edge cases.

### Changed
- City filter counts only active (non-ended) exhibitions, so the displayed count matches visible results.
- Switching cities or tapping "All" automatically clears region selection.
- `GallrFilterChip` now supports a `small` variant for compact region chips.

## [0.0.1.0] - 2026-04-08

### Added
- Opening time display on exhibition detail page. When a reception has a time recorded (e.g., "5 PM"), the label now reads "Opening today, 5 PM" instead of just "Opening today". Works across all label states: today, tomorrow, weekday, and past dates. Both Korean and English locales supported.
- New `opening_time` column in the exhibitions database (nullable text, free-form entry).
- Sync pipeline support for opening time from Google Sheet to app.
- 21 unit tests covering all label states with and without opening time, both locales, and edge cases.

### Changed
- Extracted `receptionDateLabel()` from ExhibitionDetailScreen to shared module for testability. Injectable `today` parameter enables deterministic testing.
