# Design System — gallr

## Product Context
- **What this is:** Exhibition discovery app for art lovers in Seoul, evolving into a taste-driven social platform (Letterboxd for art exhibitions)
- **Who it's for:** Art lovers in Seoul who actively visit smaller/indie galleries and treat exhibition visits as part of their identity
- **Space/industry:** Art/culture discovery, social curation
- **Project type:** Mobile app (KMP/Compose Multiplatform, iOS + Android)

## Aesthetic Direction
- **Direction:** Brutally Minimal / Reductionist Monochrome
- **Decoration level:** Minimal. Typography and whitespace do all the work. No shadows, no gradients, no rounded corners.
- **Mood:** Gallery wall. The app should feel like the white walls of a contemporary art gallery, where the art (exhibitions) is the focus and the frame disappears. Quiet confidence, not flashy.
- **Reference:** The monochrome constraint forces every pixel to earn its place. Color is reserved for a single accent that means "act now."

## Typography

### Font Stack
- **Primary (Latin):** Inter — neo-grotesque sans-serif. Clean, neutral, excellent readability at all sizes. Chosen for bilingual Korean/Latin support.
- **Fallback (Korean):** Gothic A1 Medium — Korean-optimized sans-serif for Hangul rendering. Compose uses the first font that can render a glyph, so Inter handles Latin chars and Gothic A1 handles Korean.
- **Loading:** Bundled via compose-resources (TTF files in app binary). No network font loading.

### Type Scale
| Role | Style | Size | Weight | Line Height | Letter Spacing | Usage |
|------|-------|------|--------|-------------|----------------|-------|
| displayLarge | Display | 40sp | Bold | 48sp | -0.025em | Sign-in brand title ("gallr") |
| displayMedium | Display | 32sp | Bold | 40sp | -0.015em | — |
| displaySmall | Display | 24sp | Medium | 32sp | 0em | — |
| headlineSmall | Headline | 24sp | Medium | 32sp | 0em | Empty state messages, section heroes |
| titleLarge | Title | 24sp | Bold | 32sp | 0em | For You hero title (the top pick) |
| titleMedium | Title | 18sp | Medium | 26sp | 0em | Profile display name, card titles |
| titleSmall | Title | 16sp | Medium | 22sp | 0em | — |
| bodyLarge | Body | 16sp | Normal | 24sp | 0em | — |
| bodyMedium | Body | 14sp | Normal | 20sp | 0em | Button labels, sign-in subtitles, form inputs |
| bodySmall | Body | 12sp | Normal | 18sp | 0em | Error messages, metadata, delete account |
| labelLarge | Label | 13sp | Medium | 18sp | 0.04em | Section titles ("EXHIBITION DIARY", "SETTINGS"), tab labels |
| labelMedium | Label | 12sp | Normal | 16sp | 0.04em | — |
| labelSmall | Label | 11sp | Normal | 16sp | 0.05em | Card venue names, thought badges, stat labels |

## Color

### Approach: Restrained
One accent color. Everything else is black, white, or gray. Color is rare and meaningful.

### Light Mode
| Token | Hex | Usage |
|-------|-----|-------|
| background | #FFFFFF | Screen backgrounds |
| onBackground | #000000 | Primary text, borders, filled buttons |
| surface | #FFFFFF | Card backgrounds |
| onSurface | #000000 | Card text |
| surfaceVariant | #F5F5F5 | Muted surfaces (avatar circles, image placeholders) |
| onSurfaceVariant | #525252 | Secondary text (subtitles, metadata, placeholders) |
| outline | #000000 | Card borders (1dp), focused text field borders (2dp) |
| outlineVariant | #E5E5E5 | Hairline dividers, unfocused text field borders (1dp) |
| error | #000000 | Error text (monochrome errors, see Error Treatment below) |
| scrim | #000000 | Overlays |

