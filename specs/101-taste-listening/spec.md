# Feature Specification: Taste listening and reliable catalogue data

**Feature Branch**: `101-taste-listening`
**Created**: 2026-10-26
**Status**: Draft
**Input**: User description: "Taste listening and reliable catalogue data: Stage 0 and Stage 1 of the approved design docs/designs/2026-10-26-mobile-taste-ai-design.md (Approach B staged, owner decision 2026-10-26) … Stage 2 (on-device voice) out of scope." Also, from the owner on 2026-10-26: "i want data to be more reliable."

## Background

내 취향 / FOR YOU picks exhibitions from what a visitor saved, visited and followed, and explains each pick in one
line. Two things make it feel neither accurate nor like it knows the visitor. First, the catalogue it ranks is
almost untagged: in the 2026-10-02 snapshot 73 of 78 published exhibitions carried no artist credit and 74 of 78 no
reviewed art term, so the picks rest on shared wording and a 28-word keyword list, and the reasons they give are
true but thin ("저장한 X와 비슷한 전시"). Second, the feature never listens: it asks nothing on first open, it shows
editorial picks under the 내 취향 heading until the visitor has saved something, a wrong pick cannot be corrected,
and the taste chips cannot be touched.

The approved design (docs/designs/2026-10-26-mobile-taste-ai-design.md) treats these as two problems in order:
data first, then dialogue. This specification covers Stage 0 (every running exhibition tagged and the catalogue
made trustworthy) and Stage 1 (the visitor can tell gallr their taste, see it in every reason, and correct it),
which ships in release 1.15. Stage 2, a generated personal voice on supported devices, is built only if watching
visitors after 1.15 shows they still do not feel known; it is out of scope here.

## Owner decision (2026-10-26)

Approach B staged: Stage 0 and Stage 1 first, Stage 2 gated on observation. The wireframe
(docs/designs/2026-10-26-mobile-taste-ai-wireframe.png) was accepted as the flow. The owner also asked for the
catalogue data to be more reliable; that is User Story 5. Standing rules apply: no paid service, no API key, no
hosted model, nothing about a visitor's taste leaves the device, no "AI" wording anywhere a reader sees.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A visitor tells gallr their taste on first open (Priority: P1)

A visitor opens gallr for the first time, or has never saved, visited or followed anything. On Home a sheet rises,
"어떤 전시를 좋아하세요?" / "What exhibitions do you like?", with eight to ten term chips (only terms that at least three running
exhibitions carry) and six featured covers. They tap three or more chips or covers and start; 내 취향 is built from
those picks at once. They can also skip. A visitor who skips is never asked again on their own; the sheet opens
only from a 취향 고르기 / PICK YOUR TASTE button. After a skip, and whenever there is nothing to build taste from,
the 내 취향 추천 section shows one prompt card, "전시를 고르거나 저장하면 취향에 맞춰 골라드려요" / "Pick or save
exhibitions and the picks follow your taste", with that button, instead of editorial cards. Editorial picks appear
only under 이번 주 추천 / THIS WEEK'S PICKS.

**Why this priority**: It ends the defect that every first-time visitor sees today, editorial cards under a taste
heading, and gives the ranker a real signal within twenty seconds of first open.

**Independent Test**: On a fresh install, open Home, pick three chips, start: the 내 취향 rail shows cards whose
first reason names one of the picks. Reinstall, skip: the prompt card shows, no editorial card sits under 내 취향,
and reopening the app does not raise the sheet again.

**Acceptance Scenarios**:

1. **Given** a fresh install with no saves, visits or follows, **When** Home finishes loading, **Then** the taste
   sheet is shown with eight to ten term chips and six featured covers.
2. **Given** the sheet, **When** the visitor has selected fewer than three items, **Then** the start button is
   disabled and says how many are still needed; **When** three or more, **Then** it is enabled.
