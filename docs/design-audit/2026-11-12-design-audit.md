# DESIGN.md audit — 2026-11-12

Read-only audit of `DESIGN.md` as a document and against the app code on `origin/develop` (`42a1e3e4`; DESIGN.md and
`composeApp` are identical to `4d7d2552`, which the code reading used). Owner request 2026-11-11/12: "do a full audit
on design.md as required and look for improvements and show visually." The visual version is
`docs/design-audit/2026-11-12-design-audit.html` (open it in a browser; it follows the device theme, and `#dark` /
`#light` in the address force one).

Nothing in DESIGN.md or the code was changed by this audit. Every proposal below is a proposal: DESIGN.md changes need
the owner's approval. The three diary decisions of 2026-11-12 (calendar picker, NOTES, split headings) were applied
separately to PR #410.

Paths are relative to `composeApp/src/commonMain/kotlin/com/gallr/app/` unless they start with another top-level
folder. `D:n` is a DESIGN.md line on `origin/develop`.

## Summary

| Category | Findings | High | Medium | Low |
|---|---:|---:|---:|---:|
| A. Accuracy (document vs code) | 30 | 2 | 8 | 20 |
| B. Internal consistency | 13 | 1 | 7 | 5 |
| C. Coverage gaps (surfaces and states) | 23 | 1 | 11 | 11 |
| D. Accessibility of the system | 9 | 3 | 3 | 3 |
| E. Rule violations in code | 10 | 1 | 4 | 5 |
| **Total** | **85** | **8** | **33** | **44** |

Most of the document is right. These match the code exactly: every colour token, the type sizes, line heights and
tracking, the 16dp / 12dp radii, the scrim (0.55 / 0.70 / 30% lead / 44% start), Home's banner, rail (184dp), route
card (262dp), theme tile (156dp), editor's-eye card (54% cover, D-pill), dots (6 / 6 / 18dp), the curator's note
block, the taste pick sheet, 덜 보기, map pins (48dp, 28dp grouping, 52 × 56dp lattice), the route map panel
(220dp), reorder (150ms), the required-update screen and the update nudge.

What is wrong clusters in five places: the accent (its role table is incomplete and the code uses it in eight more
places), chips (two different chip specs and a contrast failure), the route sections (they describe surfaces hidden
since 1.14.0, some with 0dp shapes the system retired), whole screens that were never written down (List tab, Map tab
chrome, events, settings, profile), and states the document never states (loading, pressed, dialogs, sheets, large
text outside Home).

## The ten most important

1. **Selected filter chips fail contrast (D1, high).** `ui/components/GallrFilterChip.kt:41` draws the selected
   label in `background` on the accent: white on #FF5400 is 3.22:1 in light mode, below AA. DESIGN.md itself says
   white on the accent fails (D:211) and that a selected count chip uses black `ctaContent` (D:651–652). Affects the
   List tab filters (`ui/tabs/list/ListScreen.kt:293–386`), the visit-time chips (`ui/route/VisitTime.kt:192`), the
   route picker and route browse chips. Fix: `selectedLabelColor = GallrAccent.ctaContent` when the fill is the
   accent.
2. **The accent status label is unreadable on pictures (D2, high).** "Opening / closing" labels are accent text at
   12sp (`ui/components/ExhibitionDateRow.kt:40`, `ui/tabs/home/ExhibitionRail.kt:226`,
   `ui/detail/ExhibitionDetailScreen.kt:296`): 3.22:1 on white; on an image card's 0.55 scrim over a light picture,
   1.47:1. DESIGN.md requires it ("the accent status label keeps its colour", D:239–240). Owner decision proposed:
   the share card's own rule, an accent dot before monochrome text (D:87–88).
3. **The accent is used outside its three roles in eight live places (E1, high).** A full-width Settings row turns
   orange when pressed (`ui/settings/SettingsScreen.kt:574`); orange text on the gallery alerts button
   (`ui/gallery/GalleryDetailScreen.kt:271`) and a decorative bar on its sheet (`:403`); `NEW n` in orange
   (`ui/mygallr/MyGallrScreen.kt:788`); the 감상 counter turns orange near the limit
   (`ui/detail/ThoughtComposer.kt:96`); the reception-date label, a resting `→` and the contact link on the exhibition
   page (`ui/detail/ExhibitionDetailScreen.kt:310, :369, :418`). Four more sit in unused map code.
