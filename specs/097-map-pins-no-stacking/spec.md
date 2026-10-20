# Feature Specification: Map pins without stacking

**Feature Branch**: `097-map-pins-no-stacking`
**Created**: 2026-10-16
**Status**: Draft
**Input**: User description: "On the map, after showing galleries within the gallr icon, it shouldn't overlap: show
individual gallr icons with exhibition images inside, so no stacking."

## Background

The Map tab draws each exhibition as a gallr-logo pin with the exhibition's cover inside (DESIGN.md, Map pins).
Exhibitions whose pins would come within 28dp of each other on screen share one pin that shows the first cover and a
count badge. Exhibitions at the same place (several shows at one gallery, or galleries in one building) share a pin at
every zoom, so zooming in never separates them. Tapping a shared pin opens a list sheet ("이 주변 전시 N개") instead of
showing the exhibitions on the map. Visitors cannot see which shows are there, or their covers, without leaving the
map for a list.

## Owner decision (2026-10-16)

Once the map is zoomed in to a neighbourhood, every exhibition has its own pin with its own cover, and pins that would
overlap are moved apart around their real place instead of stacking. Zoomed out to the city, count pins remain so the
map stays readable, and tapping one zooms in to that area instead of opening the list.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See every exhibition in a neighbourhood (Priority: P1)

A visitor zooms the map into Hannam, where six exhibitions sit within a few streets and three of them are at one
building. Today they see one pin with "6". With this feature they see six pins, each with its own cover, side by side
around their real places, none covering another. They tap the cover they like and its exhibition opens.

**Why this priority**: This is the owner's request: no stacked pins once the visitor is looking at a neighbourhood.

**Independent Test**: With the local catalogue, zoom into an area with exhibitions at one gallery and at nearby
galleries; every exhibition shows its own pin and cover, no two pins overlap, and each opens its exhibition.

**Acceptance Scenarios**:

1. **Given** the map at neighbourhood zoom or closer over an area with several exhibitions, **When** it settles,
   **Then** each exhibition shows its own pin with its own cover (or the white opening when it has none) and no count
   badge appears.
2. **Given** three exhibitions at the same gallery, **When** the map is at neighbourhood zoom or closer, **Then** three
   pins appear grouped around that gallery's place, none hidden behind another.
3. **Given** two exhibitions far enough apart not to overlap, **When** the map shows them, **Then** each pin sits
   exactly on its own place, unmoved.
4. **Given** a pin moved apart from its neighbours, **When** the visitor taps it, **Then** that exhibition opens.
5. **Given** a saved exhibition among moved pins, **When** the map shows it, **Then** its ring is orange and it is
   drawn above unsaved pins where they meet.
6. **Given** the visitor zooms further in, **When** the pins no longer need to move apart, **Then** they return to their
   exact places.

---

### User Story 2 - Count pins zoom in (Priority: P1)

At the city view the visitor sees a pin with "6" over Hannam. They tap it, and the map zooms in to Hannam so the six
exhibitions show as their own pins (User Story 1), instead of a list sheet covering the map.

**Why this priority**: Without it, the only way to separate a count pin is pinching, and the list sheet keeps the
stacking problem at city zoom.

**Independent Test**: At the opening city view, tap a count pin; the map moves to show that area at neighbourhood zoom
or closer with every one of its exhibitions on screen as its own pin.

**Acceptance Scenarios**:

1. **Given** the city view with a count pin, **When** the visitor taps it, **Then** the map zooms in to show all of
   that pin's exhibitions on screen, at neighbourhood zoom or closer, each as its own pin.
2. **Given** a count pin whose exhibitions are all at one place, **When** the visitor taps it, **Then** the map zooms
   to neighbourhood zoom centred on that place and they show around it.
3. **Given** the city view, **When** the visitor taps a pin for a single exhibition, **Then** that exhibition opens, as
   today.
4. **Given** a count pin, **When** the map zooms in after the tap, **Then** no list sheet opens.

---

### User Story 3 - Read and reach pins with assistive technology (Priority: P2)

A screen reader user explores the map. Each separate pin reads its exhibition's name and opens it; a count pin reads how
many exhibitions it holds and that activating it zooms in.

**Why this priority**: The map already offers each pin as an accessible target; moved pins and the new count-pin action
must keep that.

**Independent Test**: With a screen reader on, move through the pins at neighbourhood zoom and at city zoom; each is
announced and activates as described.

**Acceptance Scenarios**:

