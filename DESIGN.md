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
The Instagram carousel exported from an approved curator's note (Admin, spec 090) follows the same
exception at 1080×1350 with 72px margins. Its structure follows the Korean art magazines the owner
named as references on 2026-10-13: a cover line over the picture, one numbered point per slide, the
facts gathered once.
- **Cover.** The poster full-bleed under a bottom wash of the darkest palette tone. From the bottom
  up, in white: the exhibition's name and `VENUE · dates` (caption), the hook (title, at most four
  lines; the writing guide aims for two), and the section label directly above the hook.
- **Insight slides.** A picture in a 480px band at the top, its lower 120px washed into the paper.
  When the exhibition has gallery images under Media, the insights take them in order, centred,
  one each (four at most); an insight with none left shows its own slice of the cover (evenly
  spaced along it). An insight keeps its picture across its slides. On the paper (the lightest palette tone that keeps 7:1 against the darkest, else white;
  the darkest tone as ink): the label with the insight's number (`큐레이터의 시선 01`), the five
  swatches, the insight's opening sentence as a headline (title, at most three lines), then the
  rest as body. An opening sentence longer than three headline lines is set as body.
- **Picture slides** (added 2026-10-14). A gallery image the insights do not use gets a slide of
  its own: the picture full-bleed under a scrim of that picture's darkest palette tone, clear
  until 240px above the text, 82% at the text's top edge and 92% at the foot, and its alt text in
  white as the caption (caption size, at most four lines) stacked up from the footer. With no alt
  text the scrim's text edge is the footer's top, so the wordmark and the slide number still read.
  No label and no swatches: the picture is the slide. One follows each insight that has slides, in
  gallery order, and the rest come before the closing slide. A carousel never runs past ten
  slides, so it draws eight gallery images at most; a caption longer than four lines is reported
  to the editor, not shrunk, and so is a gallery image that could not be loaded.
- **Closing slide.** White paper: the label, the poster as a 400×533 tile, beside it the
  exhibition's name (body, Medium) over the dates, venue · district and hours (under the tile when
  they run long), the viewing tip, and the QR on a white tile bottom-right; the tip wraps clear of
  the QR and runs to the footer.
- **Caption.** The hook; the name, venue · district, dates and hours on their own lines; `관람
  포인트` with each insight's opening sentence numbered ①–④; the tip; `사진 · …` / `Photos · …`
  naming each credit of the pictures drawn (cover, insight and picture slides), once; hashtags from the venue, district and reviewed
  terms. No emoji and no call to action. Credits go in the caption, not on the slides, because
  the export has no type size small enough for a credit line.
An exhibition without an approved note exports the same carousel without the note (spec 090
FR-015c): the label reads `전시` / `EXHIBITION`, the exhibition's name is the cover line (title, at
most four lines) over venue · dates, the gallery images follow as picture slides, and the closing
slide carries the facts and the QR without a tip. Its caption is the facts, the credits and the
hashtags.
A roundup carousel gathers two to ten exhibitions (Admin, spec 090 FR-015a and FR-015c) at the same size and
margins. Every picture slide is full-bleed under a scrim of that picture's darkest palette tone,
clear until 240px above the text, 82% at the text's top edge and 92% at the foot, with white text
stacked up from the footer.
- **Cover.** The first exhibition's picture; the section label, the editor's cover line (title, at
  most four lines) and `전시 6곳` / `6 exhibitions` (caption).
- **Exhibition slides.** That exhibition's picture; its place `01 / 06` as the label, its name
  (title, at most three lines), venue · district and dates (caption), and the note's hook (body,
  at most four lines) when the exhibition has an approved note. A roundup with any exhibition
  without one carries the `전시` / `EXHIBITION` label instead of the curator's-eye label.
- **List slide.** White paper in the first picture's ink: the label, then each exhibition's number
  and name (caption, Medium) over venue · district · dates (caption), 16px apart; it continues on
  a second slide when it runs past the footer.