4. **The accent role table is not the real list (B1 + A1, high).** D:69–76 names three roles and says the primary
   CTA is used "ONLY in `GallrEmptyState`" (also D:211). The document itself uses the accent for the saved heart and
   status labels (D:239, :330, :363–365), and the code fills five more buttons with `ctaPrimary`
   (`ui/mygallr/MyGallrScreen.kt:349, :548, :736`, `ui/mygallr/AddGalleriesScreen.kt:171`,
   `ui/mygallr/AddPastVisitsScreen.kt:190`). Anyone checking a new use against the table gets the wrong answer.
5. **Korean may not render in Gothic A1 (A9, high, not verified on a device).** D:19 says Compose picks Gothic A1 for
   Hangul because Inter lacks the glyphs. `ui/theme/GallrTypography.kt:26–35` puts both in one `FontFamily` at the
   same weights; Compose picks one font per weight and style, and later fonts are a load-failure fallback, not a
   per-glyph fallback, so Hangul likely falls through to the system CJK font. Check one Korean screen on both
   platforms before changing anything.
6. **The password field's show/hide control has no label (D3, high).** D:716 requires "Show password" / "Hide
   password"; `ui/profile/SignInScreen.kt:176–184` is an `IconButton` holding only `◉` / `○`.
7. **Three dialogs render Material's tinted surface (E8, medium).** The colour scheme never sets
   `surfaceContainer*` (`ui/theme/GallrColors.kt:47–101`), so `AlertDialog`s that do not pass `containerColor`
   take Material's lavender default: `ui/mygallr/MyGallrScreen.kt:230, :263`,
   `notifications/NotificationPermissionHandler.kt:48`. Setting the `surfaceContainer*` slots once in
   `GallrColors.kt` fixes every present and future default.
8. **Two chip specs disagree (B2, B3, A2; medium, owner decision).** DESIGN.md says route browse chips have 0dp
   corners (D:610; code 12dp, `GallrFilterChip.kt:35`), selected filter chips take the accent (D:73) but the taste
   sheet's are `onBackground`, "never the accent" (D:409), and a count chip is unselected with an `outlineVariant`
   border and black selected text (D:651–653) while `GallrFilterChip` uses `outline` and white
   (`GallrFilterChip.kt:41, :49`). The diary adds a third use. One "Chips" pattern is needed.
9. **Route sections describe what readers cannot see (B8 + A4, medium).** `ReleaseFeatures.ROUTES = false`
   (`ReleaseFeatures.kt:14`) has hidden routes since 1.14.0; D:260, :318–325 and :468–623 read as live, "My routes"
   still sits "beside 방문 and 팔로잉" (D:537; `ui/mygallr/MyGallrScreen.kt:166–183`, and 방문 becomes 일기 in spec
   103), and its headings are `labelLarge` (D:542, :571) where the code uses `SectionHeader`.
10. **The List tab is not in the document (C1, high).** The second tab — tabs, collapsing filter rows, the filled
    search field (`ui/tabs/list/ExhibitionSearchField.kt:43–89`, which contradicts Text Fields), results, six empty
    messages and a paid promotion band (`ui/tabs/list/ListScreenComponents.kt:60–160`) — has no section.

## A. Accuracy — the document says one thing, the app does another (30)

