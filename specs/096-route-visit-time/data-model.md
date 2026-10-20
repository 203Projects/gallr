# Data Model: Choose when you're going

## RouteVisitTime (new, `shared/.../route/RouteVisitTime.kt`)

| Field | Type | Rule |
|-------|------|------|
| `day` | `LocalDate` | Today to today + 13, Seoul time (`ROUTE_TIME_ZONE`) |
| `start` | `LocalTime` | 08:00 to 21:00, minutes 00 or 30 |

"지금" is the absence of a value (`null`).

## VisitTimeOptions (new, same file)

| Member | Type | Rule |
|--------|------|------|
| `days` | `List<LocalDate>` | 14 days from today |
| `timesFor(day)` | `List<LocalTime>` | 30-minute steps 08:00–21:00; for today only steps strictly after now |

Built by `visitTimeOptions(now: LocalDateTime)`.

## PlannedDayReason (changed)

Adds `CHOSEN`: the visitor chose the day and time; the timeline starts so the first visit begins then.

## RouteEvaluator.evaluate (changed)

New optional parameter `visitTime: RouteVisitTime? = null`. Non-null: `plannedDay = visitTime.day`,
`reason = CHOSEN`, `anchor = visitTime.start`, departure shown whenever an origin is given. Null: unchanged.

## RouteVisitTimeRepository (new, shared)

| Member | Rule |
|--------|------|
| `visitTime: Flow<StoredVisitTime?>` | The stored choice, or null for "지금" |
| `suspend fun set(draftId: String, visitTime: RouteVisitTime?)` | Stores the choice for that draft; null clears it |

`StoredVisitTime(draftId, visitTime)`. DataStore implementation: one private key `ROUTE_VISIT_TIME_KEY`
(`"route_visit_time"`), value `draftId|yyyy-MM-dd|HH:mm`; a malformed value reads as null.

## UI state (changed)

- `RouteComposerUiState.visitTime: RouteVisitTime?` and `visitTimeOptions`, plus a one-time message
  `ComposerMessage.VisitTimePassed`.
- `PublicRoutePreview.visitTime: RouteVisitTime?` for the open preview.
