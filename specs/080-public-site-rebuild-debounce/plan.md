# Plan: Durable public-site rebuild coalescing

## Architecture

Add one private `AFTER INSERT` trigger on `content.outbox_events`. For
`exhibition.published`, `exhibition.archived`, and `exhibition.restored`, it:

1. takes one transaction-scoped advisory lock for the public-site rebuild lane;
2. locks the newest live pending/failed `public_site.rebuild_requested` row;
3. extends that row's `available_at` to 30 seconds after the new event, or
   inserts a new delayed request when none is pending.

A processing rebuild row is never modified. Therefore an edit arriving after a
worker has claimed the current request creates one separate pending request,
which guarantees a follow-up build. Existing outbox claim, completion, retry,
lease, and dead-letter functions need no changes.

`outbox-delivery` continues to process each lifecycle event so publication
alerts remain independent. The trigger marks the lifecycle payload with
`public_site_rebuild_queued=true`; marked events skip the direct hook and the
new durable request owns it. Unmarked events keep the old direct hook during a
mixed-version rollout, avoiding any migration/function deployment-order gap.

## Data contract

- Aggregate: `public_site` / `catalogue`
- Event type: `public_site.rebuild_requested`
- Deduplication key: `public-site-rebuild:<first lifecycle event UUID>`
- Payload: bounded source-event count plus first/latest lifecycle event IDs;
  no exhibition content or personal data
- Quiet window: fixed 30 seconds in the trigger, documented and tested

## Constitution check

- **Spec-first:** this specification, plan, and tasks precede implementation.
- **Test-first:** failing pgTAP and Edge tests precede the migration/handler
  changes.
- **Shared-first:** no reusable mobile business logic is involved.
- **Simplicity:** reuse the durable outbox and its scheduler rather than adding
  a second drain function, queue, credential, or cron job.
- **Observability:** existing outbox status, attempts, lease, error, and
  dead-letter fields remain the operational evidence.

## Verification

1. Migration lineage validator and focused pgTAP test.
2. Complete clean database replay, pgTAP, lint, advisors, and outbox concurrency
   regressions through the canonical database workflow.
3. `outbox-delivery` test/check plus every Edge Function test/check.
4. Public-web rebuild/config tests and full product-surface checks affected by
   configuration or documentation changes.
5. Documentation diff/link/command verification.

No local check authorizes a remote migration, function deployment, deploy-hook
change, or production activation.
