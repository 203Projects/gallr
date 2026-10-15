# Tasks: Home style across the app

**Input**: Design documents from `/specs/095-app-visual-system/`
**Prerequisites**: plan.md, spec.md, research.md (R1–R9), data-model.md, contracts/shape-categories.md, quickstart.md

**Tests**: Required (constitution, Test-First). The shape guard and token tests are written first and fail against
today's code; the restyle tasks make them pass. Visual results are checked by the quickstart's screenshot pass.

**Organization**: by user story. US1 (restyle) and US2 (DESIGN.md) are both P1 and ship together (owner decision 3:
doc and app agree when it ships). US3 (map labels) is already implemented on the branch.

Paths: `app/` = `composeApp/src/commonMain/kotlin/com/gallr/app/`, `hostTest/` =
`composeApp/src/androidHostTest/kotlin/com/gallr/app/`, `appTest/` = `composeApp/src/commonTest/kotlin/com/gallr/app/`.
"Token" means `GallrShapeTokens.card` (16dp) or `.small` (12dp) per contracts/shape-categories.md.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup

- [ ] T001 Record the baseline: `./gradlew composeApp:testAndroidHostTest` count on this branch, and screenshots of the
  목록 tab, an exhibition page and the route composer in light and dark on the emulator, for before/after comparison
  (no code change).

---

## Phase 2: Foundational (blocks US1 and US2)

- [ ] T002 [P] Write `appTest/ui/theme/GallrShapeTokensTest.kt` (fails first): `cardRadius` is 16dp and `smallRadius`
  12dp; `HomeVisualTokens.cornerRadius` / `innerRadius` equal them; `gallrShapes()` maps `extraSmall`, `small`,
  `medium` to 12dp and `large`, `extraLarge` to 16dp.
