# Contract: public routes database functions

Same house pattern as [database-functions.md](database-functions.md): `public` wrappers run as the caller with an empty `search_path` and call definer-rights implementations in `content_private`; anon-callable reads are definer-rights in `public` (like `get_published_route`). Errors use a SQLSTATE plus a snake_case message the app and Admin map. Implemented in one new migration after `20261010130000`, covered by a new pgTAP suite. Decisions: P1–P12 in [research.md](../research.md).

| SQLSTATE | Message | Meaning |
|---|---|---|
| 42501 | `personal_route_unauthenticated` | No signed-in user |
| 42501 | `personal_route_not_owner` | Listing action on another account's route |
| 42501 | `personal_route_not_staff` | Staff-only function |
| 55000 | `route_listing_requires_published` | Request on an unpublished route (the app publishes first) |
| 55000 | `route_listing_invalid_transition` | Event not allowed from the current state |
| 55000 | `route_listing_stale` | Staff decision on a revision that changed (P6) |
| 55000 | `route_not_listed` | Copy or report of a route not currently shown |
| 22023 | `route_listing_invalid_reason` | Unknown decline or report reason |
| 22023 | `route_listing_invalid_decision` | Decision is neither `approve` nor `decline` |
| 22023 | `route_listing_invalid_note` | Staff note longer than 500 characters |
| 22023 | `route_report_invalid_resolution` | Resolution is neither `dismissed` nor `upheld` |
| 23505 | `route_report_exists` | The account already has an open report on the route |
| 42501 | `route_report_own_route` | Owner reporting their own route |

## Author functions (EXECUTE: `authenticated`)

- `request_route_listing(p_id uuid) returns jsonb` — owner only; requires published and unrevoked; `unlisted`/`declined` → `requested`, or `approved` with `listing_decided_at = now()` when the caller has an active `content.editor_memberships` row (P5); returns the author row (see `list_my_personal_routes`).
- `withdraw_route_listing(p_id uuid) returns jsonb` — owner only; `requested`/`approved` → `unlisted`.
- `save_personal_route` (extended, 089) — when the name or the stop ids change on a `requested`/`approved` route of a non-editor, set `requested` in the same transaction (R10); unchanged otherwise.
- `list_my_personal_routes` (extended, 089) — each row adds `listing_state`, `listing_decline_reason`, `listing_decline_note`, `author_is_editor`, `listing_blocker` (data-model.md).

## Reader functions

- `list_public_routes(p_limit integer default 10) returns jsonb` — EXECUTE `anon`, `authenticated`; clamps `p_limit` to 1–10; calls `content_private.list_public_routes_impl(p_limit, (now() at time zone 'Asia/Seoul')::date)`; returns ranked eligible rows (P3, P4).
- The preview reuses `get_published_route(p_id)` (089, T075) for stops and takes listing fields, author, `is_editor` and `first_shared_day` from the list row it was opened from; no extra read function.
- `save_public_route(p_id uuid) returns jsonb` — EXECUTE `authenticated`; raises `route_not_listed` unless shown; inserts one `route_saves` row per account per approved version (no-op on repeat; nothing for the owner); returns the route with stops for `copyIntoDraft` (P7).
- `report_route(p_id uuid, p_reason text) returns void` — EXECUTE `authenticated`; route must be shown; refuses the owner; one open report per account per route (P9).

## Staff functions (EXECUTE: `authenticated`, staff membership required)

- `list_route_listing_queue() returns jsonb` — `requested` routes oldest first with name, author, requested time, stop count, `was_approved_before` (for "수정됨") and `revision`.
- `decide_route_listing(p_id uuid, p_decision text, p_reason text, p_note text, p_expected_revision timestamptz) returns jsonb` — `approve` or `decline`; decline requires a reason; raises `route_listing_stale` if `updated_at <> p_expected_revision` (P6).
- `list_reported_routes() returns jsonb` — routes with open reports: name, open count, reason tallies.
- `resolve_route_reports(p_id uuid, p_resolution text) returns jsonb` — `dismissed` keeps the state; `upheld` sets `removed`; resolves all open reports on the route.
- `unlist_route(p_id uuid) returns jsonb` / `restore_route_listing(p_id uuid) returns jsonb` — `requested`/`approved`/`declined` → `removed`; `removed` → `unlisted`.
- `get_route_for_moderation` (extended, 089) — adds listing fields, all-time copy count and open report count.

## pgTAP coverage (minimum)

Every transition-table row and refused transition; editor active, inactive and ended membership; stale decision; edit reset on name and on stops, not on an unchanged save; eligibility with fixed `p_today`: ended stop, exhibition hidden/unpublished/deleted, disjoint, overlapping, touching-on-one-day and future-only windows, 23:59 vs 00:00 Seoul; ranking order, ties, limit 10 and clamp; re-approval resets counted copies and lets an earlier copier count again; repeat copy and owner copy not counted; report uniqueness, owner refusal, reports never hiding; anon can call `list_public_routes` and `get_published_route` and nothing else; non-staff refused on every staff function; no direct table access to `route_saves`/`route_reports` for app roles.
