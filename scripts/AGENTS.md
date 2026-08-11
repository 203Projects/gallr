# gallr Operational Scripts

This guide applies to `scripts/`. Read the root [`CLAUDE.md`](../CLAUDE.md) first, then the nearest
subdirectory `README.md`. The staging rehearsal and production cutover runbooks define their exact
commands, inputs, evidence, stop conditions, and authorization boundaries; this file does not
duplicate them.

## Safety contract

- Treat target identity, reviewed-commit, clean-checkout, toolchain, evidence, cooldown, and typed
  confirmation checks as part of the operation—not optional ceremony. Never bypass, weaken, stub,
  or reorder them to make a run proceed.
- A guard `PASS` proves only the checks it documents. It is not reusable authorization and does not
  authorize the subsequent remote command, legacy retirement, credential change, or destructive
  operation.
- Production and staging references, credentials, policies, certificates, and evidence directories
  must remain environment-specific. Keep protected artifacts outside the repository with the modes
  required by the runbook; obtain secrets from the matching 1Password item.
- Never print database URLs, tokens, project references where a runbook requires fingerprints, or
  sensitive provider responses. Do not place secrets in command arguments, generated fixtures,
  committed files, logs, or assistant output.
- Preserve fail-closed behavior. Unknown targets, dirty or substituted inputs, remote-only migration
  versions, missing evidence, malformed responses, and partial writes must stop the workflow.

## Script design

- Keep shell entrypoints non-interactive unless the safety contract requires a real terminal prompt.
  Quote expansions, use explicit absolute paths at trust boundaries, reject symlinks when required,
  and avoid `eval`, implicit globs, ambient shell startup files, and inherited tool selection.
- Keep validation pure and network-free where possible. Separate target/evidence validation from
  credential-bearing execution so tests can exercise the guard without contacting a remote system.
- Make writes explicit, bounded, idempotent where possible, and ordered after validation. Preserve
  rollback evidence before mutation and never turn a read-only preflight into an executor.
- For Node and Python utilities, isolate parsing/validation from I/O and test malformed, swapped,
  empty, duplicate, boundary, and partial-failure cases. For shell, add syntax checks and behavioral
  tests with fake local children and disposable temporary directories.
- Remove legacy import, mirror, or cutover tooling only after its owning runbook's retirement gates
  are satisfied. Preserve completed evidence and historical documentation; external teardown remains
  separately authorized.

## Verification and release boundary

Run the exact network-free tests documented by the nearest `README.md`; migration-related changes
must also pass the canonical lineage validator. The CI command inventory lives in
[`database-tests.yml`](../.github/workflows/database-tests.yml) and
[`product-surfaces.yml`](../.github/workflows/product-surfaces.yml).

Passing local tests does not authorize linking Supabase, opening a credential-bearing session,
writing staging or production data, modifying trust anchors, or executing a cutover. Stop when the
runbook requires external approval or a verified environment-specific input.
