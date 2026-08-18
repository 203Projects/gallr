# Changelog

All notable changes to gallr will be documented in this file.

## [Unreleased]

## [1.9.1] - 2026-08-18

### Fixed
- **Location permission prompts explain themselves completely.** The iOS build now declares the
  always-and-when-in-use purpose string that App Store delivery requires, while the app continues to
  request when-in-use access only and never uses location in the background.

### Infrastructure
- The map stack moves as a matched set: `maplibre-compose` 0.10.4 with its declared native SDKs,
  Android 11.12.1 and iOS 6.17.1. The wrapper's 0.10 API changes (`baseStyle` replacing `styleUri`,
  and source identifiers becoming implicit) are applied in the Seoul exhibition map.
- Admin and gallery workspaces take patch updates to `@supabase/supabase-js`,
  `@testing-library/jest-dom`, and `@testing-library/user-event`.
- The Android version is now **1.9.1 (33)** and the iOS version is **1.9.1 (27)**.

## [1.9.0] - 2026-08-16

### Added
- **My Gallr is now a personal art archive for every visitor.** Guests can record exhibitions they have visited, keep the archive on their device, search the catalogue, and add several visits at once without creating an account.
- **Visitors can follow galleries and see newly published exhibitions.** Gallery search, gallery detail, visit history, follow controls, and per-device publication alerts form one continuous discovery flow.
- **Accounts can back up and restore My Gallr.** Signing in merges device visits and followed galleries into an isolated account archive with idempotent retry, cross-device restore, and safe removal convergence.
- **Ended exhibitions invite lightweight reflection.** A contextual prompt on exhibition detail lets visitors add a past exhibition to their archive without interrupting Featured or List browsing.

### Changed
- **The fourth tab opens My Gallr for signed-out and signed-in visitors.** Profile and account actions remain available by choice instead of gating the useful archive experience.
- **Account creation is requested only after value is established.** A dismissible backup invitation appears after three combined visits and followed galleries, and account entry returns to My Gallr.

### Fixed
- **Future exhibitions cannot be recorded as past visits.** The archive picker and save boundary now accept only exhibitions whose opening date is today or earlier.
- **Gallery-alert release builds fail closed when notification configuration is missing.** Android store bundles require the reviewed Firebase client values, while iOS selects sandbox or production APNs through its build configuration.

### Infrastructure
- Four additive migrations introduce stable public gallery identity, installation-scoped gallery-alert commands, publication delivery, and authenticated My Gallr archive commands while preserving installed-client catalogue compatibility.
- Notification delivery, archive merge/restore, RLS isolation, idempotency, clean migration replay, concurrency, and platform deep links are covered across database, Edge Function, shared, Android, and iOS gates.
- The Android version is now **1.9.0 (32)** and the iOS version is **1.9.0 (26)**.
- Mobile release artifacts continue to use the reviewed Seoul Supabase project and the `canonical-v2` exhibition catalogue source.

## [1.8.3] - 2026-08-14

### Added
- **Editors can review every submitted curation.** My curation now shows prior submissions with their status, review dates and notes, statement snapshots, and exhibition changes.
- **Creating a curation has its own workspace.** Add curation keeps the curatorial statement and exhibition selection together without conflating creation with submission history.

### Changed
- **The editor catalogue matches the mobile app's Seoul-date window.** Editors can curate ongoing exhibitions and exhibitions opening within 14 days; exhibitions assigned to another editor remain visible with an unavailable explanation.

### Fixed
- **Admin navigation shows only working destinations.** Non-functional Venues, Events, and Audit placeholders are removed, and the Editors destination remains restricted to administrators.

### Infrastructure
- Curation history is exposed through a least-privilege, active-membership-scoped database contract with authorization and catalogue-parity coverage.
- The Android version is now **1.8.3 (30)** and the iOS version is **1.8.3 (24)**.

## [1.8.2] - 2026-08-13

### Fixed
- **Zoomed-out map pins stay readable.** Nearby projected locations once again collapse into a counted multi-location marker as the map zooms out, multi-location markers use a distinct stacked-pin treatment, and single-location captions remain one-line and collision-aware.
- **Android edge-to-edge is clean across supported versions.** The host now handles system-bar insets and icon contrast without retaining Android 15's deprecated system-bar color APIs or the deprecated `shortEdges` cutout mode.
- **Editor invitations now ask only for an email.** Invited editors set their password and create an unpublished profile in the dedicated editor portal, while Admin retains publication and scheduling control.
- **Pending editors stay in the editor portal.** Invitation-only accounts receive a narrow onboarding role instead of being redirected to the staff Admin portal.

### Infrastructure
- The Android version is now **1.8.2 (29)** and the iOS version is **1.8.2 (23)**.
- Mobile release artifacts continue to use the reviewed Seoul Supabase project and the `canonical-v2` exhibition catalogue source.
- The pending 1.8.1 submissions are replaced by this corrected map release before review.
- Pull-request review jobs use immutable commit references so delayed CI runs remain reproducible after merge.