3. **Given** three picks and start, **When** Home returns, **Then** the 내 취향 section leads with the visitor's
   name or 내 취향 추천, the subtitle counts the picks ("선택 3"), the chips row shows the picked terms, and every card
   in the rail carries a reason that names a pick.
4. **Given** the sheet, **When** the visitor skips, **Then** the prompt card with 취향 고르기 shows in the 내 취향 추천
   section, no editorial card appears under it, and the sheet does not show again on later opens.
5. **Given** a skipped visitor, **When** they tap 취향 고르기 or save an exhibition, **Then** the sheet opens (from
   the button) or the rail is built from the save (from the save) and the prompt card goes away.
6. **Given** a visitor with picks, **When** they tap any 내 취향 chip, **Then** the sheet opens with their current
   picks already selected and lets them change them.
7. **Given** fewer than eight terms are carried by three or more running exhibitions, **When** the sheet opens,
   **Then** it shows the terms that qualify (at least four) and the six covers; with fewer than four qualifying
   terms the sheet shows covers only.
8. **Given** the update-required screen or a sign-in screen is showing, **When** the app would show the taste sheet,
   **Then** it waits until those are dismissed; it never covers the update-required screen, and it shows after the
   update nudge card has been answered.
9. **Given** the app language is English, **When** the sheet shows, **Then** every word on it is English, and the
   same picks are kept when the language is switched.

---

### User Story 2 - Every pick is explained by the visitor's own choices (Priority: P1)

Once a visitor has any taste signal (a pick, a save, a visit or a follow), every card in the Home 내 취향 rail and in
the 내 취향 기반 / BASED ON YOUR TASTE section of 모두 보기 carries a first reason that names the visitor's own
choice: "좋아하는 #미니멀" / "YOUR PICK · #MINIMAL", 좋아하는 "박서보"와 같은 작가: 박서보 / YOUR PICK "…" · SAME
ARTIST: …, 저장한 "손끝에서"와 공통 주제: 신체 / BECAUSE YOU SAVED "…" · SHARED THEME: …. The Home rail shows only
cards with such evidence, between one and six; editorial picks never fill a taste slot. The 모두 보기 list keeps its
second section, 이번 주 볼 만한 전시 / WORTH SEEING THIS WEEK, for editorial cards with their own honest reasons.
Opening an exhibition from 내 취향 shows the same reason under the title on the exhibition page.

**Why this priority**: Explanations that name what the visitor did are what makes a pick feel accurate; reasons that
fire for everything teach visitors to ignore them (spec 088, User Story 3).

