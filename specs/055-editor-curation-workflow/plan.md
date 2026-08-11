# Implementation Plan: Editor curation workflow

## Constitution check

- **Spec-first:** This specification, plan, and tasks precede implementation.
- **Test-first:** pgTAP, repository, and component tests will fail before each
  implementation path is added.
- **Simplicity:** One `content.editor_requests` queue covers only the two
  required review kinds: profile and curation. Missing exhibitions reuse the
  existing canonical submission queue.
- **Incremental delivery:** Curation, profile, and missing-exhibition stories
  have independent commands and UI states.
- **Observability:** Accepted submissions and decisions append structured audit
  records.
- **Shared-first:** This is an independent React admin surface and Supabase
  backend, not KMP product logic; no Android/iOS platform module is involved.

## Design

1. Add editor request storage and least-privilege editor/admin RPCs.
2. Stage curation changes in React and submit them as one optimistic request.
3. Add a My profile tab whose bio submission creates a review request.
4. Add a compact missing-exhibition form that reuses the existing staff
   Submissions queue under a new `editor_workspace` source.
5. Extend the admin Editors workspace with pending request approval/rejection.
6. Verify all admin, database, migration, and security suites.

## Complexity tracking

No constitution exceptions. A separate generalized workflow engine was
rejected because only two editor-request kinds are required.
