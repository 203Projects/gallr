# Walking route Edge Function

`walking-route` returns the walking path of each venue-to-venue leg of an
ordered route (spec 093,
[contract](../../../specs/093-walking-route-map/contracts/walking-route.md)).
Legs come from a shared Postgres cache and are computed by a routing provider at
most once. The app never sends the visitor's position (research R3): the leg
from where the visitor stands stays an on-device estimate.

Gateway JWT verification is off, because signed-out visitors use routes. The
handler enforces the exact origin allow-list, a 4 KiB JSON body, a per-source
quota and a project-wide provider budget, and logs only bounded counts.

## Contract summary

`POST /functions/v1/walking-route` with `Content-Type: application/json` and
`Origin: app://gallr`:

```json
{
  "waypoints": [
    { "exhibition_id": "ex-123", "lat": 37.5445, "lng": 127.056 },
    { "exhibition_id": "ex-456", "lat": 37.5345, "lng": 127.001 }
  ]
}
```

- 2 to 6 waypoints. `lat` in [33, 39] and `lng` in [124, 132]; `exhibition_id`
  is 1–128 letters, digits, `_` or `-`. Unknown keys are refused.
- Only the first waypoint may omit `exhibition_id` (or send `null`). That
  position-only leg is `unavailable` unless
  `WALKING_ROUTE_ROUTE_POSITIONS=true`, and it is never looked up, stored or
  logged either way.
- `200` returns exactly `waypoints.length - 1` legs, in order: `routed` with
  `distance_m` (integer metres) and `polyline` (precision 5, latitude first, at
  most 500 points), or `unavailable`. There is no duration: the app derives
  minutes from distance at its own pace (research R4).
- A leg shorter than 10 m is `routed` with `distance_m: 0` and a two-point
  polyline, without a lookup or a provider call.
- Errors carry no body and never echo coordinates or upstream text: `400`
  invalid body, `403` wrong origin, `405` method (`Allow: POST`), `413` over 4
  KiB, `415` media type, `429` source quota (`Retry-After` at most 3600), `503`
  cache store unreachable or the hash secret missing.

### Cache and quotas

- A position key is `round(lat, 5) || ',' || round(lng, 5)` with five decimals.
  `position.ts` reproduces Postgres exactly: the double is read with 15
  significant digits, then rounded half away from zero.
- Rows are undirected (smaller key first, compared bytewise). A reverse lookup
  reverses the polyline in the function.
- Only legs whose both ends carry an `exhibition_id` are looked up and stored.
  The database stores a pair only when both exhibitions are in
  `exhibition_catalog_v2` with coordinates that give the same keys; otherwise it
  skips the pair silently. A row older than 365 days is routed again when the
  budget allows and served as is when it does not.
- Consecutive misses go to the provider in one multi-waypoint call. When the
  budget covers only some runs, runs with a leg that has no cached fallback go
  first.
- Provider budget (project-wide): 30 calls a minute and 1,500 a Seoul day, under
  the Standard plan's 40 and 2,000. Over budget, misses are `unavailable` and
  hits are still served.
- Source quota: 120 legs an hour per source. The source is the HMAC-SHA-256 of
  the ingress address (`cf-connecting-ip`, else the first `x-forwarded-for`)
  under `WALKING_ROUTE_HASH_SECRET`; the raw address goes nowhere. Confirm that
  the hosted ingress overwrites `cf-connecting-ip` before relying on it: a
  caller who can spoof the address escapes the source quota but not the provider
  budget, so the worst case is an exhausted day of new legs (they draw dashed)
  while cached legs keep being served and the provider key stays under its plan.

### Database functions (migration `20261014130000_walking_route_cache.sql`)

All three are `security definer` with an empty `search_path`, executable only by
`service_role`; the tables in `content_private` have RLS on and no grants.

