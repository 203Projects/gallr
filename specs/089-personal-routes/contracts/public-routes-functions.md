# Contract: public routes database functions

Same house pattern as [database-functions.md](database-functions.md): `public` wrappers run as the caller with an empty `search_path` and call definer-rights implementations in `content_private`; anon-callable reads are definer-rights in `public` (like `get_published_route`). Errors use a SQLSTATE plus a snake_case message the app and Admin map by message only; refused transitions and conflicts are `PT409` (HTTP 409), a missing route is `PT404` (HTTP 404). Implemented in `20261010140000_public_route_listing.sql`, `20261010150000_public_route_ranking.sql` and `20261010160000_public_route_review.sql`, covered by pgTAP `053`–`055`. Decisions: P1–P12 in [research.md](../research.md).

| SQLSTATE | Message | Meaning |
|---|---|---|
| 42501 | `personal_route_unauthenticated` | No signed-in user |
| 42501 | `personal_route_not_owner` | Listing action on another account's route |
| 42501 | `personal_route_not_staff` | Staff-only function, or the caller's staff tier is too low |
| PT409 | `route_listing_requires_published` | Request on an unpublished route (the app publishes first) |
| PT409 | `route_listing_invalid_transition` | Event not allowed from the current state |
| PT409 | `route_listing_stale` | Staff decision on a revision that changed (P6) |
| PT409 | `route_not_listed` | Copy or report of a route not currently shown |
| PT409 | `personal_route_revoked` | Listing request on a revoked route |
| PT404 | `personal_route_not_found` | Staff action on a missing route |
| 22023 | `route_listing_invalid_reason` | Unknown decline or report reason |
| 22023 | `route_listing_invalid_decision` | Decision is neither `approve` nor `decline` |
| 22023 | `route_listing_invalid_note` | Staff note longer than 500 characters |
| 22023 | `route_report_invalid_resolution` | Resolution is neither `dismissed` nor `upheld` |
| PT409 | `route_report_exists` | The account already reported this approved version (resolved or not) |
| 42501 | `route_report_own_route` | Owner reporting their own route |

## Author functions (EXECUTE: `authenticated`)

- `request_route_listing(p_id uuid) returns jsonb` — owner only; requires published and unrevoked; `unlisted`/`declined` → `requested`, or `approved` with `listing_decided_at = now()` and `listing_author_name` set to the author's current display name when the caller has an active `content.editor_memberships` row (P5); `removed` → `route_listing_invalid_transition`; returns the author row (see `list_my_personal_routes`).
- `withdraw_route_listing(p_id uuid) returns jsonb` — owner only; `requested`/`approved` → `unlisted`. Withdrawing does not protect a route: staff may still unlist it or uphold its reports from `unlisted`.
- `save_personal_route` (extended, 089) — when the name or the stop ids change on a `requested`/`approved` route of a non-editor, set `requested` in the same transaction (R10); unchanged otherwise.
- `list_my_personal_routes` (extended, 089) — each row adds `listing_state`, `listing_decline_reason`, `listing_decline_note`, `author_is_editor`, `listing_blocker` (data-model.md).

## Reader functions

- `list_public_routes(p_limit integer default 10) returns jsonb` — EXECUTE `anon`, `authenticated`; clamps `p_limit` to 1–10; calls `content_private.list_public_routes_impl(p_limit, (now() at time zone 'Asia/Seoul')::date)`; returns ranked eligible rows (P3, P4). `author_display_name` is `listing_author_name`, the name recorded at approval, so a display name changed afterwards is not shown until the next approval; the live name is used only when the snapshot is null.
- `get_listed_route(p_id uuid) returns jsonb` — EXECUTE `anon`, `authenticated`; definer rights in `public`. The preview read: the `get_published_route` payload (`id`, `name`, `author_display_name`, `is_mine`, `updated_at`, `stops`) with `author_display_name` taken from `listing_author_name`, returned only while the route is shown (`approved`, published, unrevoked and `content_private.route_first_shared_day(p_id, today in Seoul)` not null); otherwise null. A withdrawn, declined, removed, unpublished, revoked or no-longer-walkable route reads as null here while its shared link still answers through `get_published_route`. `PersonalRouteApiClient.loadPublicStops` calls it; the copy path keeps `get_published_route` through `save_public_route`.
- `save_public_route(p_id uuid) returns jsonb` — EXECUTE `authenticated`; raises `route_not_listed` unless shown; inserts one `route_saves` row per account per approved version (no-op on repeat; nothing for the owner); returns the `get_published_route` payload for `copyIntoDraft` (P7).
- `report_route(p_id uuid, p_reason text) returns void` — EXECUTE `authenticated`; route must be shown; refuses the owner; one report per account per approved version (`route_reports (route_id, account_id, approved_at)` unique, resolved rows included, `approved_at = listing_decided_at` of the version reported), so a dismissal does not let the same account re-file until staff approve a new version (P9).

