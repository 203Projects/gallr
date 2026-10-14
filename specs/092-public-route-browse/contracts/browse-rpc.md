# Contract: public route browse reads (092)

Both are PostgREST RPCs in `public`. Each is `security definer`, `stable`, `set search_path = ''`, with
execute granted to `anon` and `authenticated` and revoked from `public`. Each is a thin wrapper over a
`content_private` implementation that takes `p_today` (Seoul date), mirroring `list_public_routes`. Callers
without an account send only the publishable key, as `PersonalRouteApiClient.publicRpc` already does.

## `public.browse_public_routes`

```text
browse_public_routes(
  p_sort     text    default 'popular',   -- 'popular' | 'newest'
  p_district text    default null,        -- region_ko; null = all districts
  p_limit    integer default 20,          -- clamped to 1..50
  p_offset   integer default 0            -- clamped to >= 0
) returns jsonb
```

**Errors**: an unknown `p_sort` raises `22023` with message `route_browse_invalid_sort`. A blank `p_district` means every district. A `p_district`
that matches nothing returns an empty page, not an error.

**Eligibility**: `content_private.route_first_shared_day(route.id, p_today) is not null`. This is the same
predicate as `list_public_routes`, `get_listed_route` and copy/report.

**District filter**: `exists (select 1 from personal_route_stops s where s.route_id = route.id and s.region_ko = p_district)`.

**Order**: `popular` sorts by `copy_count_30d desc, listing_decided_at desc, id asc`; `newest` sorts by
`listing_decided_at desc, id asc`. `copy_count_30d` is computed exactly as in `list_public_routes_impl`.

**Response**:

```json
{
  "routes": [
    {
      "id": "uuid",
      "name": "성수 오후 산책",
      "stop_count": 3,
      "first_district_ko": "성수동", "first_district_en": "Seongsu",
      "last_district_ko": "한남동",  "last_district_en": "Hannam",
      "author_display_name": "hanshin",
      "is_editor": false,
      "copy_count_30d": 4,
      "first_shared_day": "2026-10-14",
      "listing_decided_at": "2026-10-12T03:00:00Z",
      "cover_urls": ["https://…/a.jpg", "https://…/b.jpg"]
    }
  ],
  "has_more": true
}
```

Every row field except `cover_urls` matches a `list_public_routes` row exactly, so the existing
`PublicRouteSummaryDto` decodes it. `cover_urls` holds 0–3 non-blank, distinct covers in stop order.

## `public.list_public_route_districts`

```text
list_public_route_districts() returns jsonb
```

**Response**: an array ordered by `route_count desc, district_ko asc`.

```json
[
  { "district_ko": "성수동", "district_en": "Seongsu", "route_count": 5 },
  { "district_ko": "한남동", "district_en": "Hannam",  "route_count": 3 }
]
```

Only eligible routes count, and a route counts once per district. Stops with a blank `region_ko` are
ignored. `district_en` is `min(region_en)` among that district's stops.

## Database tests (`supabase/tests/database/066_public_route_browse.test.sql`)

- An unapproved, unpublished, revoked, ended-stop, missing-stop or no-shared-day route never appears in
  either read.
- The set of ids across all `popular` pages equals the set of eligible routes. The first ten, in order,
  equal `list_public_routes(10)`.
- `newest` order and the `id` tie-break are respected. Pages never overlap and never skip a row on static data.
- The district filter matches any stop. A route through the district at two stops appears once.
- The district list counts each route once per district, excludes blank regions, and follows the stated order.
- `anon` and `authenticated` can execute both wrappers; neither can execute the `content_private`
  implementations.
- An invalid sort raises `route_browse_invalid_sort`; limit and offset are clamped.

## Analytics (optional phase, R8)

The `mobile-analytics` Edge Function and `service_record_mobile_analytics` both accept:
- `public_routes_viewed`: as today (`result_count` 1–10), plus an optional `surface` in
  `featured | my_gallr | map | route_browse`. For `route_browse`, `result_count` is the rows on screen,
  capped at 10 before sending.
- `public_route_copied` (new): exactly `{ surface }`, with `surface` in the same set.

Anything else is rejected, as today. No route id, author or reader is ever sent.
