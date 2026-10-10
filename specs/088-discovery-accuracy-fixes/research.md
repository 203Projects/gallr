# Research: Local discovery accuracy fixes

All measurements use the production public catalogue (`public.exhibition_catalog_v2`), read-only,
on 2026-10-02/03. No user data was read.

## R1. For You route candidate pool

**Decision**: Add a second query to the prepared recommendation index,
`rankRouteCandidates(context)`, that scores every catalogue-visible exhibition within the route radius
from the route origin. It keeps saved exhibitions, excludes visited ones, keeps candidates with no
personal evidence (score from proximity, featured, editor and closing-soon signals only) and applies no
result limit or diversity pass. The planner's For You mode consumes this ranking instead of the six-item
recommendation list.

**Rationale**: The defect is structural. `LocalDiscoveryViewModel` calls `recommend()` with `limit = 6`
and no origin, and `NeighborhoodRoutePlanner` keeps only candidates that appear in that list. Re-measured
from map centers with a 5 km radius on 2026-10-02: Hannam 2 of 32 open exhibitions qualify, Seongsu 1 of 14,
Cheongdam 2 of 19. Reusing the prepared index keeps one scoring model, one feature schema and the existing
immutable-index reuse, so the list and the route cannot drift apart.

**Alternatives considered**:
- Raise the list limit to the 20-item maximum and pass the origin: still drops candidates with no
  evidence and still excludes saved items, so sparse-history visitors keep failing.
- A separate route scorer in the map package: duplicates feature preparation and weights.
- `RecommendationContext.purpose` flag on `recommend()`: `ExhibitionRecommendation` requires one or two
  evidence entries, which zero-evidence fillers cannot satisfy, and the flag would overload one function
  with two contracts.

## R2. Personal-first stop selection

**Decision**: Each route candidate carries `hasPersonalEvidence` (saved, artist, art-term, text or
followed-gallery evidence). The For You beam search adds a fixed relevance credit for personal candidates on
top of the existing score-based credit, so a personal candidate is preferred unless it costs a large detour.
Remaining stops are filled from non-personal candidates by the same objective.

**Rationale**: Satisfies FR-003 and acceptance scenario 2 with one tunable constant inside the existing
deterministic beam search (`ROUTE_SEARCH_BEAM_WIDTH = 64`). Tests pin the trade-off with explicit
geometry, as the current "small relevance difference" test does.

**Alternatives considered**: Hard two-tier selection (all personal first, then fillers) produces
zig-zag routes when personal picks are far apart, which the existing compact-cluster test rejects.

## R3. Saved exhibitions in For You routes

**Decision**: Add `RecommendationEvidence.Saved`. A saved route candidate gets this as its first
evidence entry and no inferred taste reason. It is never produced by `recommend()`, which keeps excluding
saved items. Labels: `SAVED` / `저장한 전시`.

**Rationale**: FR-004 requires saved stops to be identified as saved, not given an inferred reason. A
typed evidence entry flows through the existing route-evidence snapshot and presentation without a new
parallel field.

## R4. Opening-hours reading

**Decision**: A pure parser in `shared/commonMain` (`com.gallr.shared.hours`) turns the free-text `hours`
field into `WeeklyOpeningHours`: an opening interval per weekday plus a completeness of `COMPLETE`,
`PARTIAL` (times with no days, applied to every day) or `UNKNOWN`. The grammar:

- Segments split on line breaks, `·`, `;` and `/` between groups.
- Time ranges: 12-hour (`10am`, `10:30am`, `12pm`) or 24-hour (`10:00`) joined by `-`, `–`, `~` or
  `to`. A range where only the end has am/pm infers the start meridiem.
- Day expressions: full or three-letter English names, single-character Korean names (`월`…`일`), ranges
  joined by `-`, `–`, `~` and wrapping past Sunday, and lists joined by `,`, `&` or `and`.
- A segment's days and time may appear in either order. A time-only segment followed by a day-only segment
  pairs them, matching the common two-line listing.
- `Closed <days>`, `<days> closed` and Korean `휴관` / `휴무` remove those days.
- Parenthetical notes such as `(Wed & Sat ~9pm)` and phrases such as `and National holidays` or one-off
  dated closures are ignored.
