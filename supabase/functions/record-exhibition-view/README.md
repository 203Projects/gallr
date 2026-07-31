# Public exhibition impact recorder

`record-exhibition-view` records one non-unique page load against the daily
aggregate for a currently published canonical exhibition. It is intentionally
small telemetry: there is no visitor profile, unique-view claim, session table,
or discovery-ranking input.

Gateway JWT verification is disabled for signed-out visitors. The handler
requires an exact allowed origin, accepts only `POST`, bounds the JSON body, and
passes only one validated `exhibition_id` to the service-only database RPC.

## Configuration

The hosted runtime supplies `SUPABASE_URL` and `SUPABASE_SECRET_KEYS`. The
function selects `record-exhibition-view` when present and otherwise `default`;
local single-key and legacy service-role variables remain migration fallbacks.
`IMPACT_ALLOWED_ORIGINS` may override the exact comma-separated public origins.
None of these server credentials belongs in the Eleventy output, and operators
must not try to create a custom secret using the reserved `SUPABASE_` prefix.

## Contract

```http
POST /functions/v1/record-exhibition-view
Content-Type: application/json

{ "exhibition_id": "canonical-exhibition-id" }
```

A valid request returns `204` whether the published record was counted or was
ineligible, avoiding a public publication-state oracle. Invalid origin/method,
media type, size, or payload receives `403`, `405`, `415`, `413`, or `400`;
backend failures return `503`. Owner-facing totals are aggregate page loads, not
people or unique visitors.

Run `deno task test` and `deno task check` in this directory. Release and smoke
gates are in the
[gallery owner release runbook](../../../docs/gallery-owner-release-runbook.md).
