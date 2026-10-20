# Research: Choose when you're going

Grounded in `RouteEvaluator` (shared, spec 089), `PersonalRouteComposerViewModel` and `PublicRoutesViewModel`
(composeApp), `DataStorePersonalRouteDraftRepository` and `RouteComposerPresentation` (`composerReferenceDayLine`).

## R1. A chosen visit time is a fourth way to plan the day

**Decision**: Add `RouteVisitTime(day: LocalDate, start: LocalTime)` in `shared/.../route/` and an optional
`visitTime: RouteVisitTime? = null` parameter to `RouteEvaluator.evaluate`. When present the evaluator skips
`planDay` and uses a new `PlannedDayReason.CHOSEN` with `day` and `anchorMinutes = start`. The existing non-now branch
already computes `startMinutes = anchor - firstLegMinutes` (so the walk from the visitor's position ends at the chosen
time and the first visit starts then), walks the timeline from there (waiting for a later opening, as today) and
judges every stop on that day. No stop moves to another day (FR-003). `null` keeps today's behaviour exactly
(FR-004, SC-004).

**Departure**: with `CHOSEN` and a position, the departure line is always shown (`showDeparture` true for CHOSEN),
because the visitor needs to know when to leave on that day.

**Rationale**: One parameter and one enum value reuse the timeline, verdicts and legs unchanged; the rule stays in
`shared` (constitution VI) and is unit-testable with a fixed clock.

## R2. The options are a pure function in shared

**Decision**: `visitTimeOptions(now: LocalDateTime): VisitTimeOptions` returns the 14 days (today and 13 more) and,
for a day, the start times in 30-minute steps from 08:00 to 21:00; for today only steps after `now` (FR-001, FR-002).
A day with no remaining step today (after 21:00) still lists today with no times, so the control shows it disabled
rather than shifting the list.

## R3. The composer keeps the choice on the device, outside the draft

**Decision**: A `RouteVisitTimeRepository` (shared interface, DataStore implementation beside the draft's) stores one
value: the draft id plus the chosen day and time, or nothing for "지금". The composer reads it with the draft and
applies it only when the draft id matches, so a new or replaced draft starts at "지금". When the stored day is before
today, the composer clears it and shows a one-time message ("선택한 날짜가 지나 지금 기준으로 돌아왔어요" / "That day has
passed, so the route is checked from now") (FR-007).

**Rationale**: Putting it in `PersonalRouteDraft` would bump the draft revision and mark a saved route unsaved, and
would risk sending it with a save; a separate key keeps it local by construction (FR-008, FR-009). DataStore keeps it
across leaving the composer and app restarts, matching "on the device".

**Key**: a private module-level `ROUTE_VISIT_TIME_KEY` with the persisted name `route_visit_time`, value
`<draftId>|<yyyy-MM-dd>|<HH:mm>` (CLAUDE.md DataStore rules).

## R4. The preview keeps the choice for one viewing

**Decision**: `PublicRoutesViewModel` holds the chosen time in the open preview's state, reset when another route is
opened. With a choice, it evaluates with `visitTime` instead of its "first shared day" reference; without one,
today's behaviour stays (DD21).

## R5. The control: a summary row that opens a sheet

**Decision**: Under the summary lines of the composer and the preview, a 44dp text row "방문 시간 · 지금 ›" /
"VISIT TIME · NOW ›" (or "10.18(토) 13:00 ›"). It opens a bottom sheet "언제 가세요?" / "WHEN ARE YOU GOING?":
- a horizontal row of day chips: 지금, 오늘, 내일, then "토 10.18"-style chips to 13 days ahead;
- under it a wrapping grid of time chips for the selected day (08:00–21:00 in 30-minute steps).
Choosing "지금" applies and closes. Choosing a day selects it; choosing a time applies day and time and closes. That
is three taps for "tomorrow afternoon" (row, 내일, 13:00) (SC-001). Selected chips use the accent as the
`activeIndicator` role, which DESIGN.md allows for selected filter chips (FR-010). Shapes follow whichever visual
system is on `develop` when this lands (spec 095 rounds chips; this feature adds none of its own).

**Alternatives considered**: platform date and time pickers (two dialogs, minute precision nobody needs, and the
Android and iOS look differs); a slider (inaccessible, imprecise).

## R6. Wording on the summary

- Reference line for a chosen time: "10.18(토) 13:00 기준" / "SAT 10.18 · FROM 13:00"; with a position, followed by the
  existing departure "12:41 출발" / "LEAVE 12:41".
- The control's own label: "방문 시간 · …" / "VISIT TIME · …", "지금" / "NOW".
- The verdict wording is owner decision 1, already implemented (commit `09cd7fdb`).

## R7. What does not change

Visit length (45 minutes), walking times, the planner screen (unreachable; already takes a start time), the share card
and the public web page (they keep their own "today" line), analytics (no new event; FR-009).