| # | Sev | DESIGN.md | Code | Fix |
|---|---|---|---|---|
| A1 | High | D:72, :211 primary CTA "Used ONLY in `GallrEmptyState`" | Five more: `ui/mygallr/MyGallrScreen.kt:349, :548, :736`; `AddGalleriesScreen.kt:171`; `AddPastVisitsScreen.kt:190` | Owner |
| A2 | Med | D:610 route browse chips "0dp corners, 1dp `outline` border" | `ui/components/GallrFilterChip.kt:35` `GallrShapeTokens.small` (12dp) | Doc |
| A3 | Med | D:613 browse stop covers "0dp corners" | `ui/route/publicroutes/PublicRouteBrowseScreen.kt:364–369` square, no marker; D:179 says small thumbnails are 12dp | Owner |
| A4 | Med | D:537 routes "beside 방문 and 팔로잉" | `ui/mygallr/MyGallrScreen.kt:166–183` (hidden; 방문 → 일기 in spec 103) | Doc |
| A5 | Med | D:542, :571 `labelLarge` headings for 내 동선 / 추천 동선 | `SectionHeader` at `ui/route/publicroutes/PublicRoutesSection.kt:95`, `ui/route/composer/MyRoutesSection.kt:315` | Doc |
| A6 | Med | D:242 card: "Image fills top section, text below with 8dp" | `ui/components/ExhibitionCard.kt:221–254` picture behind text under the scrim, 16dp padding (D:239 agrees with code) | Doc |
| A7 | Med | D:280 banner heading `내 취향 / FOR YOU` | `ui/tabs/home/HomePresentation.kt:205, :230` `"<name> 님의 취향"`, `"PICKED FOR <name>"` | Doc |
| A8 | Med | D:22–37 type table usage column: labelLarge "EXHIBITION DIARY", "SETTINGS"; displayMedium "—"; no headlineMedium row | Settings/Profile titles use `SectionHeader` (`ui/settings/SettingsScreen.kt:983`, `ui/profile/ProfileScreen.kt:293`); greeting `ui/tabs/home/HomeHeader.kt:21`; headlineMedium titles the exhibition, gallery and event pages (`ui/detail/ExhibitionDetailScreen.kt:204`) | Doc |
| A9 | High | D:19 "Compose uses the first font that can render a glyph" | `ui/theme/GallrTypography.kt:26–35` one family, same weights — no per-glyph fallback (unverified on device) | Verify, then code |
| A10 | Med | D:646, :717 avatar edit "camera icon overlay", "Change profile photo" | `ui/profile/EditProfileScreen.kt:152–215` text button 사진 변경, description 프로필 사진 | Doc |
| A11 | Low | D:73, :672 active tab "underline" | `ui/components/GallrNavigationBar.kt:93–101` 4dp bar at the top of the tab, 1dp `outline` hairline above the bar (`:58–61`) | Doc |
| A12 | Low | D:58–67 dark table lacks onSurface, error, scrim | `ui/theme/GallrColors.kt:80, :93, :97` | Doc |
| A13 | Low | D:204 "list skeleton shimmer" | `ui/components/SkeletonCard.kt:89–101` opacity pulse (as D:290 says) | Doc |
| A14 | Low | D:281 "up to four taste chips" | `ui/tabs/home/HomeScreen.kt:434` limit 5 (capped at 4 upstream) | Code |
| A15 | Low | D:259–261 Home order | `ui/tabs/home/HomeScreen.kt:168–332` adds the update nudge first and the taste prompt card | Doc |
| A16 | Low | D:272–273 next banner "peeking 64dp"; D:302 "28dp of each neighbour" | `ui/tabs/home/BannerPager.kt:135`, `ui/tabs/home/CuratorEyePager.kt:84–85` (72dp and 20dp visible) | Doc |
| A17 | Low | D:357 theme tile headline "two lines" | `ui/tabs/home/CollectionTiles.kt:162` `maxLines = 3` | Doc |
| A18 | Low | D:324 `+N` on a `surfaceVariant` "square" | `ui/tabs/home/RoutesRail.kt:154` 12dp | Doc |
| A19 | Low | D:188–189 pressing deepens the scrim to 0.70 | Tiles keep 0.55 and a ripple: `ui/tabs/home/CollectionTiles.kt:98, :155, :198` | Code |
| A20 | Low | D:183–184 "a test fails on any other square shape" | `androidHostTest/.../AppShapesTest.kt:58` catches only `RectangleShape` / `RoundedCornerShape(0)`; implicit squares and `androidMain`/`iosMain` pass | Doc |
| A21 | Low | D:696 "or continue with"; D:708 "Mail icon" | `ui/profile/SignInScreen.kt:338–345` `또는` / `or`; no icon (`:427–448`); forgot-password mode undocumented | Doc |
| A22 | Low | D:487 route map controls "white … 1dp black border" | Map tab controls follow the theme (`ui/tabs/map/SeoulDistrictMap.kt:770–771`); full-screen ones are hardcoded white (`ui/route/RouteMapFullScreen.kt:230–231`) | Owner |
| A23 | Low | D:456, :471, :472 badge "9dp", "8dp", dot "4dp" | They are radii: `SeoulDistrictMap.kt:132`, `ui/route/RouteMap.kt:337, :233` | Doc |
| A24 | Low | D:415 banner ⋯ "white 44dp circle" | plus a 1dp `outlineVariant` ring, `ui/tabs/home/BannerPager.kt:319–320` | Doc |
| A25 | Low | D:667 gallery avatar fallback "first three characters" | uppercased, `ui/components/GalleryAvatar.kt:41` | Doc |
| A26 | Low | D:153 sm 8dp "chip padding … gutter width" | taste chips 12 × 8dp (`BannerPager.kt:350`); gutter has its own token | Doc |
| A27 | Low | D:166 "2-column grid for diary cards (12dp gap)" | `ui/profile/ProfileScreen.kt:327–331`, removed by spec 103; other grids use 8dp | Doc |
| A28 | Low | D:368 gallery "twelve at most" | no cap in the app (`shared/.../ExhibitionImage.kt:45`); Admin may enforce it — not verified | Verify |
| A29 | Low | D:20 bundled fonts | seven unreferenced TTFs ship: PlayfairDisplay ×4, SourceSerif4 ×2, JetBrainsMono (`composeResources/font/`) | Code |
| A30 | Low | Motion, CTA, spacing as documented | stale KDoc: `ui/theme/GallrMotion.kt:5` "no motion", `ui/components/GallrEmptyState.kt:24–25` "Sharp corners", `ui/theme/GallrSpacing.kt:37` "Featured"; CLAUDE.md still names a grain shader in `ui/graphics` that no longer exists | Code + CLAUDE.md |

