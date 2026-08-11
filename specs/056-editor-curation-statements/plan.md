# Implementation Plan: Editor Curation Statements

## Constitution Check

- **Spec-first:** `spec.md` defines four independently testable stories.
- **Test-first:** database, shared-model, Edge Function, repository, and React
  tests will fail before their implementation paths are changed.
- **Shared-first:** public editor data and localization logic live in
  `shared/commonMain`; Compose only renders the shared model.
- **Simplicity:** retain one curation per editor and extend the existing grouped
  curation request instead of introducing a new collection hierarchy.
- **Observability:** existing editor-request audit actions remain the evidence
  boundary and will include whether a statement changed.

## Design

1. Add `curation_description_ko` / `curation_description_en` to
   `public.editors`, backfilled from biography.
2. Extend editor profile retrieval and add a statement-aware curation RPC that
   accepts zero to 100 exhibition changes plus the bilingual statement.
3. Apply the statement only during admin approval; preserve the current public
   statement on rejection.
4. Add distinct biography and curation-statement fields to onboarding and its
   Edge Function contract.
5. Add a sharply bordered **Curation statement** panel to My curation and show
   the exact statement in admin review cards.
6. Extend the shared Editor/DTO model and render the statement in the public
   editor banner.

## Complexity Tracking

No exceptions. The existing editor, request, and banner abstractions are
extended without a new service or collection entity.
