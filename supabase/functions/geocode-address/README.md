# Address geocoding Edge Function

`geocode-address` converts a Korean street or lot-number address into up to
three NAVER Maps candidates. It is an editorial helper, not an automatic write:
the admin shows the candidates and saves the address, latitude, and longitude
only after an editor selects one.

The function requires a valid Supabase user JWT and independently verifies that
the caller is an active staff member through `admin_current_staff()`. NAVER
credentials remain server-side and must never be copied into an admin `VITE_*`
variable.

Before each NAVER request, the function consumes an atomic Postgres-backed quota
through `admin_consume_geocode_rate_limit()`: at most 10 requests per staff
member and 30 requests across the project in each fixed one-minute window. A
rejected request returns `429` with a bounded `Retry-After` header. The counters
live in `content_private`, not Edge worker memory, so concurrent workers share
the same limits. Apply the database migrations before deploying the function;
limiter RPC failure causes geocoding to fail closed.

## Provider setup

1. Create or select a NAVER Cloud Platform application.
2. Enable the Maps **Geocoding** API for that application.
3. Keep the NAVER Cloud server Geocoding API key ID and key in the function
   environment. These are not the public Web Dynamic Map client ID and must
   never enter a `VITE_*` variable:

   ```text
   NAVER_MAPS_API_KEY_ID=replace-with-naver-server-api-key-id
   NAVER_MAPS_API_KEY=replace-with-naver-server-api-key
   ```

   `.env.example` contains placeholders only. Do not commit real credentials.

For a hosted Supabase project, authenticate and verify the target before making
changes. Replace `<project-ref>` only after matching it against
`supabase projects list`; do not use `db push --include-all` to bypass migration
history differences:

```bash
supabase login
supabase projects list
supabase link --project-ref <project-ref>
supabase migration list --linked
supabase db push --dry-run
```

Review the dry run, apply the required migrations through the normal release
process, and verify that the staff authorization migrations are already present.
Then create an ignored, permission-restricted local file such as
`supabase/functions/geocode-address/.env.production.local` containing the two
NAVER variables shown above. Confirm that Git ignores it before running:

```bash
git check-ignore supabase/functions/geocode-address/.env.production.local
supabase secrets set \
  --env-file supabase/functions/geocode-address/.env.production.local \
  --project-ref <project-ref>
supabase functions deploy geocode-address --project-ref <project-ref>
supabase secrets list --project-ref <project-ref>
supabase functions list --project-ref <project-ref>
```

Delete the local secret file after the values are stored, and verify the
deployed function with an authenticated user that has an approved, active
`content.staff_members` record. These commands are a release runbook; opening or
updating this pull request does not execute them.

The Supabase runtime supplies its own URL and publishable/anonymous key. The
function configuration keeps gateway JWT verification enabled, while the handler
performs the separate active-staff authorization check.

## Request and response

The authenticated admin sends:

```json
{ "address": "서울 용산구 한남대로 28" }
```

The response contains at most three candidates. NAVER's `x` value is mapped to
longitude and `y` to latitude:

```json
{
  "candidates": [
    {
      "road_address": "서울특별시 용산구 한남대로 28",
      "jibun_address": "",
      "english_address": "28 Hannam-daero, Yongsan-gu, Seoul",
      "latitude": "37.5344",
      "longitude": "127.0005"
    }
  ]
}
```

Provider failures use sanitized codes without returning NAVER response bodies:

- NAVER `401`/`403`: `502 geocoding_provider_configuration_error`
- NAVER `429`: `429 geocoding_provider_rate_limited` with a validated numeric
  `Retry-After` (or a safe 60-second default)
- NAVER `503`: `503 geocoding_provider_unavailable`
- NAVER `504` or a local provider timeout: `504 geocoding_provider_timeout`

## Verify locally

Unit checks do not require credentials or network access:

```bash
cd supabase/functions/geocode-address
deno task check
deno task test
```

Serving the function end to end additionally requires a safely bound local
Supabase stack, a signed-in staff fixture, and local secrets. Do not start a
stack that exposes database or service ports on wildcard interfaces merely to
test geocoding; use an isolated loopback-only environment or staging project.
The repository-wide
[local verification runbook](../../../docs/exhibition-content-architecture.md#local-setup-and-verification)
includes a mandatory port-binding check.
