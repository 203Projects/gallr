# Outbox delivery

This server-only Edge Function is the authenticated receiver for non-media
events forwarded by `outbox-worker`. It keeps the worker isolated from vendor
deploy hooks and gives the durable queue one reviewed dispatch boundary.

## Behavior

- `exhibition.published`, `exhibition.archived`, and `exhibition.restored`
  trigger the configured public-web Vercel deploy hook.
- `legacy_catalog.sync_requested` invokes the exact authenticated Seoul
  `legacy-catalog-mirror` function. Failure returns `502`, so the durable outbox
  retains its normal bounded retry and dead-letter behavior.
- Known gallery claim, owner submission, Launch Kit, and local-promotion events
  are acknowledged without a public rebuild. Their canonical database and audit
  records remain the source of truth until a later notification consumer is
  introduced.
- Unknown event types return `422`. The worker retries and ultimately
  dead-letters them instead of silently losing a newly introduced event.
- A failed deploy hook returns `502`, so the outbox lease is retried.

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

## Activation

Deploying this function is inert. R1 activation requires all of the following:

1. Deploy `outbox-delivery` with `OUTBOX_DELIVERY_TOKEN` and
   `VERCEL_DEPLOY_HOOK_URL` configured.
2. Set `OUTBOX_DELIVERY_URL` on `outbox-worker` to the exact hosted
   `outbox-delivery` URL.
3. Set the same `OUTBOX_DELIVERY_TOKEN` on `outbox-worker`.
4. Invoke the worker and verify one lifecycle event is delivered and one public
   rebuild is created before scheduling recurring worker invocations.

Do not point `OUTBOX_DELIVERY_URL` directly at Vercel. The worker sends a gallr
event envelope and authentication headers, while Vercel's deploy hook is an
implementation detail owned by this receiver.

## Verification

```sh
deno task test
deno task check
```
