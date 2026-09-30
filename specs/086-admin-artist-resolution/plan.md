# Implementation plan

## Constitution check (before research and after design)

- I: specification precedes this plan and task list.
- II: failing behavioral tests precede implementation of each tested path.
- III: retain component state and existing repository APIs; offline backfill has
  no remote executor or new dependency.
- IV: Admin feedback and reviewed backfill preparation are independently testable.
- V: preserve visible search/create failures; backfill validation fails closed.
- VI: independent web artifacts are exempt from KMP placement; UI stays in Admin,
  database preparation in scripts. Mobile shared/platform code is unaffected.
- DESIGN.md: sharp corners, black row indicator, 8pt spacing, existing fonts.

## Design

Begin resolution in the click handler, preserving the prior creation draft for
Cancel. Prefill the active-language search (fallback to the available name) and
focus its input ref after prompt layout so it scrolls into view. Put a named creation action in the resolution prompt while
keeping editable bilingual fields below. Clear search generations/results on
completion or cancellation. Track the resolving index across reorder/removal.

Backfill export reads the current published-version pointer and free-text credit
pairs. Staff supplies reviewed artist identities per source pair (including
explicit splitting decisions). The SQL generator escapes literals, preserves
source provenance, checks still-current published credits, locks the artist
directory, skips existing exact names, rejects partial identity collisions, and
inserts new identities plus audit receipts in a transaction.

## Verification

Node 22.23.1; focused Vitest red/green, Admin typecheck/full tests/build;
network-free script tests and migration-lineage validator/tests; rendered local
fixture flow using Playwright (Browser plugin not available). No remote database
verification without reviewed environment-specific inputs and rollout authority.

Database CI runs offline backfill tests and a rollback-only canonical-schema
runner after the clean migration replay. The canonical runner exercises actual
Auth, staff roles, projection triggers, audit and authenticated artist-search RPCs.

Component-to-repository tests exercise the actual Supabase adapter so query
guards cannot silently defeat resolution. Adapter length validation counts
Unicode characters and accepts the RPC's one-character minimum. Resolution
creation errors are announced beside the action that initiated the request.

## Complexity tracking

No deviations.
