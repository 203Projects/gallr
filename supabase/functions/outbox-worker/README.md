# Outbox worker

This server-only Edge Function claims durable outbox events with expiring lease
tokens. It publishes validated media, removes orphaned media through the Storage
API, and forwards other event types to an optional HTTPS receiver.

## Security contract

- `supabase/config.toml` sets `verify_jwt = false` for this function because the
  caller uses a dedicated opaque token, not a user JWT.
- Every request must be `POST` with
  `Authorization: Bearer <OUTBOX_WORKER_TOKEN>`. The function checks this token
  before creating a service client.
- The function uses `SUPABASE_SECRET_KEY` (preferred), a JSON
  `SUPABASE_SECRET_KEYS` value, or the local legacy `SUPABASE_SERVICE_ROLE_KEY`.
  Never expose these values to a browser bundle.
- Worker RPCs are granted only to `service_role`; `anon` and `authenticated`
  cannot claim, complete, fail, publish, reject, sweep, or purge events.

Copy `.env.example` to a file outside version control and replace every example
secret. Generate `OUTBOX_WORKER_TOKEN` with at least 32 random bytes and encode
it with a diverse alphabet such as base64url. The function rejects tokens under
32 characters, whitespace/control characters, and low-diversity placeholders.
Configure the same value in the private scheduler that invokes the function.

## Processing rules

Before each claim, the function sweeps a bounded set of unreferenced
`pending_upload` or `ready` assets older than 24 hours. The database marks them
`orphaned` and inserts one deduplicated `media.cleanup_requested` event.

Each invocation claims exactly one event. This keeps its lease fresh through the
full network and Storage operation instead of pre-leasing a sequential batch
that could expire before later items begin. Increase throughput with parallel
invocations; `FOR UPDATE SKIP LOCKED` keeps their work disjoint.

`media.publish_requested` downloads from private `exhibition-media`, enforces a
10 MiB maximum, parses the entire JPEG/PNG/WebP container, rejects animated
PNG/WebP, and caps decoded images at 8192px per side and 12 megapixels. A
restricted, current ImageMagick-WASM build then decodes every pixel through the
forced expected coder; the worker requires exactly `width × height × 4` RGBA
bytes before computing SHA-256. It uploads once with `upsert: false` to the
immutable public path `exhibition-images/cms/{asset_id}/original.{ext}`. A retry
may use an existing object only after downloading it and proving its checksum
and metadata match. Invalid bytes are rejected durably instead of retried. If
transient publication failures exhaust the configured attempt limit, the
database moves a still-`ready` asset to `rejected`, records the diagnostic and
an audit instruction, and tells the editor to remove and re-upload the image.

`media.cleanup_requested` first obtains a database purge token, deletes private
and optional public objects with the Storage API, then finalizes the purge. The
final RPC re-locks the asset and refuses to stamp `purged_at` if a reference was
added or the token is stale. Technical metadata and canonical paths are retained
for audit.

Other events are sent to `OUTBOX_DELIVERY_URL` with `Idempotency-Key`,
`X-Outbox-Event-Id`, and `X-Outbox-Event-Type`. Production URLs must use HTTPS.

## Local verification

From this directory:

```sh
deno task check
deno task test
```

Serve locally with an env file containing real local secrets. The checked-in
function config already disables gateway JWT verification for this custom-token
endpoint. Deployment and scheduler creation are intentionally outside this
repository change.