## B. Internal consistency — the document disagrees with itself (13)

| # | Sev | Where | Problem | Fix |
|---|---|---|---|---|
| B1 | High | D:69–76 vs D:239, :330, :363–365 | Three roles and "any purpose not listed" is forbidden, yet the saved heart and accent status labels (text on small targets) are prescribed elsewhere | Doc: one complete role list |
| B2 | Med | D:73 vs D:409, D:517 | Selected filter chips take the accent; taste-sheet chips are `onBackground`, "never the accent"; visit-time chips take the accent | Owner: one chip rule |
| B3 | Med | D:649–653 vs D:610 | Count chip: unselected `outlineVariant`, black selected text; filter chip: `outline`, (in code) white selected text | Owner |
| B4 | Med | D:238, :331 vs D:265–266, :296, :636 | Card border `outline` vs Home's `outlineVariant`; the code splits the same way (`ExhibitionRail.kt:162`, `ExhibitionCard.kt:180` vs banner, tiles, prompt card) | Doc: say which, where |
| B5 | Med | D:190–192 vs D:501, :542, :571, :607 | "Section headings on every screen use `SectionHeader`" vs `labelLarge` headings; top bars come in three styles in code | Doc + owner |
| B6 | Med | D:215 vs D:715 | Buttons "40dp for inline actions" vs "minimum 44dp height on all interactive elements" | Doc: 40dp drawn, 44dp target |
| B7 | Med | D:11, :42 vs code | "Everything else is black, white, or gray", "no tints"; event brand colours fill bars, chips, bands and heroes in 17 places (`ExhibitionCard.kt:235, :248`, `EventPromotionCard.kt:48`, `EventListBanner.kt:36`, `ListScreenComponents.kt:255–264`, `EventDetailScreen.kt:167`) | Owner: event exception or remove |
| B8 | Med | D:260, :318–325, :468–623, :649–651, :680 | Route surfaces read as live; hidden since 1.14.0 (`ReleaseFeatures.kt:14`) | Doc: mark hidden |
| B9 | Low | D:179 | Lists "toggles, checkbox boxes" as 12dp pieces; there is no toggle, and the 24dp checkboxes at 12dp render as circles (`ui/mygallr/AddGalleriesScreen.kt:249–262`) | Doc + owner (switch) |
| B10 | Low | D:217–223 vs List search | Text fields are outlined with 12dp corners; the List search field is a filled field with an underline (`ExhibitionSearchField.kt:43–89`) | Owner |
| B11 | Low | D:726, :728, :746, :747, :757 | Superseded decisions (0dp everywhere, sharp fields, grain shader, blur, "a detail screen keeps 0dp shapes") are not marked | Doc |
| B12 | Low | D:729–734, :748–749 | Decisions Log out of date order | Doc |
| B13 | Low | D:189 vs D:197; D:203 | "over 100ms" vs "< 100ms"; "home hero pager" is now the leading banner | Doc |

## C. Coverage gaps — in the app, not in the document (23)

Surfaces (11):

