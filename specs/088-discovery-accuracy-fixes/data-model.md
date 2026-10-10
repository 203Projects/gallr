# Data model: Local discovery accuracy fixes

All types are in-memory domain values in `shared/src/commonMain`. Nothing is persisted, serialized,
synced or sent to analytics.

## Opening hours (`com.gallr.shared.hours`)

### `OpeningHoursCompleteness`
`COMPLETE` | `PARTIAL` | `UNKNOWN`

- `COMPLETE`: at least one weekday was read with explicit days, and no segment was left unread.
- `PARTIAL`: times were read without any day expression and are applied to all seven days, or some
  segments were unreadable alongside readable ones.
- `UNKNOWN`: blank text or nothing readable.

### `DailyOpening`
| Field | Type | Rule |
|---|---|---|
| `opens` | `LocalTime` | |
| `closes` | `LocalTime` | `closes > opens`; overnight ranges are rejected as unreadable |

### `WeeklyOpeningHours`
| Field | Type | Rule |
|---|---|---|
| `byDay` | `Map<DayOfWeek, DailyOpening>` | A missing day means closed only when the reading is `COMPLETE`; a `PARTIAL` reading never claims a closure |
| `completeness` | `OpeningHoursCompleteness` | `UNKNOWN` implies `byDay` is empty |

Derived:
- `isKnownClosedOn(date)`: `completeness == COMPLETE && date.dayOfWeek !in byDay`
- `openingOn(date)`: `DailyOpening?`
- `isVerified`: `completeness == COMPLETE`

### `parseOpeningHours(text: String?): WeeklyOpeningHours`
Pure and total: never throws for any input. Grammar in research R4.

## Route planning (`com.gallr.shared.map`)

### `RoutePlanningRequest` (changed)
| Field | Change |
|---|---|
| `startTime: LocalTime?` | **New**, default `null`. Korea local time. `null` means the earliest opening among candidates |

Existing validation is unchanged.

### `RouteStopHoursStatus` (new)
`VERIFIED` | `UNVERIFIED`. A stop is `UNVERIFIED` when its hours are `PARTIAL` or `UNKNOWN`.

### `RouteStopSchedule` (new)
| Field | Type | Rule |
|---|---|---|
| `exhibitionId` | `String` | Matches `stops[i].id` |
| `arrival` | `LocalTime` | Previous departure plus walk |
| `visitStart` | `LocalTime` | `max(arrival, opens)` when hours are known |
| `visitEnd` | `LocalTime` | `visitStart + visitMinutesPerStop` |
| `closes` | `LocalTime?` | Known closing time on the visit date |
| `hoursStatus` | `RouteStopHoursStatus` | |

Invariant for known hours: `visitEnd <= closes`.

### `ExhibitionRouteEstimate` (changed)
| Field | Change |
|---|---|
| `stopSchedules: List<RouteStopSchedule>` | **New**, same order and size as `stops` |
| `estimatedWaitMinutes: Int` | **New**, sum of `visitStart − arrival` |
| `estimatedTotalMinutes` | Now travel + visit + wait |
| `warnings` | `HOURS_UNVERIFIED` only when some stop is `UNVERIFIED` |
| `recommendationEvidenceByExhibitionId` | For You only; may contain `RecommendationEvidence.Saved`; may be empty for a filler stop |

### `RoutePlanResult.InsufficientCandidates` (changed)
| Field | Change |
|---|---|
| `closedCount: Int` | **New**, default `0`. Distinct venues otherwise eligible but closed on the visit date or closing too soon after the start time |
| `available` | Zero when every venue with known hours is closed, even if unknown-hours venues remain (research R13) |

### `NeighborhoodRoutePlanner.plan` (changed signature)
```
plan(
  exhibitions: List<Exhibition>,
  bookmarkedIds: Set<String>,
  request: RoutePlanningRequest,
  forYouRelevance: List<RouteRelevance> = emptyList(),
): RoutePlanResult
```
`recommendations: List<ExhibitionRecommendation>` is removed. In For You mode, a candidate is eligible only if
it appears in `forYouRelevance`.

## Recommendation (`com.gallr.shared.recommendation`)

### `RecommendationEvidence.Saved` (new)
A data object. Produced only by `rankRouteCandidates` for bookmarked exhibitions. Deduplication key `saved`.
It is ordered before every other evidence tier.

### `RouteRelevanceContext` (new)
| Field | Type | Rule |
|---|---|---|
| `bookmarkedExhibitionIds` | `Set<String>` | Kept as candidates and anchors |
| `visits` | `List<ExhibitionVisit>` | Excluded as candidates, used as anchors |
| `followedGalleries` | `List<FollowedGallery>` | |
| `origin` | `GeoPoint` | Required |
| `maxDistanceKm` | `Double` | `> 0` |
| `today` | `LocalDate` | |

### `RouteRelevance` (new)
| Field | Type | Rule |
|---|---|---|
| `exhibition` | `Exhibition` | Catalogue-visible on `today`, within `maxDistanceKm` |
| `scoreBasisPoints` | `Int` | `0..10_000`, the same scale as `ExhibitionRecommendation` |
| `evidence` | `List<RecommendationEvidence>` | `0..2` entries; saved candidates start with `Saved` |
| `hasPersonalEvidence` | `Boolean` | True for saved, artist, art-term, text or followed-gallery evidence |

Ordering: score descending, then exhibition ID. No diversity pass and no limit.

### `ExhibitionRecommendationIndex` (changed)
Changes from a `fun interface` to an `interface`:
- `recommend(context: RecommendationContext): List<ExhibitionRecommendation>`: unchanged contract.
- `rankRouteCandidates(context: RouteRelevanceContext): List<RouteRelevance>`: **new**.

### Scoring changes (internal)
- Per-kind, per-source strength is the noisy-OR over all matching anchors (research R8); the visible anchor
  is the strongest single match.
- `recommend()` orders candidates with personal evidence (saved-history, visited-history or followed-gallery
  matches) ahead of candidates with only generic evidence, then by score, then by id (research R11).
- The text vector removes venue-boilerplate and ubiquitous n-grams (research R7); the threshold is 0.08.
- `FEATURE_SCHEMA_VERSION` goes from 3 to 4, so a prepared index from an older schema is never reused.

## Application state (`composeApp/.../viewmodel`)

### `RouteUiState.Insufficient` (changed)
| Field | Change |
|---|---|
| `closedCount: Int` | **New**, copied from the planner result |

### `LocalDiscoveryViewModel` (changed dependencies)
- **New**: `nowProvider: () -> Instant` (default `Clock.System.now()`). Each `buildRoute()` stamps
  `visitDate` and `startTime` from it in `Asia/Seoul`.
- The private discovery snapshot keeps the prepared index and the latest history inputs, so For You builds
  can call `rankRouteCandidates` from the request origin.