- A closing time at or before the opening time is treated as unknown for that day; overnight hours are
  not supported.

**Rationale**: On 2026-10-02, 61 of 71 live exhibitions had hours. A two-pattern prototype read 58 and the
remaining three were `Tue, Thu, Fri 10:00–18:00 · Wed, Sat 10:00–21:00 · Closed Monday`,
`Tuesday–Sunday 10:00–18:00 · Closed Monday` and `12pm - 7pm`. The 2026-10-03 fixture snapshot (78 live, 68
with hours) adds `Closed on Mondays and Public Holidays` and `2pm - 5:30pm`; the grammar covers all 68, with
one `PARTIAL`. Korean day names are cheap to support and likely as owners enter hours directly.

**Alternatives considered**: Structured hours in the catalogue schema were out of scope (spec FR-020,
no migration). A regex per listing format is brittle for line-order variants.

## R5. Time zone and start time

**Decision**: Hours are Korea time. The view model stamps each route build with the current instant
converted to `Asia/Seoul`, giving `visitDate` and `startTime`. `RoutePlanningRequest.startTime` is
nullable; when null (future dates and existing tests), the planner starts at the earliest opening time
among the selected candidates.

**Rationale**: Matches FR-010 and the existing `Asia/Seoul` usage in `EventRepositoryImpl` and
`MobileAnalyticsEventFactory`. Stamping at build time, not when the planner opens, keeps "now" accurate
when someone leaves the screen open.

## R6. Schedule-aware stop selection and ordering

**Decision**:
1. Candidate filtering drops venues whose known hours show them closed on the visit date, and venues
   whose known closing time leaves less than one visit after the start time plus the walk from the origin.
   Both count toward `closedCount`.
2. Ordering simulates each permutation from the start time: arrival = previous departure + walk; visit
   start = max(arrival, opening); departure = visit start + visit minutes; feasible only if departure is
   at or before closing for every stop with known hours. The cheapest feasible ordering wins, with the
   existing distance and stable-ID tie-breaks.
3. Non-For-You modes first try today's choice (top N by mode order). If no ordering of that set is feasible,
   they fall back to first-fit in mode order: add the next candidate only when a feasible ordering of the
   enlarged set exists. Permutations stay at most 5! = 120 per check.
4. The For You beam search tracks departure time per state and prunes infeasible states.
5. Unknown-hours stops have no time constraint and are marked unverified.

**Rationale**: Keeps today's results unchanged when hours allow, so existing route tests remain valid.
Feasibility is local to the existing search loops, and the worst case for five stops is a few thousand leg
lookups through the cached estimator.

**Alternatives considered**: A full time-window TSP solver is unjustified for 2–5 stops. Penalties
instead of hard feasibility would still produce routes that arrive after closing.

## R7. Text-similarity noise

**Decision**: Before TF-IDF weighting, remove two kinds of character n-grams:
- **Venue boilerplate**: n-grams present in two or more exhibitions at the same venue, removed from those
  exhibitions' vectors only.
- **Ubiquitous**: n-grams whose document frequency exceeds `max(2, 0.20 × catalogue size)`.

Then raise `SIMILARITY_REASON_THRESHOLD` from 0.05 to 0.08 and bump `FEATURE_SCHEMA_VERSION`.

**Rationale**: Prototype on 78 exhibitions (`research` script, 2026-10-03):

| Configuration | Pairs over threshold | Rank of the two Georg Baselitz shows |
|---|---|---|
| Current (2–3 grams, 0.05) | 58.2% | 49 of 3003 |
| Ubiquitous ≤ 20% only, 0.05 | 10.0% | 3 |
| Ubiquitous ≤ 20% + venue, 0.05 | 8.4% | 1 |
| Ubiquitous ≤ 20% + venue, 0.08 | 1.7% | 1 |

The same-artist pair (Thaddaeus Ropac and Sehwa Museum) becomes the strongest similarity in the catalogue,
which is the behaviour the reason should express. The `max(2, …)` floor keeps tiny test catalogues from
losing every feature.

**Alternatives considered**: Raising only the threshold leaves boilerplate driving the top pairs. 3-gram
only lowered the Baselitz rank to 5. Word tokens break Korean, which has no reliable word segmentation here.