| Function                                                                                         | Input                                                                                                    | Result                                                                                                                                                                                           |
| ------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `service_walking_legs_lookup(p_pairs jsonb) → jsonb`                                             | `[{from_key, to_key}]`, at most 10, keys ordered                                                         | `[{from_key, to_key, distance_m, polyline, provider, routed_at}]`, exact matches only                                                                                                            |
| `service_walking_legs_store(p_legs jsonb) → integer`                                             | `[{from_key, from_exhibition_id, to_key, to_exhibition_id, distance_m, polyline, provider}]`, at most 10 | rows inserted or refreshed; unverified or malformed pairs are skipped                                                                                                                            |
| `service_walking_route_consume(p_source text, p_legs integer, p_provider_calls integer) → jsonb` | 64-hex source (required when `p_legs > 0`), legs 0–5, provider calls 0–5                                 | `{allowed, retry_after_seconds, provider_calls_granted}`. A refused source (`allowed: false`, retry 1–3600) counts nothing and is granted no call; provider calls are granted up to what is left |

The handler calls `consume(source, legs, 0)` together with the lookup, then
`consume(null, 0, runs)` once it knows how many provider calls it needs.

## Configuration

| Variable                        | Meaning                                                                                                                              |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `WALKING_ROUTE_ENABLED`         | Kill switch. Must equal `true`; otherwise every valid call returns all legs `unavailable` without touching Postgres or the provider. |
| `WALKING_ROUTE_ALLOWED_ORIGINS` | Exact comma-separated origin allow-list. Default `app://gallr`.                                                                      |
| `WALKING_ROUTE_PROVIDER`        | `ors` (default) or `osrm`. Any other value routes nothing.                                                                           |
| `ORS_API_KEY`                   | openrouteservice key, sent as the `Authorization` header. Without it, misses are `unavailable` and no budget is spent.               |
| `OSRM_BASE_URL`                 | Base URL of an OSRM service with a `foot` profile. HTTPS, or `http://127.0.0.1`/`localhost` for local tests.                         |
| `WALKING_ROUTE_HASH_SECRET`     | 32–256 random characters for the source HMAC. Missing or short gives `503`.                                                          |
| `WALKING_ROUTE_ROUTE_POSITIONS` | `true` routes a position-only first waypoint. Keep it off while the provider is openrouteservice (its terms forbid personal data).   |

The hosted runtime supplies `SUPABASE_URL` and `SUPABASE_SECRET_KEYS`; the
backend selects the `walking_route` key when present, otherwise `default`. Local
single-key variables remain fallbacks. Do not create custom secrets with the
reserved `SUPABASE_` prefix.

## Release gate

**Before `WALKING_ROUTE_ENABLED=true` in production, record HeiGIT's written
confirmation that storing openrouteservice results in this cache is allowed**
(research R1). Their public terms license results under CC-BY-SA 4.0 but do not
confirm permanent storage in a commercial app, and a forum reply is not contract
text. If HeiGIT declines, leave `WALKING_ROUTE_ENABLED` unset: every leg stays
`unavailable` and the app keeps drawing its dashed estimates. gallr uses free
services only (owner decision 2026-10-15), so no paid plan or paid router host
replaces openrouteservice.

The provider fixtures in `fixtures/` were **written by hand** from the
documented response shapes of the openrouteservice v2 directions JSON response
and the OSRM route service, not recorded from a live API (no key was available).
Before enabling, call the live provider once from staging, compare the response
with the fixture (field names, `way_points`, `segments`, encoded geometry; for
OSRM, `waypoints[].location` and `legs[].distance`), re-record the fixtures from
it with coordinates trimmed to the test venues, and keep the tests green.

Also spot-check Seoul footway quality on a few known routes, and remember the
openrouteservice maintenance windows (for example 2026-10-23): the app falls
back to estimates while legs are `unavailable`.

## Credits

Every map that shows these paths must carry the provider credit (research R7):