**Independent Test**: With one pick (#사진) and one save, open 모두 보기: every card in 내 취향 기반 starts its reason
with 좋아하는 or 저장한; no card there reads 추천 전시 or 곧 종료 alone; opening a card shows that reason on the
exhibition page.

**Acceptance Scenarios**:

1. **Given** a visitor with picks and saves, **When** the Home 내 취향 rail is shown, **Then** each card's first reason
   names a pick, a save, a visit or a followed gallery, and the rail holds between one and six cards.
2. **Given** only one exhibition carries personal evidence, **When** Home is shown, **Then** the rail shows that one
   card and no editorial card beside it under the taste heading.
3. **Given** no exhibition carries personal evidence although picks exist (for example every matching exhibition
   has ended), **When** Home is shown, **Then** the section shows a prompt card whose copy acknowledges the picks
   ("선택한 취향에 맞는 전시가 아직 없어요 · 취향 바꾸기" / "Nothing running matches your picks yet · Change picks")
   and whose button opens the sheet with the current picks selected.
4. **Given** a card has both a pick-based and a save-based reason, **When** its reasons are shown, **Then** the
   pick-based reason comes first, at most two reasons are shown, and each names the single strongest source for its
   kind.
5. **Given** a card chosen for a reviewed term the visitor picked, **When** the reason is shown, **Then** it names
   that exact term in the app language; a term the visitor did not pick is never presented as a pick.
6. **Given** the visitor opens an exhibition from the 내 취향 rail or list, **When** the exhibition page shows,
   **Then** the same reason line sits under the title; opened from anywhere else, no reason line is shown.
7. **Given** the 모두 보기 list, **When** it is shown, **Then** editorial cards sit only under 이번 주 볼 만한 전시 with
   reasons such as 에디터 큐레이션 or 곧 종료, never under 내 취향 기반.

---

### User Story 3 - A visitor can correct a pick (Priority: P1)

Each card in 내 취향 has an overflow action 덜 보기 / "Less like this". Choosing it hides that exhibition from 내 취향
until the exhibition closes and lowers, a little, every exhibition that shares its reviewed terms or credited
artists. A snackbar "숨겼어요 · 종료일까지" / "Hidden until it closes" offers 되돌리기 / UNDO. A term the visitor has
dismissed leaves the 내 취향 chips and appears in a 덜 보기 row inside the taste sheet with its own undo. Saving,
visiting or following a hidden exhibition, or following its gallery, lifts the hide. A dismissal can halve what a
term or artist contributes; it can never cancel a save, visit, follow or pick, and it never moves a card out of the
personal-evidence section.

**Why this priority**: Without a correction the feature cannot learn, and a visitor who sees one wrong card has no
way to say so except to stop using it.

**Independent Test**: Dismiss one card: it disappears from 내 취향 on the next refresh, the snackbar undo brings it
back, dismissing again and then saving it brings it back too; its term is gone from the chips and listed in the
sheet; a second card sharing that term moves down but stays in 내 취향 기반.

**Acceptance Scenarios**:

1. **Given** a card in 내 취향, **When** the visitor chooses 덜 보기, **Then** the card is hidden on the next refresh,
   a snackbar with 되돌리기 shows, and the hide lasts until the exhibition's closing date.
2. **Given** the snackbar, **When** the visitor taps 되돌리기, **Then** the card returns and every effect of that
   dismissal is removed.
3. **Given** a dismissed exhibition, **When** the visitor saves it, logs a visit to it, or follows it or its gallery,
   **Then** the hide is lifted and that dismissal's effects are removed.
4. **Given** a dismissal of an exhibition carrying the term #회화, **When** the chips are shown, **Then** #회화 is not
   among them and the taste sheet lists it in a 덜 보기 row with an undo; **When** that undo is tapped, **Then** the
   term is eligible for the chips again while the exhibition stays hidden until it closes.
5. **Given** two exhibitions A and B share a reviewed term and B is dismissed, **When** 내 취향 is refreshed, **Then**
   A ranks lower than before but stays in 내 취향 기반 with its reason.
6. **Given** a second dismissal of another exhibition with the same term, **When** 내 취향 is refreshed, **Then** the
   term's effect is no larger than after the first dismissal (one term or artist is lowered at most once).
7. **Given** a candidate carrying several dismissed terms and artists, **When** it is ranked, **Then** its score can
   fall to zero and it can drop out of the six-card rail, but it is never shown under an editorial reason.
8. **Given** an exhibition's terms are edited after it was dismissed, **When** the visitor undoes the dismissal,
   **Then** exactly the terms and artists the exhibition carried at the time of dismissal are released.
9. **Given** the visitor signs out or switches account on the device, **When** 내 취향 is shown, **Then** picks and
   dismissals are unchanged (they belong to the device); a reinstall starts fresh.

---

### User Story 4 - Every running exhibition carries reviewed terms and artists (Priority: P1)

Staff tag the whole running and upcoming catalogue without a store release. A desk tool drafts, for each
exhibition, the artist credits it can find in the exhibition's own text (matching existing canonical artists first,
proposing a new artist only when both the Korean and English names are in the source) and three to six terms from
the reviewed vocabulary, each with the sentence of the description that supports it. The tool refuses a term that
is not in the vocabulary and a supporting sentence that is not in the exhibition's text. A person applies the
credits and terms in Admin's Art / 작품 정보 section, exactly as the existing add-exhibition flow does. The vocabulary
grows by about twenty-five terms the Seoul catalogue needs; the final list is the owner's. Readers on the current
release see better 내 취향 as soon as terms are published, because reviewed terms already carry weight there.