## Staff functions (EXECUTE: `authenticated`; `publisher` or `admin` staff tier)

All of these go through `content_private.require_route_staff('publisher')` (`staff_route_for_update` for the locking ones) and insert one `content.audit_log` row per call (`entity_type = 'personal_route'`, `entity_id` = route id, `actor_user_id` = staff account, metadata with `listing_state_before`/`listing_state_after`).

- `list_route_listing_queue() returns jsonb` — `requested` routes oldest first with name, author, requested time, stop count, `was_approved_before` (for "수정됨") and `revision`.
- `decide_route_listing(p_id uuid, p_decision text, p_reason text, p_note text, p_expected_revision timestamptz) returns jsonb` — `approve` or `decline`; decline requires a reason; raises `route_listing_stale` if `updated_at <> p_expected_revision` (P6). Approve records `listing_author_name` (the author's display name at that moment) and audits `route_listing_approved` (metadata adds `revision`); decline audits `route_listing_declined` (metadata adds `revision`, `reason`).
- `list_reported_routes() returns jsonb` — routes with open reports: name, open count, reason tallies.
- `resolve_route_reports(p_id uuid, p_resolution text) returns jsonb` — resolves all open reports on the route; `dismissed` keeps the state; `upheld` sets `removed` from any state but `removed` (including `unlisted`, so an author cannot escape by withdrawing first). Audits `route_reports_resolved` (metadata adds `resolution`, `resolved_count`).
- `unlist_route(p_id uuid) returns jsonb` / `restore_route_listing(p_id uuid) returns jsonb` — any state but `removed` → `removed` (no-op when already removed); `removed` → `unlisted`, else `route_listing_invalid_transition`. Audit `route_unlisted` / `route_listing_restored`.
- `get_route_for_moderation` (extended, 089) — adds listing fields, all-time copy count and open report count.
- `revoke_personal_route` (089) — `admin` tier only; see database-functions.md.

## pgTAP coverage (minimum)

Every transition-table row and refused transition, including unlist from `unlisted` and withdraw → uphold → request refused; editor active, inactive and ended membership; editor approval records `listing_author_name`; stale decision; edit reset on name and on stops, not on an unchanged save; eligibility with fixed `p_today`: ended stop, exhibition hidden/unpublished/deleted, disjoint, overlapping, touching-on-one-day and future-only windows, 23:59 vs 00:00 Seoul; ranking order, ties, limit 10 and clamp; re-approval resets counted copies and lets an earlier copier count again; repeat copy and owner copy not counted; a display name changed after approval does not change the listed name, a new approval refreshes it; `get_listed_route` serves a listed route (with `is_mine` and the approved author name) and null for requested, withdrawn, declined, removed, unpublished, revoked, ended-stop and missing routes while `get_published_route` still serves the link; report uniqueness per approved version (refused after a dismissal, accepted after a new approval), owner refusal, reports never hiding; anon can call `list_public_routes`, `get_listed_route` and `get_published_route` and nothing else; non-staff and contributors refused on every staff function, a publisher reviews but cannot revoke, an admin revokes; one audit row per unlist, restore, approve, decline, resolve and revoke; owners cannot select `listing_decided_by` or `listing_author_name`; no direct table access to `route_saves`/`route_reports` for app roles.