## [1.8.1] - 2026-08-13

### Added
- **Exhibition tickets open directly from the detail screen.** A ticket action appears when an exhibition provides a ticket URL and hands off to the platform browser.

### Fixed
- **Map interactions are reliable on Android and iOS.** Exact-coordinate exhibitions share a single counted pin, pins no longer stack by screen proximity, and pinch gestures beginning over a pin reach the map correctly.
- **iOS content uses the full screen without double safe-area insets.** The native host and Compose now apply system insets once.
- **The gallr header logo stays visible in dark mode.** The logo follows the active theme foreground color on both mobile platforms.
- **Mobile catalogues stay aligned across supported releases.** Country identity now flows through the Seoul and Singapore compatibility readers, preventing checksum rejection and stale exhibition sets.

### Infrastructure
- Android release builds now use R8 code and resource optimization and produce deobfuscation mappings.
- The Android version is now **1.8.1 (27)** and the iOS version is **1.8.1 (22)**.
- Mobile release artifacts continue to use the reviewed Seoul Supabase project and the `canonical-v2` exhibition catalogue source.
- The approved-but-unreleased 1.8.0 store submissions were discarded before preparing this replacement release.

## [1.8.0] - 2026-08-11

### Added
- **Editors now have a focused curation workspace.** Administrators can invite editors, while each editor can maintain their own biography, write a separate curatorial statement, curate ongoing exhibitions, suggest missing exhibitions, and submit changes for administrator approval.
- **Gallery owners can maintain gallery information and hide exhibitions.** The owner workspace supports revision-checked venue profiles, reusable venue details, and safe exhibition removal without deleting canonical or published records.
- **Saved exhibitions can be explored on a personal map.** The mobile profile experience includes a dedicated map of the signed-in visitor's bookmarked exhibitions.
- **Authenticated visitors can delete their account.** Account deletion uses recent-authentication checks, operational-account safeguards, and durable avatar cleanup.

### Changed
- **Settings and privacy controls are clearer.** Profile, notification, appearance, privacy, and account actions are organized into a dedicated settings experience.
- **Editorial operations now use the canonical Admin workflow.** Legacy Google Sheets, Apps Script submission, and public anonymous submission paths are retired from the supported production flow.

### Fixed
- **Mobile catalogue startup is resilient to transient failures.** Cached exhibition data remains usable while canonical refresh errors are surfaced safely.
- **iOS hosted archives can locate JDK 17 reliably.** Xcode Cloud prepares the required Java runtime before the Kotlin/Native build phase.
- **Legacy mobile catalogue mirroring preserves canonical data integrity.** Compatibility snapshots and trigger assertions now retain the reviewed canonical projection.
- **Every supported mobile release sees the same production exhibitions.** The Seoul-to-Singapore compatibility mirror now carries country identity through both reader contracts, preventing checksum rejection from leaving pre-1.7.7 Android and iOS catalogues stale.

### Infrastructure
- Production database changes remain additive and follow the immutable recorded migration lineage before client promotion.
- Admin, gallery-owner, public web, Android, and iOS release surfaces are validated together in CI, including every deployable Edge Function.
- The Android version is now **1.8.0 (25)** and the iOS version is **1.8.0 (20)**.
- Mobile release artifacts must use the reviewed Seoul Supabase project and the `canonical-v2` exhibition catalogue source.

## [1.7.7] - 2026-08-03

### Infrastructure
- **Mobile catalogue traffic now targets the Seoul Supabase production project.** New iOS and Android builds use the Korea-region backend while installed older versions remain compatible with the retained Singapore rollback source.
- Android release configuration can receive its public Supabase URL and publishable key through Gradle properties or environment injection, so release credentials can remain sourced from 1Password without a persistent plaintext file.
- Android store bundles now fail closed unless they use the reviewed Seoul URL, the `canonical-v2` catalogue, a public API key, and the existing Play-registered upload keystore supplied through 1Password-injected environment variables or the legacy gitignored signing file.
- iOS Release now selects `canonical-v2` by default, and the checked-in App Store Connect export profile plus archive-only fastlane lane produce a reviewable artifact without uploading it.
- The Android version is now **1.7.7 (24)** and the iOS version is **1.7.7 (19)**.
- Mobile release artifacts must continue to be built with the explicit `canonical-v2` exhibition catalog source.

## [1.7.6] - 2026-07-31

### Added
- **Exhibition credits now appear in details.** When an exhibition includes Korean or English production credits, the app shows the localized credit text continuously after the exhibition description.

### Infrastructure
- The Android version is now **1.7.6 (23)** and the iOS version is **1.7.6 (18)**.
- Mobile release artifacts must continue to be built with the explicit `canonical-v2` exhibition catalog source; the rollback-safe legacy reader remains available.