### Dark Mode
| Token | Hex | Usage |
|-------|-----|-------|
| background | #121212 | Screen backgrounds |
| onBackground | #E0E0E0 | Primary text |
| surface | #1E1E1E | Card backgrounds |
| surfaceVariant | #2C2C2C | Muted surfaces |
| onSurfaceVariant | #A0A0A0 | Secondary text |
| outline | #404040 | Card borders |
| outlineVariant | #333333 | Hairline dividers |

### Accent (Single, Intentional)
| Token | Hex | Role | Rule |
|-------|-----|------|------|
| ctaPrimary | #FF5400 | Primary call-to-action button fill | ONLY for the main action button (e.g., GallrEmptyState CTA) |
| activeIndicator | #FF5400 | Active tab underline, selected filter chips, saved exhibition map pins | ONLY for current selection indicators and the saved-map state |
| interactionFeedback | #FF5400 | Pressed/active state color shift | ONLY for immediate touch feedback |

**Accent rules:** NEVER use #FF5400 for backgrounds, large surfaces, decoration, text on small targets, or any purpose not listed above. On the Map tab it identifies saved exhibitions consistently in All and My Exhibitions. The accent is a signal, not a theme.

**Artwork-derived export exception:** A downloaded exhibition QR may use a small palette sampled
from that exhibition's published poster. This is generated exhibition content, not portal chrome:
the surrounding UI stays monochrome, the QR keeps a white four-module quiet zone, and every dark
module color must maintain at least 7:1 contrast against white. Scanner-critical function modules
remain square and use the darkest sampled tone.
The exported exhibition share card (Instagram story image) follows the same exception: its paper
may take a light wash of the poster's dominant colour (in dark mode, a dark paper with only a hint
of it), and it may show the poster QR plus five swatches of the QR palette. The QR always sits on
an opaque white tile. Card type uses the darkest QR tone in light mode and the theme's text colours
in dark mode, and secondary text keeps 4.5:1 against the paper. #FF5400 appears only as the dot on
time-critical status labels (opening day, closing day, final two weeks).

## Spacing

### 8pt Grid System
All layout decisions reference these tokens. Base unit: 8dp.

| Token | Value | Usage |
|-------|-------|-------|
| xs | 4dp | Tight internal padding (icon margins, small gaps) |
| sm | 8dp | Chip padding, label gap, gutter width |
| md | 16dp | Card internal padding, screen horizontal margin |
| lg | 24dp | Card-to-card gap, section sub-spacing |
| xl | 32dp | Major section spacing |
| xxl | 48dp | Full-screen section breaks |
| screenMargin | 16dp | Left/right screen edge padding |
| gutterWidth | 8dp | Column gutter width |

### Density: Comfortable
Not cramped, not spacious. Gallery-like breathing room without wasting space on a mobile screen.

## Layout
- **Approach:** Grid-disciplined
- **Grid:** Single column on mobile (full width minus 16dp margins), 2-column grid for diary cards (12dp gap)
- **Max content width:** Device width (mobile-only app)
- **Border radius:** 0dp on everything. Sharp rectangles only. This is the most distinctive visual rule. All shapes (extraSmall through extraLarge) are `RoundedCornerShape(0.dp)`.
- **Exception:** Avatar circles use `CircleShape` (the only non-rectangular element)

## Motion
- **Approach:** Functional-minimal. Motion communicates state and liveness; it is never decorative.
- **Press duration:** < 100ms for press/active state color shift.
- **Sanctioned motion:**
  - Opacity crossfade for content swaps (tab content, cycling event surfaces) — ~150–260ms.
  - Timing-cue indicators (e.g. an auto-cycle progress bar) when content advances on a timer.
  - Auto-advancing carousels (Featured event pager, List banner, Map FAB) and the For You entry's
    picks cycle — disabled when the OS signals reduced motion or a screen reader is active (see
    Accessibility).
  - Existing enter/exit + state animations already in use: `AnimatedVisibility` (collapsing filters), `AnimatedContent` fades, list skeleton shimmer, bookmark spring.