**Why this priority**: With tags on 6% of the catalogue no ranking can give specific reasons. This is the floor the
other stories stand on.

**Independent Test**: Run the desk tool for ten running exhibitions, apply its output in Admin, publish; a visitor
who picks one of those terms sees those exhibitions in 내 취향 with the term named in the reason.

**Acceptance Scenarios**:

1. **Given** a published running exhibition with a description, **When** the desk tool drafts for it, **Then** it
   proposes three to six vocabulary terms, each with a verbatim supporting sentence from the description, and the
   credits it found, each marked as an existing canonical artist or a proposed new one with both names.
2. **Given** a draft that names a term outside the vocabulary or quotes a sentence not in the text, **When** the
   tool checks it, **Then** the check fails and names the offending term or sentence.
3. **Given** a reviewed draft, **When** a staff member applies it in Admin and publishes, **Then** the public
   catalogue shows those terms and credits for that exhibition, and nothing is published without a person's action.
4. **Given** the vocabulary expansion, **When** it is applied, **Then** every existing term keeps its identifier and
   labels, Admin and the gallery workspace accept the new terms, and an app build that does not know a new term
   ignores it without error or a missing label.
5. **Given** the current app release, **When** terms are published for an exhibition, **Then** 내 취향 on that
   release can already name those terms in reasons after its next refresh.
6. **Given** the tagging pass is complete, **When** the coverage check (User Story 5) runs, **Then** every running
   and upcoming exhibition has at least three reviewed terms, and credits wherever the source names an artist.

---

### User Story 5 - Catalogue data a visitor can trust (Priority: P1)

The owner asked for the data to be more reliable. Reliability here means four things a reader or a route can depend
on. First, provenance: every reviewed term and artist credit records what supports it (the sentence or source) and
who applied it. Second, freshness: when an exhibition's description or dates change after its terms were reviewed,
those terms are flagged stale in Admin for re-review, the same way an approved note is flagged when its source
changes; readers keep seeing the terms until a person re-confirms or removes them. Third, identity: two canonical
artists with the same normalised names cannot be created, and an existing duplicate can be merged so credits follow
the survivor. Fourth, hours: an exhibition's free-text opening hours get a structured weekly reading that a person
confirms once, so routes and open-now status stop calling the venue "unverified" when its hours are known, while
hours nobody confirmed stay marked unverified. A coverage and freshness check summarises all of this before every
store release, with an enforced floor and a stated aspiration.

**Why this priority**: A wrong tag produces a confident wrong reason; a closed door at the end of a route is the
most damaging wrong answer the app can give (spec 088); both erode the trust the other stories build.

**Independent Test**: Edit a tagged exhibition's description in Admin: its terms show a stale flag in the review
queue and still show to readers; confirm them: the flag clears. Try to create an artist whose names match an
existing one: it is refused and the existing one is offered. Confirm a venue's weekly hours: a route through it no
longer marks it unverified.

**Acceptance Scenarios**:

1. **Given** a term or credit is applied in Admin, **When** it is saved, **Then** its supporting sentence or source
   and the staff member who applied it are recorded with it and visible in Admin.
2. **Given** a published exhibition with reviewed terms, **When** its description or dates change and are
   published, **Then** the terms are marked stale in Admin's queue, readers still see them, and a person can
   re-confirm (clearing the flag) or remove them.
3. **Given** a published exhibition whose terms are stale, **When** the coverage check runs, **Then** it counts that
   exhibition as needing review.
4. **Given** an artist exists, **When** staff try to create another whose Korean or English name normalises to the
   same value, **Then** creation is refused and the existing artist is offered instead.
5. **Given** two artists that are duplicates, **When** staff merge them, **Then** every credit of the merged artist
   now points at the survivor, the public catalogue shows one artist, and the merge is recorded.