- [ ] T003 [P] Replace `hostTest/ui/theme/HomeOnlyShapesTest.kt` with `hostTest/ui/theme/AppShapesTest.kt` (fails
  first against today's 152 sites), a source scan of `app/`:
  - no `RectangleShape` or `RoundedCornerShape(0.dp)` outside an allow-list naming the square-by-design files and
    elements of contracts/shape-categories.md (screen-edge pictures and bands, the tab bar, map pins / label chips /
    credits, the share preview frame, `GalleryAvatar`);
  - no `RoundedCornerShape(<literal>.dp)` outside `ui/theme/` (radii come from tokens), except the existing profile
    skeleton if it stays;
  - each allow-list entry carries a one-line reason.
- [ ] T004 Add `app/ui/theme/GallrShapeTokens.kt` (`cardRadius`, `smallRadius`, `card`, `small`, `sheetTop`), make
  `GallrTheme`'s shapes come from it (expose `gallrShapes()` for T002), and point `HomeVisualTokens.cornerRadius` /
  `innerRadius` at it. Passes T002.
- [ ] T005 [P] Move `app/ui/tabs/home/HomeScrim.kt` to `app/ui/theme/CaptionScrim.kt` (same `internal` modifiers
  `bottomScrim`, `topScrim`, `flatScrim`); update Home's imports. Home renders identically.
- [ ] T006 [P] Move `app/ui/tabs/home/SectionHeader.kt` to `app/ui/components/SectionHeader.kt`; update Home's imports.

**Checkpoint**: T002 passes; T003 fails only on the sites US1 converts; Home unchanged on the emulator.

---

## Phase 3: User Story 1 - One look from Home to every screen (Priority: P1) 🎯 MVP

**Goal**: every card, small piece, sheet, dialog, menu and snackbar outside Home takes its token; captions on pictures
use Home's scrim; headings use Home's header.

**Independent Test**: quickstart §2 steps 1–10.

Each task below converts its files per contracts/shape-categories.md: replace `RectangleShape` with the category's
token (`MaterialTheme.shapes.small` / `.large` / `.extraLarge`, `GallrShapeTokens.sheetTop` for sheets), give
unshaped TextButtons `MaterialTheme.shapes.small`, add a `.clip(token)` to every bordered card so its picture follows
the corner, and round small thumbnails with `small`. Text, order and behaviour do not change (FR-010).

- [ ] T007 [P] [US1] Shared components in `app/ui/components/`: `ExhibitionCard` (card shape and clip; caption: white
  text on `topScrim` in both themes, deepening to the pressed alpha, replacing the white / black wash; cards without a
  cover keep the invert), `HeroExhibitionCard`, `EventPromotionCard` (card shape; caption on `bottomScrim` instead of
  the 0 → 0.6 gradient), `GallrEmptyState`, `CatalogUnavailableState`, `GallrErrorMessage`, `ExhibitionCurationBadges`,
  `SkeletonCard`. `GalleryAvatar` stays square (allow-listed).
- [ ] T008 [P] [US1] List tab: `app/ui/tabs/list/ListScreen.kt`, `ListScreenComponents.kt` (filter chips, promoted
  band, 104×136 thumbnail, buttons, menu), `ExhibitionSearchField.kt`.
- [ ] T009 [P] [US1] Map tab: `app/ui/tabs/map/MapComponents.kt` (scope panel, browse button, button),
  `SeoulDistrictMap.kt` (zoom and recentre controls, overlapping-exhibitions sheet). Map pins, label chips and credits
  stay square (allow-listed).
- [ ] T010 [P] [US1] Exhibition page: `app/ui/detail/ExhibitionDetailScreen.kt` (buttons, snackbar),
  `AddToRouteButton.kt`, `ThoughtsSection.kt`, `ThoughtComposer.kt`, `CuratorNoteBlock.kt` (fact chips and the 88dp header
  thumbnail `small`; the note pictures sit inside the page margins, so `card`),
  `ExhibitionGallery.kt` (pager stays square at the screen edge; the `2 / 5` counter becomes a 12dp pill on the 0.55
  scrim), `SharePreviewScreen.kt` (buttons; the preview frame stays square).
- [ ] T011 [P] [US1] Gallery, event, discovery, editor: `app/ui/gallery/GalleryDetailScreen.kt` (panels with clip,
  cover placeholder, 72×52 thumbnail, buttons, the alert panel as `sheetTop`), `app/ui/event/EventDetailScreen.kt`
  (hero stays square at the edge with its caption on `bottomScrim`; venue chip `small`; its `SectionLabel` replaced in
  T015), `app/ui/discovery/RecommendationsScreen.kt` (taste-tag chips, button), `app/ui/editor/EditorBanner.kt`.
- [ ] T012 [P] [US1] MY and profile: `app/ui/mygallr/MyGallrScreen.kt` (invitation card, visit card with clip, followed
  gallery row, buttons, dialogs, thumbnails), `AddPastVisitsScreen.kt`, `AddGalleriesScreen.kt` (fields, buttons,
  checkbox boxes, 64dp thumbnails), `app/ui/profile/ProfileScreen.kt` (`DiaryCard` with clip, button),
  `EditProfileScreen.kt`, `PendingThoughtsScreen.kt`, `SignInScreen.kt`, `CropScreen.kt`, `ProfileTab.kt` text button,
  `app/ui/settings/SettingsScreen.kt` (buttons, dialogs).
- [ ] T013 [P] [US1] Routes: `app/ui/route/RoutePlannerScreen.kt` (summary panel, stop cards, toggle, buttons),
  `RouteMap.kt` and the composer's map panel border (`card` with clip), `RouteMapFullScreen.kt` (control buttons
  `small`; credit strip stays square), `app/ui/route/composer/` (`ReorderableStopList.kt` dragged row, menu;
  `RoutePickerSheet.kt` sheet, field, buttons; `MyRoutesSection.kt` buttons, dialog, snackbar, menu, skeletons;
  `RouteComposerScreen.kt` name field, buttons, snackbar, empty map placeholder; `ListingConsentDialog.kt`;
  `ReplaceDraftDialog.kt`), `app/ui/route/publicroutes/` (`PublicRoutePreviewScreen.kt`, `PublicRoutesSection.kt`,
  `RouteReportSheet.kt`).
- [ ] T014 [P] [US1] `app/AppMenus.kt` (sign-up nudge sheet, menu, buttons) and
  `app/notifications/NotificationPermissionHandler.kt` (dialog takes `extraLarge` from the theme; text buttons
  `small`).