## [1.7.5] - 2026-07-30

### Fixed
- **The iOS launch logo now aligns with the in-app splash screen.** The native launch-screen mark uses the same visual size and placement as the Compose handoff, removing the apparent logo jump while the app starts.

### Infrastructure
- The Android version is now **1.7.5 (22)** and the iOS version is **1.7.5 (17)**.
- Mobile release artifacts must continue to be built with the explicit `canonical-v2` exhibition catalog source; the rollback-safe legacy reader remains available.

## [1.7.4] - 2026-07-30

### Changed
- **Mobile exhibition readers are prepared for the canonical catalog.** Android and iOS release artifacts for this version must be built with the explicit `canonical-v2` source setting, giving every exhibition surface the same published-only catalog and count/content integrity contract already verified on the production website.

### Infrastructure
- The Android version is now **1.7.4 (21)** and the iOS version is **1.7.4 (16)**.
- Android now compiles against and targets Android 16 (**API level 36**) using Android Gradle Plugin 8.10.1 and Gradle 8.11.1, satisfying the Google Play app-update requirement effective August 31, 2026.
- The checked-in reader default remains `legacy` as the rollback-safe fallback. Release commands must explicitly select `canonical-v2`; legacy tables, RPCs, and the transactional compatibility mirror remain in place throughout the mobile rollout.

## [1.7.3] - 2026-07-19

### Changed
- **Exhibition catalogs stay timely across every surface.** The Featured, List, Map, Event, and Editor views now use the same visibility window: ended exhibitions are hidden and upcoming exhibitions appear starting 14 days before opening.
- **Editor pages focus on live catalog content.** Editors without a visible exhibition no longer appear, exhibition totals exclude hidden listings, and the banner omits outdated curation date ranges.

### Fixed
- **List filters no longer bounce back near the end of a scroll.** The collapsible filter header now reacts only to deliberate user scrolling, so its own animation cannot reopen it unexpectedly.
- **Native and Compose splash screens now match.** Light and dark launch colors and logo treatment stay consistent through the iOS handoff, avoiding a visible flash during startup.
- **Map labels remain readable.** Long single-exhibition captions are truncated cleanly instead of crowding map markers.
- **Editor loading and retry states are reliable.** The editor selector waits for exhibition data, reports failures, and retries both data sources together.

## [1.7.2] - 2026-06-14

### Added
- **Shared story cards now carry the gallr mark.** The Arch Pin logo renders beside the "gallr" wordmark at the bottom of generated exhibition share images on Android and iOS, matching the monochrome brand treatment.

### Changed
- **Exhibition sharing opens the system sheet directly.** Tapping the share icon now prepares the story-card image and opens the native destination picker without the intermediate preview and Send step.

### Fixed
- **Exhibition sharing no longer crashes the app.** Share failures are caught at the tap coroutine and platform handler layers, Android no-target or image-prep failures are logged as no-ops, and iOS share sheets use a connected-scene presenter with a popover anchor for iPad and Mac.

## [1.7.1] - 2026-06-09

### Fixed
- **Each active event filter is independently selectable.** In the List tab, tapping one active event no longer selects every active event at once. The filter now stores the selected event id, so each event can be discovered on its own and tapping the selected event clears it.
- **Later-entered active events show their participant exhibitions.** Event detail pages now load exhibitions for the requested event id, and the sync pipeline no longer clears the whole exhibitions table before re-inserting rows. This avoids transient missing participant cards when syncs overlap with app reads.
- **Map pins include every active event.** Grouped map locations now preserve colored event pins for all active events instead of only the first active event at a shared location.
- **Event wording is general.** User-facing active-event copy now says "이벤트" / "Events" instead of "아트페어" / "Art Fairs" where the feature is not art-fair-specific.

### Changed
- **Image loading no longer uses Supabase Storage Image Transformations.** App image surfaces now keep public Storage object URLs and let native image loaders handle sizing/cropping, reducing Supabase transformation quota usage. Legacy render URLs are normalized back to public object URLs if encountered.

## [1.7.0] - 2026-06-08

### Added
- **Multiple art fairs now show at once.** When two or more events are active at the same time (e.g. KIAF and Frieze during Seoul Art Week), the app surfaces all of them instead of silently dropping all but one. The Featured tab gets a swipeable, auto-advancing hero pager with a dot indicator; the List tab's banner auto-cycles through every active fair with a tap-to-open / swipe-to-switch control and a timing bar; the Map tab's floating button cycles its cover image and brand color across fairs. With a single active event, every surface looks and behaves exactly as before.
- **Filter by any active fair.** The List tab shows one filter chip per active event in its brand color, and the "events only" filter now keeps exhibitions belonging to *any* active fair, not just the first one.
- **Respects reduced motion.** When the device has Reduce Motion or a screen reader turned on, the auto-advancing carousels stop animating on their own — every event still renders and stays reachable by swipe or tap, and the cards carry proper accessibility labels.

