# Feature Specification: Curator POV Notes

**Feature Branch**: `090-curator-pov-notes`
**Created**: 2026-10-11
**Status**: Draft
**Input**: User description: "Curator POV notes: automatically generated, staff-reviewed curator-perspective insights for exhibitions, delivered to the home tab, the exhibition detail screen, and as exportable Instagram carousel posts."

## Overview

A curator's note is a short bilingual (KO/EN) piece about one exhibition that says what only a
curator would say: why the show matters now, what to look for in the room, how it connects to the
artist's practice or to other shows in Seoul, and one concrete viewing tip. It is written to arouse
interest, never as a summary or marketing copy, and it never states a fact that is not already in
the exhibition's own published data, its reviewed artist metadata, or its reviewed art terms.

Notes are drafted automatically on the server, reviewed by staff editors in Admin before anything
is public, published as versioned content, and consumed by the mobile home tab ("큐레이터의 시선 /
CURATOR'S EYE"), the exhibition detail screen, and an Instagram carousel export for staff. The
public web may read approved notes later; that surface is out of scope here.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Editors review automatically drafted notes (Priority: P1)

A staff editor opens the Notes workspace in Admin and finds a queue of draft notes, one per
qualifying exhibition, each with the exhibition's facts beside the draft. The editor reads the
draft, fixes wording where needed, and approves it; or declines it with a reason; or asks for a
fresh draft. Approving publishes the note under the editor's name. Nothing reaches a visitor until
an editor approves it.

**Why this priority**: Every other surface depends on approved notes existing, and the review step
is the trust boundary that keeps an automatically written sentence from misrepresenting a gallery's
show.

**Independent Test**: Seed two published exhibitions; confirm a draft appears for each; approve one
with an edit and decline the other with a reason; confirm only the approved note is readable by a
visitor-level reader and that the declined one records the reason and stays private.

**Acceptance Scenarios**:

1. **Given** a published exhibition that has a description, dates, venue and cover image and no
   note yet, **When** the scheduled drafting runs, **Then** a draft note exists for it in the queue
   within one scheduled cycle, carrying its hook line, its insight paragraphs, its viewing tip, both
   languages, and the provenance of how it was produced.
2. **Given** a draft in the queue, **When** the editor edits the Korean text and approves,
   **Then** the note becomes the published version with the edited text, the editor's name, and the
   approval time, and the draft is no longer in the queue.
3. **Given** a draft in the queue, **When** the editor declines with a reason, **Then** the note
   is not published, the reason is stored with the draft, and the exhibition does not receive another
   automatic draft until an editor asks for one.
4. **Given** a draft the editor finds weak, **When** the editor asks for a fresh draft, **Then** a
   new draft replaces it in the queue within the on-demand time budget, and the previous draft is
   kept for comparison.
5. **Given** an approved note whose exhibition's published data later changes (dates, description,
   artists), **When** the next scheduled drafting runs, **Then** a new draft is queued for review
   while the approved note stays live until an editor approves the new one.
6. **Given** a draft that mentions something absent from the exhibition's sources, **When** the
   editor marks the sentence as unsupported, **Then** the note cannot be approved until that sentence
   is edited or removed, and the unsupported marking is kept as review history.

---

### User Story 2 - Visitors meet the curator's eye on the home tab (Priority: P1)

On the home tab, between the hero and the rails, a section titled 큐레이터의 시선 / CURATOR'S EYE
shows approved notes as a carousel of large cards: the exhibition's cover, the note's hook line, and
one insight line. Tapping a card opens the exhibition's detail screen scrolled to the full note.
The section appears only when at least one approved note exists for a currently running or
upcoming exhibition, and it never shows a draft.

**Why this priority**: This is the owner's stated reason for the feature: a fun, editorial addition
that makes the home tab worth opening and gives people a reason to tap into exhibitions.

**Independent Test**: With three approved notes and one draft in the data, open the home tab and
confirm three cards in the stated order, none for the draft; tap one and land on the detail screen
with the note visible; switch language and confirm the cards switch with it.

**Acceptance Scenarios**:

1. **Given** approved notes for running or upcoming exhibitions, **When** the home tab loads,
   **Then** the section shows them newest approval first, with cover, hook line and one insight line
   per card, a position indicator, and the next card peeking.
2. **Given** no approved note for any running or upcoming exhibition, **When** the home tab loads,
   **Then** the section is absent and nothing else on the tab moves.
3. **Given** the app language is English, **When** the section renders, **Then** every card shows
   the English text of the note, falling back to Korean only when no English exists.
4. **Given** a card, **When** the visitor taps it, **Then** the exhibition's detail screen opens
   with the note in view, and the tap is attributed to the curator's-eye section for analytics.

---

### User Story 3 - Visitors read the full note on the exhibition page (Priority: P2)