- **Avoid:** Gratuitous positional/translate animation that carries no state meaning. Prefer opacity/color over movement.
- **Accessibility:** All timer-driven motion must check `isReduceMotionOrScreenReaderActive()` and fall back to a static, manually-controlled presentation.

## Component Patterns

### Buttons
- **Primary CTA (rare):** Orange fill (#FF5400), black text, sharp rectangle, `labelLarge` text, uppercase. Used ONLY in `GallrEmptyState`. Black provides WCAG AA contrast (6.52:1); white does not (3.22:1).
- **Standard action:** Black fill, white text, sharp rectangle, `bodyMedium` text. Used for sign-in buttons, primary form actions.
- **Outlined:** Black border, transparent background, sharp rectangle. Used for settings actions (sign out).
- **Text button:** No border, no background. Used for secondary actions (delete account, skip, toggle links).
- **Height:** 52dp for primary auth buttons, 44dp for settings buttons, 40dp for inline actions.

### Text Fields (new, added 2026-04-08)
- **Shape:** `RectangleShape` (0dp radius)
- **Unfocused border:** `outlineVariant` color, 1dp
- **Focused border:** `outline` color (black), 2dp
- **Error border:** `outline` color (black), 2dp + error text below
- **Placeholder:** `bodyMedium`, `onSurfaceVariant` color. Disappears on focus (no floating label animation).
- **Input text:** `bodyMedium`, `onBackground` color

### Error Treatment (new, added 2026-04-08)
- **Style:** Monochrome. No red. Stays on-brand.
- **Format:** `! Error message text` (exclamation prefix)
- **Typography:** `bodySmall`, `onBackground` color
- **Position:** Below the offending field, 4dp gap
- **Field indication:** Border thickens to 2dp on the errored field

### Empty States
- `GallrEmptyState` component: `headlineSmall` message text, centered, with optional orange CTA button below (24dp gap).
- Empty states should have warmth, a primary action, and context. "No items found" is not a design.
- Recoverable catalogue failures are intentionally quieter than empty states: `bodyMedium` secondary text with a compact 44dp outlined Retry action. They must not read like a catastrophic full-screen error.

### Cards
- 1dp border (`outline` color), no shadow, no border radius
- Image fills top section, text content below with 8dp horizontal + 8dp vertical padding

### Hero card (For You top pick, added 2026-10-06)
- Only the top-ranked pick overall takes it, and it opens whichever group comes first; every other
  card stays a standard card.
- Cover in full colour at 4:3 above the text block, no wash. A `surfaceVariant` block at the same
  ratio stands in while the image loads or when there is none.
- Text block with 16dp padding: reason eyebrow (`labelMedium`), title `titleLarge` (max two lines),
  venue `labelMedium` and city `labelSmall` in `onSurfaceVariant`, hairline divider, then the date
  row with its accent status label. The bookmark heart sits top-right of the text block, never on
  the cover.
- 1dp `outline` border, 0dp corners, no accent beyond the heart and status roles the standard card
  already has.
- Press: the text block inverts like a no-image card and the cover takes a 50% background wash.

### For You entry (Featured tab)
- 1dp `outline` row with the `labelLarge` title and, below it, what waits inside. Once the list is
  personal the row cycles through the first three picks, one frame each: the reason (`labelMedium`,
  `onSurfaceVariant`) over the name (`titleSmall`), one line each so every frame has the same height.
  Frames crossfade (260ms) every 5s and a 2dp `activeIndicator` line runs along the bottom edge as
  the timing cue. With reduced motion or a screen reader the row stands still on a `labelMedium`
  teaser naming the top pick and how many picks follow it; on a cold start the teaser nudges toward
  saving; while nothing is ready the row is the bare label. Chevron on the trailing edge.
- The current pick's cover fills the row behind the text under the same wash the exhibition cards use
  at rest (50% white in light, 45% black in dark) and crossfades with the frames; when the row stands
  still it carries the top pick's cover. Text switches to the card's on-image colours only once the
  cover has loaded, so a missing image leaves a plain bordered row rather than unreadable text.

### Taste tags (For You)
- Under the basis line, a `labelSmall` "내 취향" label followed by up to four outline chips
  (`labelMedium`, 1dp `outlineVariant` border, 0dp, 8dp × 4dp padding, no fill, no accent) naming the
  taxonomy terms that recur across the exhibitions the visitor saved or visited. Terms come from the
  editor's reviewed metadata first and otherwise from the exhibition's own text; at most two per
  category lead so the row reads as a profile. The row disappears when nothing can be said. The same
  detected terms explain picks ("공통 주제: 정체성") weighted by how rare the term is in the
  catalogue, so a term on a third of the shows is never the reason.

### Route map panel (added 2026-10-07)
- At the top of a built route: a 220dp panel on the quiet Seoul style with a 1dp `outline` border and
  0dp corners. A 2dp black line follows the legs, stops are 10dp white circles with a black stroke
  and their number in `Noto Sans`, the origin is a 4dp black dot, and the camera fits the whole
  route with 24dp padding. The panel is a picture: no gestures, so the list keeps scrolling over it.
  Monochrome throughout; the map's own saved-pin orange does not appear here.
- The summary states the departure time (`10:53 출발 기준`) only when the route leaves later than
  the visitor planned, so the first stop is open on arrival; waiting at home is never route time.
  The same rule holds in the personal route composer: "HH:MM 출발" appears only when a starting
  location is known and leaving is later than now. The composer's panel may zoom out to city scale
  so stops across the metropolitan area stay in view.

### Route composer (personal routes, added 2026-10-08)
- Header: back arrow and "새 동선" (unsaved) or "동선 편집" (saved) in `labelLarge`, followed by
  `labelSmall` "공개됨 · 저장됨 / 저장 안 됨" once the route exists on the server. No header actions.
- Name field per Text Fields; an empty name is reported only after a save is tried, a name over 60
  characters while typing.
- Summary, unboxed and verdict first: `titleSmall` "모두 열림" (only when every stop is open) or
  "1곳 시간 충돌 · 3번"; `bodySmall` `onSurfaceVariant` distance · travel · total; `bodySmall`
  reference day ("내일 11:00 개관 기준 · 10:41 출발"). The text button "현재 위치에서 출발" sits below
  it and is hidden once location is permanently denied.
- Itinerary rows: 40dp number column, title `titleSmall`, venue `labelSmall`, leg and visit window
  `bodySmall`, hairline dividers. Status lines come from the shared label table: blocking lines are
  `bodySmall` `onBackground` with the "!" prefix and error semantics, and dim the title to
  `onSurfaceVariant`; "운영 시간 미확인" is informational `labelSmall` `onSurfaceVariant`. A stop
  reached after midnight reads "! 자정 이후 도착 · 18:00 마감", never a clamped clock time.
- Bottom bar above the navigation bar: one line (save error with "빼고 저장", "공유 준비됐어요", or
  the public note "공유하면 링크를 가진 누구나 이 동선과 작성자 이름(…)을 볼 수 있어요" / "저장됨 · 공개
  페이지에 반영"), then 저장 (outlined, "저장 중…" while saving) and 공유 (standard black). Both need
  two stops. Removal shows "삭제했어요 · 되돌리기" for five seconds (ten with a screen reader).

### My routes (added 2026-10-08)
- The MY tab's third section, "동선" / "ROUTES", sits beside 방문 and 팔로잉 with its count in the
  archive counts row (a saved route with unsaved edits counts once). It opens with the full-width
  outlined "+ 새 동선 만들기" button, matching "+ 지난 전시 추가", then the draft row and saved routes
  with their ⋯ menu. With no routes at all, the GallrEmptyState carries the orange "내 동선 만들기"
  CTA instead of the outlined button.
- The Map tab's route sheet shows the same list under a `labelLarge` "내 동선" heading with a
  "+ 만들기" text button. Every menu here and in the composer has a 1dp `outline` border.

### Reorderable lists (added 2026-10-08)
- Long-press the 44dp drag handle to lift a row; the held row gets a 2dp `interactionFeedback`
  border, the list auto-scrolls at its edges, and rows slide over 150ms. Under reduced motion or a
  screen reader rows snap instead of sliding.
- Haptics: a threshold tick on lift, a segment tick per position passed, a gesture-end tick on drop.
  The draft changes once, on drop.
- Every row has a 44dp ⋯ menu (위로, 아래로, 맨 위로, 삭제, offering only the moves that apply); the same
  actions are screen-reader custom actions, and a move is announced "N번으로 이동했어요".

### Route order diagram (web, added 2026-10-08)
- The shared route page draws "방문 순서" as an SVG in a 1px-framed square: dashed connectors in stop
  order, numbered 12px-radius circles on the paper colour, district labels, a north mark and a
  round-number scale. Monochrome in both themes; it carries `role="img"` with the order and districts
  as its label, and presentation attributes keep it legible without the stylesheet.
- The page follows the device theme with DESIGN.md's dark palette, sets a 640px column from 768px,
  and keeps "첫 전시 길찾기" sticky at the bottom below 768px.

### Route share card (added 2026-10-08)
- 1080×1920, paper #FFFFFF (light) or #121212 (dark), 0dp corners, no accent and no poster palette.
  Top to bottom: wordmark, "전시 동선" eyebrow, the route name (two lines, ellipsis), the numbered
  drawing in a bordered square, one line per stop "01 제목", "N곳 · 약 X KM", and the route link's
  QR on a white tile with a four-module quiet zone. The link is shared alongside the image.

### Public routes (추천 동선, added 2026-10-08)
- **Section.** In the Map tab's route sheet, after 내 동선, which is capped at three saved routes plus a
  "모두 보기" text row to MY → 동선. Heading "추천 동선" / "POPULAR ROUTES" in `labelLarge` with
  `heading()`, then the order note "최근 30일 복사 많은 순" in `labelSmall` `onSurfaceVariant`. The top
  three rows show; "동선 더 보기" / "접기" (44dp, expanded/collapsed state) expands in place to the ten
  fetched. While loading, three `SkeletonRow`s; loaded rows crossfade in (instant under reduced motion
  or a screen reader); an empty list hides the section; a failure shows the error with 다시 시도.
- **Rows.** No cards, rank numbers or icons: name in `titleSmall`, then `labelSmall` `onSurfaceVariant`
  "{first}–{last district} · {n}곳 · {에디터 | author} · 복사 {n}", adding "{M}월 {D}일부터" when the
  route works only from a later day. One district shows once; no author name or no copies in 30 days
  drops that part. 52dp minimum, hairline `outlineVariant` dividers, one merged Button node per row;
  name and line 2 wrap to two lines at large text.
- **Preview.** Back and a 44dp ⋯ ("더 보기" / "MORE", holding 신고), the name in `titleLarge` with
  `heading()` (two lines), the byline "에디터 · 4곳" in `labelMedium` `onSurfaceVariant`, "{M}월 {D}일
  기준" when stops are judged for a later shared day, then the composer's map panel, verdict-first
  summary and stop rows without drag handles or menus. The bottom bar holds one full-width standard
  black button: "내 동선으로 복사" ("복사 중…" while copying, busy state), or "내 동선에서 열기" on the
  reader's own route, which has no 신고. "! THIS ROUTE IS NO LONGER LISTED" is a polite live region.
- **Copy.** Replace-draft confirm first when the draft has unsaved stops; after the copy the composer's
  message line reads "복사했어요 · 저장하면 내 동선에 남아요" until the first save or edit.
- **Report sheet.** Bottom sheet titled "이 동선을 신고하는 이유" in `titleMedium`; four radio-style
  option rows in a `selectableGroup` (the orange selection bar marks the choice); standard black
  "신고하기", disabled until a reason is chosen, "보내는 중…" while sending. Success snackbar
  "신고했어요"; the ⋯ item then reads "신고함", disabled.
- **Listing from 내 동선.** The row ⋯ adds 공개 목록에 올리기 / 공개하고 목록에 올리기 / 목록에서 내리기
  (and 수정하기 / 다시 요청 after a decline), each through a consent dialog in `ReplaceDraftDialog`'s
  style. Row line 2 adds the listing status ("목록 검토 중", "목록 승인됨", …) and an optional line 3
  gives one reason, never truncated. Small actions disable while running and fail to a snackbar with
  다시 시도.
- **No accent.** No `ctaPrimary` anywhere in public routes; English follows the route screens'
  casing (uppercase labels, sentence-case explanations).

### Avatar
- `CircleShape`, 72dp on profile screen
- Background: `surfaceVariant`
- Content: first letter of display name, `headlineSmall`, `onSurfaceVariant`
- Edit state: camera icon overlay at bottom-right corner

### Selection (option rows and count chips)
- Selected option row (radio-style lists such as the route planner's curation mode): 3dp
  `activeIndicator` bar on the row's leading edge, full row height. No check mark glyph.
- Selected count chip (route planner stop count): `activeIndicator` fill, 1dp `activeIndicator`
  border, `ctaContent` (black) text, the same AA rule as the primary CTA. Unselected: 1dp
  `outlineVariant` border on the background colour.
- Curation badges: the Featured tab omits the Featured badge (its premise is curation) and keeps the
  editor's pick badge; the For You list shows no badges at all, because each card's eyebrow states
  its reason.
- For You cards: the reason is the card's eyebrow (`labelMedium`, no "추천 이유" prefix) above the
  title. Cards sit in two labelled groups, personal matches first, then editorial and timing picks,
  and the screen opens with one `labelSmall` basis line (saves · visits · follows · on-device).
- Disclosures for estimates stay in the wording of the figure itself ("예상", "~"); no standalone
  warning lines under a summary.

### Navigation
- 4-tab bottom navigation: Featured | List | Map | Profile
- Active tab: `activeIndicator` (#FF5400) underline
- Inactive tab: `onSurfaceVariant` color

## Auth Screen Hierarchy (added 2026-04-08)

### Sign-In Screen Layout (top to bottom)
1. Brand: "gallr" in `displayLarge`
2. Tagline: "discover exhibitions through taste" in `bodyMedium`, `onSurfaceVariant`
3. 64dp spacer
4. Email text field (outlined, sharp rectangle)
5. Password text field (with show/hide toggle)
6. Primary action button: "Sign In" (black fill, full width, 52dp height)
7. Toggle link: "Don't have an account? Sign Up" (`bodySmall`)
8. "Forgot password?" link (`bodySmall`, `onSurfaceVariant`)
9. Divider: "or continue with" (`outlineVariant` line, `bodySmall` text)
10. "Continue with Google" button (black fill)
11. "Continue with Apple" button (black fill)

### Sign-Up Mode
- Button text changes to "Sign Up"
- Toggle: "Already have an account? Sign In"
- "Forgot password?" hidden
- Instant swap, no animation

### Verification Screen
- "gallr" brand at top
- Mail icon or text
- "Check your email" in `headlineSmall`
- "We sent a verification link to [email]" in `bodyMedium`, `onSurfaceVariant`
- "Didn't receive it? Resend" text button
- "Back to Sign In" outlined button

## Accessibility
- Touch targets: minimum 44dp height on all interactive elements
- Password toggle: content description "Show password" / "Hide password"
- Avatar edit: content description "Change profile photo"
- Error messages: semantics role = error for screen readers
- Material3 components provide built-in accessibility labels

## Decisions Log
| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-03-27 | Monochrome + single orange accent | Gallery-wall aesthetic, art is the focus |
| 2026-03-27 | Inter + Gothic A1 fonts | Bilingual Korean/Latin support |
| 2026-03-27 | 0dp border radius everywhere | Sharp, editorial, anti-generic |
| 2026-03-27 | 8pt spacing grid | Standard, predictable, flexible |
| 2026-04-08 | Custom text fields (sharp, no floating labels) | Match monochrome system, avoid Material3 rounded defaults |
| 2026-08-08 | Black text on orange primary CTA | Meets WCAG AA contrast in light and dark themes |
| 2026-08-08 | Quiet catalogue recovery state | Temporary loading failures should remain actionable without visually alarming the user |
| 2026-04-08 | Monochrome error treatment (! prefix, no red) | Stay on-brand, avoid introducing a third color |
| 2026-04-08 | Email above OAuth on sign-in screen | Email is the new feature, matches test account goal |
| 2026-04-08 | No toggle animation (instant swap) | Consistent with sharp, minimal aesthetic |
| 2026-04-08 | Avatar: letter initial + camera icon overlay | Clear edit affordance, personal before upload |
| 2026-06-08 | Sanction functional motion (crossfade, progress cues, auto-cycling event surfaces, gated by reduced-motion) | Communicates liveness for multi-event promotion; aligns the doc with shipped patterns |
| 2026-08-10 | Orange saved-exhibition map pins | Makes personal saves identifiable in All while preserving black for the general catalogue |
| 2026-08-23 | Web exhibition imagery in full colour; monochrome only once a run has ended | The artwork is the subject, and desaturation reads as a status signal rather than decoration |
| 2026-08-25 | Published exhibition QR exports may inherit a scan-safe poster palette | Makes each gallery's physical QR feel native to its exhibition while keeping portal UI monochrome and the code reliably scannable |
| 2026-09-23 | Exhibition share cards inherit the poster palette (paper wash, swatches, poster QR) | A shared image should feel like the exhibition it promotes and still lead back to gallr; the app UI itself stays monochrome |
| 2026-10-06 | Orange selection bar and fill instead of check marks; no standalone disclosure lines; Featured badge omitted where implied | Selection reads at a glance through the one accent, and repeated or implied text was noise on the planner and Featured tab |
| 2026-10-06 | Hero card for the For You top pick; the Featured entry previews that pick | One unwashed cover gives the list a focal point, and the entry says what waits inside instead of a bare label |
| 2026-10-07 | Route drawn on a static map panel; departure shifts to the first opening; taste tags from the catalogue taxonomy; the For You entry cycles its picks | A route is a shape before it is a list, a route cannot start before the venue opens, taste is easier to trust when it is named, and the one sanctioned motion on the Featured tab now carries the reasons |
| 2026-10-08 | Personal route composer: verdict-first summary, shared status labels, held-row orange border with a 150ms slide that snaps under reduced motion, ⋯ menu as screen-reader actions; monochrome route card and web order diagram | Authors fix conflicts by reading one line first; the app and the shared page must word a stop's status identically; reordering must work without dragging; a route shared outside the app carries no accent (design review DR-D5–DR-D31, Pass 5) |
| 2026-10-08 | Routes become a MY tab section (동선) beside 방문 and 팔로잉, with a count and "+ 새 동선 만들기" | Making routes is a core feature, so an author's routes live with their other records instead of only inside the Map tab's route sheet (owner decision) |
| 2026-10-08 | Public routes: 추천 동선 as plain ranked rows (top three, expand to ten) under a stated order, a read-only preview reusing the composer's map and rows, a black copy button, and a radio-row report sheet | Readers judge a route by where it goes and who made it, not by decoration; reusing the composer's surfaces makes a copy feel like opening your own draft; the accent stays reserved (design review DD1–DD22, D24–D28) |