- **Caption.** The cover line; a block per exhibition numbered ①–⑩ with its name, venue ·
  district and dates; hashtags from the venues and districts, fifteen at most.
Export type sizes are fixed and never shrunk: title 64px (1.15 line height), body 44px (1.4),
caption 32px; text that does not fit continues on the next slide, and after the fourth insight
slide it is reported, not squeezed. No accent anywhere on the slides.

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
  - Auto-advancing carousels (Home event pager, List banner, Map FAB) — disabled when the OS signals
    reduced motion or a screen reader is active (see Accessibility). The home hero pager and the rails
    never advance on their own: the visitor reads them, and the counter says where they stand.
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

### Home tab (added 2026-10-10, recomposed 2026-10-12)
The first tab is a magazine page of image-led cards and horizontal rails, not a vertical stack of
cards. Top to bottom: the city-wide event pager when an event runs, the greeting, the leading
banner, the curator's eye, the routes getting attention, the remaining rails, the curation grid and
the mood and theme rail. A section appears only when it has content.

- **Home visual system v2 (Option A, owner decisions 2026-10-11 and 2026-10-12, spec 091).** On the
  Home tab only: every card and tile uses a 16dp corner radius, and the small pieces on a card
  (counter pill, chips, stop covers) a 12dp corner radius; covers are clipped to the card's shape
  inside a 1dp `outlineVariant` border. Captions over a cover are white on a black scrim: the scrim
  reaches 0.55 alpha at the caption's edge and holds it under the caption, which keeps white text at
  4.5:1 over any cover in both themes, and fades out over 30% of the cover beyond that edge,
  starting no later than 44% from the top for a caption at the bottom. Pressing a banner deepens the
  scrim to 0.70 alpha over 100ms, with no animation under reduced motion. There is no blur, no
  translucent surface and no tint: the tab bar is solid on every tab. Every other tab keeps 0dp
  shapes. The values live in `HomeVisualTokens`.
- **Greeting.** A `displayMedium` title that addresses a signed-in visitor by name (`하신 님, 이번 주
  볼 만한 전시` / `This week for Hanshin`) and otherwise names the city. No date line above it (owner
  decision, 2026-10-11): the phone already shows the date. The name comes from the profile; it is
  never guessed from an email.
- **Leading banner.** One exhibition at a time on a 3:4 cover about three quarters of the screen
  wide (16dp margins, the next card peeking 64dp, 8dp apart). On the cover: a `01 / 06` counter
  (`labelMedium`, white on a black 0.55 pill) top-left and the save heart on a 44dp white circle
  top-right. On the scrim, bottom-left: the reason line (`labelMedium`, white, after a 6dp white
  dot), the title (`titleLarge`, white, two lines) and `venue · dates` (`labelMedium`, white, one
  line). It never auto-advances. Without a cover the `surfaceVariant` block carries the same scrim
  and caption. When the visitor's picks rest on their own saves, visits or follows, the banner is
  내 취향 / FOR YOU under its section header (subtitle states the basis, action to the For You
  screen), followed by a row of up to five taste chips (`labelLarge` on a 1dp `outlineVariant`
  outline, 12dp corners) after a `labelMedium` 내 취향 / YOUR TASTE label. Otherwise the banner is
  the featured list with 이번 주 추천 / THIS WEEK'S PICKS as its reason line, and 내 취향 stays a rail.
- **Curator's eye (added 2026-10-11, spec 090; restyled 2026-10-12; caption card 2026-10-13).**
  `큐레이터의 시선` / `CURATOR'S EYE` follows the banner when at least one approved curator's note is
  about a running or upcoming exhibition: a `HorizontalPager` of 3:4 caption cards, one centred with
  28dp of each neighbour showing, 8dp apart. The top 54% of the card is the cover in full colour, no
  scrim; while the show closes within 14 days a `D-9` / `D-DAY` pill (`labelMedium`, black on a
  white 12dp pill) sits at the cover's top-left. Under it, on the `background` surface with 16dp
  padding: the hook (`titleMedium` Bold, two lines), one insight line (`bodyMedium`, two lines),
  then at the bottom the exhibition's name (`bodyMedium` Medium, one line) and a facts line
  (`labelMedium`, `onSurfaceVariant`, one line): venue · district · dates · `노트 3단락` /
  `3 insights`. No byline or drafting line (decision 2026-10-13). Under the carousel, centred: 6dp
  dots 6dp apart in `outlineVariant`, the current page an 18dp pill in the active indicator colour,
  shown for at most ten cards and changing with the settled page without animation. One merged
  semantics node per card reads the section, hook, insight, exhibition, facts, badge and position.
  Newest approval first. No save heart: the card is a door to the note, not a catalogue card.
