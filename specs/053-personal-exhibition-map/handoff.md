# Design Handoff: Personal Exhibition Map

## Overview

The Map tab becomes a Korea-first personal exhibition atlas. It uses abstract dotted geography for
country and city scope while retaining exact coordinates for nearby search and external navigation.
Users plan with To Visit, review Visited, explore All, and can log private visits manually or from a
photo. The approved mockups are directional design references; real catalogue copy and imagery must
replace all placeholder exhibition names and dates.

## Screen Inventory

| Screen/state | Reference | Purpose |
|---|---|---|
| Korea / All | [mockup](assets/korea-country-scope.png) | City-level country overview |
| Seoul / All | [mockup](assets/seoul-all-scope.png) | District and exhibition exploration |
| Seoul / Near Me | [mockup](assets/seoul-near-me.png) | Approximate position plus distance-ranked results |
| Log Visit / photo match | [mockup](assets/log-visit-photo-match.png) | Confirm venue, exhibition, note, and privacy |
| Seoul / Visited | [mockup](assets/seoul-visited-diary.png) | Historical visited map and private memory |

## Layout

- Entire screen uses `MaterialTheme.colorScheme.background` and safe-drawing insets.
- Horizontal content uses `GallrSpacing.screenMargin`.
- Major vertical groups use `GallrSpacing.lg` or `GallrSpacing.xl`; internal groups use
  `GallrSpacing.sm` or `GallrSpacing.md`.
- The top title and filter row remain outside the map canvas so text scaling never distorts geometry.
- The scope header, legend, and summary are Compose content; only geography and visual marks are drawn
  inside the canvas.
- The map canvas receives remaining height after header/filter content. On short screens, result/detail
  panels become modal bottom sheets rather than compressing the map below a usable height.
- All panels and buttons use `RectangleShape`; photo thumbnails are rectangles, not avatar exceptions.

## Design Tokens

| Token | Usage |
|---|---|
| `MaterialTheme.colorScheme.background` | Screen and panel backgrounds |
| `MaterialTheme.colorScheme.onBackground` | Text, visited marks, panel borders |
| `MaterialTheme.colorScheme.onSurfaceVariant` | Metadata and secondary copy |
| `MaterialTheme.colorScheme.outlineVariant` | Background dots, dividers, empty geometry |
| `GallrAccent.activeIndicator` | Active tab/scope underline and selected semantic mark only |
| `GallrSpacing.xs` | Tight icon/label gaps |
| `GallrSpacing.sm` | Legend and row spacing |
| `GallrSpacing.md` | Panel padding and screen gutters |
| `GallrSpacing.lg` | Major content separation |
| `labelLarge` | Tabs, section/action labels |
| `labelMedium` / `labelSmall` | Counts, dates, distances, privacy metadata |
| `titleMedium` / `titleLarge` | Scope and exhibition titles |

## Visual Semantics

| Element | Appearance | Meaning |
|---|---|---|
| Background geography dot | `outlineVariant`, smallest radius | Shape only; never data |
| Unexplored semantic mark | Pale gray, larger radius | Active catalogue exhibition without visit/save |
| Saved semantic mark | Hollow outline | Bookmarked and not visited |
| Visited semantic mark | Solid `onBackground` | At least one visit exists |
| Selected semantic mark | Solid `activeIndicator` | Current selection only |
| Approximate user location | Crosshair plus “YOU” semantics | Display projection of real location |
| Nearby radius | One subtle outline ring | Approximate visual context, not distance scale |

Color is never the only communication. Each selected or focused result has text, list position, and
screen-reader state. Dark mode substitutes theme tokens; it does not hardcode light-mode values.

## Components

| Component | Key inputs | Behavior |
|---|---|---|
| `MapModeTabs` | mode, counts, language | To Visit / Visited / All; one active indicator |
| `MapScopeHeader` | scope, parent, near-me availability | Shows scope and parent/search/location actions |
| `DotMapCanvas` | geometry, projected marks, selection, approximate user point | Batched drawing and hit testing |
| `MapLegend` | visible states, language | Omits states irrelevant to the current mode |
| `ScopeSummaryPanel` | child scope and aggregate counts | Opens next scope or equivalent list |
| `NearbyPanel` | ordered nearby results | Shows real distance and opens exhibition/details |
| `VisitMemoryPanel` | visit snapshot, private-photo thumbnail | Opens private visit detail |
| `LogVisitScreen` | selected exhibition/venue, photo result, match candidates | Creates confirmed visit only |
| `PhotoCandidateRow` | exhibition, schedule, selected | Sharp row; left active edge and text state |

## Scope and Projection Behavior

- Country scope displays child-city aggregates; city scope displays district aggregates and projected
  venue/exhibition marks.
- Scope geometry is presentation data. Projection maps real coordinates into its bounds, then snaps
  visual marks to available cells while retaining original coordinates separately.
- Multiple exhibitions at one venue remain one visual mark until selected; the result panel lists all.
- Background dot count and spacing may adapt to canvas size, but semantic identity and hit target must
  remain stable across recomposition.
- Pinch-to-zoom is not required. Navigation uses explicit country/city/district selections so the
  abstract form remains legible and accessible.

## States and Interactions

