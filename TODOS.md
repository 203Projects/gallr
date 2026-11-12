# TODOS

Last updated: 2026-11-12. Revalidate external service and release status before
acting on older operational entries.

This file is the authoritative open-work list. Unchecked boxes in completed or
superseded specifications are historical execution records unless an item below
links back to them explicitly.

## Rollout Queue — Integrated, Not Yet Activated

### Local discovery, aggregate analytics, and explainable recommendations

Four completed specifications are now integrated into `develop`:

- `071-local-discovery-intelligence`: deterministic, private on-device
  recommendations and neighborhood route planning.
- `072-mobile-product-analytics`: aggregate-only mobile analytics with a bounded
  offline queue, Supabase ingestion, disclosure, and user/release gates.
- `073-local-discovery-experience`: the mobile For You and neighborhood-route
  presentation.
- `074-explainable-art-recommendations`: reviewed artist/art metadata and
  bilingual evidence for personalized recommendations.

PRs #246–#253 and #259 landed bottom-up on 2026-09-03 with green automated
checks. Repository integration does not authorize applying the metadata or
analytics migrations, deploying `mobile-analytics`, enabling analytics
collection, changing hosted configuration, or releasing new mobile builds.

Roll out through staging in contract order: apply the migrations; deploy the
disabled function with an environment-specific component secret; verify Admin,
Gallery, canonical-v2, legacy fallback, mobile analytics-disabled behavior, and
recommendation evidence; then make a separate production enablement decision.
Keep both mobile analytics release flags and `MOBILE_ANALYTICS_ENABLED` false
until disclosure, user preference, and staged aggregate evidence are approved.

The Home tab's 지금 가장 많이 본 curation (spec 091) reads these aggregates through
`list_most_viewed_exhibition_ids` (migration `20261012120000_most_viewed_exhibitions`, applied to
staging and production on 2026-10-14). It stays hidden until collection is enabled.
The ranking caps a day at twenty opens per exhibition and needs opens on two days, but the aggregate
cannot tell one visitor from many and the day is the one the client reports (the ingest accepts the
last seven days), so one client inside the ingest quota can backdate opens across the week in a
single batch and push an exhibition up at once. Once real traffic passes twenty opens a day, popular
exhibitions also tie at the cap and fall back to id order. Before the tile is shown to real visitors,
decide whether that is acceptable or whether to count distinct sources and server-side days, which
needs a privacy review because it links a device digest to an exhibition
(`specs/091-home-option-a/contracts/most-viewed.md`).

The Instagram carousel keeps four insight slides. With the picture band, an insight whose text runs
past one slide takes a second, so long notes can lose a whole insight (reported in the export dialog
and `report.txt`). Measured on the 40 desk notes of 2026-10-13: no Korean carousel loses anything;
12 of 39 English carousels lose an insight (English insights of 250 to 300 characters). Decide
whether English insight slides get a shorter band or the English note a shorter target length.

On the exhibition page and Home the closing countdown (`D-3`) uses the device's time zone, like the
exhibition status lines; event surfaces use Korea time. Around midnight in Seoul a device in another
zone is a day off. The diary (spec 103) already uses Korea time for 오늘, 어제 and whether a show has opened
(`DiaryEntryViewModel`, `App.kt` `seoulToday`), so on such a device the diary row and the status line can
disagree for that hour. Move exhibition dates to Korea time in one change.

Spec 091 also adds the `visit` category to the reviewed vocabulary (migrations
`20261012130000_visit_taxonomy_category` and `20261012130100_visit_taxonomy_terms`). The deployed
Admin and gallery workspace reject a category they do not know: deploy both before applying the
second migration, or their exhibition editors fail to load the controlled descriptors.

## P1 — Visit diary with 감상 (spec 103, release 1.16)

Spec 103 is merged to `develop` (US3 in PR #423), with fixes from two local end-to-end runs (#425, #426 and
`fix/103-rerun-polish`). Nothing of it is applied to a hosted project. The task list is
`specs/103-visit-diary/tasks.md`; Phase 11 holds the owner steps.

### Apply the five unapplied migrations, in number order

