# gallr Supabase

This guide applies to `supabase/`. Read the root [`CLAUDE.md`](../CLAUDE.md) first. Before changing
SQL, read [`docs/database-migration-lineage.md`](../docs/database-migration-lineage.md). For an Edge
Function, also read its local `README.md` and `deno.json`.

This subtree owns the database schema, RLS and grants, transactional command APIs, Storage policy,
pgTAP contracts, and Deno Edge Functions. Treat schema and authorization behavior as public
contracts shared by the mobile, public web, Admin, gallery, and compatibility writers.

## Migration contract

- Never edit, rename, reorder, squash, or repair an applied migration. The documented version-005
  clean-replay exception is already locked; do not create another exception or use
  `--include-all` to bypass a lineage mismatch.
- Put every new schema change in a new chronological migration. Make it concrete and replay-safe;
  do not leave placeholders or require an operator to edit SQL during deployment.
- Preserve least privilege explicitly. Review RLS, table grants, function `EXECUTE`, anonymous and
  authenticated denial, and service-role-only paths together. Privileged `SECURITY DEFINER`
  functions must pin an empty `search_path` and schema-qualify referenced objects.
- Keep lifecycle commands transactional, revision-checked, and idempotent. Retain stable request
  identities across ambiguous retries and test concurrency when ordering or uniqueness matters.
- Schema, RPC response validation, client adapters, compatibility projection, and tests must ship as
  one contract change. Add pgTAP coverage for both successful behavior and unauthorized roles.

## Local verification

Use the Deno version declared in the root `.tool-versions` file; CI reads the same source. Run the
network-free lineage checks from the repository root before starting a database:

```bash
node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs
node --test scripts/staging-rehearsal/lib/validate-migration-lineage.test.mjs
```

The complete clean-replay, pgTAP, lint, security-advisor, and concurrency sequence is maintained in
[`database-tests.yml`](../.github/workflows/database-tests.yml). Follow it rather than inventing a
shorter database gate. Local Supabase startup creates mutable local state; confirm the intended
local/disposable target before running it.

## Edge Functions

- Keep `index.ts` as the runtime composition boundary. Put request validation and response mapping in
  a testable handler and external I/O behind a narrow backend interface.
- Authenticate and authorize before privileged work; validate unknown payloads, body sizes, methods,
  content types, timeouts, pagination, and provider responses. Fail closed and return stable,
  sanitized error codes without leaking upstream bodies, secrets, or personal data.
- Browser-callable functions must keep explicit CORS behavior and accept only publishable client
  credentials. Service-role, webhook, worker, Stripe, and provider secrets remain server-only.
- Every deployable function needs a local `README.md`, `deno.json`, lockfile, focused tests, and these
  passing commands from its directory:

```bash
deno task test
deno task check
```

## Release boundary

A passing migration replay or function test does not authorize linking a project, applying a remote
migration, deploying a function, changing RLS/grants, provisioning Storage, or rotating credentials.
Use staging first, verify the exact project identity, inject secrets from the matching 1Password
item, and follow the relevant release or cutover runbook for any external change.