| # | Sev | Surface | Code |
|---|---|---|---|
| C1 | High | List tab: 전체 전시 / 내 전시 tabs (default 2dp indicator), collapsing filter rows, search field, gallery and exhibition results, six empty messages, the promotion band "내 주변 프로모션" | `ui/tabs/list/ListScreen.kt:236–504`, `ListScreenComponents.kt:60–268`, `ExhibitionSearchField.kt` |
| C2 | Med | Map tab chrome: mode tabs (3dp indicator), zoom ± and 내 위치 controls (44dp, 12dp, `outline`) | `ui/tabs/map/MapComponents.kt:73–108`, `SeoulDistrictMap.kt:726–890` |
| C3 | Med | Events: detail page, list banner, map FAB, promotion card, brand-coloured chips | `ui/event/EventDetailScreen.kt`, `ui/components/Event*.kt` |
| C4 | Med | Gallery page: name, 팔로우 / 알림 buttons, the hand-built alert sheet | `ui/gallery/GalleryDetailScreen.kt:139–403` |
| C5 | Low | Editors: selector, detail, banner, tile, top bar | `ui/editor/` |
| C6 | Med | Profile, edit profile, crop | `ui/profile/ProfileScreen.kt`, `EditProfileScreen.kt`, `CropScreen.kt` |
| C7 | Med | Settings: rows (52dp, `›`), language and theme choice, analytics, notifications, account deletion | `ui/settings/SettingsScreen.kt:355–983` |
| C8 | Med | MY tab chrome: counts row, section tabs, invitation card, storage line, 팔로잉; add-past-visits and add-galleries screens | `ui/mygallr/` |
| C9 | Low | Sign-up nudge sheet, connect menu, notification permission dialog | `AppMenus.kt:41, :122`, `notifications/NotificationPermissionHandler.kt:48` |
| C10 | Low | Shared pieces: `BookmarkButton`, curation badges, date row, share preview, collection screen, For You chrome, splash | `ui/components/`, `ui/detail/SharePreviewScreen.kt`, `ui/tabs/home/CollectionScreen.kt`, `splash/SplashOverlay.kt` |
| C11 | Med | Top app bars: centred `titleMedium` (Settings, Collection, For You), left `labelLarge` (composer, browse), left `labelMedium` (`EditorTopBar`) | `SettingsScreen.kt:979`, `CollectionScreen.kt:49`, `ui/editor/EditorTopBar.kt` |

States the document never specifies (12):

| # | Sev | State | What the code does today |
|---|---|---|---|
| C12 | Med | Loading | Three patterns: skeletons, `CatalogLoadingState`, and `CircularProgressIndicator` in 11 places (e.g. `ui/profile/AccountScreen.kt:61`); `GallrLoadingState` (a 1dp line) is unused |
| C13 | Low | Disabled | Black buttons fall back to Material's disabled colours; chips use `surfaceVariant` / `onSurfaceVariant` (`GallrFilterChip.kt:42–43`) |
| C14 | Med | Pressed | Ripple by default; scrim 0.55 → 0.70 on cards; inversion on no-image cards; orange full row in Settings |
| C15 | Low | Keyboard / switch focus | No visible focus style anywhere |
| C16 | Med | Dark theme per component | Tokens are documented; 21 UI files hardcode `Color.White` / `Color.Black` |
| C17 | Med | Large text | A rule for Home and route cards only (D:359–362); fixed 52dp heights elsewhere (`SignInScreen.kt:206`, `AppMenus.kt:90`) |
| C18 | Low | Touch targets | 44dp constants for custom controls; Material components get 48dp; D:715 says 44dp only |
| C19 | Low | Snackbars | Consistent: 12dp, inverse colours; short by default, long or indefinite for undo with a screen reader — unwritten |
| C20 | Low | Dialogs | Consistent: 16dp, `background`, `titleMedium`, text buttons, black confirm — unwritten (three exceptions, E8) |
| C21 | Low | Menus | Consistent: 12dp, `background`, 1dp `outline` — written only for routes (D:543) |
| C22 | Low | Bottom sheets | `sheetTop`, `background`, no drag handle — except the taste sheet (`ui/discovery/TastePickSheet.kt:68–73`) |
| C23 | Low | Selection indicators | Tab bar 4dp top bar, map tabs 3dp, List and MY tabs 2dp, option rows 3dp leading bar |

## D. Accessibility of the system (9)

Contrast of the tokens (WCAG 2.x, computed from the hex values):

| Pair | Light | Dark | Verdict |
|---|---:|---:|---|
| `onBackground` on `background` | 21.00 | 14.19 | pass |
| `onSurfaceVariant` on `background` | 7.81 | 7.16 | pass AAA |
| `onSurfaceVariant` on `surfaceVariant` | 7.17 | 5.34 | pass |
| `outline` on `background` (card border) | 21.00 | 1.81 | dark below 3:1 |
| `outlineVariant` on `background` (hairline, unfocused field) | 1.26 | 1.48 | below 3:1 |
| accent on `background` (indicator, 3:1 needed) | 3.22 | 5.82 | pass, narrowly in light |
| accent as text on `background` | 3.22 | 5.82 | fails AA in light |
| black `ctaContent` on accent | 6.52 | 6.52 | pass |
| white on accent | 3.22 | — | fails AA |
| white on the 0.55 scrim over a white picture | 4.74 | 4.74 | pass (the floor works) |
| white on the 0.70 pressed scrim | 8.45 | 8.45 | pass |
| accent on the 0.55 scrim over a white picture | 1.47 | 1.47 | fails badly |

