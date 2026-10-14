# Contract: `walking-route` Edge Function (093)

Works out the walking path of each venue-to-venue leg of an ordered route. Legs are served from a shared cache and
computed at most once. The app never sends the visitor's position (research R3). The provider is hidden behind this
contract (research R1/R2), so it can be replaced without an app update.

## Transport

- `POST /functions/v1/walking-route`, gateway JWT verification **off** (signed-out visitors use it), like
  `promoted-nearby` and `mobile-analytics`.
- Required headers: `Content-Type: application/json`, `Origin: app://gallr` (exact allow-list,
  `WALKING_ROUTE_ALLOWED_ORIGINS`; a routing check, not authentication). Body at most 4 KiB.
- Kill switch `WALKING_ROUTE_ENABLED` (must equal `true`); otherwise every valid call answers with all legs
  `unavailable`, so the app falls back to estimates.

## Request

```json
{
  "waypoints": [
    { "exhibition_id": "ex-123", "lat": 37.54450, "lng": 127.05600 },
    { "exhibition_id": "ex-456", "lat": 37.53450, "lng": 127.00100 }
  ]
}
```

- 2 to 6 waypoints, each with an `exhibition_id`. A position-only first waypoint is reserved for a future
  self-hosted router: it is routed only when `WALKING_ROUTE_ROUTE_POSITIONS=true` (off, and kept off while the
  provider is openrouteservice), otherwise its leg is `unavailable`. It is never stored or logged either way.
- `lat` in [33, 39], `lng` in [124, 132] (South Korea bounds); finite numbers. Anything else is `400`.
- `exhibition_id` matches the catalogue id grammar (letters, digits, `_`, `-`, at most 128 characters).

## Response `200`

```json
{
  "legs": [
    { "status": "routed", "distance_m": 5230, "polyline": "_p~iF~ps|U_ulLnnqC" }
  ]
}
```

- Exactly `waypoints.length - 1` legs, in order.
- `routed`: `distance_m` (integer metres along the path) and `polyline` (encoded polyline, precision 5,
  latitude first), simplified to at most 500 points. No duration: the app derives walking minutes from distance at
  its own pace (research R4), so the number never depends on the provider.
- `unavailable`: no path could be produced (provider down or over budget, quota reached, no walkable path, kill
  switch off). The app keeps its estimate for that leg.
- A leg between two points closer than 10 m is returned `routed` with `distance_m: 0` and a two-point polyline.

## Errors

`400` invalid body, `403` wrong origin, `405` method, `413` size, `415` media type, `429` caller quota exceeded
(with `Retry-After`, at most 3600), `503` cache store unreachable. Responses never echo coordinates or upstream
error text.

## Caching rules (server)

- A **position key** is `round(lat, 5) || ',' || round(lng, 5)`.
- A leg is cacheable only when **both** ends carry an `exhibition_id` whose current `exhibition_catalog_v2`
  coordinates have the same position key. A stop whose exhibition left the catalogue, or whose catalogue
  coordinates changed, is routed but its legs are never stored.
- Cache rows are undirected: the pair is stored with the smaller key first; a reverse lookup reverses the polyline.
- A cached row older than 365 days counts as a miss and is routed again when the provider budget allows; if the
  budget is spent, the old row is served. A row is never served for a different position key.
- Misses for one request are sent to the provider in a single multi-waypoint call when they are consecutive.

## Quotas (server, Postgres-backed, atomic)

- **Provider budget** (project-wide): at most 30 provider calls per minute and 1,500 per Seoul day (below the
  provider's free daily limit, research R1). Over budget, misses come back `unavailable`; cache hits still answer.
- **Source quota**: at most 120 legs per hour per source, keyed by an HMAC of the trusted ingress address with
  `WALKING_ROUTE_HASH_SECRET` (the `mobile-analytics` pattern); rows expire after the window. Over quota: `429`.

## Logging

Only bounded outcomes: `walking_route` with counts `{legs, cache_hits, routed, unavailable}` and an outcome code.
Never coordinates, exhibition ids, polylines, addresses or upstream payloads.

## Database objects (migration `20261014130000_walking_route_cache.sql`)

| Object | Purpose | Access |
|---|---|---|
| `content_private.walking_legs` (`from_key`, `to_key`, `distance_m`, `polyline`, `provider`, `routed_at`; PK `from_key, to_key`; check `from_key < to_key`) | Venue-to-venue cache | service role only |
| `content_private.walking_route_quota` (`bucket`, `window_start`, `count`) | Provider budget and source quotas | service role only |
| `public.service_walking_legs_lookup(p_pairs jsonb)` | Batch read of cached pairs | service role only |
| `public.service_walking_legs_store(p_legs jsonb)` | Insert-or-refresh cached pairs, verifying both ends against the catalogue | service role only |
| `public.service_walking_route_consume(p_source text, p_legs int, p_provider_calls int)` returning `{allowed, retry_after_seconds, provider_calls_granted}` | Atomic quota check: source legs (all or nothing) and provider calls (granted up to the budget) | service role only |

## Tests

- **Deno** (`supabase/functions/walking-route/*_test.ts`): validation and status codes; origin allow-list; kill
  switch; a cache hit never calls the provider; a mixed request routes only misses; the starting leg is never passed
  to the store; reverse lookups reverse polylines; provider failure and budget exhaustion give `unavailable` legs;
  logs carry no coordinates; a position-only waypoint is `unavailable` unless enabled and never stored; provider adapters (openrouteservice, OSRM) parse their responses and split legs by
  waypoint.
- **pgTAP** (`supabase/tests/database/067_walking_route_cache.test.sql`): tables and functions closed to `anon`
  and `authenticated`; store refuses a pair whose ends do not match the catalogue; undirected key order enforced;
  quota windows and limits; lookup returns only exact position keys.
