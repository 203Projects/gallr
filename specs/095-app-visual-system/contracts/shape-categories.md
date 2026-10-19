# Contract: which shape each element takes

Every element drawn in `composeApp` falls in one category. The shape guard (`AppShapesTest`, research R6) enforces
the square exceptions by name; everything else takes a token.

| Category | Shape | Examples |
|----------|-------|----------|
| Card / tile / framed panel | `GallrShapeTokens.card`, content clipped | `ExhibitionCard`, `HeroExhibitionCard`, `EventPromotionCard`, route stop cards, route summary and map panels, `ScopeSummaryPanel`, `DiaryCard`, `VisitCard`, `AccountInvitationCard`, `FollowedGalleryRow`, gallery panels, the promoted band |
| Small piece | `GallrShapeTokens.small` | every Button / OutlinedButton / TextButton, text fields, `GallrFilterChip`, `GallrEventFilterChip`, taste-tag and fact chips, curation badges, stop-count toggle, checkbox boxes, the gallery `2 / 5` counter, small thumbnails, skeleton bars |
| Dialog | `MaterialTheme.shapes.extraLarge` (16dp) | `AlertDialog`s |
| Bottom sheet | `GallrShapeTokens.sheetTop` | `ModalBottomSheet`s, the gallery alert panel |
| Menu, snackbar | `MaterialTheme.shapes.small` (12dp) | `DropdownMenu`s, `Snackbar`s |
| Map control | `GallrShapeTokens.small` | zoom, recentre, the full-screen map's close / fit and the panel's expand control |
| Square by design | none (`RectangleShape` allowed) | screen-edge pictures and bands (exhibition gallery pager, event hero, event list band), the tab bar and its indicator, map pins, map label chips, map credits, exports (share renderers, the share preview frame), `GalleryAvatar` |
| Circle | `CircleShape` | profile avatars |

## Captions on pictures

| Where | Treatment |
|-------|-----------|
| Caption at the bottom of a picture | `bottomScrim` (Home's bottom band), white text |
| Caption at the top of a picture | `topScrim` (Home's top band), white text |
| Even overlay under a centred or full caption | `flatScrim(SCRIM_ALPHA)` |
| Pressed | the band deepens to `SCRIM_PRESSED_ALPHA` over `GallrMotion.PRESS_DURATION_MS`; no animation under reduced motion |

No caption on a picture uses a white wash or dark text.