6. **Given** an exhibition with free-text hours that the app can already read, **When** staff open it in Admin,
   **Then** a structured weekly reading is proposed and a person can confirm or correct it; once confirmed, routes
   and open-now treat the hours as verified and no longer show the unverified mark for that venue.
7. **Given** hours that cannot be read or were never confirmed, **When** a route includes the venue, **Then** the
   unverified mark is shown exactly as today.
8. **Given** a release is being prepared, **When** the coverage check runs, **Then** it reports, for running and
   upcoming exhibitions: the share with at least three reviewed terms, with credits where a source names an artist,
   with confirmed hours, with coordinates, with dates not in the past, with an English description, and the number
   flagged stale; the result is recorded in the release pull request, and the release does not proceed below the
   enforced floor.

---

### User Story 6 - Recommendations are measured before release (Priority: P2)

Before the ranking changes ship, an offline accuracy check runs against a snapshot of the published catalogue with
at least fifteen scenarios the owner labels (a set of picks or saves, and the three exhibitions the owner expects on
top). The check passes only when at least three of every five expected exhibitions appear in the top three across
the scenarios, every shown reason is supported by the scenario's picks or saves, and the correction rules of User
Story 3 hold exactly (one dismissal halves a term's effect, a second adds nothing, no score goes below zero, undoing
and lifting leave no trace). A failed check holds the release until the owner either relabels a scenario they got
wrong or accepts a lower bar in writing; nobody tunes the bar silently.

**Why this priority**: "Feels accurate" is not a release gate; a number the owner labelled is.

**Independent Test**: Run the check on the snapshot with the fifteen scenarios; it reports precision and the list
of scenarios that missed, and fails when any correction-rule assertion is violated.

**Acceptance Scenarios**:

1. **Given** the labelled scenarios, **When** the check runs, **Then** it reports precision at three across them
   and passes at 0.6 or above.
2. **Given** a scenario, **When** its top three are produced, **Then** every reason shown names a pick, save, visit
   or follow present in that scenario.
3. **Given** the correction scenarios, **When** the check runs, **Then** each rule of User Story 3 holds to the
   number stated in the design.
4. **Given** the check fails, **When** the owner reviews the missed scenarios, **Then** the record shows which
   scenario missed and why, and the release waits.

---

### User Story 7 - Loading reads as work (Priority: P3)

While 내 취향 is being computed, Home and the 모두 보기 list show the standard skeleton cards instead of a single line
of text, so the swap from the featured banner to the 내 취향 banner is not a jump.

**Why this priority**: Small, but it is the one moment where the feature can look like it is doing something.

**Independent Test**: Open Home with a cold cache: skeleton cards occupy the 내 취향 slot until the rail is ready,
and the banner does not change height when it arrives.

**Acceptance Scenarios**:

1. **Given** 내 취향 is computing, **When** Home is shown, **Then** skeleton cards fill the section at the final
   height; **When** the rail is ready, **Then** it replaces them without moving the content below.

---

### Edge Cases

- One or two picks: the start button stays disabled and shows the count still needed; closing the sheet counts as
  a skip only if the visitor taps 건너뛰기, otherwise it reopens next time.
- A visitor picks only covers, no chips: the picks are those exhibitions; their terms and artists drive the reasons
  ("좋아하는 "X"와 같은 작가").
- Chosen covers leave the pool like saves: the visitor said they like them, so 내 취향 shows what is like them, not
  them.
- A picked term later leaves the running catalogue (no running exhibition carries it): the chip stays, the term
  simply produces no cards; the seeded-but-empty prompt handles the all-empty case.
- A term is retired or renamed in the vocabulary: identifiers are stable, so picks and dismissals keep pointing at
  the same term; a retired term disappears from chips and the sheet.
