# Research: Home style across the app

Grounded in an inventory of the code on this branch (2026-10-15): 152 explicit shape sites in 45 files outside
`ui/tabs/home/`, almost all `RectangleShape` passed by hand. The theme's `GallrShapes` are all 0dp, but only one
component (`NotificationPermissionHandler`'s dialog) inherits them. `HomeOnlyShapesTest` fails the build if any rounded
shape appears outside Home.

## R1. One set of app-wide shape tokens, Home's values unchanged

**Decision**: Add `GallrShapeTokens` in `ui/theme/` with the values Home already uses: `card` 16dp (cards, tiles,
framed panels, sheets' top corners, dialogs) and `small` 12dp (chips, pills, counters, small thumbnails, buttons,
fields, menus, snackbars, map controls). `HomeVisualTokens.cornerRadius` and `innerRadius` read from them, so Home
does not change and one value is the source for both (FR-001, FR-002, spec Assumptions).

**Rationale**: The owner chose Home's look as the app's; copying its numbers into a second place would let them
drift. Scrim values stay in `HomeVisualTokens` and move with the scrim modifiers (R4).

**Alternatives considered**: Renaming `HomeVisualTokens` to an app-wide name (churns every Home file for no visible
change); a third radius for sheets (Home has none to copy).

## R2. Material theme shapes follow the tokens; explicit sites name a token

**Decision**: Set `GallrShapes` to `extraSmall` and `small` = 12dp, `medium` = 12dp, `large` and `extraLarge` = 16dp.
Then replace each hand-passed `RectangleShape` by its category's token through `MaterialTheme.shapes` (`small` for
small pieces, `large` for cards, `extraLarge` for dialogs, a top-only 16dp shape for bottom sheets). TextButtons that
pass no shape get `small` so their press ripple matches.

**Rationale**: Naming the category at each site keeps the intent readable and lets the shape guard (R6) check it.
Changing only the theme would round one dialog.

## R3. What changes shape, and what does not (contracts/shape-categories.md)

| Category (inventory count) | Shape after |
|---|---|
| Card / tile / framed panel (18, plus the route map panel) | 16dp, picture clipped to it |
| Small piece: button, field, chip, badge, toggle, checkbox box, counter (88, plus 24 unshaped TextButtons) | 12dp |
| Bottom sheet (4 + the gallery alert panel) | 16dp top corners |
| Dialog (7) | 16dp |
| Dropdown menu (5), snackbar (4) | 12dp |
| Map controls: zoom, recentre, full-screen close / fit / expand | 12dp |
| Small thumbnails inside a card or row (list, gallery, visits, curator note header) | 12dp |
| Screen-edge pictures and bars: exhibition gallery pager, event hero, event list band, tab bar | unchanged (square) |
| Avatars: profile circles, `GalleryAvatar` square | unchanged (FR-007) |
| Map pins, map label chips, map credits | unchanged |
| Exports: share-card renderers on both platforms and the share preview frame | unchanged (FR-007) |
| Skeleton bars | 12dp, so loading matches the loaded piece |

Cards that have a border but no clip (`DiaryCard`, `VisitCard`, the gallery panels) get a clip with the same shape,
or their covers would poke past the rounded corners.

## R4. Captions on pictures: Home's scrim, white text, everywhere

**Decision**: Move `HomeScrim.kt`'s modifiers (`bottomScrim`, `topScrim`, `flatScrim`) from `ui/tabs/home/` to
`ui/theme/` as `internal` and use them for every caption on a picture:
- `ExhibitionCard`: today a flat wash over the whole cover, white at 0.50 under dark text in light mode and black at
  0.45 in dark mode. After: its text stays at the top (layout unchanged, FR-010), white on Home's top scrim band in
  both themes, deepening to `SCRIM_PRESSED_ALPHA` when pressed. Cards without a picture keep today's invert.
- `EventPromotionCard` and the event page hero: their 0 → 0.6 gradient becomes Home's bottom scrim band.
- The exhibition gallery's `2 / 5` counter: black 0.60 box with 0dp → Home's counter pill (black 0.55, 12dp).

**Rationale**: FR-004 asks for one caption treatment with 4.5:1 contrast. Home's band is already tested for 4.5:1
over a white cover (`HomeVisualTokensTest`). The light-mode white wash is the one place a caption is dark on a
picture, and the owner chose Home's white-on-black.

## R5. Section headings use Home's header

**Decision**: Move `SectionHeader` from `ui/tabs/home/` to `ui/components/` and use it for the headings that now are
`labelLarge` / `labelMedium` text: profile 전시 일기, thoughts, the list's galleries and exhibitions, promoted near you,
recommendations, 내 동선 / 추천 동선 headers (the action keeps its text button), the planner, gallery 현재 전시, map
scope labels, My Gallr account, the event page's `SectionLabel`, and settings' section labels. The curator note's
label stays a label: it names the block above a headline, as on Home's story card.

**Rationale**: FR-005. Strings do not change (FR-010); Home already shows English headings in capitals
(`EDITOR'S PICKS`), so the existing English capitals fit.

## R6. Replace `HomeOnlyShapesTest` with an app-wide shape guard

**Decision**: Retire `HomeOnlyShapesTest` and add `AppShapesTest` (Android host, source scan like its predecessor):
- no `RectangleShape` or `RoundedCornerShape(0.dp)` in `commonMain` outside an allow-list that names the documented
  exceptions (screen-edge pictures, the tab bar's indicator, map pins / labels / credits, exports, `GalleryAvatar`);
- no literal corner radius outside `ui/theme/` (shapes come from tokens).
`HomeTokensDocumentationTest` moves to the app-wide tokens and DESIGN.md's new section.

**Rationale**: SC-001 needs a check that keeps working after this feature; the old test enforced the opposite rule.

## R7. Documentation that states the old rule

`DESIGN.md` lines that say 0dp or "no rounded corners" (Aesthetic Direction, Layout, Buttons, Text Fields, Cards, Hero
card, the Home visual system's "Home tab only" wording, the exhibition gallery counter, Gallery Avatar), plus
`CLAUDE.md` "Read first" and the root `AGENTS.md`. The Home visual system text becomes a top-level "Visual system"
section under Layout; Home keeps its composition rules. The decisions log keeps its historical 0dp rows (history) and
gains the 2026-10-15 row. Color is untouched (FR-006).

## R8. Map labels in the reader's language

Already built on this branch (`c6a8e605`, `MapStyleLanguage.kt`, 4 tests): the bundled style's name labels are
rewritten at load for the app language, with fallbacks (FR-009).

## R9. Verification

- Unit: token values, scrim contrast (existing), the shape guard, map labels (existing).
- Visual: a screenshot pass of every tab and the screens they open, in light and dark, at default and largest font,
  on the Android emulator and the iOS simulator (quickstart). No screenshot-diff tooling exists in the repo and none
  is added.
- Gates: `composeApp:ktlintCheck composeApp:allTests`, Android lint and debug build.
