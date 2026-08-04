# Legacy mobile catalogue mirror

This temporary bridge keeps installed pre-1.7.7 mobile clients current while
they still call the Singapore Supabase project. Seoul is the only source of
truth. The bridge copies only the public mobile reader resources:

- `public.exhibitions`
- `public.events`
- `public.editors`

It never mirrors Auth users, sessions, profiles, bookmarks, thoughts, gallery
ownership, submissions, audit history, or server configuration. It is not a
dual-writer design.

The checked-in Node command remains the operator dry-run and emergency/manual
verification tool. Normal operation is automatic: an activated Seoul catalogue
trigger enqueues one transaction-deduplicated outbox event, the authenticated
delivery function calls the Seoul coordinator, and a five-minute Cron job runs
the same idempotent coordinator as reconciliation.

## Safety model

Migration `20260804010156_legacy_mobile_catalog_mirror.sql` installs a single
snapshot RPC and a private configuration row. The configuration is disabled by
default in every project. Browser roles cannot execute the RPC. A database
owner must enable the exact Singapore target, record the expected Seoul project
ref, and confirm that legacy exhibition writes are already frozen.

Each changed snapshot:

1. validates the source ref and payload shape;
2. stages all three resources in one transaction;
3. rejects an empty exhibition catalogue, duplicate IDs, or deletions above the
   owner-configured fraction (25% by default);
4. upserts dependencies before exhibitions and removes stale rows afterward;
5. uses the existing private legacy-write context, preserving the canonical
   ownership guard;
6. records one audit event and snapshot hash.

Replaying the same snapshot is a no-op. The hosted path uses a Seoul coordinator
and Singapore receiver so neither project stores the other project's Supabase
secret. Secrets and row payloads are never written to stdout.

## Deployment and activation

The repository production-cutover guard still applies. Deploy the additive
migration from a reviewed, clean commit to Seoul and Singapore. Deploy
`legacy-catalog-mirror` only to Seoul and
`legacy-catalog-mirror-receiver` only to Singapore. Do not enable the target
replacement configuration in Seoul.

On Singapore only, after confirming the compatibility project is frozen, a
database owner records the activation in one transaction:

```sql
begin;

select pg_catalog.pg_advisory_xact_lock(73241, 1);

update content_private.exhibition_catalog_runtime
set legacy_mirror_enabled = false,
    legacy_writes_blocked = true,
    legacy_mirror_enabled_at = null,
    reason = 'temporary Seoul-to-Singapore mobile compatibility mirror'
where singleton;

update content_private.legacy_mobile_catalog_mirror_config
set enabled = true,
    expected_source_project_ref = 'oqrvbstopuppznxqoonp',
    max_delete_fraction = 0.25,
    reason = '<change record and operator>'
where singleton;

commit;
```

This is a production write and requires the exact target confirmation and
change record required by the release runbook. Never run it against Seoul.

On Seoul only, after both functions and the existing outbox worker/delivery
chain are verified, enable source enqueueing and schedule reconciliation:

```sql
begin;

update content_private.legacy_mobile_catalog_mirror_config
set source_outbox_enabled = true,
    reason = '<change record and operator>'
where singleton;

select cron.schedule(
  'gallr-legacy-catalog-reconcile-5m',
  '*/5 * * * *',
  'select content_private.invoke_legacy_catalog_mirror()'
);

commit;
```

Before that transaction, store the exact Seoul coordinator URL and its inbound
token in Seoul Vault as `gallr_legacy_catalog_mirror_url` and
`gallr_legacy_catalog_mirror_token`. The same inbound token is configured on
Seoul `outbox-delivery`; a separate receiver token connects the Seoul
coordinator to Singapore. No database credential crosses the project boundary.

With the existing recurring outbox worker running at least once per minute, a
catalogue change normally reaches Singapore within one to two minutes. The
five-minute job repairs missed delivery independently. Monitor failed
`legacy_catalog.sync_requested` outbox rows and the target configuration's
`last_applied_at`; alert when either remains unhealthy for ten minutes.

## Dry run

Use the separate `DEV` vault items. `hostname` contains the project URL and
`credential` contains that project's server credential.

```sh
env \
  GALLR_SEOUL_SUPABASE_URL='op://DEV/gallr-korea-server/hostname' \
  GALLR_SEOUL_SECRET_KEY='op://DEV/gallr-korea-server/credential' \
  GALLR_LEGACY_SUPABASE_URL='op://DEV/gallr-production-server/hostname' \
  GALLR_LEGACY_SECRET_KEY='op://DEV/gallr-production-server/credential' \
  GALLR_LEGACY_MIRROR_REASON='Hanshin; this task; compatibility snapshot' \
  op run -- node scripts/legacy-mobile-mirror/legacy-mobile-mirror.mjs
```

The dry run reads both projects and reports only counts, hashes, and aggregate
insert/update/delete counts. Review every deletion before applying.

## Apply and verify

After the target configuration is enabled and the dry-run diff is accepted:

```sh
env \
  GALLR_SEOUL_SUPABASE_URL='op://DEV/gallr-korea-server/hostname' \
  GALLR_SEOUL_SECRET_KEY='op://DEV/gallr-korea-server/credential' \
  GALLR_LEGACY_SUPABASE_URL='op://DEV/gallr-production-server/hostname' \
  GALLR_LEGACY_SECRET_KEY='op://DEV/gallr-production-server/credential' \
  GALLR_LEGACY_MIRROR_REASON='Hanshin; this task; compatibility snapshot' \
  op run -- node scripts/legacy-mobile-mirror/legacy-mobile-mirror.mjs --apply
```

Run the dry run again. Every resource must report zero inserts, updates, and
deletes. During the temporary window, use this command for independent
verification. Investigate any deletion-limit failure rather than increasing
the limit reflexively.

## Retirement

Retirement is separately approved:

1. confirm the minimum-supported mobile version no longer calls Singapore;
2. capture a final dry-run and traffic/adoption evidence;
3. set `enabled = false` in the private configuration;
4. archive the final audit/hash evidence and a restorable Singapore backup;
5. remove the operational credentials and only then consider project deletion.
