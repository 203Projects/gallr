# ADR-0004: Permit explicit solo-operator cutover governance

**Status:** Accepted
**Date:** 2026-07-23
**Decider:** gallr owner/operator
**Related:** [ADR-0001](0001-staged-postgres-cms-cutover.md),
[public catalog cutover runbook](../public-exhibition-catalog-cutover-runbook.md)

## Context

The original exhibition-catalog cutover controls assume that separate people
can act as executor, reviewer, approver, and operational owners. gallr is
currently maintained by one developer. Inventing aliases for that developer or
counting CI, AI, scripts, or database sessions as additional reviewers would
create misleading evidence without adding independent human judgment.

The technical architecture selected by ADR-0001 remains appropriate: imports
are staged, canonical writes are private and transactional, public readers move
through gated deployments, and the legacy pipeline remains available through
the rollback window. The governance procedure needs an honest solo path that
reduces accidental target and sequencing errors while acknowledging that it
cannot reproduce separation of duties.

## Decision

The cutover supports two explicit governance profiles:

- `separated_humans` is the default. Its existing executor, reviewer, and
  independent-approver requirements remain unchanged.
- `solo_operator` is opt-in. One stable, real human identity may hold every
  operational responsibility. Evidence records `human_reviewer_count=0`,
  `automation_is_independent_human_review=false`, and explicit acceptance of
  the remaining single-operator risk. The same identity may appear in legacy
  `executor` and `reviewer` fields only for schema compatibility; that does not
  represent an independent review.

Aliases, alternate capitalization, bots, CI, AI assistants, and multiple
terminal or database sessions do not create additional human reviewers.
Technical automation may validate deterministic invariants, retain evidence,
or stop an operation, but it may not be described as approval.

### Solo staging control

Before the first remote staging mutation, the operator must bind the exact
staging target, excluded production target, and reviewed commit in two literal
confirmations separated by at least 900 seconds. Both literals end with an
explicit risk acknowledgement:

```text
INTENT STAGING <staging-ref> NOT PRODUCTION <production-ref> <reviewed-commit> ACCEPT_NO_INDEPENDENT_REVIEW
EXECUTE STAGING <staging-ref> NOT PRODUCTION <production-ref> <reviewed-commit> ACCEPT_NO_INDEPENDENT_REVIEW
```

The first literal is hashed into the schema-2 operator manifest and disposable
clone identity policy. The policy is sealed outside the repository. Both its
issue time and actual file modification time must satisfy the 15-minute
cooldown; replacing or editing it restarts the cooldown. The action-time
confirmation is entered after the policy and cooldown validation succeeds. The
installer then validates the linked target, direct URL/TLS shape, checkout and
artifact stability before opening the database mutation session.

The cooldown is a human-error control, not peer review. A malicious or
compromised operator, operating-system account, repository owner, or database
superuser remains able to defeat controls within that authority.

### Solo production controls

Gate 4 and Gate 6 each require a fresh schema-2 production policy, a fresh
30-minute cooldown, and distinct fixed operations:

| Gate | Authorized operation |
| --- | --- |
| `gate4` | `additive_database_deploy` |
| `gate6` | `ownership_transfer` |

The intent and execution literals bind the exact production project, explicitly
exclude the staging project, bind the gate and fixed operation, and bind the
reviewed commit:

```text
INTENT PRODUCTION <production-ref> NOT STAGING <staging-ref> <gate> <authorized-operation> <reviewed-commit>
EXECUTE PRODUCTION <production-ref> NOT STAGING <staging-ref> <gate> <authorized-operation> <reviewed-commit>
```

Changing the target, gate, operation, commit, migration set, operator manifest,
change record, evidence directory, or policy starts a new authorization and
cooldown. The production-confirmation environment variable remains unset in
solo mode; only after validating the policy and 30-minute cooldown does the
guard prompt for the `EXECUTE` literal. A production target-guard pass is
read-only and authorizes only the recorded Gate 4 or Gate 6 operation; it is not
a general production session.

### Solo evidence and exception policy

Solo evidence must retain the governance profile, stable operator identity,
zero human-reviewer count, automation disclosure, risk acceptance, target
fingerprints, reviewed commit, migration and dry-run hashes, policy and
manifest hashes, confirmation hashes and timestamps, required and observed
cooldown, backup identifier, rollback mode and thresholds, and pre/post
reconciliation results. It must not contain raw project references, database
URLs, or credentials where the existing evidence contracts prohibit them.

Identity, row-count, checksum, authorization, draft-exposure, and unexplained
data-integrity differences remain unwaivable. A solo operator may document only
preclassified non-material warnings. A material judgment call requires fixing
the source or switching to `separated_humans` with a real external reviewer.

### Legacy retirement

No staging, Gate 4, or Gate 6 policy authorizes destructive retirement. Dropping
the legacy exhibition table, RPC, or active workflow requires all of the
following:

1. Gate 7 and at least one full editorial cycle have completed without
   unexplained drift.
2. Supported readers and rollback dependencies no longer require the legacy
   surface.
3. A separate change record, separate reviewed commit, and explicit retirement
   migration exist with fresh restore evidence.
4. The exact retirement intent is sealed for at least 24 hours before execution.

The retirement procedure must be implemented and tested separately. A
successful migration rehearsal, canary, ownership transfer, or cooldown cannot
be interpreted as permission to delete legacy data.

## Consequences

- A solo developer can execute the documented staging and cutover path without
  fabricating people or weakening target, TLS, migration, reconciliation, or
  rollback invariants.
- Operational roles remain named for accountability even when one human fills
  all of them.
- Solo execution has greater residual insider, workstation-compromise, repeated
  reasoning-error, and operator-availability risk than `separated_humans`.
- Time separation and automation reduce accidental mistakes but never become
  independent human review.
- This ADR changes governance and evidence only. It does not change schemas,
  data transformation, application behavior, reader defaults, migration order,
  ownership state transitions, or rollback semantics.

## Supersession boundary

ADR-0001's architectural decision and historical decider record remain
unchanged. This ADR supersedes only the assumption that its operational gates
must always be staffed by multiple humans.