| Element/state | Behavior |
|---|---|
| Map initial loading | Preserve header; show static pale geometry or existing skeleton treatment |
| Scope selected | Fade old/new geometry over sanctioned 150–260ms crossfade |
| Semantic mark tapped | Select mark and open/update equivalent detail panel |
| Empty scope | Keep geography; show “No exhibitions in this area yet” and parent/search actions |
| To Visit | Saved and unvisited semantic marks/results only |
| Visited | Historical visits, including closed/unavailable catalogue records |
| All | Active catalogue plus personal state; panel exposes mixed counts |
| Near Me inactive | Location is not requested solely for opening the map |
| Near Me permission needed | Explain purpose, request foreground permission, then resolve once |
| Near Me resolving | Disable repeated action and announce progress; preserve map content |
| Near Me success | Open supported city, show approximate crosshair, highlight nearest results |
| Near Me unavailable | Explain unavailable fix; retain city search/manual exploration |
| Photo selected | Read metadata locally, derive candidates, show confirmation screen |
| Photo ambiguous | No default auto-confirm; user must choose candidate |
| Photo metadata absent | Show manual venue/exhibition selection and no-photo path |
| Visit save in progress | Disable confirm action; prevent duplicate submissions |
| Photo upload failure | Preserve visit draft and local photo; allow retry or save without photo |
| Visit confirmed | Update local state immediately, then sync; surface new visit in Visited mode |

## Content Specifications

- All user-visible strings are bilingual using the existing inline `AppLanguage` pattern.
- Counts use localized noun order rather than concatenating English fragments.
- Exhibition titles are single-line in compact panels and two-line maximum in lists; overflow uses an
  ellipsis. Full titles remain available in semantics and detail views.
- Venue and district labels are one line, falling back from English to Korean using existing model
  helpers.
- Distances use meters below 1 km and one decimal kilometer at or above 1 km.
- Visit notes follow the existing thought-scale tone but are private and separate; initial maximum is
  280 characters unless planning establishes a stronger product need.
- Mockup names “FORM AND MEMORY” and “THE LIVING ARCHIVE” are placeholders and MUST NOT ship.

## Photo and Privacy Treatment

- Camera/library choice occurs before Log Visit confirmation.
- Metadata extraction is local and best-effort. It is never displayed beyond what is needed to
  explain the suggestion.
- Candidate UI may show venue distance and capture date but never raw latitude/longitude.
- “Photo stays private” is the default and cannot be silently changed by authentication or migration.
- Upload only a resized/re-encoded derivative with metadata removed. The original remains outside
  Gallr storage.
- Deleting a visit removes its private photo object through a recoverable, explicit confirmation flow.

## Responsive Behavior

| Device condition | Adaptation |
|---|---|
| Compact phone | Result panel becomes scrollable bottom sheet; map keeps a usable minimum height |
| Standard phone | Inline/anchored panel follows approved mockups |
| Tablet / landscape | Center content to a bounded map region; detail panel may sit beside the map |
| Large text | Move counts/legend to multiple rows; never scale canvas labels into overlap |
| Dark mode | Recalculate background-dot contrast using theme tokens; retain state hierarchy |

## Edge and Error Presentation

- Missing geometry: show equivalent scope list and structured error; do not show a blank canvas.
- Missing exhibition coordinate: include it in scope list with “Location unavailable,” omit from map
  and Near Me ranking.
- Unsupported current country/city: retain Korea scope for launch and offer Find a City; do not imply
  nearby coverage.
- Offline with cached catalogue/visits: render cached scope and diary; mark distances as last-known if
  source location is cached.
- Catalogue refresh failure: preserve the last verified catalogue and personal state.
- Very dense scope: aggregate same/near cells and disclose count; never allow overlapping invisible
  touch targets.

## Motion

| Trigger | Treatment | Duration |
|---|---|---|
| Scope change | Opacity crossfade between geometry | 150–260ms |
| Mode change | Opacity crossfade semantic marks and panel | 150ms |
| Selection | Immediate color/state change | <100ms |
| Visit confirmation | Immediate local state update; optional brief fade into memory panel | 150ms |

All animation is disabled or made immediate when reduced motion or screen-reader activity is detected.
No decorative travel, map flight, bounce, pulse, or continuous location animation is permitted.

## Accessibility

- The canvas is not exposed as hundreds of independent nodes. It exposes the current scope summary and
  selected item; an adjacent list provides every actionable result.
- Reading order: title, modes, counts, scope actions, summary/list, selected detail, bottom navigation.
- All actions meet at least 48dp touch targets even when visual marks are smaller.
- Screen readers announce state explicitly: “Visited,” “Saved,” “Unexplored,” “Selected,” and
  “Approximate location.”
- Near Me announces permission, resolving, result count, and nearest result changes.
- Photos use exhibition/venue-derived descriptions; private note text is not used as alt text.
- Contrast and state remain valid without orange and in both themes.

## Implementation Boundaries

- Shared business logic includes scope resolution, projection inputs, geographic distance, candidate
  matching, visit state, repositories, migration, and privacy decisions.
- Platform code is limited to foreground location, photo picker/camera, metadata decoding, sanitized
  image encoding, and external-map launch.
- Compose common UI owns the renderer and all screens. Do not retain Naver/Apple map SDK views behind
  the abstract visualization.
- Precise navigation uses external-map URLs/intents; an embedded street-map fallback is not part of
  this release.