## R8. Aggregating taste evidence

**Decision**: For each kind of personal evidence (artist, art term, text) and each source (saved,
visited), combine per-anchor strengths with a noisy-OR, `1 − Π(1 − sᵢ)`. The result stays in [0, 1], each
extra match adds less, and the existing per-kind weights keep any one kind bounded. The visible evidence
still names the strongest single anchor.

**Rationale**: Satisfies FR-015 and FR-016 without changing weights, the score scale or evidence types.
Deterministic and independent of anchor order.

**Alternatives considered**: Sum then cap is order-independent but saturates abruptly. Recency decay
would need visit and save timestamps in the ranking contract, which this feature does not require.

## R9. Catalogue fixture

**Decision**: Commit a test fixture of the published catalogue as raw catalogue-view JSON rows inside a
Kotlin `commonTest` source file, decoded through the production `ExhibitionDto` → `toDomain()` path. Each
row is one raw string literal, well under the 64 KB constant limit; the largest description is 13.8 KB.
The export query and the generator live in `specs/088-discovery-accuracy-fixes/fixture/`. The reference
date is 2026-10-02 (a Friday). The venue `contact` field (gallery phone numbers and emails) is excluded by
both the query and the generator: no test needs it and it should not live in the repository.

**Rationale**: KMP `commonTest` has no portable resource loading. Decoding real rows also exercises DTO
mapping for `hours`, artists and terms. Only public, published fields are included.

**Alternatives considered**: Hand-built synthetic exhibitions cannot reproduce boilerplate noise or the
real hours formats. A JSON resource needs a platform-specific loader per target.

## R11. Personal evidence ranks first in the For You list

**Decision**: `recommend()` sorts candidates that carry personal evidence (saved-history, visited-history or
followed-gallery matches) ahead of candidates explained only by generic signals, then by score, then by id.
The diversity pass and the six-item limit are unchanged.

**Rationale**: Found while running the fixture taste scenarios. With the spec 074 weights, a Featured flag
(0.08) plus an editor pick (0.05) outweighs a genuine text match: the two Georg Baselitz shows score 0.138
cosine after calibration, worth 0.028 of weight. So the shows a visitor saved could never surface their
closest match in the top three (SC-005), and the list would not reflect "selected using my saves" (spec
073). The tier mirrors the For You route rule (R2) and keeps the cold-start list unchanged, since no
candidate has personal evidence without history.

**Alternatives considered**: Raising the text weight changes the relative weight of every signal and would
need the 074 weights re-justified. Rescaling cosine to a saturation curve still left Featured ahead of
realistic similarities (0.1–0.3).

## R12. Partial hours never claim a closure

**Decision**: `isKnownClosedOn` is true only for a `COMPLETE` reading. A `PARTIAL` reading (times without
days, or an unreadable segment next to readable ones) never excludes a venue; its known days still bound the
visit.

**Rationale**: A partial listing may have described the missing weekday in the part that could not be read,
so excluding the venue would risk hiding an open show. The data model table in Phase 1 said a missing day
under `PARTIAL` meant closed; the implementation and documents now follow this safer rule.

## R13. Nothing open: shortage instead of an unverified route

**Decision**: If any venue in scope has known hours and all of them are closed or closing too soon,
`plan` returns `InsufficientCandidates(available = 0, closedCount)` even when unknown-hours venues
remain. The planner message for that case reads "지금 열려 있는 전시가 없습니다. 주변 N곳은 문을
닫았거나 곧 닫습니다." If no venue in scope has known hours, nothing can be inferred and the route is
built from unverified stops as before.

**Rationale**: Seen on the iOS simulator at 19:52: with 32 known venues closed, a two-stop route was
built from the only two venues with no listed hours. Those venues are almost certainly closed too; an
honest shortage serves the visitor better than a route of unverified stops. R12 (a partial reading never
claims a closure) still holds for individual venues; this rule reasons about the neighbourhood.

## R10. Unchanged boundaries

- Analytics: `routeCreated` and `routeStarted` keep the same fields. The total-duration band now
  includes waiting time, a value change inside an allowlisted band, not a new field.
- No schema, migration, permission, network call or persisted profile.
- Paid promotion stays outside both queries; neither reads `PromotedExhibition`.