```text
© openrouteservice by HeiGIT | Data © OpenStreetMap contributors
```

With OSRM, the OpenStreetMap credit alone applies to the routing data.

## Deploy order

1. Apply the migration `20261014130000_walking_route_cache.sql` through the
   normal release process (staging first).
2. Set the secrets and deploy the function with `WALKING_ROUTE_ENABLED` absent
   or `false`, then smoke-test that a valid call returns `unavailable` legs.
3. Ship the app. Enable the switch only after the release gate above.

Authenticate and verify the target before making changes. Replace
`<project-ref>` only after matching it against `supabase projects list`; do not
use `db push --include-all` to bypass migration history differences:

```bash
supabase login
supabase projects list
supabase link --project-ref <project-ref>
supabase migration list --linked
supabase db push --dry-run
```

Then create an ignored, permission-restricted local file such as
`supabase/functions/walking-route/.env.production.local` with the variables
above (generate `WALKING_ROUTE_HASH_SECRET` freshly, for example with
`openssl rand -hex 32`, and keep `ORS_API_KEY` in 1Password). Confirm that Git
ignores it before running:

```bash
git check-ignore supabase/functions/walking-route/.env.production.local
supabase secrets set \
  --env-file supabase/functions/walking-route/.env.production.local \
  --project-ref <project-ref>
supabase functions deploy walking-route --project-ref <project-ref>
supabase secrets list --project-ref <project-ref>
supabase functions list --project-ref <project-ref>
```

Never commit keys. Delete the local secret file after the values are stored.
These commands are a release runbook; opening or updating a pull request does
not execute them.

## Switching to OSRM

Not planned: there is no free OSRM service suitable for production, and a
self-hosted one needs a paid server (research R2). The adapter stays because it
costs nothing unused. The provider sits behind `WalkingProvider`
(`providers/provider.ts`), so a switch would be configuration only:

```text
WALKING_ROUTE_PROVIDER=osrm
OSRM_BASE_URL=https://osrm.example.internal
```

The adapter calls
`GET {OSRM_BASE_URL}/route/v1/foot/{lng,lat;…}?overview=full&geometries=polyline&steps=false`
and reads `legs[i].distance`. OSRM returns one overview geometry, so the adapter
splits it at each snapped waypoint (`waypoints[i].location`): among the vertices
after the previous cut and within 5 m of that location, it takes the one whose
distance along the path is closest to the sum of the earlier legs, which also
handles a path that passes a stop before reaching it.

Cached rows keep their `provider`. To recompute everything with the new
provider, an operator can age the rows past 365 days or delete them; they are
routed again as the budget allows.

## Provider behaviour

- openrouteservice:
  `POST https://api.heigit.org/openrouteservice/v2/directions/foot-walking/json`
  with `{coordinates: [[lng, lat], …], elevation: false, instructions: true}`.
  Legs come from `routes[0].geometry` split at `routes[0].way_points`, with
  `routes[0].segments[i].distance`. `api.openrouteservice.org` is retired in
  November 2026 and is not used.
- Every call has an 8 s timeout and reads at most 2 MiB. HTTP errors, timeouts
  and malformed bodies make every leg of that call `unavailable`. A "no route"
  answer (code 2009, or 2010 when a point has no walkable way nearby) also
  covers the whole call: a run of one leg marks only that leg, a longer run
  marks all its legs, and they are retried on a later request.

## Logging

One line per request:
`{event: "walking_route", outcome, legs, cache_hits, routed, unavailable}` with
`outcome` one of `served`, `store_failed`, `disabled`, `rate_limited`,
`rejected` or `unavailable`. Never coordinates, exhibition ids, polylines,
addresses, sources or upstream payloads.

## Verify locally

Unit checks need no credentials or network:

```bash
cd supabase/functions/walking-route
deno task test
deno task check
```

The database contract is covered by
`supabase/tests/database/067_walking_route_cache.test.sql`.
