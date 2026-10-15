# Feature Specification: Home style across the app

**Feature Branch**: `095-app-visual-system`
**Created**: 2026-10-15
**Status**: Draft
**Input**: User description: "Update DESIGN.md to match the current Home style but retain the core colours, and restyle the app to match (owner decisions 2026-10-15). Map labels in the reader's language."

## Owner decisions (2026-10-15)

1. **Home is the style.** The Home tab's visual system (spec 091, "Option A": rounded, image-led cards and tiles, white captions on a black scrim) becomes the style of the whole app, and `DESIGN.md` is rewritten to say so.
2. **Core colours stay.** Black, white and grays, the dark theme, and the single orange accent `#FF5400` in its three roles (primary call to action, active indicator, touch feedback) do not change.
3. **Doc and app together.** The other tabs and screens are restyled in the same feature, so the design guide and the app agree when it ships.
4. **Free only.** No paid font, image or map service is introduced.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - One look from Home to every screen (Priority: P1)

A visitor moves from Home to 목록, opens an exhibition, then checks MY. Today Home has rounded, picture-led cards while every other screen has sharp black-outlined boxes, so the app feels like two products. After this feature cards, tiles, chips, buttons, fields, sheets and dialogs share Home's shapes and caption treatment everywhere, in the same black, white and gray with the same orange accent.

**Why this priority**: This is the owner's request and what visitors see on every screen.

**Independent Test**: Walk every tab and the screens opened from them (exhibition, gallery, event and editor pages, For You, collections, route composer, public route preview, settings, sign-in, profile) in light and dark themes and compare each component with Home's.

**Acceptance Scenarios**:

1. **Given** the 목록 tab, **When** the exhibition list shows, **Then** its cards have Home's rounded corners and covers clipped to the card's shape.
2. **Given** a screen with a filter chip, a button and a text field, **When** it shows, **Then** each has the rounded corner Home uses for small pieces.
3. **Given** a bottom sheet or dialog opens, **When** it shows, **Then** its corners match the Home style.
4. **Given** any screen, **When** it shows, **Then** the only non-gray colour is the orange accent in one of its three roles.
5. **Given** a caption sits on a picture anywhere in the app, **When** it shows, **Then** it is white on Home's black scrim and keeps 4.5:1 contrast over any picture.

---

### User Story 2 - DESIGN.md describes the app as it is (Priority: P1)

A designer or developer opens `DESIGN.md` before building a screen. Today its core rules say "no rounded corners" and "brutally minimal", contradicting Home. After this feature the aesthetic direction, layout rules and component patterns describe the Home style as the default, keep the colour section unchanged, and leave no rule that contradicts the app.

**Why this priority**: `DESIGN.md` is the single source of truth for all UI work; a guide that contradicts the app causes every future change to drift.

**Independent Test**: Read `DESIGN.md` from top to bottom and check every shape, caption, card and component rule against the running app.

**Acceptance Scenarios**:

1. **Given** the Aesthetic Direction and Layout sections, **When** read, **Then** they describe rounded, image-led cards with white captions on a scrim, and no longer say "no rounded corners" or "0dp on everything".
2. **Given** the Color section, **When** compared with today's, **Then** every token, value and accent rule is unchanged.
3. **Given** the Home tab section, **When** read, **Then** the visual system it introduced is described once as the app-wide system, and Home-only wording ("On the Home tab only", "Every other tab keeps 0dp shapes") is gone.
4. **Given** the decisions log, **When** read, **Then** it records the 2026-10-15 owner decision.

---

### User Story 3 - The map speaks one language (Priority: P2)

A visitor using the app in Korean looks at the map and sees "종로" and "청계천로" rather than "Jong-ro 종로". In English they see English names. This applies on the Map tab and every route map.

**Why this priority**: It makes the map read like the rest of the app in either language. It is small and independent of the shape changes.

**Independent Test**: Open the Map tab and a route map in Korean and in English, zoom into central Seoul and read the street and area names.

**Acceptance Scenarios**:

1. **Given** the app is in Korean, **When** the map shows street and area names, **Then** each name is in Korean only, falling back to the local name where no Korean name exists.
2. **Given** the app is in English, **When** the map shows names, **Then** each is in English only, falling back to the romanised and then the local name.
3. **Given** the visitor switches the app language, **When** they return to a map, **Then** its names follow the new language.

---

### Edge Cases

- **Pictures at the screen edge.** A picture that runs edge to edge (the exhibition page's cover pager, full-bleed headers) keeps square corners where it meets the screen edge; only framed pictures inside the margins are rounded.
- **Avatars** stay circles; **map pins** keep their current shape; the **tab bar** keeps its current look (spec 094 handles its visibility).
- **Exports** (the exhibition share card, the route share card, the Instagram carousel and QR codes) keep their own documented rules and are not restyled.
- **Large text.** Rounded components grow with text as they do today; corners never clip text.
- **Dark theme.** Every restyled component uses the dark-theme tokens already defined.
- **Missing pictures.** A card without a picture shows the gray placeholder with the same corners and, where it carries a caption, the same scrim.
- **Map labels with no name in the reader's language** fall back as described; a place with no name shows nothing.
- **Accent creep.** Rounding a component never gives it the accent; the three roles stay the only uses.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Cards and tiles everywhere MUST use the Home card corner radius, with pictures clipped to the card's shape.
- **FR-002**: Small pieces everywhere (chips, pills, counters, small thumbnails, buttons, text fields) MUST use the Home small-piece corner radius.
- **FR-003**: Bottom sheets and dialogs MUST use rounded corners consistent with the Home card radius.
- **FR-004**: Every caption set on a picture MUST be white on the Home black scrim, with at least 4.5:1 contrast over any picture in both themes.
- **FR-005**: Section headings on other screens MUST follow the Home section header (title, optional line under it, optional trailing action).
- **FR-006**: Colours MUST NOT change: every colour token, both themes and the accent's three roles stay as documented.
- **FR-007**: Avatars, map pins, the tab bar, pictures running to the screen edge and exported images MUST keep their current shapes.
- **FR-008**: `DESIGN.md` MUST describe the Home style as the app-wide system (aesthetic direction, layout, component patterns), keep the Color section unchanged, remove every rule that contradicts the app, and log the 2026-10-15 decision.
- **FR-009**: Map street and area names MUST be shown in the app's language only, with fallbacks (Korean: Korean, then local; English: English, then romanised, then local), on the Map tab and every route map, and MUST follow a language change.
- **FR-010**: Interaction, layout order, text and behaviour of every screen MUST stay as they are; only shapes, caption treatment and heading style change.
- **FR-011**: Touch targets, contrast and screen reader labels MUST meet or exceed today's.

### Key Entities

- **Visual tokens**: the named corner radii, scrim strengths and border colours Home introduced, now shared by the whole app.
- **Map label language**: which name a map label shows for the app's current language.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Outside the documented exceptions (avatars, map pins, the tab bar, screen-edge pictures, exports), no screen shows a square-cornered card, tile, chip, button, field, sheet or dialog.
- **SC-002**: Every colour on every screen is one of the documented tokens; the accent appears only in its three roles.
- **SC-003**: Every caption on a picture measures at least 4.5:1 against its scrim in both themes.
- **SC-004**: `DESIGN.md` contains no rule that the running app contradicts, checked section by section.
- **SC-005**: In each language, 100% of street and area names shown in central Seoul at street level are in that language or its documented fallback, with no paired-script labels.
- **SC-006**: No existing flow gains or loses a step, and no screen's text or order changes.

## Assumptions

- The Home tokens already in the app (16dp for cards and tiles, 12dp for small pieces, the 0.55 scrim) are the values adopted app-wide; this feature does not change Home itself.
- Buttons and text fields count as small pieces (12dp); there is no pill-shaped button.
- The tab bar's look is unchanged here; whether it stays visible everywhere is spec 094.
- The web site, Admin and the gallery workspace are out of scope; they follow their own guides.
- Map labels use the names already in the free OpenStreetMap-based map data; no paid map or translation service is added.