**What:** `20261111051000_security_storage_boundaries` (its own plan, PR #400), then M1
`20261112100000_thought_moderation_status`, M2 `20261112100100_thought_reports_and_review`, M3
`20261112100200_retire_mobile_thought_moderation` and M4 `20261112100300_visit_diary_sync`. Staging first: the
five in order, with the Admin 감상 section deployed as a Vercel Preview against staging between M2 and M3, then the
staging pass (quickstart "Staging", tasks T128). Production in the same order, with the Admin production deploy
(`gallr-admin`, which deploys production from `main`) between M2 and M3 and M3 applied the same day (owner decision
8). Each apply starts with `node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs` and a dry run that
proposes exactly the expected version; never `--include-all`.

**Why:** Admin's 감상 section needs M2's functions; M3 retires the 1.15 in-app staff queue and must not land before
Admin can moderate; 1.16 sends mutation kinds that only M4 accepts.

**Gate:** submit 1.16 to the stores only after all five are live in production (tasks T134–T135).

**Apply M2 as it is on `develop`:** `20261112100100_thought_reports_and_review` was edited in place before it was
applied anywhere (after its merge in PR #415, most recently by PR #425). That is allowed only because no project
records it; apply the file exactly as it is on `develop` at the time of the apply, never an earlier copy (a local
stack or rehearsal that ran an earlier copy must be reset), and from then on change it only with a new migration.

**Priority:** P1
**Depends on:** owner authorisation for each hosted apply and deploy

### Keep internal builds off staging until M4 is applied there

**What:** A `develop` build signed in against staging sends `update_visit` (any diary save) and `merge_visit` (sign-in
with guest visits). Staging without M4 rejects the batch with `mutation_kind_invalid`, so the account outbox stays at
retry and nothing else syncs either. Use guest mode, or apply M4 to staging first.

**Priority:** P1 until M4 is on staging

### Device checks still open

Each story was checked by tests, and twice end to end on a local Supabase stack (signed in, Android and iOS, with
Admin); none was run against the hosted staging project, because staging has none of the migrations. Open: US5
T026–T027 (a 1.15 build after M3), US1 T066–T067, US2 T081–T082, US4 T097–T098, US3 T109–T110, US6 T117–T118, each on
Android and iOS against staging, including the "session refresh pending" case. Also open: T122 (analytics allowlist
assertion in `MobileAnalyticsEventTest`), T123 (privacy and logging review) and T126 (full verification on `develop`).
Copy written after the UI contract is now recorded in `contracts/diary-ui.md`; the owner still confirms it, in
particular the failure and fallback lines (PRs #417, #421, #426) and the sign-up card body.

### Offline save of an entry deleted on another device

**What:** Saving an existing entry reads the account once more first, so a sheet left open while another device
deleted the entry closes with 다른 기기에서 삭제된 기록이에요 instead of re-creating it. Offline (or while a session
refresh is pending) that read cannot run, the save is queued, and the server's `update_visit`, which creates a missing
entry, brings the deleted entry back when the outbox is sent. Known limit, accepted for 1.16. A fix needs the server
to tell a deleted entry from one never synced (for example a tombstone per exhibition that `update_visit` honours
unless the save is newer than the deletion), which is a new migration.

**Priority:** P3
**Depends on:** None

### The exhibition page's 감상 interval

**What:** A visible exhibition page reads its 감상 again every 60 seconds (`QUIET_READ_INTERVAL` in
`ExhibitionThoughtsViewModel`), on entering, and on returning to the foreground; a page out of sight is not read. A
감상 taken down can therefore stay on a page for up to a minute. Revisit the interval with the read counts after
release (the read is one `list_exhibition_thoughts` call per visible page).

**Priority:** P3
**Depends on:** 1.16 live

### One record the server refuses (account sync)

**What:** `sync_my_gallr_archive` applies a batch in one transaction and fails it whole on the first bad record without
saying which. Found in the second end-to-end run: a guest's follow of a gallery the server does not have failed the
foreign key on `gallery_id` (SQLSTATE 23503), and since the app decoded the error body as an archive
(`JsonConvertException`), every sync on that device stopped at 백업 재시도 필요. Fixed in the app
(`fix/103-rerun-polish`): the client reads the error's SQLSTATE and, after a class 22 or 23 refusal, sends each
mutation alone, dropping the one refused for good (`sync_mutation_refused`). Left: (1) the refused guest follow stays
on the device, so each sign-in sends it once more and it is refused again, costing a few requests; (2) the server
could keep such a follow by storing a `null` `gallery_id` when the gallery is unknown (catch `foreign_key_violation`
in the `follow_gallery` branch), or report per-mutation results so the client needs no one-by-one retry. Both are a new
migration, not written here.

**Priority:** P3
**Depends on:** None

### M5: refuse a 감상 without a diary entry, and drop the 1.15 staff path

**What:** Once the minimum app version in Admin → 앱 버전 is 1.16.0 or higher (only after 1.16 is live in both stores,
spec 098), a new migration `…_thoughts_require_diary_entry` (next pgTAP number then) refuses a 감상 with no
`content_private.my_gallr_visits` row for the same person and exhibition. In the same cleanup delete the trigger's
legacy 1.15 staff branch (unreachable since M3), and drop `profiles.is_admin` with its mirror triggers
(`content_private.enforce_profile_admin_mirror`, `sync_profile_admin_mirror`); the app stops reading it in 1.16.

**Priority:** P2
**Depends on:** 1.16 live in both stores and the minimum version raised (spec 103 FR-030, plan step 7)

### Read the moderation and adoption numbers

Weekly after release: the SC-003 and SC-004 queries in `specs/103-visit-diary/quickstart.md` (pending count and
oldest wait; reports open longer than 48 hours, expected 0). Eight weeks after 1.16 is live: SC-001 (accounts with
synced entries) and SC-002 (share of 1.16 entries with a date and a feeling, tags or note, counting filled fields
only).

### Set the privacy page's effective date when it goes live

`web/privacy.html` reads "Effective 12 November 2026" and describes the diary. Change the date (and
`web/tests/privacy-page.test.ts`) to the day the page reaches production if that is later, and deploy it before the
1.16 submission (`docs/mobile-analytics-store-disclosures.md`, owner steps for 1.16).

### Queue signed-in bookmark changes made offline

**What:** A signed-in save or unsave made offline, or while a session refresh is pending, is applied to the screen
only (`SyncBookmarkRepository.addBookmark`/`removeBookmark` log and drop the failed write), and the next successful
read of the account's bookmarks replaces it, so the change is lost. Diary entries and follows do not have this gap:
they go through the My Gallr account outbox. Queue bookmark writes the same way, or retry them after the refresh.

**Priority:** P2
**Depends on:** None

### Keep the screen trail when Android recreates the activity

**What:** On Android, switching the system light/dark setting recreates `MainActivity`, and the app comes back on the
Home tab: `AppNavigationState` (selected tab and each tab's trail) is held in `remember`, not saved across
recreation. Found in the spec 103 end-to-end run; it predates spec 103. Save the selected tab and trails (for example
with a `Saver` over destination keys, re-resolving exhibitions from the catalogue) or keep the state outside the
activity.

**Priority:** P2
**Depends on:** None

## P2 — Design system follow-ups (audit of 2026-11-12)

### Korean text renders in the system font, not Gothic A1

PR #420 measured it on both platforms: `GallrTypography` puts Inter and Gothic A1 in one `FontFamily`, Inter wins every
weight, and Hangul falls through to Apple SD Gothic Neo (iOS) or Noto Sans CJK KR (Android). The 2.29 MB
`GothicA1_Medium.ttf` ships and is never drawn. Owner decision: delete the file and write the system fonts into
DESIGN.md, or give Korean its own family so Gothic A1 renders (it only has Medium, so Bold needs another file).

### Answer the open questions of PR #419

The ten owner questions in PR #419's description (DESIGN.md corrections, still open): the five extra orange buttons on
MY and the add screens; the List search field; the unselected chip border (`outline` or `outlineVariant`); event brand
colours; route browse stop covers; full-screen route map controls; section-tab indicator thickness (2dp or 3dp); the
three top-bar styles; the taste sheet's drag handle; the status dot size (6dp). Write the answers into DESIGN.md.

### Write up the audit's coverage gaps

`docs/design-audit/2026-11-12-design-audit.md` section C lists 23 surfaces and states that the app has and DESIGN.md
does not describe. PR #419 writes up the List tab (C1) and snackbars, dialogs, menus and sheets (C19–C22); the Map tab
chrome, events, gallery page, editors, profile, settings, MY chrome, small sheets, shared pieces, top bars (C2–C11)
and the loading, disabled, pressed, focus, dark-theme, large-text and touch-target states (C12–C18) remain. Each needs
its own read of the screen. Findings the approved fix list left out (D4 field borders, D5 fixed heights at large text,
D7 focus indicator, E7 weights) stay with the owner.

## P1 — Server functions not live in production

### Gallery alerts do not deliver in production

Reported on 2026-11-12 (not re-read for this entry): `gallery-alert-enrollment` is not deployed to production,
`GALLERY_ALERT_DELIVERY_ENABLED` is not set for `outbox-delivery`, and the APNs and FCM credentials
(`GALLERY_ALERT_APNS_*`, `GALLERY_ALERT_FCM_SERVICE_ACCOUNT_JSON`) are missing, so a visitor who turns on a gallery's
알림 is never alerted. Follow `supabase/functions/gallery-alert-enrollment/README.md` and
`supabase/functions/outbox-delivery/README.md` (staging rehearsal first), with the owner's authorisation for each
deploy and secret.

### `walking-route` and `mobile-analytics` are not deployed in production

Reported on 2026-11-12. `walking-route` matters only once routes return (`WALKING_ROUTE_ENABLED` also needs the
provider's written terms, see its README); `mobile-analytics` stays disabled by design until the rollout at the top
of this file. Deploy each only under its own README and an owner decision; compare `supabase functions list` with
the repository first.

## P2 — CI does not run on `develop`

Every workflow that tests code runs on `pull_request` only (`product-surfaces.yml`, `database-tests.yml`,
`codex-pr-review.yml`); nothing runs on a push to `develop`. Two green pull requests can merge into a `develop` that
fails, and nobody sees it until the next pull request (PR #423 ran the combined gate by hand). Add a `push` trigger
for `develop` to `product-surfaces.yml` and `database-tests.yml`, or merge through a queue.

## P1 — 내 취향 that listens (spec 101, release 1.15)

Stage 1 of `specs/101-taste-listening` is built on `101-taste-listening` (PR #378 into `develop`): the taste
pick sheet, personal-only 내 취향, chips that open the sheet, 덜 보기, the reason on the exhibition page and the
loading skeletons. Picks and dismissals stay on the device. What remains before 1.15 is the data and the gate.

### Tag every running exhibition

**What:** The owner tags about ten exhibitions with the desk `tag-exhibition` drafts, then fixes the final
vocabulary list (spec 101 T045); the vocabulary migration is applied; then every running and upcoming exhibition
gets three or more reviewed terms and its artist credits through Admin (T050).

**Why:** On 2026-10-02, 74 of 78 exhibitions had no reviewed term and 73 had no artist. Without terms the pick
sheet offers covers only, reasons fall back to shared wording, and 덜 보기 cannot learn which subjects to show
less of (it records only reviewed term ids and credited artists).

**Progress:** 0 of the running catalogue tagged by the desk tool (the tool itself is T043, T048 and T049, not yet built).
Coverage is counted by `scripts/catalogue-coverage` once it exists (T069); until then the owner runs the count.

**Effort:** M for the tool (CC: ~2 hours); about three hours of owner time for the pass (research R12)
**Priority:** P1
**Depends on:** the owner's term list; owner authorisation to apply the vocabulary migration in production

### Re-check a sample of tagged terms

**What:** A second person re-judges the terms of at least one in five tagged exhibitions against their supporting
sentences, without seeing the first answers; the agreement goes in the 1.15 release PR (spec 101 US6, T072). The
owner-labelled scenario set it replaces was withdrawn on 2026-11-04.

**Why:** Judging the ranking against the terms it uses only proves the code follows them; the terms themselves need
an independent check, and a sentence can be judged without knowing the show.

**Effort:** S; about half an hour of a second person's time per twenty exhibitions
**Priority:** P1
**Depends on:** the tagging pass

### Catalogue reliability (spec 101 US5)

**What:** Provenance on every term and credit, stale flags when an exhibition's text or dates change,
duplicate-artist prevention with a merge tool, confirmed weekly hours, and the coverage check in the release gate
(T051–T069).

**Why:** The owner asked for data that can be trusted; a reason built on a stale term or a duplicate artist is
wrong in front of the reader.

**Effort:** L (CC: ~1 day)
**Priority:** P1
**Depends on:** owner authorisation for each migration on staging and production (T068)

### Decide whether the note check allows the formal imperative

**What:** The 101 ship review follow-ups are fixed (branch `101-review-followups`). One question stays with the owner:
the desk note check refuses the formal imperative (-십시오, -ㅂ시다) along with the plain -다 and 해요체 endings.
The house prompt asks for gentle suggestions (~보면 좋습니다, ~를 권합니다), so the refusal matches it today.

**Why:** Allowing the formal imperative is an editorial choice, not a bug.

**Priority:** P3
**Depends on:** the owner's decision

### Stage 2 voice: build only if observation asks for it

**What:** A generated personal line on supported devices (Apple Foundation Models, ML Kit GenAI) behind a
`TasteNarrator` interface with the template as fallback, in its own specification.

**Rule:** After 1.15 is live, watch three visitors use 내 취향 (the design's Assignment, repeated). Write the
Stage 2 spec only if they still do not feel the picks know them. If they do, the design is complete without it.
Either way: free, on the device, and no "AI" wording anywhere a reader sees.

**Priority:** P3 until the observation
**Depends on:** 1.15 in the stores and the three-visitor observation

## P1 — Post-Launch

### Push Notifications
Weekly "N new exhibitions near you" push via FCM (Android) + APNs (iOS). Primary retention mechanism. Needs a reviewed server-side scheduler and delivery worker; do not revive the retired Apps Script pipeline. Depends on basic analytics being in place.
- Effort: M (human) → S (CC: ~1 day)
- Context: Design doc identifies retention as key initiative. Without a trigger, users forget to open the app.
- Gate: Stage the aggregate analytics rollout above before designing the
  notification scheduler so delivery can be measured without introducing a
  second identity or event pipeline.

### Close My Gallr physical-device validation

Automated, simulator, disposable Auth/Data API, and hosted-branch isolation
evidence is complete. The remaining release evidence is a signed-in physical-
device account-isolation pass plus hands-on VoiceOver gesture and spoken-pacing
validation.

- Source: `specs/060-my-gallr-guest-archive/tasks.md` T022 and
  `specs/064-my-gallr-account-sync/tasks.md` T008.
- Do not mark these complete from simulator or accessibility-tree inspection
  alone; the remaining checks explicitly require a physical device and human
  listening/interaction.

### Decide whether the neighbourhood route planner stays

**What:** Either give the spec 073 neighbourhood route planner (`AppDestination.RoutePlanner`, `RoutePlannerScreen`, the route sheet) a new entry point, or retire it and its planner-only code. 추천 동선 already has its home again: it follows 내 동선 on the MY tab since 2026-10-11.

**Why:** The Map tab's 동선 만들기 button was removed on 2026-10-11 (owner request, PR #317); it was the planner's only entry point, so the planner is intact but unreachable from the UI. Personal and public routes do not depend on it.

**Context:** `composeApp/src/commonMain/kotlin/com/gallr/app/ui/route/RoutePlannerScreen.kt`, `AppNavigationState.showRoute`, `LocalDiscoveryViewModel.routeState`, DESIGN.md "Route map panel".

**Effort:** S (new entry) to M (retire)
**Priority:** P2
**Depends on:** owner decision

### Finish the home tab device pass with assistive tech

**What:** Done on 2026-10-11 with the live catalogue: Android 17 (AVD `Medium_Phone_API_37.0`, light and dark, AGSL shader active with no `grain_wash_shader_compile` warning; four polish fixes landed from it), Android 12L (AVD `gallr_api32`, gradient fallback renders, no crash), and iOS (iPhone 17 Pro, iOS 26 simulator through the `iOS simulator screenshots` workflow on a macOS runner; light and dark, Skia shader compiled with no warning). The 200% font-scale pass (hero wash now follows the caption, rail cards widen, tile subtitles wrap) and the accessibility tree check (one merged node per hero and rail card with counter, reason, title, venue and dates; labelled bookmark and 모두 보기 buttons) are also done on the Android 17 emulator. The collection list screen was opened from a tile on the emulator (title, subtitle, standard cards, Back returns to the home tab). Later on 2026-10-11: TalkBack's actual utterances were captured from its verbose log on the Android 17 emulator (the hero card reads "01 / 06, 이번 주 추천, title, venue, dates. In horizontal pager"; the back buttons that read "Leftwards arrow" were fixed in PR #326), and the event pager and event detail screen were exercised with a seeded live event against a clean local Supabase replay (which surfaced the missing `events`/`editors` reader grants fixed in PR #327). Still open and needing a person: VoiceOver on a device, and the continuous-reading pacing of TalkBack (a gesture walk cannot be injected). The reveal chip was exercised with two seeded live events on the local replay on 2026-10-11; it never appeared because its state was remembered before the events loaded, fixed in PR #329.

### Keep each tab's scroll position when returning from a detail screen

**What:** Wrap the tab content and the detail destinations in a `SaveableStateHolder` (`SaveableStateProvider` keyed by tab and destination) and switch the tabs' `rememberLazyListState()` to `rememberSaveable(saver = LazyListState.Saver)`, so coming back from an exhibition, a collection or a gallery lands where the visitor left.

**Why:** App.kt swaps destinations with a plain `when`, so the home tab (and the other tabs) recompose from scratch on return and scroll to the top; seen on the emulator after opening a collection. The old Featured tab behaved the same, but the home tab is now long enough for it to matter.

**Context:** `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt` (`when (destination)` and the tab `AnimatedContent`), `ui/tabs/home/HomeScreen.kt`, `ui/tabs/list/ListScreen.kt`.

**Effort:** S (CC: ~45 min)
**Priority:** P2
**Depends on:** None

**Context:** `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/home/`, `ui/graphics/GrainWash*.kt`; DESIGN.md "Home tab". The local AVD `Medium_Phone_API_37.0` and the debug APK with `local.properties` reproduce the Android pass.

**Effort:** S (~45 min with a Mac for the iOS part)
**Priority:** P1
**Depends on:** macOS for the iOS simulator

### Compose UI semantics tests for the route screens

**What:** Decide on and add a Compose UI test harness (Robolectric + compose-ui-test on the Android host, or the multiplatform ui-test), then write the spec 089 T126 semantics tests: one merged Button node per 내 동선 and 추천 동선 row, menu item labels, consent dialog focus, 200% font scale.

**Why:** The repo has no UI test harness, so the accessibility findings of the routes design review (merged rows, custom actions, focus return after the consent dialog) were checked only by reading code and the emulator tree.

**Context:** Deferred from plan: `specs/089-personal-routes/tasks.md` T126 (owner decision on 2026-10-10 to keep it open rather than add the harness inside the routes PR). Start in `gradle/libs.versions.toml` and `composeApp/src/androidHostTest`.

**Effort:** M (CC: ~3 hours)
**Priority:** P1
**Depends on:** owner decision on the harness

### Admin route review: show where a queue selection went and subordinate Revoke

**What:** Make a queue selection visible (scroll or focus to the preview, or a selected-row indicator; or show details beside the queue), and give Revoke less prominence than Approve while keeping its confirmation.

**Why:** With a long queue, opening a route appears to do nothing because the preview renders below the whole list, and the irreversible Revoke shares Approve's black-button weight in the same action group.

**Context:** Codex design voice during the ship review of 089 (2026-10-10), `admin/src/components/RouteModerationWorkspace.tsx`. Opening a reported route before Dismiss/Unlist was done in the routes PR (an Open action per report row).

**Effort:** S (CC: ~30 min)
**Priority:** P2
**Depends on:** None

## P2 — Home tab follow-ups

### Order and pick the themed collections by taste

**What:** When the For You basis is not empty, rank the neighbourhood and theme collections by overlap with the visitor's taste terms and visited districts (shared `tasteTerms`, `ExhibitionVisit`), and offer a "내 동네" collection from the district of their saves.

**Why:** v1 collections are the same for everyone (busiest districts, most specific recurring terms). The references the owner liked address the visitor personally; the feed builder already takes follows, so taste is the natural next input.

**Context:** `shared/src/commonMain/kotlin/com/gallr/shared/home/HomeFeedBuilder.kt` (`neighborhoods`, `themes`), `HomeViewModel`. Keep it deterministic and on-device.

**Effort:** M (CC: ~1.5 hours)
**Priority:** P2
**Depends on:** the home tab device pass

### Hero and rail impressions in the analytics dashboard

**What:** Add the `editor` and `gallery` discovery kinds that the home rails now report to the aggregate queries, and a hero position breakdown.

**Why:** The home tab attributes taps and impressions per section (hero → featured, For You → recommendation, editors' picks → editor, followed galleries → gallery), which the current aggregate views do not split out.

**Context:** `HomeSection.discoveryKind` in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/tabs/home/HomeScreen.kt`; the analytics dashboard item under P3.

**Effort:** S (CC: ~30 min)
**Priority:** P2
**Depends on:** mobile analytics rollout

### Fill in the visit terms, and decide on more of them

**What:** 무료 관람 and 아이·가족과 함께 exist as reviewed terms (spec 091 FR-022) but no exhibition carries them yet. Editors need a pass over the running exhibitions, and galleries can set them in their workspace. Further visit terms (accessibility, pets, parking) and a second, editor-written story format are undecided.

**Why:** The Home tab's 무료 관람 curation appears from two tagged exhibitions and 아이·가족과 함께 from three; until then both stay hidden. Nothing infers these from an exhibition's text (one of 52 open exhibitions stated free admission on 2026-10-12).

**Context:** Admin and gallery `ExhibitionArtMetadataEditor` (Visit information group); `VisitTag` in `shared/src/commonMain/kotlin/com/gallr/shared/data/model/ExhibitionArtMetadata.kt` (not the diary's
`com.gallr.shared.diary.VisitTag` from spec 103, which shares the name); `HOME_SITUATIONS` and `buildHomeFeed` in `shared/.../home/`. A new term is a new row in `content.art_taxonomy_terms` plus a `VisitTag` entry.

**Effort:** S for the editorial pass; S per new term (CC: ~20 min)
**Priority:** P2
**Depends on:** the 091 migrations applied after Admin and the gallery workspace are deployed

## P2 — Curator's notes follow-ups

### Let the desk replace a waiting draft whose exhibition changed

**What:** Let `store_curator_note_draft` replace an open draft when the payload carries `override` and a requester (or when the open draft is stale), marking the old one `replaced` as `request_curator_note_draft_impl` does. Or add a non-blocking decline reason for "the source changed".

**Why:** When an exhibition's text is edited while its draft waits, Approve is disabled, Regenerate is hidden while API drafting is off, and the desk queue leaves out exhibitions with an open draft. The only way forward is to decline the draft, which records an editorial reason that is not true and blocks automatic drafting until an override.

**Context:** `supabase/migrations/20261013100000_curator_notes_desk_drafts.sql` (the open-draft check runs before `v_override`), `supabase/functions/draft-curator-notes/README.md` (documents decline-then-override as the current path), pgTAP 059. Found in the pre-landing review of 2026-10-14.

**Effort:** S (CC: ~45 min)
**Priority:** P2
**Depends on:** nothing

### Refuse "Draft now" on an approved, unchanged exhibition before calling the provider

**What:** In the API path, check for an approved note with the same packet hash before `api.draft` and answer 409 instead of calling Claude and then failing the store with a 500.

**Why:** Only reachable with `VITE_ADMIN_NOTE_DRAFTING_ENABLED=true`, but it spends a provider call and shows a server error for an expected refusal.

**Context:** `supabase/functions/draft-curator-notes/handler.ts` (`override: requestKind === "regenerate"`, the `store_failed` mapping).

**Effort:** S (CC: ~30 min)
**Priority:** P3
**Depends on:** API drafting being switched on anywhere

## P1 — Routes (동선) return

### Show routes again with content and a composer that reads well

Owner decision 2026-10-20: routes are hidden in 1.14.0 behind `ReleaseFeatures.ROUTES`
(`composeApp/src/commonMain/kotlin/com/gallr/app/ReleaseFeatures.kt`) because no listed route
exists and the composer feels unfinished. Before flipping it back on: seed eight to ten listed
routes from the desk so the Home rail and 동선 둘러보기 have content on day one; run a UX pass over
the composer (adding stops, visit time, verdicts, sharing) with the owner naming the worst moments;
apply migrations `20261014120000` and `20261014130000`; then flip the switch, restore the route
entries in CHANGELOG and the store text, and verify on both platforms. Specs 089, 092, 093 and 096.

## P2 — Required app update follow-ups

### Google Play in-app immediate update

Spec 098 shipped the required-update notice with a store link on both platforms and an
`InAppUpdater` seam (`composeApp/src/commonMain/.../platform/InAppUpdater.kt`, default `None`).
Implement the Android side with Play Core's `AppUpdateManager` in immediate mode so a required
update installs without leaving the app, wire it in `MainActivity`, and verify it on a
Play-distributed internal-testing build, because the emulator and local APKs never see Play's
update flow. iOS keeps the App Store link; there is no equivalent API. Owner decision 2026-10-20
(specs/098-required-app-update/research.md R6).

## P2 — Routes follow-ups

### Debounce the route name draft writes

**What:** Keep the typed name in field state and persist it to the draft DataStore debounced (300–500 ms) and on focus loss, save, share and back.

**Why:** Every keystroke in the composer rewrites the whole app Preferences DataStore, wakes every `dataStore.data` collector and re-runs the route evaluation, which is visible as jank on long names.

**Context:** Ship performance review of 089 (2026-10-10): `RouteComposerScreen.kt` `onValueChange` → `PersonalRouteComposerViewModel.rename` → `DataStorePersonalRouteDraftRepository.rename`.

**Effort:** S (CC: ~30 min)
**Priority:** P2
**Depends on:** None

### Load the route lists lazily and once per save

**What:** Load 추천 동선 on the first route-sheet showing instead of in the ViewModel's `init`; let the composer take its listing row from 내 동선's loaded list so one save causes one `list_my_personal_routes` read instead of three; ignore the `isSaved` true→false transition.

**Why:** With routes shown, every cold start runs the heaviest public query before 추천 동선 is on screen, and each save triggers three list reads. While `ReleaseFeatures.ROUTES` is false neither list is read (2026-11-11); do this before flipping it back.

**Context:** Ship performance review of 089 (2026-10-10): `App.kt` ViewModel creation, `PublicRoutesViewModel.init`, `PersonalRouteComposerViewModel.refreshListing` and the `ListingReadKey` collector, `MyRoutesViewModel`'s saved-revision collector.

**Effort:** M (CC: ~1 hour)
**Priority:** P2
**Depends on:** None

### Keep route evaluation out of the App root and memoise the catalogue map

**What:** Expose only the booleans the App root needs (`shareReady`, replace-confirm, route count, one-off requests) as distinct flows or move their effects into a child composable; derive the `exhibitionsById` map once per catalogue and memoise `parseOpeningHours` by hours string; `remember` the picker's filter and map and the MapLibre GeoJSON sources; keep the route map outside the lazy list so it is not recreated on scroll-back.

**Why:** Every composer state emission re-executes the ~1,100-line App root content, rebuilds a map of the whole catalogue and re-parses each stop's hours; the native map is torn down and rebuilt when the list scrolls past it.

**Context:** Ship performance review of 089 (2026-10-10): `App.kt` collectAsState at the root, `PersonalRouteComposerViewModel`/`PublicRoutesViewModel` `associateBy`, `RouteEvaluator.evaluate`, `RoutePickerSheet.kt`, `RouteComposerScreen.kt` map item, `RouteMap.kt`.

**Effort:** M (CC: ~3 hours)
**Priority:** P2
**Depends on:** None

### Measure list_public_routes before 추천 동선 is promoted

**What:** Run `EXPLAIN (ANALYZE, BUFFERS)` on `list_public_routes` with a few thousand seeded routes; if needed, compute eligibility set-based in the query (join stops and the catalogue once, GROUP BY route) instead of calling `route_first_shared_day` per approved route, and aggregate the 30-day copies with one grouped join.

**Why:** The function ranks every approved route on every call before applying LIMIT 10, is granted to anon and runs at every cold start and sheet opening.

**Context:** Ship performance and data-migration reviews of 089 (2026-10-10), `supabase/migrations/20261010150000_public_route_ranking.sql`. The partial indexes on `listing_state` were added in the routes PR; `scripts/sample-routes` can seed data.

**Effort:** S (CC: ~45 min)
**Priority:** P2
**Depends on:** the routes migrations applied to staging

### Close the route test gaps named by the ship review

**What:** Make `FakePersonalRouteDraftRepository` follow `DataStorePersonalRouteDraftRepository`'s contract (replace/clear/detach/undo/move semantics) or run the ViewModel suites on the real repository over an in-memory DataStore; add cases for the own-route copy guard, the stale `resumeCopy` branches (expired, edited draft, other route, no preview), the listing in-flight guard and failed-withdraw retry, the web handler's 405/400/event-failure paths, `loadMine` with an empty result, and make `RetainedResourceTest` exercise `MainActivity`'s wiring.

**Why:** These paths are reachable by users but have no test, and the draft fake's drift means the route ViewModel suites verify behaviour production does not have.

**Context:** Ship testing specialist on 089 (2026-10-10); each item names its file. Several listing and report branches were covered in the routes PR itself; the second-pass follow-up added the Admin stale re-approval, the Unlist button for withdrawn routes, the `route-data.js` catalogue read and the read timeout.

**Effort:** M (CC: ~3 hours)
**Priority:** P2
**Depends on:** None

### Purge the route page cache when a route is revoked

**What:** Purge the Vercel cache for `/route/{id}` (or shorten `s-maxage`) when staff revoke a route, and send the page language in the share link (`?lang=`) so the CDN key carries it.

**Why:** A revoked route stays publicly served for up to about two minutes after the takedown, and the first reader's language is served to the other language's readers for the cache window (`Vary: Accept-Language` added in the routes PR mitigates the second).

**Context:** Ship security and api-contract reviews of 089 (2026-10-10), `web/api/_lib/route-handler.js` `CACHE_PAGE`, `RouteShareOrchestrator` link format.

**Effort:** S (CC: ~30 min)
**Priority:** P2
**Depends on:** None

## P3 — Technical Debt

### Full Analytics Dashboard
Turn the aggregate counters from `072-mobile-product-analytics` into a useful
operator dashboard for discovery, recommendation, route, and intent rates.

- Effort: M (CC: ~1 day after the analytics stack is integrated and staged).
- Start with the planned Supabase SQL views/queries. Evaluate an external
  dashboard only after the first-party aggregates and privacy boundaries are
  proven insufficient.
- Do not report unique users, sessions, cross-visit funnels, or retention: the
  aggregate-only event model intentionally has no stable person/device identity.

### Route-shaped link preview image for shared personal routes

**What:** Render a route-shaped Open Graph PNG (numbered stops joined by a line, plus the route name) for each shared route page, cached by route id and revision.

**Why:** Personal routes v1 previews use the first still-published stop's cover, so a shared route reads like a single exhibition in KakaoTalk and iMessage.

**Context:** Deferred by the personal routes engineering review (D20, 2026-10-08; design doc `docs/designs/2026-10-07-personal-routes-design.md`, Open Question 2). Link scrapers do not render SVG. Start from the route page's inline SVG in its Vercel Function. Build only if the recipient-open rate (shared routes opened within 7 days) is low after launch.

**Effort:** M (CC: ~1 hour)
**Priority:** P3
**Depends on:** Personal routes v1 shipped and its recipient-open numbers.

### Abuse limits on personal route writes and page counts

**What:** Add a per-account cap on saved personal routes (for example 100) and a per-route daily ceiling on route page-count increments.

**Why:** v1 has no limit on routes per account, and the page-count database function is callable by anyone holding the publishable key, so spam routes or inflated opens could skew the recipient-open metric.

**Context:** Deferred by the personal routes engineering review (D21, 2026-10-08; design doc `docs/designs/2026-10-07-personal-routes-design.md`, decisions D5 and D14). Page counts are labelled unauthenticated traffic. Start in `save_personal_route` and `record_route_page_event`, with pgTAP cases for each limit.

**Effort:** S (CC: ~20 min)
**Priority:** P3
**Depends on:** v1 traffic showing spam routes or skewed page counts.

### Guard public route ranking against throwaway accounts

**What:** Count or flag saves from very new accounts in the 090 public route ranking.

**Why:** Saves rank the public list and any signed-in account counts once, so one person with throwaway accounts could promote a route.

**Context:** Raised by the public routes engineering review (Section 1 #4, D21, 2026-10-08; design doc `docs/designs/2026-10-08-public-routes-design.md` §Ranking). Pre-approval and launch volume make it unlikely early. Start in `list_public_routes_impl` and `route_saves`, with pgTAP cases.

**Effort:** S (CC: ~45 min)
**Priority:** P3
**Depends on:** 090 launched and saves concentrated on a few new accounts.

### Full public route list with keyset paging

**What:** Add a '모두 보기' screen listing every eligible public route with stable keyset paging over the ranking.

**Why:** 090 shows only the top 10 inline, so routes ranked 11 and lower are unreachable in the app.

**Context:** Deferred by the public routes engineering review (D1, D22, 2026-10-08). Start from `list_public_routes(p_limit)`. Offset paging skips or repeats rows as saves change the order (office-hours reviewer R2-8), so page by (save count, approval time, id).

**Effort:** M (CC: ~1-2 hours)
**Priority:** P3
**Depends on:** more than about 30 eligible listed routes.

### Weekday-hours check for listed routes

**What:** Require at least one shared date on which every stop's parsed opening hours are open, treating unknown hours as open.

**Why:** The 090 shared date window (D15) can still list a route whose only common days are a gallery's closing days.

**Context:** Deferred by the public routes engineering review (D23, 2026-10-08). The 089 opening-hours parser and its JS port (`web/api/_lib/opening-hours.js`) are the starting point; hours text is often partial.

**Effort:** M (CC: ~3 hours)
**Priority:** P4
**Depends on:** listed routes found unwalkable because of weekday closures.

### Cap route screen width on tablets

**What:** One maximum content width (for example 600dp, centred) for the Map route sheet, the 089 composer and the 090 public route preview.

**Why:** No route screen has a maximum width today, so on tablets stop rows and bottom-bar buttons run edge to edge and text lines get very long.

**Pros:** One DESIGN.md token fixes all three screens at once.

**Cons:** Needs a DESIGN.md decision on the value and a tablet visual pass on Android and iPad.

**Context:** Found by the public routes design review (D28, D30, 2026-10-08). 089 and 090 were designed phone-first and DESIGN.md has no tablet layout rules; 090 keeps the existing full-width behaviour.

**Effort:** S (CC: ~30 minutes plus a device pass)
**Priority:** P4
**Depends on:** best after 090 lands so the preview is included.

### Simplification pass on the routes code

**What:** `MyRoutesViewModel.openOwn` should take a route id instead of fabricating a placeholder summary; merge `PersonalRouteApiClient.publicRpc`/`rpc` into one call with a `requireAuth` flag and reuse `idBody`; reuse `web/scripts/lib/site-date.js` `seoulDateIso` in `route-verdict.js`; route Admin `revoke`/`decide` through `callRoute`; drop the unused `listPublic(limit)` parameter; share `clockLabel`, `AppLanguage.pick` and the English month table between the route presentation files; make the composer's `routeRepository` non-null; consider `node:util` `parseArgs` in `scripts/sample-routes`.

**Why:** About 60 lines of duplicated or speculative structure flagged as advisory by the ship simplification specialist; none is a defect.

**Context:** Ship review of 089 (2026-10-10); each item names its file and line in the review record.

**Effort:** S (CC: ~45 min)
**Priority:** P3
**Depends on:** None

### Route page: keep district labels inside the diagram, and sentence-case the composer notes

**What:** Anchor district labels inward near the right edge of the route SVG (`web/api/_lib/route-page.js` `renderDrawing`), and use one casing for the composer's message line (sentence case for explanatory notes such as the copied note and the public note, per DESIGN.md "uppercase labels, sentence-case explanations").

**Why:** Long district names clip for eastern stops; the composer mixes an uppercase sentence with a sentence-case warning in the same slot.

**Context:** Codex design voice and the design specialist during the ship review of 089 (2026-10-10).

**Effort:** S (CC: ~20 min)
**Priority:** P3
**Depends on:** None

## Deferred Product Inputs — Not Work-Ready

- Reconsider the square `G` mark only in a deliberate product-wide brand
  project, not as an isolated flow tweak (`design-qa.md`).
- Add gallery logos only after the canonical catalogue owns verified logo
  assets; do not synthesize monograms (`design-qa.md`).
- Routine dependency updates remain owned by Dependabot PRs and are not product
  roadmap items.
