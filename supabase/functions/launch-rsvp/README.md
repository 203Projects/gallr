# Public Launch Kit RSVP

`launch-rsvp` serves one public opening invitation and accepts guest responses
for an active Gallery Launch Kit. Gateway JWT verification is disabled because
visitors are signed out. Access is limited by an exact origin allow-list and an
unguessable public Kit UUID; browser roles never receive direct guest-table
access.

## Configuration

Supabase automatically provides the hosted `SUPABASE_SECRET_KEYS` map; the
function selects `launch-rsvp` when present and otherwise `default`. Local
single-key and legacy service-role variables remain migration fallbacks. The
function also requires `RSVP_HASH_SECRET` containing at least 32 characters.
`RSVP_ALLOWED_ORIGINS` may override the exact comma-separated public origins.
Keep the hash secret in the matching staging or production 1Password item, never
in the public web build.

## Contract

`GET ?token=<public-kit-uuid>` returns only published presentation fields needed
by the invitation: cover URL, bilingual exhibition/venue identity and
description, exhibition dates, reception date/time, address, hours, and contact.
It never returns payment, membership, review, audit, internal-media, or guest
data. `POST` to the same token accepts only:

```json
{
  "name": "Guest name",
  "email": "guest@example.com",
  "party_size": 2,
  "privacy_acknowledged": true
}
```

The database performs the authoritative field bounds, Kit-state checks,
deduplication, and rate limiting. The function combines the secret, request
source, and Kit token into a SHA-256 digest; it does not store a raw IP address
or user agent. Success returns `204`, an unknown/inactive token returns `404`,
rate limiting returns `429`, and unavailable configuration/service returns
`503`.

Guest names and email addresses are personal data. Do not include them, the
public token, request source, or hash material in logs or screenshots.

Run `deno task test` and `deno task check` in this directory. Public smoke and
privacy gates are in the
[gallery owner release runbook](../../../docs/gallery-owner-release-runbook.md).
