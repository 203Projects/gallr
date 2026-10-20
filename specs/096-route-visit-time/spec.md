# Feature Specification: Choose when you're going

**Feature Branch**: `096-route-visit-time`
**Created**: 2026-10-15
**Status**: Draft
**Input**: User description: "What causes 시간 충돌? It doesn't sound intuitive. Do both: change the wording, and spec a time picker so a route is checked for when the visitor is actually going."

## Background

A route is checked against opening hours by walking it through in order: walking time per leg and 45 minutes per
visit, starting **now** (or at the first stop's next opening, up to a week ahead). Each stop's arrival and visit are
compared with its venue's hours and its exhibition's dates. Because the start is always "now", the same route reads
differently depending on when it is opened: opened at 22:00 it shows evening visit times and stops that cannot be
visited; opened at 10:00 the same route is fine. Visitors plan routes for a later day far more often than for this
minute, so "now" is usually the wrong question.

## Owner decisions (2026-10-15)

1. **Plain wording.** "시간 충돌" becomes "관람 어려움": "1곳 관람 어려움 · 3번" / "1 STOP WON'T WORK · STOP 3",
   "모두 관람 가능" / "ALL STOPS WORK", "관람 가능 · 2곳 운영 시간 미확인" / "ALL WORK · 2 WITH UNCONFIRMED HOURS".
   Implemented on this branch ahead of the picker (User Story 2).
2. **Choose when you're going.** A visitor can tell a route which day and time they are going, and every stop is
   checked against that day and time (User Story 1).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Check a route for the day I'm going (Priority: P1)

On Thursday evening a visitor builds a route for Saturday afternoon. Today the composer checks it from Thursday 22:00
and says two stops won't work. With this feature they choose "토요일 13:00", and the summary, each stop's visit window
and each stop's status are worked out from Saturday 13:00.

**Why this priority**: It removes the false "won't work" results that come from checking a route at the wrong time,
which is the cause of the confusion the owner raised.

**Independent Test**: Build a route whose venues close at 18:00. Opened after 18:00, it reports stops that won't work;
choose tomorrow 13:00 and every stop reports its visit window that afternoon with no warning.

**Acceptance Scenarios**:

1. **Given** a route in the composer, **When** the visitor opens the visit time control, **Then** they can choose
   "지금" (now), today, tomorrow or one of the next days, and a start time.
2. **Given** the visitor chose Saturday 13:00, **When** the route is shown, **Then** the first stop's visit starts at
   13:00 (after the walk from the visitor's position, when it is used), each later stop follows in order, and every
   status refers to Saturday.
3. **Given** a stop's venue is closed on the chosen day, **When** the route is shown, **Then** that stop reads
   "! 휴관일" and the summary counts it, without moving the route to another day.
4. **Given** the visitor chose a time, **When** they read the summary, **Then** it names it, for example
   "10.18(토) 13:00 기준" / "SAT 10.18 · FROM 13:00".
5. **Given** the visitor chose "지금", **When** the route is shown, **Then** it behaves exactly as today, including
   moving to the first stop's next opening when it is closed now.
6. **Given** a public route's preview, **When** the visitor chooses a time, **Then** the preview is checked for that
   time; the route's author and other viewers are not affected.

---

### User Story 2 - Read why a stop won't work (Priority: P1)

The summary says how many stops can't be visited as planned in words a visitor reads without explanation: "1곳 관람
어려움 · 3번" instead of "1곳 시간 충돌 · 3번". Each stop keeps its own reason line ("! 휴관일", "! 19:10 도착 · 19:00
마감", "! 관람 15분밖에 없어요").

**Why this priority**: The owner found "시간 충돌" unintuitive; the wording costs nothing and ships first.

**Independent Test**: Read the composer summary for a route with an open stop, a closed stop and a stop with
unconfirmed hours, in Korean and English.

**Acceptance Scenarios**:

1. **Given** every stop is open at its visit time, **Then** the summary reads "모두 관람 가능" / "ALL STOPS WORK".
2. **Given** one or more stops can't be visited as planned, **Then** it reads "N곳 관람 어려움 · K번" /
   "N STOP(S) WON'T WORK · STOP K", K being the first.
3. **Given** nothing is blocked but some hours are unconfirmed, **Then** it reads "관람 가능 · N곳 운영 시간 미확인" /
   "ALL WORK · N WITH UNCONFIRMED HOURS".

---

### User Story 3 - Keep my plan while I edit (Priority: P2)

A visitor who chose Saturday 13:00 adds, removes and reorders stops. The route stays checked for Saturday 13:00 until
they change it, including after leaving and coming back to the composer.

**Why this priority**: Without it, the choice would reset on every edit and the visitor would have to choose again.

**Independent Test**: Choose a time, edit the route, leave the composer and return; the chosen time is still applied.

**Acceptance Scenarios**:

1. **Given** a chosen time, **When** the visitor adds, removes or reorders stops, **Then** the route is re-checked for
   the same time.
2. **Given** a chosen time, **When** the visitor leaves the composer and comes back, **Then** the same time is applied.
3. **Given** a chosen day that has passed, **When** the composer opens, **Then** it returns to "지금" and says so once.

---

### Edge Cases

- **A start time later than every closing.** Every stop reads "! … 마감" with its own times; nothing is hidden.
- **A route that runs past midnight.** A stop reached after midnight reads as arriving after closing, with no clock
  time past 24:00, as today.
- **Exhibitions on the chosen day.** One that has not opened yet reads "! 10.20 개막"; one that has ended reads
  "! 종료된 전시".
- **Unknown hours.** A stop without confirmed hours is still timed and reads "운영 시간 미확인"; it never counts as a
  stop that won't work.
- **Starting from the visitor's position.** The walk from the position to the first stop is planned to end at the
  chosen time, so the summary shows when to leave ("12:41 출발").
- **Language switch.** Day names and times follow the app language.
- **Large text and screen readers.** The control is reachable with a screen reader and reads the chosen day and time.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The route composer and the public route preview MUST offer a visit time control with "지금" / "NOW"
  (default), today, tomorrow, and each of the following days up to 13 days ahead, and a start time.
- **FR-002**: Start times MUST be offered in 30-minute steps from 08:00 to 21:00; today offers only times from the next
  step on.
- **FR-003**: With a chosen day and time, the route MUST be checked from that time on that day: the first visit starts
  at the chosen time (or the walk from the visitor's position ends then), later stops follow in order, and no stop is
  moved to another day.
- **FR-004**: With "지금", the route MUST be checked exactly as it is today.
- **FR-005**: The summary MUST name the chosen day and time in both languages ("10.18(토) 13:00 기준" /
  "SAT 10.18 · FROM 13:00"), and keep the departure line when the visitor's position is used.
- **FR-006**: The summary verdict MUST use the wording of owner decision 1 (User Story 2).
- **FR-007**: The composer MUST keep the chosen time with the route draft on the device across edits and visits, and
  return to "지금" when the chosen day has passed.
- **FR-008**: A preview's chosen time MUST stay with the viewer: it is not saved to the route, shared, or shown to the
  author or other viewers.
- **FR-009**: The chosen time MUST NOT be sent to the server or included in analytics beyond what the app already
  records.
- **FR-010**: The control's look MUST follow DESIGN.md (monochrome, the accent only for the selected choice).

### Key Entities

- **Visit time**: either "now" or a chosen day (today to 13 days ahead) and start time (30-minute steps). Belongs to the
  composer's draft on the device, or to one viewing of a public route.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A visitor can set "tomorrow afternoon" in at most three taps from the composer.
- **SC-002**: For a route whose venues are open on the chosen day and time, the summary reports no stop that won't
  work in 100% of test routes, whatever the current time of day.
- **SC-003**: The same route with the same chosen day and time gives the same statuses whenever it is opened.
- **SC-004**: Choosing "지금" gives exactly the same result as before this feature in 100% of existing test routes.
- **SC-005**: No visitor-facing text in the app says "충돌" for route timing.

## Assumptions

- Visit length stays 45 minutes per stop; letting the visitor change it is out of scope.
- Walking time is the route's walking time (estimates or walking paths), unchanged by this feature.
- The chosen time is the visitor's own; saved and shared routes do not carry it.
- The public web page for a shared route keeps its own "today" summary; changing the web is out of scope.
- The route planner screen is not reachable today; it already accepts a start time and is not changed here.
- No paid service is involved (owner decision 2026-10-15: free options only).
