# Implementation Plan: Gallery Public Impact

## Approach

Add an aggregate-only telemetry seam to the existing canonical exhibition
identity. A public detail page sends its exhibition ID to a keyless Supabase Edge
Function. The function validates origin and payload, then uses its server-side
client to invoke a private recorder. PostgreSQL verifies the live publication
pointer and increments one UTC daily row atomically.

Extend the existing authorized owner exhibition JSON rather than adding a broad
analytics read API. This keeps tenant checks in the established owner list path
and makes the two explicit counts available only with the exhibition the owner
already has permission to read.

## Data and permission model

- `content.exhibition_daily_metrics(exhibition_id, metric_date, page_loads)`
- `content_private.record_exhibition_page_load_impl(exhibition_id)` is callable
  only by `service_role` and returns whether a published row was counted.
- Public browser roles receive no table or RPC privileges.
- `owner_exhibition_json` computes rolling 30-day and all-time sums only for a
  currently published owner exhibition.

## Frontend structure

- Eleventy derives `impactEndpoint` from `GALLR_IMPACT_ENDPOINT`, falling back to
  the configured `SUPABASE_URL` function origin.
- Only exhibition detail pages render `data-exhibition-impact` and the deferred
  recorder script.
- The owner dashboard adds a compact impact column; the published editor panel
  repeats the two counts and the non-unique caveat beside its public link.

## Verification

1. Add failing pgTAP, handler, web-build, repository, and component contracts.
2. Add the aggregate table, private recorder, and authorized owner projection.
3. Add the isolated Edge Function and passive public browser recorder.
4. Add owner DTO fields and published-only impact presentation.
5. Run focused and full database/frontend tests, lint, typechecks, and builds.

## Operational notes

The Edge Function needs the normal server-side Supabase URL and secret supplied
through the existing deployment secret mechanism. The web build needs only a
public endpoint URL; no credential is embedded in visitor markup or JavaScript.