- **Section header.** A `titleMedium` Bold title that names what the section is (`에디터 추천`,
  `EDITOR'S PICKS`), an optional `bodySmall` `onSurfaceVariant` line under it, and an optional
  trailing `labelMedium` text action (`모두 보기 ›`) on a 44dp target. Headings carry `heading()`
  semantics.
- **Routes getting attention.** `지금 주목받는 동선` / `ROUTES GETTING ATTENTION`: up to three public
  routes from the 추천 동선 ranking as compact 262dp cards (16dp padding, `background` surface,
  8dp between lines): who made it (`labelMedium`; `에디터 · <이름>` in `onBackground` Bold for an
  editor, otherwise the author's name in `onSurfaceVariant`) with `복사 24회` / `24 COPIES` on the
  trailing edge when the route was copied in the last 30 days; the route's name (`titleMedium` Bold,
  one line); `N곳 · 첫 구 → 마지막 구` (`labelMedium`, `onSurfaceVariant`); and up to four stop
  covers at 40dp with 12dp corners, then `+N` on a `surfaceVariant` square for the rest. A route
  whose stops could not be read shows no covers. Tapping opens the public route preview.
- **Rails.** A `LazyRow` of 184dp portrait cards, 8dp apart, 16dp margins so the next card peeks.
  Each card: a 3:4 cover in full colour (no scrim), then an 8dp text block with the eyebrow
  (`labelSmall` `onSurfaceVariant`; the reason on the For You rail; rails without reasons reserve no
  eyebrow line), the title (`titleSmall`, two lines, fixed to two so every card is the same
  height), the heart, the venue `labelSmall`, and one footer line: the accent status label when the
  run is about to start or end, otherwise the dates. 1dp `outline` border. Rails: 내 취향 / FOR YOU
  when it does not lead (top six picks, on a cold start minus what the banner and the editors'
  rail already show), 이번 주 추천 / THIS WEEK'S PICKS when 내 취향 leads, 에디터 추천 / EDITOR'S
  PICKS, and 팔로우한 갤러리 / GALLERIES YOU FOLLOW. Rails without reasons of at most ten cards
  show 6dp dots under the rail, the first fully visible card in `onBackground`, the rest
  `outlineVariant`, with no animation.
- **Curations.** `큐레이션` / `CURATIONS`: tiles two to a row, 8dp apart, slightly taller than
  wide. A cover borrowed from the collection's exhibitions in full colour under a bottom scrim, the
  headline in white `titleSmall` Bold (two lines) and a white `labelMedium` line that says what
  gathers the exhibitions and how many. In order: 지금 가장 많이 본 / MOST VIEWED NOW (`이번 주 기준
  · N개 전시`; the exhibitions opened most in the app over seven days, shown from three), 이번 주
  마감 / CLOSING THIS WEEK, 이번 주 개막 / OPENING THIS WEEK and 무료 관람 / FREE ADMISSION (`에디터
  확인 · N개 전시`; only exhibitions an editor or the gallery tagged free, shown from two).
  No two tiles borrow the same cover.
  Tapping opens the collection as a plain list of the standard cards.
