# Transparent local promotion delivery

`promoted-nearby` selects at most one eligible paid placement for the visitor's
coarse Korean city/region and atomically records the daily frequency cap. It is
separate from the catalogue and editorial Featured readers: a response cannot
change organic ordering or curation.

Gateway JWT verification is disabled for public web and mobile callers. The
handler enforces the exact origin allow-list, accepts only a small JSON payload,
hashes the installation key before calling Postgres, and logs only a bounded
delivery outcome (`served`, `none`, `rejected`, or `unavailable`).

## Configuration

The hosted runtime supplies `SUPABASE_URL` and `SUPABASE_SECRET_KEYS`. The
function selects `promoted-nearby` when present and otherwise `default`; local
single-key and legacy service-role variables remain migration fallbacks.
`PROMOTION_ALLOWED_ORIGINS` may override the exact comma-separated allow-list;
include the mobile `app://gallr` origin when mobile delivery is enabled. Do not
create a custom secret using the reserved `SUPABASE_` prefix.

`PROMOTION_DELIVERY_ENABLED` is the server-side R4 kill switch. It must equal
the lowercase string `true` before the handler hashes an installation key or
constructs its database backend. When it is absent or any other value, valid
POST requests return `204` with no placement. Keep it absent or false throughout
the R3 Launch Kit beta.

## Contract

```json
{
  "installation_key": "stable-random-installation-key",
  "city_ko": "서울",
  "region_ko": "용산구"
}
```

The installation key must be 16–128 URL-safe characters and is never sent to the
database or logs in raw form. An eligible placement returns `200` with a
`placement` whose disclosure is always `paid_placement`; no eligible placement
or an already-used daily cap returns `204`. Invalid calls fail closed, and
service failures return `503` without falling back to organic content disguised
as promotion.

Run `deno task test` and `deno task check` in this directory. Scheduling,
frequency, label, and organic-isolation smoke gates are in the
[gallery owner release runbook](../../../docs/gallery-owner-release-runbook.md).
