# Gallery alert enrollment

`gallery-alert-enrollment` is the bounded transport for per-device gallery
alerts. Installation identities are chosen by the client, so the enrollment RPCs
cannot meter abuse on their own: any anonymous caller could mint unlimited
installations, each costing a bcrypt hash and a durable row, then amplify one
publication into one provider job per synthetic installation.

This function turns an unmeterable request into a metered one. It derives a
pseudonymous source key the caller cannot choose, and the database spends that
key against per-source and per-project budgets before it creates a row.

Gateway JWT verification is disabled because alerts must work without an
account, per `specs/063-followed-gallery-publication-alerts`. The function
verifies any supplied bearer token itself and treats a missing, unverifiable, or
anonymous session as no account, matching the database rule that an anonymous
JWT never claims an installation.

## Configuration

Supabase automatically provides the hosted `SUPABASE_SECRET_KEYS` and
`SUPABASE_PUBLISHABLE_KEYS` maps; the function selects
`gallery_alert_enrollment` when present and otherwise `default`. Local
single-key and legacy service-role variables remain migration fallbacks.

The function also requires `GALLERY_ALERT_HASH_SECRET` containing at least 32
characters. Keep it in the matching staging or production 1Password item, never
in a client build. Rotating it resets every per-source budget, which is the
intended recovery action if a source key space is ever considered burned.

The source key is derived from the ingress client address. Confirm the trusted
address header before relying on per-source budgets in a new environment: if a
caller can spoof `cf-connecting-ip` or `x-forwarded-for`, per-source metering
degrades to the project-wide ceiling, which still bounds durable growth.

## Contract

`POST` with `application/json` and at most 8192 bytes. Every request carries
`installation_id` and `installation_secret`; `action` selects the command.

```json
{
  "action": "register_installation",
  "installation_id": "<uuid>",
  "installation_secret": "<32-256 chars>",
  "platform": "ios",
  "locale": "ko-KR",
  "expected_revision": 0
}
```

`register_push_token` adds `provider`, `provider_token`, and
`provider_environment`. `set_subscription` adds `gallery_id` and `enabled`.
`get_installation` takes only the two credential fields. Unknown fields and
unknown actions are refused.

Responses are the database payloads unchanged, so installation state keeps its
`revision` and `subscriptions` shape and token state keeps `push_token_revision`
and `push_token_status`. Errors return a stable code and never echo an upstream
message: `400` invalid request, `403` wrong installation secret, `409` revision
or account conflict, `413` oversized body, `415` wrong content type, `429` rate
limited or subscription ceiling reached, `503` unavailable or unconfigured.

## Budgets

Ceilings live in `content_private.gallery_alert_enrollment_limits()` as one
constant, and only the creation of a durable installation is metered — a
returning device refreshing itself is never rate limited. Trusted traffic
through this function and legacy traffic from released clients through 1.10.1
calling the RPCs directly spend separate project budgets, so abuse on the legacy
path cannot starve this one.

The forward migration keeps at most 200 existing subscription rows per
installation, preferring enabled and recently changed choices. Publication
fan-out materialization is serialized per outbox event and retains at most
50,000 deliverable jobs, including when upgrading pre-limit state.

The installation secret is a bearer credential. Do not include it, the request
source, or the derived source key in logs or screenshots.

Run `deno task test` and `deno task check` in this directory.