The exhibition detail screen shows the approved note in a labelled block: the hook line, the
insight paragraphs, the viewing tip, and "reviewed by <editor name>". Without an approved note the
block is absent.

**Why this priority**: The home carousel is a door; the full note is the room. It also serves people
who arrive at the exhibition from the list, the map, a route, or a notification.

**Independent Test**: Open an exhibition with an approved note and one without; confirm the block
with the editor's name on the first and no empty block on the second.

**Acceptance Scenarios**:

1. **Given** an exhibition with an approved note, **When** the detail screen opens, **Then** the
   note block shows the hook line, the paragraphs, the tip, and the reviewing editor's name, in the
   app language.
2. **Given** an exhibition without an approved note, **When** the detail screen opens, **Then** no
   note block or placeholder appears.
3. **Given** a note that was superseded by a newer approved version, **When** the detail screen
   opens, **Then** only the newest approved version is shown.

---

### User Story 4 - Staff export a note as an Instagram carousel (Priority: P3)

From an approved note in Admin, a staff member exports an Instagram carousel: a cover slide with the
exhibition cover and hook line, two to four insight slides, and a closing slide with the dates, the
venue, and a code that opens the exhibition's gallr page; plus a caption with hashtags. The slides
follow the monochrome design system and may borrow the poster's palette the way the existing
exhibition share card and QR exports do. The export downloads as one archive.

**Why this priority**: It turns each reviewed note into an external post without extra writing, but
it depends on approved notes existing and is useful only once the mobile surfaces work.

**Independent Test**: Export one approved note; open the archive; confirm the slide count, the
1080×1350 size of each slide, readable text on every slide, a scannable code on the last slide, and
a caption file whose hashtags match the exhibition's reviewed terms.

**Acceptance Scenarios**:

1. **Given** an approved note, **When** staff choose Export carousel, **Then** within 30 seconds
   an archive downloads containing the cover slide, two to four insight slides, the closing slide,
   and a caption text file.
2. **Given** a note whose language was chosen as English for the export, **When** the archive is
   produced, **Then** every slide and the caption use the English text.
3. **Given** a slide whose text would not fit, **When** the export runs, **Then** the text is split
   across additional insight slides rather than shrunk below the design system's readable size, up to
   the four-slide limit, after which the export reports what was left out.

---

### Edge Cases

- An exhibition qualifies in Korean but has no English description: the draft is produced in Korean
  only and the English fields stay empty; the home card and detail block fall back to Korean.
- An exhibition's cover image is removed after a note is approved: the home card uses the
  no-image treatment the home tab already defines; the export refuses the cover slide and reports it.
- The drafting service is unavailable: the queue shows the exhibitions still waiting, nothing is
  published, and the next scheduled run retries; editors can still edit and approve existing drafts.
- Two editors open the same draft: the second approval or decline is refused with a message that the
  note was already decided, and the workspace reloads the decision.
- The exhibition is unpublished or hidden by its gallery: its approved note disappears from every
  visitor surface at once and stays in Admin history.
- A generated draft is in the wrong language or empty: it is marked unusable and never shown to an
  editor as reviewable; it counts as a failed draft for the exhibition.
- An exhibition has ended: no new drafts are made for it and its note leaves the home carousel,
  while the detail screen keeps showing the note as long as the exhibition page exists.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST draft one curator's note for every published exhibition that has a
  description, opening and closing dates, a venue name, and a cover image, and that has not ended.
- **FR-002**: A note MUST contain, in each available language: a hook line (one sentence), between
  two and four insight paragraphs that cover why the show matters now, what to look for in the room,
  and how it connects to the artist's practice or to other shows in Seoul, and one concrete viewing
  tip.
- **FR-003**: Every statement in a draft MUST be traceable to the exhibition's published fields, its
  reviewed artist metadata, or its reviewed art terms; the draft MUST carry the list of source fields
  it drew on.
- **FR-004**: Drafting MUST run on a schedule at least once a day and on demand for a single
  exhibition from Admin; an on-demand draft MUST appear within two minutes.
- **FR-005**: Every draft MUST record its provenance: how it was produced, which version of the
  instructions produced it, when, and from which source fields.
- **FR-006**: No draft MUST be readable by visitors, galleries, or the public web; only staff editors
  can see drafts.
- **FR-007**: Staff editors MUST be able to edit, approve, decline with a reason, or request a
  fresh draft for any draft, and to mark individual sentences as unsupported; a draft with an
  unsupported sentence MUST NOT be approvable.
- **FR-008**: Approving MUST publish the note as a new version carrying the editor's display name
  and the approval time; earlier versions MUST be kept and never shown to visitors.
- **FR-009**: When an exhibition's published data changes after approval, the system MUST queue a
  new draft and keep the approved note live until a newer version is approved.
