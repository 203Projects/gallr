# Legacy mobile catalogue mirror

This temporary bridge keeps installed pre-1.7.7 mobile clients current while
they still call the Singapore Supabase project. Seoul is the only source of
truth. The bridge copies only the public mobile reader resources:

- `public.exhibitions`
- `public.exhibition_catalog_v2`
- `public.events`
- `public.editors`

It never mirrors Auth users, sessions, profiles, bookmarks, thoughts, gallery
ownership, submissions, audit history, or server configuration. It is not a
dual-writer design. Every legacy-mobile catalogue column is copied, including
bilingual descriptions, dates, country and location identity, contact, ticket,
credits, editor/event links, and cover-image URLs. The compatibility project
already contains the same `event-images` objects. Event cover URLs from Seoul's
public `event-images` bucket are rewritten to the equivalent Singapore bucket
path before comparison and apply, while every other media field keeps the
authoritative snapshot value.

The canonical-v2 resource is required by the 1.7.4 and 1.7.5 iOS release
artifacts. It is copied row-for-row with its source content checksums, keeping
those builds aligned with the same normalized city labels and published
exhibitions as current clients.

The checked-in Node command remains the operator dry-run and emergency/manual
verification tool. Normal operation is automatic: an activated Seoul catalogue
trigger enqueues one transaction-deduplicated outbox event, the authenticated
delivery function calls the Seoul coordinator, and a five-minute Cron job runs
the same idempotent coordinator as reconciliation.

This is bounded-freshness replication rather than synchronous database
replication. A committed catalogue change normally arrives through the outbox
path within one to two minutes. The scheduled pass repairs a missed delivery or
any later target-row drift within five minutes. Target table writes invalidate
the remembered source hash, so an unchanged Seoul snapshot is still reapplied
when Singapore no longer matches it.

## Safety model

