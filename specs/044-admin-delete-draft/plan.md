# Implementation Plan: Permanently Delete Never-Published Drafts

## Approach

Add an administrator-only, idempotent database RPC for deleting one
never-published draft. Expose it through the Admin repository and a guarded
confirmation dialog. Keep Archive/Restore unchanged for editorial records.

## Constitution check

- **Spec-first:** This specification and plan precede implementation.
- **Test-first:** Repository, UI, and pgTAP tests are added and observed failing
  before implementation.
- **Simplicity:** One narrow command and one dialog; no bulk-delete framework.
- **Incremental delivery:** The feature is independently testable.
- **Observability:** The command appends `exhibition.draft_deleted` to the
  immutable audit log.
- **Shared-first:** Not applicable to the standalone React Admin application;
  no mobile business logic changes.

## Security design

- `content_private` owns the `SECURITY DEFINER` implementation with an empty
  `search_path` and fully qualified objects.
- The public RPC is `SECURITY INVOKER`.
- Execution is revoked from `PUBLIC`, `anon`, and `service_role`, then granted
  only to `authenticated`.
- The private implementation resolves `auth.uid()` and requires active `admin`
  membership from `content.staff_members`.
- Direct table deletion remains revoked.

## Verification

1. Run the new pgTAP contract against a clean local Supabase reset.
2. Run Admin unit tests, typecheck, and production build.
3. Exercise fixture Admin in the browser on desktop and mobile viewports.
4. Inspect the rendered confirmation dialog and deletion result.