- **FR-010**: A declined exhibition MUST NOT receive another automatic draft until an editor
  requests one.
- **FR-011**: The mobile home tab MUST show a "큐레이터의 시선 / CURATOR'S EYE" section of approved
  notes for running or upcoming exhibitions, newest approval first, as a horizontal carousel of
  large cards with cover, hook line and one insight line, a position indicator, and the next card
  peeking; the section MUST be absent when there is nothing to show.
- **FR-012**: Tapping a carousel card MUST open the exhibition's detail screen with the note in
  view, and the tap and the card's impression MUST be attributed to the curator's-eye section in the
  existing aggregate analytics.
- **FR-013**: The exhibition detail screen MUST show the newest approved note in a labelled block
  with the reviewing editor's name, and MUST show nothing when there is no approved note.
- **FR-014**: Visitor surfaces MUST show the note in the app language and fall back to Korean when
  English is missing.
- **FR-015**: Staff MUST be able to export an approved note as an Instagram carousel: a cover slide,
  two to four insight slides, a closing slide with dates, venue and a scannable code to the
  exhibition's gallr page, every slide 1080×1350, plus a caption text with hashtags drawn from the
  exhibition's reviewed terms, delivered as one archive within 30 seconds.
- **FR-016**: Export slides MUST follow the monochrome design system, with the poster-palette
  exception already allowed for exported exhibition content, and MUST keep every line at or above
  the readable size the design system sets for exports.
- **FR-017**: An approved note MUST leave every visitor surface at once when its exhibition is
  unpublished, hidden, or has ended (home carousel only, for ended runs).
- **FR-018**: Notes MUST be produced and reviewed in Korean and English; a note may be published
  with only Korean when the exhibition has no English description.
- **FR-019**: Drafting and export MUST run on the server or in the staff workspace, never on a
  visitor's device, and MUST not change any exhibition data.
- **FR-020**: Visitor surfaces MUST disclose that the note was drafted with assistance and reviewed
  by a named editor. [NEEDS CLARIFICATION: how should the note be attributed to the public: as the
  named editor's note with a small "drafted with AI assistance, reviewed by <name>" line; as an
  unsigned "gallr 에디터" voice with the same assistance line; or with no mention of assistance at
  all? This changes the copy on every card, the detail block, and the carousel's closing slide.]

### Key Entities

- **Curator's note**: the content for one exhibition in one language pair: hook line, insight
  paragraphs, viewing tip; its status (draft, declined, approved, superseded); the reviewing editor
  and time; the version number.
- **Draft provenance**: how a draft was produced: the producer and the instruction version, the
  time, and the exhibition source fields it drew on; kept with every version.
- **Review decision**: an editor's action on a draft: edit, approve, decline with reason, request a
  fresh draft, mark a sentence unsupported; the editor and time.
- **Carousel export**: the set of slides and the caption produced from one approved note in one
  language, with the time and the staff member who exported it.

## Assumptions

- "Qualifying" means published, not ended, with a description of at least a few sentences, opening
  and closing dates, a venue name, and a cover image; exhibitions without a description are skipped
  rather than drafted from the title alone.
- Scheduled drafting once a day is enough: the catalogue changes a few times a week.
- Editors who already hold the house-editor or staff role in Admin review notes; no new role.
- Notes are published under the gallr editors' voice with the reviewing editor named, pending the
  attribution decision in FR-020.
- Hashtags come from the exhibition's reviewed terms, the venue name and the district; no free-form
  tag invention.
- The home carousel shows at most ten notes; older approvals wait on the detail screen only.
- The public web will read approved notes through the same published content later; nothing here
  prevents that.

## Dependencies

- Exhibitions carry reviewed artist metadata and reviewed art terms (spec 074) for the notes to draw
  on; exhibitions without them get notes from their description alone.
- The home tab rebuilt on 2026-10-10 (hero, rails, collections) is where the section lives.
- The existing exported-content design exception (poster palette on share cards and QR exports)
  applies to the carousel slides.
- Aggregate mobile analytics (spec 072) record the section's impressions and taps.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Within one week of launch, every featured exhibition that qualifies has an approved
  note.
- **SC-002**: Editors spend under three minutes per note on average from opening a draft to
  approving or declining it, measured over the first fifty decisions.
- **SC-003**: Exhibition detail opens from the home tab rise by at least 20% over the two weeks
  after launch compared with the two weeks before, with the curator's-eye section accounting for at
  least a quarter of those opens.
- **SC-004**: In a monthly staff spot-check of twenty published notes, none contains a statement
  absent from its exhibition's sources.
- **SC-005**: At least 80% of automatically produced drafts are approved with light or no edits, so
  the drafts are worth the editors' time.
- **SC-006**: An on-demand draft is ready for review within two minutes, and a carousel export
  downloads within 30 seconds, in 95% of attempts.
