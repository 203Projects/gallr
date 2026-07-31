# Implementation Plan: Automatic Media Publishing

## Approach

Keep the current upload, durable outbox, worker, and Admin polling flow. Add one
database-owned invocation function that reads an exact worker URL and opaque
token from Supabase Vault, then queues an authenticated `pg_net` request. A
Supabase Cron job calls that function once per minute. Scheduling remains a
separate environment activation step so staging and production cannot be
enabled accidentally by applying a migration.

Improve the disabled Publish button’s explanation when attached media is still
processing. No browser code receives a worker credential and no second media
processing path is introduced.

## Current flow

1. Admin uploads bytes to the private `exhibition-media` bucket.
2. The database records a `ready` asset and a `media.publish_requested` outbox
   event.
3. Admin polls media state every five seconds.
4. The deployed worker claims one media event, validates and publishes the
   object, then marks the asset `published`.
5. Admin observes the new state and enables Publish.

The missing link is step 4 being triggered automatically.

## Constitution check

- **Spec-first:** This specification and plan precede implementation.
- **Test-first:** pgTAP and Admin component tests will be added and observed
  failing before implementation.
- **Simplicity:** One database invocation function, one Cron job, and one
  contextual UI message reuse the existing queue and worker.
- **Incremental delivery:** The migration is inert until a specific environment
  schedule is activated; staging can be validated independently.
- **Observability:** Cron run history, `pg_net` response history, worker
  structured logs, and durable outbox state cover each boundary.
- **Shared-first:** Not applicable to the standalone React Admin and Supabase
  backend; no mobile business logic changes.

## Security design

- Store `gallr_outbox_worker_url` and `gallr_outbox_worker_token` in Supabase
  Vault.
- Validate the URL as an HTTPS Supabase project URL ending exactly in
  `/functions/v1/outbox-worker`.
- Require the token to meet the worker’s minimum length and whitespace rules.
- Keep the invoker in `content_private`, use `SECURITY DEFINER` with an empty
  `search_path`, and fully qualify all objects.
- Revoke invocation from `PUBLIC`, `anon`, `authenticated`, and `service_role`.
  The database owner’s Cron job remains the only normal caller.
- Schedule only `select content_private.invoke_media_outbox_worker();`; the
  stored cron command contains no secret.
- Continue using the media-only claim RPC while `OUTBOX_DELIVERY_URL` is absent.

## Supabase research

- Supabase documents `pg_cron` plus `pg_net` as the supported pattern for
  periodically invoking Edge Functions.
- Supabase recommends Vault for credentials referenced by scheduled calls.
- Supabase Cron records jobs in `cron.job` and runs in
  `cron.job_run_details`; `pg_net` retains HTTP response records.
- The July 2026 breaking-change feed states direct inserts or updates on
  `cron.job` are no longer allowed. Activation and rollback therefore use only
  `cron.schedule()` and `cron.unschedule()`.
- The current worker uses a dedicated opaque bearer token with
  `verify_jwt = false`; the schedule must preserve that contract rather than
  substitute a browser or user JWT.

## Activation and rollback

1. Apply and validate the migration in staging.
2. Create or update the two named Vault values in staging.
3. Create `gallr-media-publisher-v1` with `cron.schedule()` at `* * * * *`.
4. Upload a staging image and verify one queued event becomes delivered, the
   asset becomes published, Admin updates without reload, and lifecycle events
   remain untouched.
5. Disable with `cron.unschedule('gallr-media-publisher-v1')`.
6. Repeat in production only after a fresh backup, dry run, explicit
   authorization, and staging evidence review.

## Verification

1. Run the new pgTAP contract on a clean local Supabase database.
2. Run outbox worker type checks and tests.
3. Run Admin tests, typecheck, and production build.
4. Exercise the processing-state Publish message in the browser.
5. Perform a staging end-to-end upload and inspect Cron, `pg_net`, outbox,
   media-asset, Storage, and Admin evidence.

## Complexity tracking

No exception. A second worker, browser-triggered privileged call, larger claim
batch, or general event scheduler would add security and delivery complexity
without solving a current requirement.