- **By mood and theme.** `상황별·주제별` / `BY MOOD AND THEME`: a rail of 156dp tiles (156:200). First
  the situations enough exhibitions answer, in a fixed order: 조용히 보고 싶은 날 / A QUIET VISIT,
  사진 찍기 좋은 공간 / SPACES TO PHOTOGRAPH, 둘이서 천천히 / SLOWLY, FOR TWO, 아이·가족과 함께 /
  WITH CHILDREN AND FAMILY, 기분 전환이 필요할 때 / FOR A LIFT and 낯선 감각을 찾는다면 / SOMETHING
  STRANGE. A situation is a bundle of art terms (moods, styles, media) read from the exhibition's
  reviewed metadata or its own text, shown from three exhibitions, and its tile names those signals
  (`#고요함 #미니멀 · 11개 전시`) so it never claims more than the terms say. A situation that judges
  the visitor rather than the work is never inferred: 아이·가족과 함께 gathers only exhibitions an
  editor or the gallery tagged for it. Then the two busiest districts and the
  two most specific recurring art terms that no shown situation already covers; a term on a third
  of the catalogue or more is never a theme. A borrowed cover under one even 0.55 scrim, the
  headline top-left in white `titleMedium` Bold (two lines) and the hashtag line bottom-left in
  white `labelMedium`.
- **Large text.** A scrim's full-strength edge follows the measured caption, so a caption that
  grows with the font scale stays on the scrim; rail cards widen with the font scale up to 1.6×,
  route cards up to 1.3× and theme tiles up to 1.4×, so titles keep their lines; tile subtitles may
  take two lines. Nothing shrinks below the type scale to fit.
- **Accent.** On Home the accent appears only as the active indicator (the current story dot), the
  status labels on rail cards and a saved heart. Reason dots, the editor mark, counters and tiles
  are monochrome.
- **Exhibition gallery (detail screen, added 2026-10-14).** The top of the exhibition page is the
  cover in a 16:9 frame. When the exhibition has gallery images it becomes a swipeable pager in
  the same frame: the cover first, then the gallery in staff order (twelve at most), each cropped
  to the frame on `surfaceVariant` while loading. A counter `2 / 5` (`labelMedium`, white on 60%
  black, 0dp, 8dp × 4dp padding) sits 8dp inside the bottom-right corner and is read aloud as
  `이미지 5장 중 2번째` / `Picture 2 of 5`. The current picture's credit, when the source gives one,
  is under the pager in `labelSmall`, `onSurfaceVariant`, at the screen margin. No dots, arrows or
  auto-advance. An exhibition with only a cover looks as before, with no counter.
- **Curator's note block (detail screen, added 2026-10-11; thumbnail header and fact chips
  2026-10-13).** After the description, under a 1dp `outlineVariant` rule: a header row of the
  cover as an 88dp square (0dp, `surfaceVariant` while loading, nothing without a cover) and beside
  it the label `큐레이터의 시선` (`labelLarge`, `onSurfaceVariant`, heading semantics) over the hook
  (`titleLarge`); then the insight paragraphs (`bodyLarge`), each under its own picture when the
  exhibition has gallery images (added 2026-10-13: the picture in the same place as the insight,
  full width, 0dp, cropped into a frame that follows its shape between 3:4 and 16:9, `surfaceVariant`
  while loading, its alt text read aloud; the source's credit under it in `labelSmall`,
  `onSurfaceVariant`, 4dp below; 8dp to the paragraph; four pictures at most; an insight without
  one is just its paragraph); `관람 팁 · …` in `bodyMedium` Medium, and
  a scrolling row of outline chips (`labelMedium`, 1dp `outlineVariant`, 0dp, 8dp × 4dp padding):
  `10.31까지 · D-18` / `Until Oct 31 · D-18` (the badge only within 14 days), the hours on one line,
  and venue · district. The block carries no byline or drafting line (decision 2026-10-13). Nothing
  renders without an approved note, never a placeholder; opening from the carousel scrolls the block
  to the top.