1. **Given** neighbourhood zoom, **When** focus reaches a moved pin, **Then** it reads that exhibition's name and opens
   it on activation, at the place the pin is drawn.
2. **Given** city zoom, **When** focus reaches a count pin, **Then** it reads "전시 N개 · 확대" / "N EXHIBITIONS · ZOOM IN"
   and zooms in on activation.

### Edge Cases

- **Many exhibitions at one place** (for example eight at one building): all of them show around the place, the
  nearest positions filled first, none overlapping; the group stays centred on the place.
- **Crowded block at neighbourhood zoom** (moved pins would spill into a neighbouring group): pins keep moving apart
  until none overlap; each stays as close as possible to its own place.
- **The visitor pans or zooms**: pins move with the map; they are rearranged as the zoom changes and never flicker
  between arrangements while the map is still.
- **Crossing the neighbourhood threshold**: zooming out past it merges pins into count pins again; zooming in past it
  separates them.
- **Exhibition without a cover**: its pin shows the white opening, as today.
- **Titles**: a single exhibition's title still sits under its pin only where it does not collide with a pin or another
  title; with moved pins, titles yield first.
- **"내 전시" (saved) view**: the same rules apply to the pins it shows.
- **Count pin at the edge of Seoul or near the map's zoom limit**: the zoom-in stays within the map's existing limits
  and still shows all of the pin's exhibitions.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: At neighbourhood zoom or closer (the visible map about 3 km across or less on a phone), the Map tab MUST
  show every exhibition as its own pin with its own cover, and MUST NOT show count pins.
- **FR-002**: Pins that would overlap MUST be moved apart, each as little as possible from its real place, until no two
  pins overlap; a pin that overlaps nothing MUST stay exactly on its place.
- **FR-003**: Exhibitions at the same place MUST each get a pin arranged around that place.
- **FR-004**: Below neighbourhood zoom, exhibitions whose pins would come within 28dp MUST share a count pin, as
  today.
- **FR-005**: Tapping a count pin MUST zoom the map in to show all of its exhibitions on screen at neighbourhood zoom
  or closer, within the map's existing zoom limits, and MUST NOT open a list sheet.
- **FR-006**: Tapping an individual pin, moved or not, MUST open that exhibition.
- **FR-007**: Saved exhibitions MUST keep the orange ring and draw above unsaved pins; titles MUST keep yielding to pins.
- **FR-008**: Every pin MUST be its own accessible target at its drawn position: a single pin reads its exhibition's
  name; a count pin reads its count and that it zooms in (KO/EN).
- **FR-009**: Arrangement MUST be stable: the same exhibitions at the same zoom and screen size MUST get the same
  arrangement, and pins MUST NOT jump between arrangements while the map is still.
- **FR-010**: Route maps (route composer, public route preview, full-screen route map) MUST keep their numbered stop
  pins unchanged.
- **FR-011**: The change MUST use only the existing free map stack; no new paid service or data.

### Key Entities

- **Map pin**: one exhibition's mark: its real place, the place it is drawn (equal unless moved), its cover, saved state
  and title.
- **Count pin**: below neighbourhood zoom, the exhibitions that share one mark, its place and count.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: At neighbourhood zoom or closer, 0 pins overlap another pin and 0 count badges show, for the local
  catalogue's busiest area and for a test case of 8 exhibitions at one place.
- **SC-002**: Every exhibition in view at neighbourhood zoom can be opened with one tap on its own pin.
- **SC-003**: From the opening city view, any exhibition hidden in a count pin is visible as its own pin after one tap
  on that count pin.
- **SC-004**: A moved pin stays within two pin widths of its real place in the 8-at-one-place case, and pins that
  overlap nothing move 0dp.
- **SC-005**: Panning and zooming stay as smooth as today on the emulator and simulator used for testing, with no
  visible flicker of pins once the map is still.

## Assumptions

- "Neighbourhood zoom" is the zoom at which the visible map is about 3 km across on a phone; the planning phase fixes
  the exact zoom level. The opening view (city, about zoom 12) stays below it, so count pins still show on first open.
- Moved pins show no leader line to their real place; staying close to it is enough at neighbourhood zoom.
- The list sheet ("이 주변 전시 N개") is no longer opened from the Map tab; its removal from the Map tab is part of this
  feature.
- The visual pin (logo shape, ring, cover, white edge, count badge style) is unchanged; DESIGN.md's Map pins section is
  updated to describe the new grouping and spreading rules.
- Platforms: Android and iOS through the shared map code; the public web has no exhibition map with these pins.