- An app build older than this feature receives new term identifiers from the catalogue: it ignores them.
- The device language changes: labels switch; picks and dismissals are identifiers and are unchanged.
- Two accounts on one device: picks and dismissals are the device's and are shared; the account's saves differ.
- Reinstall: picks and dismissals are gone; the sheet shows again.
- A dismissed exhibition closes: its hide expires with it; its record is dropped and its terms are released.
- A candidate with several dismissed terms and artists: the penalties add, the score may reach zero, the card may
  leave the six-card rail, and it still never shows under an editorial reason.
- Staff change an exhibition's terms while a visitor has it dismissed: the dismissal keeps the identifiers captured
  at the time, so undo releases exactly those.
- A stale-flagged exhibition is unpublished: the flag goes with it; republishing re-evaluates freshness.
- A merge is attempted on an artist with credits on a draft revision: the merge updates drafts and published
  versions alike and is refused if either artist is in use by a conflicting draft of another owner.
- Hours confirmed by staff later change in the gallery's own text: the confirmed reading is flagged stale like a
  term.

## Requirements *(mandatory)*

### Functional Requirements

**Taste pick (Stage 1)**

- **FR-001**: On Home, when the visitor has no saves, visits or follows, no stored picks and has not skipped, the
  app MUST show the taste sheet with eight to ten term chips (terms carried by at least three running exhibitions;
  at least four, else covers only) and six featured covers, in the app language.
- **FR-002**: The sheet MUST require at least three selections to start, MUST say how many are still needed, and
  MUST offer a skip that is recorded once so the sheet never shows on its own again.
- **FR-003**: Picks (term identifiers and exhibition identifiers) and the skip MUST be stored on the device only, as
  explicit visitor-entered preferences, MUST survive sign-out and account switch, and MUST NOT sync or be sent
  anywhere.
- **FR-004**: After a skip, or whenever no taste signal exists, the 내 취향 추천 section MUST show the prompt card with
  취향 고르기 and MUST NOT show editorial cards under a taste heading. When picks exist but no running exhibition
  matches, the prompt card MUST acknowledge the picks and open the sheet with them selected.
- **FR-005**: The 내 취향 chips MUST be buttons that open the sheet with the current picks selected; changes MUST take
  effect on the next refresh.
- **FR-006**: The sheet MUST NOT show over the update-required screen and MUST wait until the update nudge card and
  any sign-in screen are dismissed.

**Reasons and rail (Stage 1)**

- **FR-007**: A picked term MUST count as taste evidence for exhibitions that carry it as a reviewed term (strongly)
  or as a detected term (weakly, scaled by rarity as today), and MUST be named in the reason as the visitor's pick.
- **FR-008**: A picked cover MUST act as an anchor like a save (same kinds of evidence, at six tenths of a save's
  weight) and MUST leave the candidate pool; its reasons MUST name it as the visitor's pick.
- **FR-009**: Once any taste signal exists, the Home 내 취향 rail MUST contain only cards with personal evidence,
  from one to six; the 모두 보기 list MUST keep 내 취향 기반 and 이번 주 볼 만한 전시 and MUST never show an editorial
  reason under 내 취향 기반 (spec 088 FR-017 and FR-022 unchanged).
- **FR-010**: Each card MUST show at most two reasons; pick-based reasons MUST come before save-based ones, then the
  existing order (artist, term, text); each reason MUST name the single strongest source of its kind.
- **FR-011**: The exhibition page MUST show the card's reason line under the title when opened from 내 취향 and MUST
  NOT show one otherwise.

**Correction (Stage 1)**

- **FR-012**: Each 내 취향 card MUST offer 덜 보기, which hides the exhibition until its closing date and records one
  dismissal holding the exhibition, its closing date, and the reviewed term and credited artist identifiers it
  carried at that moment.
- **FR-013**: A dismissal MUST lower every candidate that carries a dismissed term or artist by at most half of what
  a saved shared term or artist would earn, MUST apply at most once per term or artist however many dismissals
  carry it, MUST be applied after all positive evidence, MUST never take a score below zero, and MUST never move a
  card out of the personal-evidence section or into an editorial reason.