| # | Sev | Finding | Code |
|---|---|---|---|
| D1 | High | Selected filter chip: white on accent, 3.22:1 | `ui/components/GallrFilterChip.kt:41` |
| D2 | High | Accent status labels as 12sp text: 3.22:1 on white, as low as 1.47:1 on an image card | `ExhibitionDateRow.kt:40`, `ExhibitionRail.kt:226`, `ExhibitionDetailScreen.kt:296`; D:239–240 |
| D3 | High | Password toggle has no accessible name | `ui/profile/SignInScreen.kt:176–184` |
| D4 | Med | Unfocused field borders and hairlines are 1.26–1.48:1; a field's edge is not perceivable for low vision (WCAG 1.4.11) | `outlineVariant`, D:219 |
| D5 | Med | Fixed heights clip at large font scale: 52dp buttons, search field `heightIn(max = 48.dp)` | `SignInScreen.kt:206`, `AppMenus.kt:90`, `ExhibitionSearchField.kt` |
| D6 | Low | Motion that ignores reduced motion: bookmark spring, List filter expand | `ui/components/BookmarkButton.kt:49–65`, `ListScreen.kt:268` |
| D7 | Low | No visible focus indicator for keyboard or switch control | — |
| D8 | Med | Dark mode: a hardcoded black divider disappears; full-screen map controls stay white | `ui/event/EventDetailScreen.kt:102`, `ui/route/RouteMapFullScreen.kt:230–231` |
| D9 | Low | `labelSmall` (11sp) carries information (venues, status words, credits); fine at 11sp, but nothing smaller should be added | type scale |

## E. Rule violations in code (10)