Migration `20260804010156_legacy_mobile_catalog_mirror.sql` installs a single
snapshot RPC and a private configuration row. The configuration is disabled by
default in every project. Browser roles cannot execute the RPC. A database owner
must enable the exact Singapore target, record the expected Seoul project ref,
and confirm that legacy exhibition writes are already frozen. Additive migration
`20260804105819_legacy_mobile_catalog_self_healing.sql` installs the private
target-drift invalidation triggers used by scheduled reconciliation and
invalidates an enabled target's recorded hash once so existing drift is repaired
by the first post-deployment pass. Additive migration
`20260805125734_legacy_mobile_canonical_catalog_mirror_fix.sql` extends the same
guarded snapshot transaction and drift detection to `exhibition_catalog_v2`. It
temporarily accepts the original three-resource payload so the database
migration can safely precede the coordinator deployment.
Additive migration
`20260812130428_legacy_mobile_country_parity.sql` independently upgrades the two
public reader contracts on the isolated Singapore target, carries
`country_code` through the guarded replacement, refreshes canonical checksums,
and clears the remembered snapshot so reconciliation repairs existing drift.
Additive migration
`20260819104500_legacy_mobile_gallery_identity_parity.sql` restores checksum
parity after Seoul gained `public.exhibition_catalog_v2.gallery_id`. See
[Row-shape parity](#row-shape-parity-non-negotiable) below; it must be applied
to both projects in the pair before the matching coordinator deployment.

## Row-shape parity (non-negotiable)

The canonical integrity checksum is derived from
`to_jsonb(row) - 'content_checksum_sha256'`, so it covers **every column of the
row**, not a curated subset. Two consequences govern every future change:

1. **Any column added to `public.exhibition_catalog_v2` on Seoul must be added
   to the compatibility project and carried in the snapshot in the same
   rollout.** Otherwise the two projects hash different row shapes, every
   mirrored row fails `legacy_mobile_catalog_canonical_v2_checksum_mismatch`,
   the guarded apply aborts, and installed mobile clients silently freeze on
   the last good snapshot while Seoul keeps moving.
2. **Identity columns must be carried, not recomputed.** `gallery_id` is a
   random UUID owned by the Seoul gallery directory. The compatibility project
   has no directory of its own, so it cannot derive the same value; the
   coordinator sends it and the target stores it verbatim.

This is exactly how the August 2026 outage happened:
`20260814010000_public_gallery_identity.sql` added `gallery_id` to Seoul only.
Singapore stopped applying snapshots on 2026-08-13, 24 outbox events
dead-lettered after five attempts each with `HTTP 502`, and the compatibility
catalogue stayed eight exhibitions behind for five days. The guard behaved
correctly — it refused to publish a snapshot it could not verify — but nothing
alerted, so the freeze was invisible.

When changing the canonical reader, update all four in the same change:

- the Seoul table (migration),
- the compatibility table (same pair migration),
- `RESOURCE_COLUMNS.exhibition_catalog_v2` in
  `supabase/functions/legacy-catalog-mirror/backend.ts`,
- `RESOURCE_COLUMNS.exhibition_catalog_v2` in
  `scripts/legacy-mobile-mirror/legacy-mobile-mirror.mjs`.

`supabase/tests/database/020_legacy_mobile_catalog_mirror.test.sql` pins the
checksum payload definition and fails closed when a snapshot omits the carried
gallery identity; the coordinator's `backend_test.ts` asserts the column is both
selected and forwarded.

Each changed snapshot:

1. validates the source ref and payload shape;
2. stages all four resources in one transaction;
3. rejects an empty legacy or canonical-v2 exhibition catalogue, duplicate IDs,
   checksum mismatches, or deletions above the owner-configured fraction (25% by
   default);
4. upserts dependencies before exhibitions and removes stale rows afterward;
5. uses the existing private legacy-write context, preserving the canonical
   ownership guard;
6. records one audit event and snapshot hash.

Replaying the same snapshot is a no-op only while the target catalogue still
matches the last applied snapshot. The hosted path uses a Seoul coordinator and
Singapore receiver so neither project stores the other project's Supabase
secret. Secrets and row payloads are never written to stdout.

## Deployment and activation

The repository production-cutover guard still applies. Deploy the additive
migration from a reviewed, clean commit to Seoul and Singapore before deploying
the updated `legacy-catalog-mirror` coordinator to Seoul. The existing
`legacy-catalog-mirror-receiver` remains protocol-compatible in Singapore. Do
not enable the target replacement configuration in Seoul.

For this two-database migration, create a separate local preflight manifest for
each direction with `GALLR_PRODUCTION_TARGET_MODE=legacy_mobile_catalog_pair`.
Seoul's manifest must exclude Singapore, and Singapore's manifest must exclude
Seoul. The pair rollout must include
`20260812130428_legacy_mobile_country_parity.sql`; it is self-contained on the
isolated Singapore project and must not be replaced by unrelated Seoul
editorial migrations. Apply it to both projects before deploying the updated
coordinator. The migration-cleared snapshot marker then forces one expanded
reconciliation without changing either project's activation settings.

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

This is a production write and requires the exact target confirmation and change
record required by the release runbook. Never run it against Seoul.

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

## Parity monitoring

The August 2026 freeze lasted five days because a guarded apply failed silently
every five minutes and nothing compared the two catalogues. Run the read-only
watchdog on a schedule:

```sh
env \
  GALLR_SEOUL_SUPABASE_URL='op://DEV/gallr-korea-server/hostname' \
  GALLR_SEOUL_SECRET_KEY='op://DEV/gallr-korea-server/credential' \
  GALLR_LEGACY_SUPABASE_URL='op://DEV/gallr-production-server/hostname' \
  GALLR_LEGACY_SECRET_KEY='op://DEV/gallr-production-server/credential' \
  op run -- node scripts/legacy-mobile-mirror/check-mirror-parity.mjs
```

It compares row counts for all four mobile reader resources and the newest
`updated_at` on both projects. Exit `0` means Singapore matches gallr-korea,
`1` means drift (message names the diverging resources and the lag), and `2`
means the check itself could not run. It never writes and prints no row
payloads. Alert on any non-zero exit that persists for two consecutive runs.

Verify the pure logic without network access:

```sh
node --test scripts/legacy-mobile-mirror/check-mirror-parity.test.mjs
```

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

Before reporting or applying a snapshot, the command also proves that the
legacy `exhibitions` reader and canonical `exhibition_catalog_v2` reader contain
the same IDs and values for every column shared by released mobile clients. It
fails closed before apply if Seoul would replicate inconsistent contracts. A
Singapore-only mismatch is printed with the complete aggregate repair diff and
causes the dry-run command to exit non-zero, preserving deletion review before
repair. This parity check is network-free unit-tested in the database CI
workflow; the dry run is the read-only live production verification entrypoint.

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

Run the dry run again. All four resources must report zero inserts, updates, and
deletes. During the temporary window, use this command for independent
verification. Investigate any deletion-limit failure rather than increasing the
limit reflexively.

## Retirement

Retirement is separately approved:

1. confirm the minimum-supported mobile version no longer calls Singapore;
2. capture a final dry-run and traffic/adoption evidence;
3. set `enabled = false` in the private configuration;
4. archive the final audit/hash evidence and a restorable Singapore backup;
5. remove the operational credentials and only then consider project deletion.