- **FR-014**: 되돌리기 on the snackbar and a save, visit or follow of the hidden exhibition or its gallery MUST remove
  that dismissal entirely; a per-term undo in the sheet MUST release that term from every standing dismissal while
  the hides stand; a dismissal MUST expire when its exhibition closes.
- **FR-015**: A term with a standing dismissal MUST be excluded from the 내 취향 chips and listed in the sheet's
  덜 보기 row with an undo.
- **FR-016**: Dismissals MUST be stored on the device only, with the same lifecycle as picks (FR-003).

**Catalogue coverage (Stage 0)**

- **FR-017**: A desk tool MUST draft, for a published exhibition, three to six vocabulary terms each with a verbatim
  supporting sentence from the exhibition's description, and artist credits matched to existing canonical artists
  or proposed with both names from the source; it MUST refuse a term outside the vocabulary or a sentence absent
  from the description, and it MUST NOT write to the catalogue itself.
- **FR-018**: The vocabulary MUST be extended additively with stable identifiers and bilingual labels (final list
  decided by the owner; starting list in the design); existing identifiers MUST NOT change; apps that do not know
  a new identifier MUST ignore it.
- **FR-019**: Terms and credits MUST reach readers only through the existing staff review and publish flow.

**Reliability (Stage 0)**

- **FR-020**: Every applied term and credit MUST record its supporting sentence or source and the staff member who
  applied it, visible in Admin.
- **FR-021**: When a published exhibition's description or dates change, its reviewed terms (and any confirmed hours
  reading) MUST be flagged stale for re-review in Admin, MUST remain visible to readers meanwhile, and a person MUST
  be able to re-confirm (clearing the flag) or remove them.
- **FR-022**: Creating a canonical artist whose normalised Korean or English name matches an existing one MUST be
  refused with the existing artist offered; staff MUST be able to merge two artists so every credit follows the
  survivor, with the merge recorded.
- **FR-023**: For free-text hours the app can read, Admin MUST propose a structured weekly reading that a person
  confirms or corrects; confirmed readings MUST be treated as verified by routes and open-now, unconfirmed or
  unreadable hours MUST stay marked unverified.
- **FR-024**: A read-only coverage and freshness check MUST report, for running and upcoming exhibitions, the share
  with at least three reviewed terms, with credits where the source names an artist, with confirmed hours, with
  coordinates, with dates not in the past and with an English description, plus the count flagged stale; it MUST be
  recorded in every release pull request; the release MUST NOT proceed below the enforced floor (Assumptions).

**Measurement and boundaries**

- **FR-025**: An offline accuracy check over a published-catalogue snapshot with at least fifteen owner-labelled
  scenarios MUST pass only at precision@3 of 0.6 or higher, with every shown reason supported by the scenario and
  every correction rule of FR-013 and FR-014 holding exactly; a failure MUST hold the release until the owner
  relabels or accepts a lower bar in writing.
- **FR-026**: Ranking MUST stay on the device with no hosted model, no network call for ranking, no new permission,
  no location on the For You screen (spec 088 FR-005), and no paid service.
- **FR-027**: Spec 088 FR-018 and spec 073 User Story 1 criterion 4 are amended to this extent only: explicit,
  visitor-entered preferences (picks, chosen covers, dismissals) MAY be stored on the device; a derived taste
  representation MUST still never be stored or transmitted.
- **FR-028**: Analytics events and their allowlisted fields MUST stay unchanged; picks, dismissals, reasons and
  scores MUST never be recorded.
- **FR-029**: No reader-facing copy MAY say "AI", name a model, or carry a drafting or review line.

### Key Entities

- **Taste pick**: the visitor's explicit choice on this device: picked term identifiers and picked exhibition
  identifiers, plus whether the sheet was skipped. Lives on the device only.
- **Dismissal**: one record per exhibition the visitor asked to see less of: the exhibition, its closing date, and
  the term and artist identifiers it carried when dismissed. Counts per term or artist are derived from standing
  records, never stored.
