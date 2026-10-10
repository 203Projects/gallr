# Data model: Personal routes

Decision identifiers refer to [`docs/designs/2026-10-07-personal-routes-design.md`](../../docs/designs/2026-10-07-personal-routes-design.md).

## Server (Supabase, `public` schema)

### `personal_routes`

| Column | Type | Rules |
|---|---|---|
| `id` | uuid, primary key | Chosen by the app (E-D17); no default needed |
| `owner` | uuid, not null | References `auth.users(id)` on delete cascade |
| `name` | text, not null | `char_length(btrim(name)) between 1 and 60` |
| `is_published` | boolean, not null, default false | Changed only by `publish_personal_route` |
| `published_at` | timestamptz, null | Set once by the first successful publish; never cleared (E-D14) |
| `revoked_at` | timestamptz, null | Set only by `revoke_personal_route` |
| `created_at` | timestamptz, not null, default now() | |
| `updated_at` | timestamptz, not null, default now() | Bumped by every save; serves as the route revision (E-D16) |

Row-level security: owners select their own rows; anonymous and signed-in roles select rows where `is_published and revoked_at is null`. No insert, update or delete policies; writes go through the functions below (E-D4 baseline, design "Supabase").

### `personal_route_stops`

| Column | Type | Rules |
|---|---|---|
| `route_id` | uuid, not null | References `personal_routes(id)` on delete cascade |
| `position` | smallint, not null | 0-based; primary key (`route_id`, `position`) |
| `exhibition_id` | text, not null | Catalogue id (`exhibition_catalog_v2.id` is text), no foreign key so a stop survives catalogue edits |
| `name_ko`, `name_en` | text | Snapshot copied from `exhibition_catalog_v2` (E-D7) |
| `venue_name_ko`, `venue_name_en` | text | Snapshot |
| `latitude`, `longitude` | double precision, not null | Snapshot; ids without coordinates are rejected (RO5) |
| `region_ko`, `region_en`, `city_ko` | text | Snapshot district and city for labels (RR2) |

Row-level security: select allowed when the parent route is selectable by the caller under the rules above.

Invariants enforced by `save_personal_route`: 2–10 stops, distinct `exhibition_id`, positions 0..n−1 contiguous.

### `route_page_daily`

| Column | Type | Rules |
|---|---|---|
| `route_id` | uuid, not null | References `personal_routes(id)` on delete cascade |
| `day` | date, not null | Asia/Seoul calendar day |
| `event` | text, not null | `route_page_opened` or `route_page_started` |
| `shared` | boolean, not null | True when the link carried `s=share` |
| `count` | integer, not null, default 0 | Incremented by `record_route_page_event` |

Primary key (`route_id`, `day`, `event`, `shared`). No direct grants to anonymous or signed-in roles (E-D5).

### `personal_route_recipient_loop` (view)

Aggregate only: for routes grouped by the ISO week of `published_at`, the number published and the number with at least one `route_page_opened` where `shared` is true within 7 days of `published_at`, plus `route_page_started` per `route_page_opened` (E-D14, SC-001). Readable by staff only.

## Device (shared/commonMain)

### `PersonalRoute` (domain)

| Field | Type | Rules |
|---|---|---|
| `id` | String | App-chosen uuid |
| `name` | String | 1–60 after trim |
| `stops` | List<PersonalRouteStop> | 2–10 for saving, distinct `exhibitionId`; drafts may hold 0–10 |
| `isPublished` | Boolean | |
| `revision` | Instant? | Server `updated_at`; null for never-saved |

### `PersonalRouteStop` (domain)

`exhibitionId`, `nameKo`, `nameEn`, `venueNameKo`, `venueNameEn`, `point: GeoPoint`, `regionKo`, `regionEn`, `cityKo`. In a draft, stops added from the live catalogue carry catalogue values; after a save they carry the server snapshot.

### `PersonalRouteDraft` (DataStore, one per device)

