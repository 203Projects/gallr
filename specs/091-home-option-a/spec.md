# Feature Specification: Home tab visual system v2 (Option A)

**Feature Branch**: `091-home-option-a`
**Created**: 2026-10-11
**Status**: Draft
**Input**: User description: "Home tab visual system v2 (Option A): on top of the 1.12.0 Home tab (rails, hero pager, themed collections, grain-wash shader), move the Home tab to the owner-approved Option A direction from the prototype canvas (https://claude.ai/artifact/VPDymUKaeAAoEQSJ5UKSVt): 16dp rounded corners on Home cards and collection tiles, progressive-blur captions over covers instead of a flat wash, a faint poster-colour tint per card derived from the cover, a bottom tab bar with backdrop blur under it (solid-neutral fallback), carousel position feedback kept (numeric counter on the hero, dots on editorial rails), and a new 지금 주목받는 동선 rail fed by the public routes ranking that opens the existing public route preview. Native on Android and iOS, graceful degradation without runtime blur, reduced motion respected, 4.5:1 text contrast, bilingual KO/EN. DESIGN.md gains a Home token set and a Decisions Log row dated 2026-10-11 superseding the 2026-10-10 rejection of rounded corners and translucent surfaces for the Home tab only. Out of scope: 아트 스토리 editorial content type, new catalogue fields, a readable popularity aggregate. Owner decision D5 on 2026-10-11."

## Context

The Home tab shipped in 1.12.0 as a magazine page: the city event pager, the greeting, the 4:5 hero
pager with a `01 / 06` counter, the 큐레이터의 시선 carousel (spec 090, on `develop` since
2026-10-12), the 내 취향 / 에디터 추천 / 팔로우한 갤러리 rails and the 테마로 보기 tiles, all with 0dp
corners and a dithered grain wash under captions. The owner reviewed three prototype directions on
2026-10-11 and chose Option A in full for the Home tab: softer card geometry, captions that sit on a
progressive blur of the cover instead of a wash, a faint tint drawn from each poster, a translucent
tab bar, and a rail that shows the routes other visitors are copying. Every other tab keeps the
sharp 0dp system until a separate decision.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Home reads as one soft, legible magazine page (Priority: P1)