- **Reviewed term**: a vocabulary term attached to a published exhibition, with its supporting sentence, the staff
  member who applied it, and a stale flag.
- **Artist credit**: a canonical artist attached to a published exhibition, with its source and applier; canonical
  artists are unique by normalised names and can be merged.
- **Confirmed hours**: a structured weekly reading of an exhibition's hours, confirmed by a person, with a stale
  flag when the source text changes.
- **Coverage report**: the per-release summary of catalogue completeness and freshness.
- **Accuracy scenario**: an owner-labelled set of picks or saves with the expected top three exhibitions, over a
  catalogue snapshot.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every running and upcoming exhibition has at least three reviewed terms, and credits wherever the
  source names an artist, before release 1.15 is submitted (from about 6% today); the enforced floor in Assumptions
  applies to exhibitions added in the final week.
- **SC-002**: A first-time visitor reaches a 내 취향 rail built from their own picks within 20 seconds of first open,
  and no editorial card is ever shown under a taste heading.
- **SC-003**: 100% of cards in the Home 내 취향 rail and in 내 취향 기반 carry a first reason naming the visitor's own
  pick, save, visit or follow, on both platforms and in both languages.
- **SC-004**: A dismissed card is gone on the next refresh in 100% of attempts; undo, lift and expiry each restore
  the pre-dismissal state exactly, verified by the offline check.
- **SC-005**: The offline accuracy check reports precision@3 of at least 0.6 across the fifteen owner-labelled
  scenarios with zero unsupported reasons, and runs as part of the release gate.
- **SC-006**: After the tagging pass, the share of routes showing an "unverified hours" mark for venues whose hours
  are written in the catalogue falls to zero for confirmed venues.
- **SC-007**: Every stale term, credit or hours reading created by an exhibition edit appears in Admin's review queue
  within the same publish, and none is hidden from readers while waiting.
- **SC-008**: Zero duplicate canonical artists can be created after the change, and existing duplicates found by the
  coverage check are merged before 1.15.
- **SC-009**: Nothing about picks, dismissals, reasons or scores leaves the device: the analytics allowlist is
  unchanged and no new network call carries them.
- **SC-010**: Once analytics collection is enabled (separate decision), saves made from 내 취향 per screen display and
  모두 보기 open rate are measurable from existing events; a target is set after a two-week baseline.

## Assumptions

- The enforced coverage floor is: 100% of running and upcoming exhibitions published more than seven days before
  the release, and 90% overall; the aspiration is 100%. The owner may change both.
- The desk tool's drafts are applied by the owner or a publisher-tier staff member; gallery owners keep their
  existing ability to add terms to their own listings.
- The starting vocabulary list is the one in the design (medium 도예, 섬유, 미디어아트, 사운드, 건축·디자인; style
  단색화, 민화, 한국화, 팝, 초현실; theme 신체, 시간, 생태, 노동, 집, 젠더, 아카이브, 풍경; mood 어두운, 따뜻한, 유머,
  쓸쓸한); the owner finalises it after tagging ten exhibitions.
- Stale terms and hours readings stay visible to readers; the owner prefers a possibly dated tag over a missing one
  (the vocabulary's own rule: "a missing tag is quieter than a wrong one" applies to new tags, not to removal).
- Hours confirmation covers the patterns the app already reads (spec 088 FR-006); venues with unreadable hours stay
  unverified until a person writes a structured reading by hand.
- The penalty constants (one dismissal costs half of a saved shared term's or artist's weight) and the pick weights
  are those in the approved design; the plan carries the exact numbers.
- The six covers on the taste sheet are the current featured exhibitions in their Home order.
- Stage 2 (a generated personal line, natural-language search, weekly digest) is a separate specification, written
  only if the post-1.15 observation calls for it.
- Proximity on the For You screen stays off (spec 088 FR-005); revisiting it needs an owner decision.