### Fixed
- **Second and third active events are no longer invisible.** Previously the app kept only the first active event and discarded the rest, so a second active fair never appeared anywhere. All active events are now retained and displayed.

## [1.6.4] - 2026-06-03

### Changed
- **Event screens read cleaner.** The Featured banner and event detail page no longer stamp a hardcoded "ART EVENT" / "아트페어" label on every event. The detail top bar reads "EVENT" / "이벤트"; the detail banner's eyebrow now shows the event's status ("Upcoming" / "예정" or "NOW ON" / "진행 중") instead of repeating the venue, which already appears on the date line — no more duplicated venue and no inaccurate "CITY-WIDE" / "도시 전역" prefix. The participating-galleries section is retitled "Participants" / "참여".
- **Upcoming events can be promoted before they open.** An active event now surfaces (Featured banner, List banner, Map button, exhibition-card ribbon) as soon as it's marked active — including before its start date — instead of only during its run. Its eyebrow is date-aware: "Upcoming" / "예정" before the start date, then "NOW ON" / "지금 진행 중" once it's running. Events still auto-retire after their end date with no manual flip needed.
- **Event map button is now the event's image.** The persistent button on the Map tab — previously a square showing an awkwardly truncated text label (e.g. "Everythi…") — is now a circular crop of the event's cover image with a brand-color ring. It's instantly recognizable and works for any event name length. When an event has no cover image, it falls back to a solid brand-color circle.

### Fixed
- **Event detail banner no longer clips long locations.** The hero banner had a fixed height that cut off longer venue strings (e.g. "홍익대학교 | 문헌관, 아트앤디자인밸리") at the bottom edge. The banner now grows to fit its text, so the full location is always visible; shorter events get a shorter banner.
- **Event detail back button no longer hides under the status bar.** The event detail top bar didn't reserve the system status-bar / camera-cutout inset, so the back arrow and "EVENT" label drew under the clock and notch on Android. The top bar now reserves the status-bar inset.

### Infrastructure
- New nullable `events.short_label` column (recorded as `20260603052153_add_event_short_label.sql`) for a compact event tag (recommended ≤ 12 chars, e.g. "FLUX 614") shown in the pink corner ribbon on exhibition cards. When set by the admin it's used verbatim; otherwise the app falls back to truncating the localized event name to 12 characters. Wired through `EventDto`, the `Event` model, and the Apps Script sync (`KNOWN_COLUMNS` + blank-cell defaults).

## [1.6.3] - 2026-05-19

### Added
- **Anyone can submit an exhibition.** A public submission form is now live on the web: galleries and curators fill in the details, attach images, and the listing enters a review queue before it appears in the app. Submissions are validated, rate-limited, and image-checked end to end; nothing publishes until it's approved.

### Changed
- **iOS catches up to the current release.** The iOS app version had lagged two releases behind Android. It is realigned to 1.6.3, so iPhone users now get the exhibition story sharing and the saved-exhibitions sign-up reminder that already shipped on Android.

## [1.6.2] - 2026-05-14

### Fixed
- **Editor banner no longer shows "하우스 에디터" twice.** The house-editor row seeded its title field with the same string the banner already auto-renders as a type label, so the label appeared on two consecutive lines. The title line now hides when it would duplicate the auto-generated label (or when it's empty).
- **Notification permission body wording.** The Korean prompt now reads "북마크한 전시가 곧 마감, 종료하거나 오프닝 리셉션이 있을 때 알려드릴게요." English aligned to match: "We'll let you know when bookmarked exhibitions are closing soon, ending, or hosting an opening reception."

### Changed
- **Editor detail cards sit on a margin.** Exhibition cards on the editor detail page now have the standard horizontal screen margin and inter-card spacing, matching the rest of the app instead of running edge-to-edge with no gaps.

## [1.6.1] - 2026-05-13

### Fixed
- **Editor screens respect Android system insets.** Tapping the Editors chip on Android no longer slides the back arrow under the status bar, and the screen now paints a solid background instead of reading as a faded translucent block in dark mode. Both `EditorSelectorScreen` and `EditorDetailScreen` now wrap their content in a Material3 `Scaffold` with `WindowInsets.safeDrawing` and `colorScheme.background`. Status bar, display cutouts, and the gesture/navigation bar are all reserved on both light and dark themes. Chrome-only fix — no behavior or data changes.

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
- New `exhibitions.editor_id` foreign key column replaces both legacy editor fields. Recorded migration `20260513110749_unify_editors.sql` performs the rename, seed, and backfill; later recorded May migrations expose generated compatibility aliases for v1.5 clients.
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