| Field | Type | Rules |
|---|---|---|
| `draftId` | String | New uuid for each new draft |
| `revision` | Long | Incremented on every change (RO2, RO3) |
| `route` | PersonalRoute | `route.id` is the app-chosen remote id (E-D17) |
| `ownerAccountId` | String? | Account that last saved it; mismatch detaches (E-D8) |
| `savedRevision` | Long? | Draft revision acknowledged by the last successful save; drives "저장됨 / 저장 안 됨" |
| `pendingAction` | PendingAction? | See below |

Decoding failure: replaced by an empty draft and logged as `route_draft_decode_failed` (first-review correction).

### `PendingAction`

`kind` (SAVE or SHARE), `createdAt` (Instant), `draftId`, `draftRevision`. Valid for 30 minutes. Cleared on any draft change, draft replacement or sign-in cancel; runs only when `draftId` and `draftRevision` match (RR1, RO2).

### Draft operations (atomic, in the draft repository)

- `append(exhibition)`: refuses when 10 stops or duplicate; refuses exhibitions without coordinates (RO5).
- `move(from, to)`, `remove(position)` returning an undo token, `undo(token)`: undo refuses if it would exceed 10 or duplicate, and expires on any later change (RO4).
- `rename(name)`, `replace(newDraft)`, `detach()` (new remote uuid, clears owner and saved revision).
- `setPending(kind)`, `clearPending()`, `acknowledgeSave(draftId, sentRevision, savedRoute)`: applies the remote id only when `draftId` matches and marks saved only when `sentRevision` equals the current revision (RO3).

### `PersonalRouteEvaluation` (pure)

Inputs: stops in order, current catalogue exhibitions by id, origin (device point or stop 1), `now` in Asia/Seoul, leg estimator. Output: planned day and label, anchor, optional departure (shown only when later than now, DR-D31), per-stop `RouteStopVerdict`, legs, totals, conflict count and first conflict.

`RouteStopVerdict`: `Open`, `ClosedOnPlannedDay`, `ArrivesAfterClose`, `VisitCutShort(minutes)`, `NotYetOpen(openingDate)`, `Ended`, `Unavailable`, `HoursUnknown`.

Reference-day rule: anchor at the later of now and the first stop's opening today; when today cannot be walked from the first stop, roll to the next day within 7 days on which its venue opens and its exhibition runs, never past its end; when the first stop is HoursUnknown, Ended or Unavailable, anchor now with no rollover (design "Reference time", E-D15).

### Location permission state

`LocationPermissionStatus`: `GRANTED`, `CAN_ASK`, `DENIED_PERMANENTLY`, refreshed on app resume (RO6).

## Web (route page)

### Page view model (derived per request)

Route name, author display name, stop count, total straight-line walking distance, per-stop: number, title, venue, district label, today's hours, today's verdict (same wording table as the app), walking estimate from the previous stop, directions link when listed. Verdict line per RO1. Open Graph fields per DR-D20.

## State transitions

```
personal route:
  (none) ──save──► saved, private ──publish (first share)──► public ──revoke (staff)──► revoked
                         ▲                                     │
                         └──────────── save (edits) ───────────┘   (stays public; readers see the new version)
  any owned state ──delete (owner)──► gone (stops and counts cascade)

draft pending action:
  none ──tap 저장/공유 while signed out──► pending(kind, draftId, revision)
  pending ──signed in, same draft and revision, < 30 min──► run ──► none
  pending ──edit / replace / cancel sign-in / expiry──► none
```

# Public routes (User Stories 7–11)