### Taste tags (For You)
- Under the basis line, a `labelSmall` "내 취향" label followed by up to four outline chips
  (`labelMedium`, 1dp `outlineVariant` border, 0dp, 8dp × 4dp padding, no fill, no accent) naming the
  taxonomy terms that recur across the exhibitions the visitor saved or visited. Terms come from the
  editor's reviewed metadata first and otherwise from the exhibition's own text; at most two per
  category lead so the row reads as a profile. The row disappears when nothing can be said. The same
  detected terms explain picks ("공통 주제: 정체성") weighted by how rare the term is in the
  catalogue, so a term on a third of the shows is never the reason.

### Map pins (thumbnail pins, added 2026-10-13)
- **Shape.** A pin is the gallr logo (`drawable/logo.xml`): its ring, 48dp across with the tip on the
  place, and the exhibition's cover cropped into the logo's opening. A 1.3dp white edge outside the
  ring keeps it readable on any map colour. The logo shape is the one non-rectangular mark on a map,
  like the avatar circle elsewhere; it is drawn from the logo's own path, never approximated.
- **Ring.** Black, or the active indicator orange for a saved exhibition (the existing saved-map
  role of the accent; no other accent on the map). Saved pins draw on top where pins overlap.
- **No cover yet, or none at all.** The same pin with a white opening. It also stands under every
  pin while its cover loads, so the map is never empty.
- **Groups.** Exhibitions within 28dp of each other share one pin that pictures the first of them
  with a cover, with the count on a 9dp white badge (1dp black stroke, 11sp `Noto Sans`) over the
  ring's upper right. The group's ring is orange when any of its exhibitions is saved.
- **Titles.** A single exhibition's title sits 0.7em under the tip (12sp, black on a 1dp white
  halo). Titles lie under the pins and yield to them: a pin always shows, a title only where it does
  not collide with a pin or another title.

### Route map panel (added 2026-10-07, pins 2026-10-13)
- At the top of a built route: a 220dp panel on the quiet Seoul style with a 1dp `outline` border and
  0dp corners. A 2dp black line follows the legs, each stop is a map pin picturing its exhibition at
  three quarters of the Map tab size (36dp across) with its number on an 8dp white badge, the origin
  is a 4dp black dot, and the camera fits the whole route with 24dp padding below 32dp of headroom
  for the pins. The panel is a picture: no gestures, so the list keeps scrolling over it.
  Monochrome throughout apart from the covers; the map's own saved-pin orange does not appear here.
- Walking paths (added 2026-10-14, spec 093): a leg between two venues follows the streets as a solid
  line. A leg that is only an estimate, always including the leg from the visitor's own position
  (which never leaves the device), is a 2dp black dashed line (2 on, 2 off) drawn straight. The
  panel carries a `labelSmall` "© OpenStreetMap" credit on a translucent white strip at its bottom
  left and a 44dp expand control at its top right: a white square with a 1dp black border and four
  corner brackets, "지도 크게 보기" / "EXPAND MAP".

### Full-screen route map (added 2026-10-14, spec 093)
- Opened from the panel's expand control on the route planner, the composer and a public route's
  preview; drawn over that screen, so closing returns to it exactly as it was. Same pins, lines and
  origin dot as the panel, on `background` within the safe area.
- Pinch, double-tap and drag zoom and pan; north stays up (no rotation, no tilt); zoom 10 to 18.
- Controls are the Map tab's 44dp white squares with a 1dp black border and 0dp corners: close (✕)
  at the top left, "경로 전체 보기" / "FIT ROUTE" (corner brackets) at the bottom right, which
  returns to the whole route. System back also closes.
- The credit sits at the bottom left like the Map tab's: "© OpenFreeMap · © OpenStreetMap", plus
  "© openrouteservice by HeiGIT" whenever a walking path is shown; tapping it opens the OpenStreetMap
  copyright page. The map is described for screen readers as "경로 지도 · 정류장 N개 · 약 X KM".
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
- **Section.** On the MY tab's 동선 section, after 내 동선 (since 2026-10-11, when the Map tab's
  동선 만들기 button was removed; the route planner sheet still shows it after its own 내 동선, which
  is capped at three saved routes plus a "모두 보기" text row to MY → 동선). Heading "추천 동선" / "POPULAR ROUTES" in `labelLarge` with
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
- Curation badges: the home tab's hero and rails carry no badges, because each section already says
  why a card is there; the For You list shows none either, because each card's eyebrow states its
  reason. Standard cards elsewhere keep both badges, omitting the Featured badge only where a
  surface's premise already implies it.
