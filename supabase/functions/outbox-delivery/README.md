# Outbox delivery

This server-only Edge Function is the authenticated receiver for non-media
events forwarded by `outbox-worker`. It keeps the worker isolated from vendor
deploy hooks and gives the durable queue one reviewed dispatch boundary.

## Behavior

- `exhibition.published`, `exhibition.archived`, and `exhibition.restored`
  durably enqueue or extend one `public_site.rebuild_requested` event through
  the database trigger. The request waits for a 30-second quiet window, then
  triggers the configured public-web Vercel deploy hook. An edit committed while
  a rebuild is processing creates a separate follow-up request, so coalescing
  cannot lose late catalogue changes.
- Lifecycle events marked `public_site_rebuild_queued=true` do not call Vercel
  directly. Unmarked events retain the direct-hook path as a deployment-order
  compatibility fallback, so deploying either the migration or function first
  cannot suppress a required rebuild.
- When the exact `GALLERY_ALERT_DELIVERY_ENABLED=true` staging flag is present,
  `exhibition.published` also claims idempotent delivery jobs for explicitly
  opted-in installations and sends localized APNs or FCM HTTP v1 alerts. Invalid
  addresses are disabled, transient failures retain the outbox lease for retry,
  and provider bodies and raw addresses are never logged.
- `legacy_catalog.sync_requested` invokes the exact authenticated Seoul
  `legacy-catalog-mirror` function. Failure returns `502`, so the durable outbox
  retains its normal bounded retry and dead-letter behavior.
- Owner-workspace `submission.accepted` and `submission.rejected` events send a
  transactional email through Resend. The outbox deduplication key is forwarded
  as Resend's idempotency key so delivery retries do not intentionally duplicate
  a message. Requests include an explicit receiver `User-Agent`, as required by
  the provider API.
- Known gallery claim, submission-received, Launch Kit, and local-promotion
  events are acknowledged without a public rebuild. Their canonical database and
  audit records remain the source of truth.
- Unknown event types return `422`. The worker retries and ultimately
  dead-letters them instead of silently losing a newly introduced event.
- Only `public_site.rebuild_requested` calls the deploy hook. A failed hook
  returns `502`, so the existing outbox lease/backoff/dead-letter path retries
  the coalesced request.

## Security contract

Gateway JWT verification is disabled because the caller is another Edge
Function, not a user session. Every request must be a `POST` with the same
high-entropy `OUTBOX_DELIVERY_TOKEN` configured on `outbox-worker`. The receiver
validates the event ID, type, and idempotency headers against the body before
dispatch.

`VERCEL_DEPLOY_HOOK_URL` is server-only and must be an HTTPS
`api.vercel.com/v1/integrations/deploy/...` URL for the public gallr project.
Neither secret belongs in a browser bundle, repository file, log, or screenshot.

Automatic legacy compatibility additionally requires `LEGACY_CATALOG_MIRROR_URL`
and `LEGACY_CATALOG_MIRROR_TOKEN`. The URL must be the mirror function under
this deployment's exact reviewed Seoul `SUPABASE_URL`; partial, foreign, or weak
configuration fails closed.

Owner decision email requires `RESEND_API_KEY` and
`OWNER_NOTIFICATION_FROM_EMAIL`. The sender must use a domain verified for the
configured Resend account. Missing or invalid notification configuration fails
closed so the durable outbox can retry and dead-letter the event. Provider
failures return only a bounded HTTP status and allowlisted machine code; never
forward the provider message, request body, recipient, or API response verbatim.

Gallery-alert delivery additionally requires a server credential resolved from
`SUPABASE_SECRET_KEYS`, `SUPABASE_SECRET_KEY`, or the local legacy
`SUPABASE_SERVICE_ROLE_KEY`. APNs uses `GALLERY_ALERT_APNS_KEY_ID`,
`GALLERY_ALERT_APNS_TEAM_ID`, `GALLERY_ALERT_APNS_TOPIC`, and
`GALLERY_ALERT_APNS_PRIVATE_KEY`. FCM uses
`GALLERY_ALERT_FCM_SERVICE_ACCOUNT_JSON`. Source all provider values from the
staging 1Password item; do not place them in repository files or logs.

## Activation

Deploying this function is inert. R1 activation requires all of the following:

1. Deploy `outbox-delivery` with `OUTBOX_DELIVERY_TOKEN` and
   `VERCEL_DEPLOY_HOOK_URL` configured.
2. Set `OUTBOX_DELIVERY_URL` on `outbox-worker` to the exact hosted
   `outbox-delivery` URL.
3. Set the same `OUTBOX_DELIVERY_TOKEN` on `outbox-worker`.
4. Invoke the worker until both the lifecycle event and its delayed
   `public_site.rebuild_requested` event are delivered. Verify one public
   rebuild is created before scheduling recurring worker invocations.

Gallery alerts remain off unless `GALLERY_ALERT_DELIVERY_ENABLED` is exactly
`true`. Enable that flag only after the gallery-alert migrations, APNs
capability, Firebase client configuration, provider credentials, and sandbox
delivery checks are complete in staging.

### Gallery-alert staging gate

Keep `GALLERY_ALERT_DELIVERY_ENABLED` unset while preparing staging. Record the
reviewed commit and exact staging project, then complete this order:

1. Replay the full migration lineage on a clean disposable database and retain
   the pgTAP and two-session gallery-alert concurrency results.
2. Apply the reviewed migrations to the isolated staging project. Confirm the
   installation command APIs are unavailable as direct table writes and that a
   wrong installation secret cannot read or mutate subscriptions.
3. Build staging Android and iOS clients against that same staging project.
   Confirm the platform configuration belongs to the staging Firebase/APNs
   applications and does not contain a provider private key.
4. Load the provider credentials from the `gallr-staging` 1Password item into
   the staging Edge Function secrets. Do not copy production credentials into
   staging or expose secret values in a shell transcript.
5. Deploy the reviewed `outbox-delivery` function with delivery still disabled.
   Exercise one publication and confirm the public rebuild path is unchanged and
   no gallery delivery jobs are claimed.
6. Use one Android and one iOS sandbox installation to follow a staging-only
   gallery and explicitly enable its alert. Retain only redacted evidence:
   installation ID suffix, platform, gallery ID, exhibition ID, timestamps,
   status codes, and delivery-job state. Never retain raw addresses or provider
   bodies.
7. Set `GALLERY_ALERT_DELIVERY_ENABLED=true`, publish a new staging-only
   exhibition once, and verify one alert per opted-in installation, the correct
   localized title and exhibition deep link, and a completed idempotent delivery
   job. Retry the same event and verify no duplicate alert is sent.
8. Revoke one sandbox address and repeat with a second publication. Verify the
   invalid address is disabled without blocking a valid installation. Exercise
   one transient provider failure and confirm the job remains retryable rather
   than being marked complete.

Stop the rehearsal and remove `GALLERY_ALERT_DELIVERY_ENABLED` if any target,
credential, audience, deep link, deduplication, or redaction check is uncertain.
Disabling the flag is the immediate delivery rollback; it does not delete
follows, subscriptions, installations, or durable publication events. Production
activation requires a separate reviewed change after the staging evidence is
accepted.

Do not point `OUTBOX_DELIVERY_URL` directly at Vercel. The worker sends a gallr
event envelope and authentication headers, while Vercel's deploy hook is an
implementation detail owned by this receiver.

## Verification

```sh
deno task test
deno task check
```
