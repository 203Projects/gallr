# Canonical Supabase migration lineage

This document records the one migration sequence that is safe for both clean
database replays and production-derived staging restores. The authoritative
version IDs are the versions already stored in
`supabase_migrations.schema_migrations` on the production lineage. Do not
invent replacement versions, repair the remote history, or use `--include-all`
to work around a mismatch.

## Why the repository was normalized

Several May 2026 changes were applied through Supabase with timestamp versions
while the repository retained local numeric filenames. The SQL for homepage
curation, guest editors, and editor unification was byte-identical, but the
version IDs differed. Four additional timestamped migrations existed in the
database history but not in the repository. The CLI therefore refused even a
linked dry run against a production-derived staging restore.

The repository now uses the recorded timestamp versions. This keeps the normal
push path chronological and leaves no reason to repair migration history.

## Canonical mapping

The seven recovered statement hashes below are SHA-256 values of the exact SQL
stored by Supabase (the checked-in file's one final LF is excluded from this
comparison). `.gitattributes` forces migration SQL to LF on every checkout, and
`validate-migration-lineage.mjs` (Node.js 18+) enforces the complete canonical
file set, versions, and bytes.

| Canonical version | Purpose | Recorded statement SHA-256 | Repository provenance |
| --- | --- | --- | --- |
| `20260507150314` | Add `ticket_url` and temporary `featured` | `93bea25bbc8979898793102a540de6bc27b12a4b94c53824859845178d2803d1` | Recovered from migration history |
| `20260507150817` | Drop temporary `featured` | `3f6c62bc009cdef49ac055515bfc0dd9073e2fb699e9640e9f03d77fbd5f4982` | Recovered from migration history |
| `20260511101318` | Add homepage curation | `2acd81e0ceb09c0e6495ff51618f6426263ade658f62a28501c1321976f70f04` | Former numeric `015`, byte-identical |
| `20260513110737` | Add guest editors | `ac3133940174a12fa28dd5e44ce15b7689d6b98e795f6685474981254d165b74` | Former numeric `016`, byte-identical |
| `20260513110749` | Unify editors | `05fa4deb043166ee73aac46a46ced112c766ab0002f7365c9935f090423db56e` | Former numeric `017`, byte-identical |
| `20260513112154` | Add v1.5 editor compatibility fields | `e224562fa31129028261d53bffecd5d58eeb662dfaaf8cd69693a0550774b605` | Recovered from migration history |
| `20260513112327` | Make editor-pick compatibility null-safe | `224b28bc3729e62589313a892dcc40dce3bf6ebf840b35fa65043a45b757d909` | Recovered from migration history |

The former numeric `018_add_event_short_label.sql` is now
`20260603052153_add_event_short_label.sql`, using its original Git commit time
in UTC. The SQL remains idempotent. It sorts after the recorded May history and
before the July catalog migrations that read `events.short_label`.

## The version-005 replay exception

The historical profiles/bookmarks migration used the alphanumeric version
`005b`, which Supabase CLI ignores. Production applied those objects outside a
separate recorded migration and already contains the profiles table, bookmarks
table, signup trigger, and policies.

For a clean replay, the hardened idempotent `005b` SQL is folded into
`005_add_opening_time.sql`. That guarantees the objects exist before migrations
`006`–`009` depend on them without introducing an out-of-order version `000`.
Production-derived databases skip the composite version `005` because that
version is already recorded; the staging pre-migration inventory must prove
the out-of-band objects are present before any catalog migration is applied.

This is the only supported modified historical file. Its exact bytes are
locked by the lineage validator.

## Supported database histories

Two histories are supported:

1. A clean database applies the normalized directory from `001` onward.
2. A production-derived database already records `001`–`014` and the seven May
   timestamp versions. A normal dry run then proposes only the unrecorded
   chronological migrations.

A database that records local-only versions `000` or `015`–`018` is not the
production lineage. Disposable local databases should be reset. A
non-disposable database with those versions requires a separate,
schema-equivalence-backed history-alignment change; do not repair it during a
staging rehearsal.

## August 2026 Admin forward reconciliation

Production recorded `20260812130428` from the mobile catalogue parity release
before the parallel Admin feature branch containing `20260811120000` merged.
Consequently, production does not record or expose the earlier Admin migration,
even though clean databases apply it normally before `20260812130428`.

Do not repair the production history or use `--include-all` to apply the
backdated migration. Migration
`20260813120000_forward_apply_admin_exhibition_list_and_schedule.sql` is the
chronological forward repair. It accepts only a fully missing production state
or a fully applied clean-replay state, rejects partial installations, and then
replays the exact immutable `20260811120000` body. The lineage regression test
keeps those bodies byte-for-byte aligned.

## Required checks

Run the network-free lineage guard and its tests before starting a database:

```bash
node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
node --test scripts/staging-rehearsal/lib/validate-migration-lineage.test.mjs
```

Then replay a clean local database and run pgTAP. The
`000_legacy_lineage_compatibility.test.sql` suite proves the composite version
005 objects, recovered May schema, generated compatibility columns, and event
short label all exist in the final schema.

For the production-derived staging restore, the guarded migration history must
show no remote-only version. The reviewed dry run must propose exactly these 13
pending versions:

```text
20260603052153
20260721043214
20260721051120
20260721060345
20260721060349
20260721075225
20260721103104
20260721105000
20260721105100
20260721105200
20260721120000
20260722090000
20260722102309
```

Stop if the set differs, a recovered `202605*` version is pending, a remote-only
version appears, or the CLI requests `--include-all`.
