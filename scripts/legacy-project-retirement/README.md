# Singapore project retirement

The owner explicitly accepted the observed legacy traffic and approved ending
Singapore project support on 2026-09-30. This ends support for pre-1.7.7 builds;
it does not retire legacy Supabase key formats still used against Seoul.

`archive.mjs` performs only a read-only, direct-TLS database export of the
reviewed Singapore target. It rejects Seoul, staging and any other project,
checks committed source and the canonical pg_dump executable, uses a temporary
0600 passfile, and encrypts output with AES-256-GCM before it reaches disk. Load
the target-bound database credential and archive key from their dedicated
1Password items into memory. Never pass a URI, password or key in argv.

```sh
node --test scripts/legacy-project-retirement/*.test.mjs
```

Storage is encrypted as newline-delimited records (`legacy-storage.ndjson.aesgcm`)
so the complete archive never becomes one in-memory JSON string. Verification
decrypts the entire stream, authenticates its GCM tag, rejects duplicate objects,
and checks every object's SHA-256 before creating a success receipt. Interrupted
temporary archives are removed; existing completed archives are never replaced.
The default volume limit remains 512 MiB. An owner-approved, private 0400 volume
record is required to raise it to 1 GiB for the inventoried 338 Singapore objects
totaling 839,800,404 bytes. Changed object counts or bytes stop the archive.
The local disk must retain at least 2 GiB beyond the estimated encrypted output.

The owned restore fixture must preload the extensions supplied by the Supabase
image, use `cron.database_name=legacy_retirement_restore`, and keep
`cron.launch_active_jobs=off`. Postmaster settings belong in server configuration,
not connection `PGOPTIONS`. The restore checks these prerequisites before
decrypting the archive and checks them again before recording success. Its
container has no network access or published host ports.

An archive integrity receipt is not restore evidence. Before project deletion:

1. Archive database/Auth, Storage bytes, Edge Functions and provider settings.
2. Restore-test the encrypted database in an isolated local database with no
   network access or active scheduler, and verify object content and counts.
3. Confirm the public stores offer Seoul-capable upgrades. Retire the live
   mirror configuration and its schedules through a separately reviewed change.
4. Seal the exact target, operation, object list, reviewed commit, backup hashes,
   restore evidence, operator identity and exclusions in a retirement intent.
5. Wait the full 24-hour solo retirement hold. Input or evidence changes restart
   it. The destructive executor and action-time confirmation must be implemented
   and tested separately; the staging and production-cutover guards do not
   authorize project deletion.
6. Remove hosted mirror credentials after shutdown, preserving archival
   credentials in 1Password. Project deletion invalidates its keys and removes
   provider backups too; retain the independent encrypted archive first.

Android version codes through 23 are the pre-Seoul cohort; 1.7.7 is code 24.
Google Play recovery prompts can reach eligible old app bundles without prior
integration, but users can dismiss them. Prepare and review the exact version
selection before activating a prompt. Current gallery-alert push registrations
do not identify that older cohort. iOS has no implemented minimum-version gate
in these clients; an update prompt cannot be retrofitted into an installed app.

Sources: [Play recovery tools](https://support.google.com/googleplay/android-developer/answer/13812041?hl=en),
[project deletion](https://supabase.com/docs/guides/platform/delete-project),
and the [retirement gate](../../docs/public-exhibition-catalog-cutover-runbook.md#legacy-retirement-separate-approval).
