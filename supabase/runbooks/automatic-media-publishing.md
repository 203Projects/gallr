# Automatic media publishing

This runbook activates the inert
`content_private.invoke_media_outbox_worker()` database function installed by
migration `20260730140500`. Apply and validate the migration before using this
runbook.

Activation is environment-specific. Complete every staging step and preserve
its evidence before requesting a separate production authorization.

## Safety boundary

- The worker claims exactly one event per invocation.
- Without `OUTBOX_DELIVERY_URL`, it uses `outbox_claim_media_events` and cannot
  claim exhibition lifecycle events.
- The schedule does not publish an exhibition. It only processes media assets.
- Never put the worker token in a migration, SQL query, shell history, browser
  bundle, cron command, or log.
- Never reuse one project’s URL or token in the other project.

## 1. Add Vault values

In the target Supabase project, open **Integrations → Vault** and create these
two exact names:

| Name | Value |
| --- | --- |
| `gallr_outbox_worker_url` | `https://<target-project-ref>.supabase.co/functions/v1/outbox-worker` |
| `gallr_outbox_worker_token` | The target project’s existing `OUTBOX_WORKER_TOKEN` |

The URL is not secret, but storing both values in Vault keeps the schedule
configuration in one protected location. Do not rotate the existing Edge
Function token during initial activation.

Confirm only names and timestamps; do not select `decrypted_secret`:

```sql
select name, created_at, updated_at
from vault.secrets
where name in (
  'gallr_outbox_worker_url',
  'gallr_outbox_worker_token'
)
order by name;
```

Exactly two rows must be present.

## 2. Validate one inert request

With no media event pending, run:

```sql
select content_private.invoke_media_outbox_worker() as request_id;
```

The result is a `pg_net` request ID. Inspect the corresponding HTTP response in
the Supabase `pg_net` interface or its response history. It must return HTTP 200
with `claimed: 0`. A 401 means the Vault token and Edge Function secret differ;
stop rather than creating the schedule.

## 3. Create the one-minute schedule

Enable the Supabase Cron integration if it is not already enabled, then run:

```sql
select cron.schedule(
  'gallr-media-publisher-v1',
  '* * * * *',
  $schedule$
    select content_private.invoke_media_outbox_worker();
  $schedule$
);
```

Do not insert or update `cron.job` directly. Use `cron.schedule()` and
`cron.unschedule()` so the configuration remains compatible with the hosted
Supabase Cron contract.

Verify the non-secret job contract:

```sql
select jobname, schedule, command, active
from cron.job
where jobname = 'gallr-media-publisher-v1';
```

Exactly one active row must use `* * * * *` and call only
`content_private.invoke_media_outbox_worker()`.

## 4. Staging canary

1. Upload one valid cover image to a staging draft.
2. Confirm Admin shows **Processing for publication**.
3. Wait up to two minutes without manually invoking the worker.
4. Confirm the asset becomes **Published** and the Publish button unlocks
   without a page reload.
5. Confirm its `media.publish_requested` event is delivered.
6. Confirm every pending non-media event keeps its previous status and attempt
   count.
7. Inspect the latest Cron run, `pg_net` HTTP response, and worker log.

Stop and disable the schedule on any 401, repeated HTTP failure, rejected valid
image, non-media claim, or cross-project evidence.

## Rollback

Disable automatic invocations without changing the worker, queue, or media:

```sql
select cron.unschedule('gallr-media-publisher-v1');
```

Confirm no row remains:

```sql
select count(*)
from cron.job
where jobname = 'gallr-media-publisher-v1';
```

The result must be zero. Existing pending media remains durable and can be
retried after the configuration is corrected.