- [ ] T015 [US1] Headings to `SectionHeader` (research R5): `ProfileScreen` 전시 일기, `ThoughtsSection`, `ListScreen`
  galleries / exhibitions, `ListScreenComponents` promoted band, `RecommendationsScreen`, `MyRoutesSection`
  `PlannerHeader` (action keeps its text button), `PublicRoutesSection`, `RoutePlannerScreen` sections,
  `GalleryDetailScreen` 현재 전시, `MapComponents` labels, `MyGallrScreen` account, `ExhibitionDetailScreen` MY GALLR,
  `EventDetailScreen`'s `SectionLabel`, `SettingsScreen` section labels. Strings unchanged. Depends on T006.
- [ ] T016 [US1] Run `AppShapesTest` and `GallrShapeTokensTest`; convert any site they still report; confirm every
  allow-list entry is a documented exception.

**Checkpoint**: the shape guard passes; quickstart §2 on the Android emulator in light and dark.

---

## Phase 4: User Story 2 - DESIGN.md describes the app as it is (Priority: P1)

**Independent Test**: read `DESIGN.md` top to bottom against the running app (SC-004).

- [ ] T017 [P] [US2] Replace `hostTest/ui/theme/HomeTokensDocumentationTest.kt` with
  `hostTest/ui/theme/ShapeTokensDocumentationTest.kt` (fails first): DESIGN.md's Visual system section states 16dp,
  12dp and the 0.55 / 0.70 scrim, and no longer contains "0dp on everything" or "no rounded corners".
- [ ] T018 [US2] Rewrite `DESIGN.md` per research R7: Aesthetic Direction; Layout (border radius → Visual system
  section with the two tokens, the categories table, the scrim rules and the square exceptions); Buttons; Text Fields;
  Cards; Hero card; Home tab (the visual system text moves up, Home-only wording removed, composition rules stay);
  exhibition gallery counter; Navigation unchanged; Color untouched; decisions-log row for 2026-10-15. Passes T017.
- [ ] T019 [P] [US2] Update `CLAUDE.md` ("Read first": shapes come from `GallrShapeTokens`, 16dp / 12dp, square
  exceptions; avatars) and the root `AGENTS.md` line that states 0dp corners.

---

## Phase 5: User Story 3 - The map speaks one language (Priority: P2)

- [X] T020 [US3] `app/ui/tabs/map/MapStyleLanguage.kt` and `appTest/ui/tabs/map/MapStyleLanguageTest.kt` (4 tests),
  wired into the Map tab and the route maps (commit `c6a8e605`).
- [ ] T021 [US3] On the emulator switch the app to English and confirm the Map tab and a route map show English names
  (quickstart §2 step 2).

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T022 Run the gates: `./gradlew composeApp:ktlintCheck composeApp:allTests` and
  `./gradlew androidApp:ktlintCheck composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug`.
- [ ] T023 Quickstart §2 on the Android emulator and the iOS simulator (light, dark, largest font, exports unchanged);
  record results here.
- [ ] T024 Before/after screenshots of the 목록 tab, an exhibition page and the route composer (from T001) for the
  owner, with the light-mode exhibition card called out (plan Risks).

---

## Dependencies & Execution Order

- Setup → Foundational (T002–T006) → US1 (T007–T016) and US2 (T017–T019) → US3 check (T021) → Polish (T022–T024).
- T004 before any US1 task (tokens must exist); T005 before T007 and T011 (scrims); T006 before T015 (header).
- US2 can run beside US1 once T004 exists; T018 describes the result of US1, so review it after T016.

### Parallel opportunities

- T002 ∥ T003; T005 ∥ T006.
- T007–T014 touch disjoint files and can run in parallel; T015 touches files from several of them and runs after.
- T017 ∥ T019.

## Parallel Example: User Story 1

```text
Task: "T008 [US1] List tab shapes in app/ui/tabs/list/"
Task: "T009 [US1] Map tab shapes in app/ui/tabs/map/"
Task: "T012 [US1] MY and profile shapes in app/ui/mygallr/ and app/ui/profile/"
```

## Implementation Strategy

1. Foundational: tokens, theme, the failing shape guard, shared scrim and header.
2. US1 area by area until the guard passes; emulator check in light and dark.
3. US2: DESIGN.md, CLAUDE.md and AGENTS.md describe what US1 built.
4. Polish: gates, both platforms, screenshots for the owner. US1 + US2 is the releasable unit.

## Notes

- Commit after each checkpoint.
- Exports (share renderers in `androidMain` / `iosMain`) are not touched.
