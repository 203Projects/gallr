# Archived migration sources

This directory retains immutable SQL bodies that are required for lineage
regression checks but must not be offered to Supabase CLI as active migrations.

`20260811120000_admin_exhibition_list_and_schedule.sql` merged after production
had already recorded the later `20260812130428` migration. Production must not
repair that skipped history or apply it with `--include-all`. The active
chronological migration
`20260813120000_forward_apply_admin_exhibition_list_and_schedule.sql` contains
the exact archived body, guarded by a fail-closed state assertion and a
byte-for-byte regression test.
