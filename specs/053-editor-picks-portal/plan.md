# Implementation Plan

## Constitution Check

- Spec-first: `spec.md` defines the editor boundary and acceptance scenarios.
- Test-first: pgTAP, repository, auth, and component tests are added and run
  failing before their implementation paths.
- Simplicity: use a separate editor-membership table and two narrow RPCs rather
  than widening the staff-role enum or branching every existing admin command.
- Incremental delivery: the slice is independently usable for collection
  membership changes; publication remains the existing staff workflow.
- Observability: accepted mutations append structured audit metadata.
- Shared-first: not applicable. This is the independent React admin and
  Supabase backend, with no Android/iOS business logic.

## Steps

1. Add failing database tests for membership, grants, visibility, optimistic
   concurrency, draft cloning, ownership, and audit evidence.
2. Add an additive migration with editor memberships, portal access resolution,
   and narrow list/mutation RPCs.
3. Add failing React repository, auth, and workspace tests.
4. Implement the typed editor-picks repository, dedicated workspace, and role
   routing while retaining the existing staff workspace.
5. Update onboarding/admin documentation and verify migration lineage,
   database tests, admin tests, typecheck, and production build.

## Complexity Tracking

No constitution exception. The editor path is deliberately separate from the
large staff repository/workspace so editor authorization cannot accidentally
inherit staff capabilities.