Decisions referenced as P1–P12 are in [research.md](research.md#public-routes-user-stories-711-combined-2026-10-08).

## Server additions

### `personal_routes` (new columns)

| Column | Type | Rules |
|---|---|---|
| `listing_state` | text, not null, default `unlisted` | one of `unlisted`, `requested`, `approved`, `declined`, `removed` |
| `listing_requested_at` | timestamptz, null | set on each request |
| `listing_decided_at` | timestamptz, null | set on approve/decline/editor auto-approve; identifies the approved version for copies (P7) |
| `listing_decided_by` | uuid, null | staff or editor account that decided |
| `listing_decline_reason` | text, null | one of `name_or_description`, `promotional`, `composition`, `other` when declined |
| `listing_decline_note` | text, null | optional staff note shown to the author, ≤ 500 characters |

Checks: decline reason present exactly when `declined`; `approved` requires `listing_decided_at`. Writes only through functions (089 rule FR-035 extends to listing).

### `route_saves` (copies)

| Column | Type | Rules |
|---|---|---|
| `route_id` | uuid, references `personal_routes` on delete cascade | |
| `account_id` | uuid, references `auth.users` on delete cascade | never the route owner |
| `approved_at` | timestamptz, not null | the route's `listing_decided_at` at copy time |
| `created_at` | timestamptz, default now() | 30-day window for ranking |

Unique `(route_id, account_id, approved_at)`. Index on `(route_id, approved_at, created_at)` for ranking. RLS: no direct access for app roles.

### `route_reports`

| Column | Type | Rules |
|---|---|---|
| `id` | uuid primary key | |
| `route_id` | uuid, references `personal_routes` on delete cascade | |
| `account_id` | uuid, references `auth.users` on delete cascade | never the route owner |
| `reason` | text, not null | `inappropriate`, `promotional`, `wrong_information`, `other` |
| `created_at` | timestamptz, default now() | |
| `resolved_at` | timestamptz, null | |
| `resolution` | text, null | `dismissed` or `upheld` when resolved |

Partial unique index on `(route_id, account_id)` where `resolved_at is null`. RLS: no direct access for app roles.

### Public route row (returned by `list_public_routes`)

`id`, `name`, `stop_count`, `first_district_ko/en`, `last_district_ko/en`, `author_display_name` (live, may be empty), `is_editor` (live membership), `copy_count_30d`, `first_shared_day` (date), `listing_decided_at`. Order as received; at most 10 rows.

### Author listing view (added to `list_my_personal_routes` rows)

`listing_state`, `listing_decline_reason`, `listing_decline_note`, `author_is_editor` (live membership, for the composer warning FR-044), and `listing_blocker`: the single highest-precedence reason the route is not shown (`revoked`, `removed`, `declined`, `ended_stop`, `missing_stop`, `no_shared_day`, or null) (FR-045, DD13).

## Device additions (shared/commonMain)

| Type | Fields / purpose |
|---|---|
| `RouteListingState` (enum) | `Unlisted`, `Requested`, `Approved`, `Declined`, `Removed` |
| `RouteListingBlocker` (enum) | `Revoked`, `Removed`, `Declined`, `EndedStop`, `MissingStop`, `NoSharedDay` |
| `RouteDeclineReason` (enum) | `NameOrDescription`, `Promotional`, `Composition`, `Other` |
| `PersonalRouteSummary` (extended) | adds `listingState`, `declineReason`, `declineNote`, `listingBlocker`, `authorIsEditor` |
| `PublicRouteSummary` | the public route row above as a domain model |
| `PublicRoute` | a listed route with stops for the preview: the `PublicRouteSummary` it was opened from plus stops from `get_published_route` |
| `RouteReportReason` (enum) | `Inappropriate`, `Promotional`, `WrongInformation`, `Other` |
| `CopyIntoDraftResult` | `Applied(draftId)`, `DraftChanged` (P8) |
| `PendingKind.COPY` | resumes a copy after sign-in with the route id (P11) |

## State transitions (listing)

| From | Event | To |
|---|---|---|
| `unlisted` | author requests (`request_route_listing`) | `requested`; active editor → `approved` |
| `requested` | author withdraws | `unlisted` |
| `requested` | staff approve / decline at the displayed revision | `approved` / `declined`; stale revision → error, no change |
| `declined` | author requests again | `requested` (editor → `approved`) |
| `requested`, `approved` | owner saves a change to name or stops (non-editor) | `requested`, leaves the list |
| `approved` | author turns listing off or deletes; staff revoke | `unlisted` (delete removes the row) |
| `requested`, `approved`, `declined` | staff unlist (or uphold a report) | `removed` |
| `removed` | staff restore | `unlisted` |

Visibility (derived): `approved` ∧ published ∧ unrevoked ∧ P4 eligibility.