A visitor opens the Home tab on either platform and sees the same page as today, but every card and
tile (hero, curator's eye, rail cards, collection tiles) has gently rounded corners, each caption
sits on a band that blurs and darkens the cover progressively toward the caption, each card carries a
faint tint taken from its own poster, and the bottom tab bar lets the page show through as a blur
beneath it. Text stays as readable as today on any cover, in light and dark themes, at any font
scale.

**Why this priority**: this is the owner's chosen direction for the app's first screen; without it
the feature does not exist. It changes no data and no navigation, so it can ship on its own.

**Independent Test**: open Home with the fixture catalogue on an Android 13+ device, an Android 12
device and an iOS simulator, in light and dark, at 100% and 200% font scale; every Home card shows
the rounded geometry, the caption band and the tint, and the tab bar shows the page beneath it (or a
solid neutral bar on Android 12); captions meet the contrast floor on every fixture cover.

**Acceptance Scenarios**:

1. **Given** the Home tab with at least one hero exhibition, **When** the page renders on a device
   with runtime blur, **Then** the hero card has 16dp corners, its caption sits on a progressive blur
   band whose text contrast against the band is at least 4.5:1, and a faint tint of the cover's
   dominant colour is visible behind the caption band but never on text.
2. **Given** the same page on a device without runtime blur (Android 12 and earlier), **When** it
   renders, **Then** every card keeps the 16dp corners and the tint, captions sit on the existing
   gradient wash, and the tab bar is a solid neutral surface; nothing is clipped or missing.
3. **Given** the page scrolls under the tab bar, **When** content passes beneath it, **Then** the bar
   shows a blur of that content with its labels at full contrast; with blur unavailable it stays
   solid.
4. **Given** the List, Map or MY tab, **When** any of them renders, **Then** every shape there is
   still 0dp and no blur or tint appears.
5. **Given** a cover that cannot be loaded, **When** a card renders, **Then** the card shows the
   existing `surfaceVariant` block with the caption, no tint and no blur band.

---

### User Story 2 - Routes getting attention on Home (Priority: P2)

Below 내 취향, a visitor sees a rail named 지금 주목받는 동선 / ROUTES GETTING ATTENTION with up to
three public routes ordered as the public ranking orders them (copies in the last 30 days). Each card
names the route, its author, the stop count and the first and last districts; tapping a card opens
the existing public route preview, from which the visitor can copy the route into their draft as
today.

**Why this priority**: the owner asked for "other people's routes or routes getting attention" on
Home; the public routes ranking and preview already exist (spec 089), so this is a new surface over
existing data and the second most visible change.

**Independent Test**: with three or more listed public routes in the fixture, Home shows the rail
with three cards in ranking order and a tap opens the preview of that route; with no listed routes
the rail is absent and the sections around it close up.

**Acceptance Scenarios**:

1. **Given** the public ranking returns three or more listed routes, **When** Home renders, **Then**
   the rail shows exactly three cards in that order, each with the route name, author display name,
   `N곳` / `N stops` and `첫 구 → 마지막 구`.
2. **Given** the ranking returns one or two routes, **When** Home renders, **Then** the rail shows
   those cards and no placeholder.
3. **Given** the ranking returns none, or the read fails, **When** Home renders, **Then** the rail is
   absent, the rest of Home is unaffected, and a failed read is logged without an error shown.
4. **Given** a visitor taps a route card, **When** the preview opens, **Then** it is the same preview
   the Map tab's 추천 동선 opens for that route, with copy and report as today; the Home tab is
   restored on back.
5. **Given** a signed-out visitor, **When** Home renders, **Then** the rail still shows (the ranking
   works without an account) and copying from the preview asks to sign in as today.

---

### User Story 3 - Carousels keep saying where you are (Priority: P3)

The hero keeps its numeric `01 / 06` counter; the curator's eye keeps its counter; horizontal rails
with a fixed, small set of cards (에디터 추천, 지금 주목받는 동선, 팔로우한 갤러리) show a row of dots
under the rail that marks the first fully visible card, so a visitor knows how much is left without
scrolling to the end.

**Why this priority**: the owner's references all carry position feedback; the counter exists, so
this adds the rails' share of it.

**Independent Test**: scroll each rail; the highlighted dot follows the first fully visible card and
the dot count equals the card count; the hero's counter is unchanged.

**Acceptance Scenarios**:

1. **Given** the 에디터 추천 rail with six cards, **When** the second card becomes the first fully
   visible one, **Then** the second of six dots is highlighted; screen readers announce the position
   as part of the card's existing merged node, not as a separate control.
2. **Given** the 내 취향 rail, **When** it renders, **Then** it shows no dots (its "모두 보기" action
   already leads on) and the hero still shows its counter tile.
3. **Given** reduced motion, **When** the highlighted dot changes, **Then** it changes without a
   sliding animation.

---

### User Story 4 - The design system records the decision (Priority: P4)

A designer or engineer reading `DESIGN.md` finds a Home visual system v2 token set (corner radius,
caption blur band, tint alpha, tab bar blur and fallback) and a dated Decisions Log row that
supersedes the 2026-10-10 rejection of rounded corners and translucent surfaces for the Home tab
only, so the next change to Home or to any other tab starts from the right rule.

**Why this priority**: the constitution makes DESIGN.md the single source of truth; without the
record, the next reviewer flags every rounded corner as a violation.

**Independent Test**: `DESIGN.md` names every token the implementation uses with its value, states
that the tokens apply to the Home tab only, and the Decisions Log carries the 2026-10-11 row.

**Acceptance Scenarios**:

1. **Given** DESIGN.md, **When** the Home section is read, **Then** it lists the v2 tokens with values
   and scope, and the 2026-10-11 Decisions Log row names what it supersedes and why.
2. **Given** any value the Home implementation uses for radius, blur, tint or the tab bar, **When** it
   is compared with DESIGN.md, **Then** the values match.

---

### Edge Cases

- A cover whose dominant colour is near white or near black: the tint alpha is fixed, so the tint
  disappears into the paper; the caption band still reaches the darkening floor that keeps 4.5:1.
- A very light cover under a light theme: the band's final darkening is a floor, not a scale, so
  white-on-pale never results.
- Blur unavailable on part of a session (shader compile failure after launch): the card falls back
  to the gradient wash for that render; the failure is logged once per process with its operation
  name, never per frame.
- Font scale 2.0: the caption band grows with the caption as the hero wash does today; rounded
  corners never clip the counter tile or the heart target.
- The routes ranking answers slowly: Home renders without the rail and the rail appears when the
  answer lands, below the fold or above it, without shifting the hero.
- A route in the rail is unlisted or deleted between the read and the tap: the preview shows the
  existing "no longer listed" notice.
- The same visitor's own route appears in the ranking: the card shows it like any other; the preview
  already hides copy and report for the author's own route.
- Ten or more collection tiles or rail cards: dots are shown only for rails of at most ten cards;
  longer rails show none.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Every card and tile on the Home tab (hero, curator's eye, rail cards, route rail cards,
  collection tiles, the counter tile) MUST use a 16dp corner radius; the hero's and tiles' covers
  MUST be clipped to the same radius. No other tab, screen, sheet or dialog changes its 0dp shapes.
- **FR-002**: Captions over covers (hero, curator's eye, collection tiles) MUST sit on a progressive
  blur band: the cover is unblurred above the band, blurs progressively over the band's upper
  portion and darkens toward the caption edge to a fixed floor, in place of the flat or grain wash.
  Caption text contrast against the band MUST be at least 4.5:1 in light and dark themes for every
  cover in the fixture catalogue.
- **FR-003**: Each cover-bearing Home card MUST carry a tint derived from the dominant colour of its
  own cover, applied at one fixed low alpha token to the card's caption band and card surface, never
  to text, icons, the status label or the heart, and never using the orange accent.
- **FR-004**: On the Home tab the bottom tab bar MUST draw a backdrop blur of the content beneath it
  with its labels and indicator at full contrast; where runtime blur is unavailable it MUST draw a
  solid neutral bar at the same height. Other tabs keep the solid bar.
- **FR-005**: The hero and the curator's eye MUST keep their numeric counter tile. Rails with a fixed
  set of at most ten cards (에디터 추천, 지금 주목받는 동선, 팔로우한 갤러리) MUST show one dot per card
  under the rail with the first fully visible card highlighted; the 내 취향 rail shows no dots.
- **FR-006**: A new rail 지금 주목받는 동선 / ROUTES GETTING ATTENTION MUST show up to three listed
  public routes in the order of the existing public ranking (copies in the last 30 days), each card
  naming the route, the author's display name, the stop count and the first and last districts; the
  rail sits after 내 취향 and before 에디터 추천, and is absent when no route is listed or the read
  fails (the failure is logged, not shown).
- **FR-007**: Tapping a route card MUST open the existing public route preview for that route with
  its copy, report and own-route rules unchanged, and back MUST return to the Home tab at the same
  scroll position.
- **FR-008**: All treatments MUST be implemented once in shared Compose code and render natively on
  Android and iOS; platform code is limited to the thin shims the blur and colour work needs.
- **FR-009**: Where the platform cannot blur at runtime, captions MUST fall back to the existing
  gradient wash and the tab bar to the solid bar, with corners and tint unchanged, so legibility
  never depends on blur.
- **FR-010**: Under reduced motion, no blur, tint or dot change MAY animate; the grain and blur
  bands never animate in any mode.
- **FR-011**: Captions on blur bands MUST grow with the font scale as the hero wash does today, and
  rounded corners MUST not clip the counter tile, the heart or any text at font scale 2.0.
- **FR-012**: The new rail's title, subtitle, stop count and district line MUST exist in Korean and
  English through the existing inline language switch.
- **FR-013**: DESIGN.md MUST gain a Home visual system v2 token set (corner radius, caption blur band
  radii and stops, darkening floor, tint alpha, tab bar blur radius and fallback surface) scoped to
  the Home tab, and a Decisions Log row dated 2026-10-11 that supersedes the 2026-10-10 rejection of
  rounded corners and translucent surfaces for the Home tab only.
- **FR-014**: Opening a route from the rail MUST be counted with the existing public-route preview
  analytics; no new event name or dimension is introduced.

### Key Entities

- **Home visual tokens**: corner radius, blur band (start fraction, radius, darkening floor), tint
  alpha, tab bar blur radius, fallback surface; values live in DESIGN.md and in the theme.
- **Poster tint**: the dominant colour of a cover, computed from the loaded image with the existing
  share-card extractor, cached per cover URL for the session, absent when the cover is absent.
- **Routes rail card**: a listed public route as the ranking returns it (id, name, author display
  name, stop count, first and last district names, listing decided-at); read-only on Home.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On the reference set (Android 13+, Android 12, iOS 17+; light and dark; 100% and 200%
  font scale), the Home tab shows no clipped text, counter, heart or tile and no visible banding in
  the blur bands; verified by screenshot review of the fixture catalogue.
- **SC-002**: Caption text contrast against its band is at least 4.5:1 on every fixture cover (12
  covers, light and dark), measured on the rendered pixels at the caption's first line.
- **SC-003**: Scrolling the Home tab with blur enabled keeps frame time under 16ms at the 95th
  percentile on a mid-range Android device (Pixel 6a class) and shows no dropped-frame jank in the
  iOS simulator's frame debugger; measured over a 10-second scroll of the fixture page.
- **SC-004**: With three listed routes the rail appears in the same load as the rest of Home (no
  separate spinner) and the preview opens within one tap and under 300ms after the tap.
- **SC-005**: Every existing Home test passes and new tests cover the token selection, the blur
  fallback decision, the dot index, the rail's ordering and absence rules and the tint derivation as
  pure functions in shared code.
- **SC-006**: DESIGN.md carries the token set and the 2026-10-11 decision row, and a search for
  rounded shapes outside the Home package finds none introduced by this feature.

## Assumptions

- The Option A board on the prototype canvas is the visual reference for geometry, band and tint;
  exact token values are chosen in planning against the contrast floor and recorded in DESIGN.md.
- The share-card poster palette extractor is reusable for the per-card tint; if it proves too slow
  on first paint, the tint arrives after the cover loads and the card is correct without it.
- Public routes ranking, listing and preview from spec 089 are the data and the destination; this
  feature ships after the 089 migrations are applied to the hosted projects, since the rail reads
  the same ranking function.
- The rail sits after 내 취향 and before 에디터 추천 (owner listed "other people's routes" right after
  the taste recommendations); the owner can move it in the design review.
- Rails show dots only up to ten cards; the hero and curator's eye keep their counters.

## Out of Scope

- The 아트 스토리 editorial content type (the curator's eye carousel from spec 090 already covers
  curator notes; no new schema).
- New catalogue fields such as free admission or audience tags, and the 상황별 · 주제별 curations
  that need them.
- A readable popularity aggregate (most viewed, most clicked) and the curations built on it.
- Rounded corners, blur or tint on any tab other than Home.

## Revision 2 (owner feedback, 2026-10-12)

The owner reviewed the first build beside the Option A artboard and asked for three changes. This
section supersedes the requirements it names; everything else above stands.

1. **Match the artboard's composition.** The first build kept the old hero and layered blur on it.
   The artboard is a different page: image-led cards with a dark gradient under white captions.
2. **Glass is not required.** No runtime blur anywhere: no blurred caption bands and no translucent
   tab bar. The blur library is removed.
3. **Two more sections.** 내 취향 as the leading big banner, and 지금 가장 많이 본 as a curation.

### Requirements that change

- **FR-002 (replaced)**: Captions over covers MUST sit on a black gradient scrim under white text.
  The scrim reaches 0.55 alpha at the caption's edge and holds it under the caption, which keeps
  white text at 4.5:1 over any cover in both themes. No blur.
- **FR-003 (withdrawn)**: No poster tint.
- **FR-004 (withdrawn)**: The tab bar stays solid on every tab.
- **FR-005 (replaced)**: The leading banner keeps its numeric counter, as a pill on the cover. The
  curator's eye carousel shows centred dots with the current page as a wider pill in the active
  indicator colour. Rails of at most ten cards keep their dots.
- **FR-009, FR-010 (replaced)**: With no blur there is no fallback path. Press feedback darkens the
  scrim over 100ms and does not animate under reduced motion.
- **FR-011 (kept, reworded)**: The scrim's full-strength edge follows the measured caption, so a
  caption that grows with the font scale stays on the scrim.

### Requirements that are new

- **FR-015**: When the visitor's picks rest on their own saves, visits or follows, Home MUST lead with
  내 취향 as a banner carousel (cards about three quarters of the screen wide, 3:4, the next card
  peeking): cover, counter pill, save button, the reason line, the title, and venue with the closing
  date. The taste terms the picks rest on appear as chips under it. The featured picks then appear
  as an ordinary rail. Without a personal basis the featured picks lead in the same banner form and
  내 취향 stays a rail, as today.
- **FR-016**: 큐레이터의 시선 cards MUST carry the hook and one insight at the top over a top scrim,
  with no reviewer or drafting line (owner decision 2026-10-13), and the exhibition's name as a chip
  at the bottom.
- **FR-017**: A route card MUST be a compact card: who made it (an editor mark, or the author's name),
  how many times it was copied in 30 days when that is above zero, the route's name, the stop count
  with the district line, and up to four stop covers with a count of the rest.
- **FR-018**: Home MUST group collections in two sections: 큐레이션, a two-column grid of the timing
  collections and 지금 가장 많이 본; and 동네·주제별, a rail of smaller tiles for neighbourhoods and
  art terms. Tiles show their borrowed cover in colour under the scrim.
- **FR-019**: 지금 가장 많이 본 MUST list the exhibitions opened most often in the app over the last
  seven days, most opened first, limited to exhibitions the catalogue currently shows, and appear
  only when at least three qualify. The app reads an ordered list of exhibition ids only: no counts
  and no per-visitor data leave the server. The ranking is built from the aggregate mobile
  analytics, whose collection is not yet enabled for release builds (TODOS.md, Rollout Queue); the
  tile stays hidden until that rollout and this spec's migration are both in place.
- **FR-020**: The accent colour on Home is used only as the active indicator (the current story dot).
  Reason dots, the editor mark and counters use the theme's content colours.
- **FR-021**: Home MUST offer situational collections in the 상황별·주제별 rail, ahead of the
  neighbourhoods and themes: a quiet visit, spaces to photograph, slowly for two, for a lift, and
  something strange. Each is a fixed bundle of art terms the app can already read from an
  exhibition (reviewed metadata first, otherwise its own text), appears from three exhibitions, and
  shows the terms it rests on. A term a shown situation covers is not also a theme tile. No
  situation may infer suitability for a kind of visitor or a price from an exhibition's text.
- **FR-022**: Editors and gallery owners MUST be able to state two facts about visiting an
  exhibition, through the same reviewed vocabulary and editor as art terms, in a new `visit`
  category: 무료 관람 / Free admission and 아이·가족과 함께 / Good with children and family. The
  app reads them as visit tags, separate from art terms, so they never count as taste, as a reason
  for a pick or as a theme. Home shows 무료 관람 as a curation from two tagged exhibitions and
  아이·가족과 함께 as a situation from three, both only from tagged exhibitions. App builds that do
  not know the category ignore it.

### Still out of scope

- **A second, editor-written story format.** The art stories idea is delivered as 큐레이터의 시선:
  curator's notes on running exhibitions (spec 090), shown here as the story carousel. A separate
  long-form format with its own authoring screen is not part of this spec.
- **More visit terms.** Accessibility, pets, parking and similar need their own review of wording
  and of who may assert them.

### Also fixed in this revision (not Home)

The Map tab aborted on iOS on `develop`. The cause was a dependency bump on 2026-10-05 that raised
Compose Multiplatform to 1.12.1 (Skiko 0.150) while the map library (0.10.4) still calls a Skiko
function that release removed. Compose and Coil were first held at the versions `main` ships; on
2026-10-13 the hold was replaced by the map library migration (`org.maplibre.compose` 0.20.0), which
this branch now carries, and native links fail on this class of mismatch.

## Revision 3 (owner feedback, 2026-10-13)

Three requests after reviewing the build on device.

- **FR-023**: Curator's notes MUST show no drafting line, reviewer line or 검수 wording to readers:
  not on the exhibition page, the Home story cards, the Home section subtitle or the exported
  Instagram carousel. Admin keeps its review wording and still records who approved a note.
- **FR-024**: A pin on the Map tab MUST be the gallr logo with the exhibition's cover in its
  opening, a black ring, or the active-indicator ring when the exhibition is saved. A pin without a
  cover, or whose cover has not loaded, MUST still show as the logo with a white opening. A group
  MUST picture one of its exhibitions and state its count. Titles MUST NOT be drawn across pins.
- **FR-025**: Each stop on a route map MUST be the same kind of pin, picturing its exhibition, with
  its number in route order. Stops whose exhibition has left the catalogue show the plain logo pin.
- **FR-026**: Saving or unsaving an exhibition MUST NOT change the order or contents of a
  recommendation list that is on screen; the saved state of the card changes in place. The new
  ranking MUST be applied when the reader returns to Home from another tab, opens the full list or
  pulls to refresh, and immediately when the list was empty. Route planning MUST use the current
  saves regardless.
- **SC-009**: After saving a card in 내 취향, the same cards are in the same places and only the
  heart has changed.

Not in this revision: pins on the public web route page, and animated transitions between two
rankings when a refresh does change the list.