| # | Sev | Rule | Count | Instances |
|---|---|---|---:|---|
| E1 | High | Accent outside its roles | 8 live, 4 unused | `SettingsScreen.kt:574`; `GalleryDetailScreen.kt:271, :403`; `MyGallrScreen.kt:788`; `ThoughtComposer.kt:96`; `ExhibitionDetailScreen.kt:310, :369, :418`; unused: `ui/tabs/map/DotMapCanvas.kt:251, :257`, `MapComponents.kt:206`, `MapAtlasOverlays.kt:120` |
| E2 | Med | `ctaPrimary` beyond `GallrEmptyState` | 5 | see A1 |
| E3 | Med | Emoji in UI copy | 3 | `ui/detail/ThoughtsSection.kt:232, :233` (✍️ 감상 남기기 — replaced by spec 103's `감상 남기기`), `ui/profile/ProfileScreen.kt:477` (💭) |
| E4 | Low | Square without the marker (implicit: no `shape` given) | 11 | `HomeEventPager.kt:137–143, :161`; `ExhibitionCard.kt:248`; `PublicRouteBrowseScreen.kt:368`; documented squares unmarked: `GallrNavigationBar.kt:50`, `EventListBanner.kt:46`, `EventDetailScreen.kt:181`, `SeoulDistrictMap.kt:465`, `RouteMapFullScreen.kt:207`; rules `EditorBanner.kt:58`, `EditorTile.kt:67`. Explicit squares: 0 unmarked |
| E5 | Med | Hardcoded colours | 88 lines in 23 files | ~50 are documented (white on a scrim, map drawing); the rest: brand fills (B7), white/black alpha as grey text (`EventPromotionCard.kt:94, :106`, `EventListBanner.kt:69`, `EventDetailScreen.kt:215, :227`, `RouteMapFullScreen.kt:204`, `SeoulDistrictMap.kt:461`), `SettingsScreen.kt:557–558` (should be `ctaContent`), `outline.copy(alpha = 0.2f)` dividers (`EditorTile.kt:102`, `EditorTopBar.kt:58`), splash duplicates (`splash/SplashOverlay.kt:32–38`), a 0.44 scrim (`GalleryDetailScreen.kt:379`) |
| E6 | Low | Literal spacing | 61 token-equal, 27 off-grid, 18 files | worst: `ProfileScreen.kt` (20), `EventDetailScreen.kt` (21), `SignInScreen.kt` (14); off-grid: 12dp ×15, 2dp ×4, 6dp ×3, 3/10/13/14/64dp |
| E7 | Low | Type outside the scale | 26 | `BookmarkButton.kt:89` 20sp; weights not bundled (fall back): SemiBold `EventListBanner.kt:62`, `ExhibitionCard.kt:241`, Black `EventDetailScreen.kt:222`, `EventPromotionCard.kt:101`; 14 Bold overrides |
| E8 | Med | Material defaults that bring tint | 3 dialogs + pull-to-refresh | `MyGallrScreen.kt:230, :263`; `NotificationPermissionHandler.kt:48`; `HomeScreen.kt:158`, `ListScreen.kt:515` |
| E9 | Low | Shadows | menus and snackbars carry Material's default elevation (not checked on device); the exported story image draws a cover shadow | `iosMain/.../ShareHandler.ios.kt:319–326`, `androidMain/.../ShareHandler.android.kt:233` |
| E10 | Low | Sheet chrome | taste sheet keeps the drag handle; all four other sheets pass `dragHandle = null` | `TastePickSheet.kt:68–73` |

Unused code found on the way (no rule, cleanup only): `GallrLoadingState`; in `ui/tabs/map`, `DotMapCanvas`,
`MapLegend`, `MapScopeHeader`, `ScopeSummaryPanel`, `BrowseMapResults`, `BrowseButton`, `NearbyExhibitionsSection`,
`CountryCityLabels`, `CityDistrictLabels`; `EventMapFab`.

## Improvement proposals (ranked)

### Fix the document to match the code (no owner choice involved)

1. **Write the accent as one complete role table**: CTA fill (with its five current users or the rule that admits
   them), active indicator (tab bar, mode tabs, option-row bar, count chip, carousel dot, saved map pin), interaction
   feedback (held row), saved heart, and status label (pending owner decision 2 below). Delete "ONLY in
   `GallrEmptyState`".
2. **Mark hidden surfaces.** One line under each route heading: "Hidden since 1.14.0 (`ReleaseFeatures.ROUTES`)";
   fix "beside 방문" (A4), browse 0dp chips (A2) and `labelLarge` headings (A5) while there.
3. **Refresh the foundations tables**: type-scale usage column and a headlineMedium row (A8), the full dark table
   (A12), spacing usage (A26), tab indicator (A11), card anatomy (A6).
4. **Correct the small numbers and copy**: A7, A10, A13, A15–A18, A21, A23–A25, A27, B13.
5. **Decisions Log hygiene**: sort by date; add a "Superseded by" note to D:726, :728, :746, :747, :757 (B11, B12).
6. **Restructure** (no content change):
   - Split the document into **Foundations** (colour, type, spacing, shape, motion, accessibility), **Components**
     (buttons, chips, fields, cards, sheets, dialogs, menus, snackbars, headers, badges), **Screens** (Home, List,
     Map, exhibition page, MY, routes [hidden], settings, auth) and **Exports** (QR, share card, carousels).
   - Move the 65-line export exception (D:78–143) out of Color into Exports; it is not app UI.
   - Add a **component index** (name → file) at the top of Components, so drift is visible.
   - Give each section a status tag: live / hidden / retired.
7. **Document the de-facto patterns that are already consistent** (C19–C22): snackbars, dialogs, menus, sheets.

### Fix the code to match the document

1. `GallrFilterChip` selected label → `GallrAccent.ctaContent` on the accent (D1). One line.
2. Remove the eight off-role accent uses (E1); delete the unused map code that holds four more.
3. Set `surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerHighest`, `surfaceContainerLow(est)` and
   `surfaceBright`/`surfaceDim` in both schemes (E8) — fixes the tinted dialogs and pull-to-refresh at once.
4. Add the password toggle's content description (D3).
5. Remove the emoji (E3); spec 103 removes ✍️ with the composer, 💭 goes with the Profile grid.
6. Verify Hangul rendering on both platforms (A9); if Gothic A1 is not used, give Korean text its own family or
   declare the fallback per platform.
7. Delete the seven unused fonts (A29) — a smaller app binary — and stale KDoc (A30).
8. Replace hardcoded colours that are not documented exceptions with tokens (E5, D8); drop SemiBold and Black (E7).
9. Taste sheet `dragHandle = null` (E10); tile press scrim (A19); bookmark spring under reduced motion (D6).
10. Extend `AppShapesTest` to `androidMain` and `iosMain`, and to `.background(…)` / `.border(…)` without a shape
    (A20, E4), or narrow the document's claim.

### Owner decisions needed

1. **Publish control and its label** (spec 103): two chips or a switch; wording. Recommendation below.
2. **Status labels on cards** (D2): accent text (fails AA) or monochrome text with an accent dot, as the share card
   already does. Recommendation: the dot.
3. **One chip rule** (B2, B3, PR #410 item 2): unselected border `outline` or `outlineVariant`; selected fill the
   accent (filtering a list) or `onBackground` (choosing inside a sheet or form). Recommendation: `outlineVariant`
   border; accent only where the chip filters what is on screen, with black text; `onBackground` in sheets.
4. **Where `ctaPrimary` may appear** (A1): widen to "the one primary action of a screen that has no other accent"
   or move the five buttons to black. Recommendation: move them to black, keeping the CTA rare.
5. **Event brand colours** (B7): write an "event brand exception" (bar, chip, band, hero; text contrast rules) or
   take them out.
6. **A field border token** (D4): a mid-grey `fieldBorder` (about #767676 light, #8C8C8C dark, ≥ 3:1) for unfocused
   fields, keeping `outlineVariant` for hairlines.
7. **Route browse stop covers** (A3): 12dp like Home's route covers, or square with a marker.
8. **Top bars and indicator thickness** (C11, C23): one top-bar style and one indicator thickness (3dp is the
   middle value already used twice).
9. **Search field** (B10): keep the filled underline field as a named exception or move it to the outlined spec.

### New tokens worth adding (proposal)

- `GallrSize`: `touchTarget = 44.dp`, `buttonHeight = 52.dp`, `rowMin = 52.dp`, `thumbnail = 56.dp`,
  `indicator = 3.dp`, `hairline = 1.dp` — these literals repeat across 30 files.
- `GallrMotion`: `press = 100`, `reorder = 150`, `crossfade = 200` ms — the doc names them, the code repeats them.
- A colour role for event brand fills if the owner keeps them.

## The publish label (spec 103)

Context: the private 메모 becomes a public 감상 on the exhibition page, with the author's name, after a check by
staff. House tone: plain 해요체, quiet, no AI wording, no emoji, no review jargon for readers. The other parts of the
entry sheet are questions answered by chips: `언제 다녀오셨어요?`, `어땠어요?`, `무엇이 남았나요?`.

Why "감상으로 공개" sounds off: it is a noun phrase that names a category ("as a 감상") rather than saying what
happens; the reader must translate "감상" (the field above is called 메모), and it reads like an admin form.

| Option | KO | EN | Tone |
|---|---|---|---|
| Current | 감상으로 공개 | PUBLISH AS A NOTE | Form-like; names a category instead of an outcome |
| 1 | 전시 페이지에 공개 | SHOW ON THE EXHIBITION PAGE | Plain and exact: says where; best single label for a switch |
| 2 | 전시 페이지에 감상 남기기 | LEAVE IT ON THE EXHIBITION PAGE | Warm; echoes the page's `감상 남기기` button; long for a switch row |
| 3 | 다른 관람객과 나누기 | SHARE WITH OTHER VISITORS | Social and soft, but vague: hides that it is public to everyone and shows the name |
| 4 | 모두에게 보여주기 | SHOW TO EVERYONE | Clear audience, casual; could read as "the whole diary" |
| 5 | 감상 공개하기 | PUBLISH NOTE | An action button's wording (-하기), wrong for a state; 감상 vs 메모 |
| **6 (recommended)** | **누가 볼 수 있나요?** — chips **나만 보기** / **전시 페이지에 공개** | **WHO CAN SEE IT?** — **ONLY ME** / **EXHIBITION PAGE** | Same voice as the sheet's other questions; the private default is a visible choice |
| 7 | 공개 범위 — 나만 보기 / 전체 공개 | VISIBILITY — ONLY ME / EVERYONE | Familiar from Korean blogs and SNS; terse; "전체 공개" does not say where |

Recommendation: **option A (two chips) with option 6.** It needs no new component (no first switch, no pill-shape
exception), it reads like the rest of the sheet, and the private state is a labelled choice rather than the absence
of one. If the owner prefers the switch, use **option 1** (`전시 페이지에 공개` / `SHOW ON THE EXHIBITION PAGE`).

Disclosure line, by state (proposal; the contract's line stays for the public state):

- private: `메모는 나만 볼 수 있어요.` / `Only you can see this note.`
- public: `공개하면 검토 후 이 전시 페이지에 내 이름과 함께 보여요. 기분과 태그는 공개되지 않아요.` / `If you
  publish, this note appears on the exhibition page with your name after review. Your feeling and tags stay
  private.` (`검토` matches the status word `검토 중` the author will see.)

## What was not verified

- Nothing was run on a device or simulator; no Gradle task ran. Colours, shapes and copy were read from source.
- Korean font rendering (A9) is inferred from how Compose matches fonts, not observed.
- Material default elevations on menus and snackbars (E9) and the tinted dialog surface (E8) come from Material 3's
  defaults, not from a screenshot.
- The gallery-image cap (A28) may be enforced in Admin, which was not read.
- Contrast figures are computed from token hex values; real pictures under a scrim vary between the worst case
  shown and much better.