- For You cards: the reason is the card's eyebrow (`labelMedium`, no "추천 이유" prefix) above the
  title. Cards sit in two labelled groups, personal matches first, then editorial and timing picks,
  and the screen opens with one `labelSmall` basis line (saves · visits · follows · on-device).
- Disclosures for estimates stay in the wording of the figure itself ("예상", "~"); no standalone
  warning lines under a summary.

### Gallery Avatar
- Square (`RectangleShape`, never `CircleShape`), 56dp, 1dp `outline` border
- Content: staff-curated gallery logo or space photo (pre-normalized 512px square), cropped to fill
- Fallback: first three characters of the gallery name in `labelMedium`, always drawn underneath, so it shows while loading, on error, and for galleries without an image
- Never an exhibition image. Attribution credit, when required, appears on the gallery detail screen in `labelSmall`, `onSurfaceVariant`

### Navigation
- 4-tab bottom navigation: Home | List | Map | Profile (홈 · 목록 · 지도 · MY)
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
| 2026-10-10 | Home tab rebuilt as rails: dated greeting by name, a 4:5 hero pager with a numeric counter and peek, For You / editors' / followed-gallery rails, themed collection tiles, and a native grain-wash shader instead of flat scrims; the For You entry row is retired | The owner's references favour horizontal carousels with position feedback, sections that name themselves, personal address and themed collections over a vertical card stack; the shader keeps covers legible without a flat wash and stays within the monochrome, 0dp system. Rounded corners and translucent "glass" surfaces from the references were considered and not taken: sharp rectangles remain the system's signature |
| 2026-10-11 | Home tab moves to Option A: 16dp corners, progressive-blur caption bands with a fixed darkening floor, a faint poster tint, a backdrop-blurred tab bar, rail position dots and a routes-getting-attention rail | The owner reviewed three prototype directions and chose the softer, more native-feeling Home for the first screen; this supersedes the 2026-10-10 rejection of rounded corners and translucent surfaces for the Home tab only, and sharp rectangles stay the system's signature everywhere else until a separate decision |
| 2026-10-12 | Home tab recomposed to the Option A artboard: image-led banners and tiles with white captions on a black scrim, 내 취향 as the leading banner with taste chips, story cards with centred dots, compact route cards with stop covers, a curation grid with 지금 가장 많이 본, and a rail of situations, neighbourhoods and themes; blur, tint and the translucent tab bar are withdrawn | The owner compared the first build with the artboard and found it off: it had kept the old hero and layered blur on it. Glass was not required, so captions rely on a scrim with a fixed floor instead; this also removes a blur dependency that required a Compose version the map library could not run on. Home tab only, as on 2026-10-11 |
| 2026-10-11 | Curator's notes: an automatically drafted, editor-approved note per exhibition, shown as the 큐레이터의 시선 carousel on the home tab and a labelled block on the detail screen, each naming the reviewing editor with "AI 초안 · <이름> 검수"; an Instagram carousel export in Admin under the exported-content exception | Readers get a curator's reason to go, not a summary; nothing reaches them before a named editor signs it, and the disclosure is short and specific because audiences distrust unlabelled generated text (owner decision A, spec 090 research) |
| 2026-10-13 | Curator's notes carry no drafting or review line anywhere a reader sees them: `AI 초안 · <이름> 검수` leaves the detail block, `<이름> 검수` leaves the Home story cards, the Home subtitle no longer says 검수, and the Instagram export's closing slide drops its disclosure line | Owner decision: the line made the notes read as machine output. Supersedes the disclosure rule in the 2026-10-11 curator's notes row. Notes are still published only after an editor approves them, and Admin still records who approved |
| 2026-10-13 | Map and route pins become the gallr logo with the exhibition's cover in its opening; saved exhibitions keep the orange ring, groups a count badge, route stops a number badge | Owner request: a pin should show what is there, in the brand's own mark, instead of an anonymous marker. The logo ring keeps the monochrome system and the single accent role |
| 2026-10-13 | Saving an exhibition never re-ranks a recommendation list that is on screen; the new ranking appears when the reader returns to Home from another tab, opens 모두 보기, or pulls to refresh | Owner feedback: the card under the finger disappeared and the whole list reshuffled, which read as a glitch |
| 2026-10-13 | Curator's eye cards become caption cards: the cover on top in full colour with a closing badge, the hook, one insight, the exhibition's name and a facts line on a white panel; the detail note block gets a cover thumbnail beside its heading and fact chips after the tip | Owner chose direction B of three (poster-first, caption card, spread) on the design canvas: the card should say why and when on its own, and the page the tap opens should keep the same picture. Readers only ever get one image per exhibition, so no new data is needed |
| 2026-10-13 | The Instagram carousel is restructured after the owner's reference magazines: the section label sits on the hook, each insight is numbered and opens with its first sentence as a headline, each insight shows its own slice of the poster, the closing slide leads with the exhibition's name, and the caption becomes hook, facts, numbered viewing points, tip | Owner supplied eight magazines and seventeen posts as the reference. What they share is one idea per slide in two or three large lines, a small tag over the cover line, and the who/where/when gathered in one block; what gallr does not take from them is emoji, sticker labels, the conversational register and mood or season hooks |
| 2026-10-13 | Roundup carousels: one carousel from two to ten approved notes, a picture slide per exhibition with its name, place, dates and the note's hook, then a list slide | Eight of the owner's seventeen reference posts are roundups (closing this month, one neighbourhood, one theme) and the export made only single-note carousels; the owner asked for the roundup to complete the feature. The editor writes the cover line, so mood or urgency there is the editor's call on the day of posting, not something a stored note carries |
| 2026-10-13 | Each insight of the curator's note shows one of the exhibition's gallery images above it, with the source's credit, in the app and in the carousel | Owner asked why notes and carousels had no pictures beyond the cover and approved showing them. Admin already stored gallery images with alt text, credit and a rights link, but readers received only the cover and the export never read them. The picture goes with the insight in the same place so the two surfaces tell the note the same way; nothing is shown for an exhibition with only a cover |
| 2026-10-14 | The exhibition page pages through the cover and the gallery images at its top, with or without a curator's note | Testing on the emulator showed that pictures placed only inside the note were invisible for every exhibition without one, which is every exhibition in production today and any whose description is too thin to draft from. The owner chose a gallery on the page itself. A counter, not dots, because a detail screen keeps 0dp shapes and the count can reach thirteen |
| 2026-10-14 | The carousel gives every gallery image the insights do not use a full-bleed slide of its own, up to ten slides in all | The owner asked for carousels that carry more pictures, as the reference posts do, so that one exhibition makes a full post. Insight slides keep their text; the extra slides are pictures with the alt text as a caption, dealt between the insights so text and picture alternate |
| 2026-10-14 | Any published exhibition still on can be exported as a carousel, alone or in a roundup, with or without an approved note; without one the name is the cover line and the slides are its pictures and facts | Owner asked for carousels like the reference magazines for every exhibition or a chosen set. Most reference roundups print name, venue and dates only, and production had no approved note, so the export had nothing to offer |
| 2026-10-14 | Route maps follow real walking paths between venues (dashed where only an estimate, always for the leg from the visitor's position), and every route map opens full screen with zoom, pan, fit route and close | The owner found straight lines untrustworthy and the small panel unreadable. Paths come from free OpenStreetMap routing computed once per venue pair; the visitor's position never leaves the device, so that leg stays an honest dashed estimate. The panel stays a picture so the page still scrolls; the full-screen map is an overlay so closing loses nothing (spec 093) |
