# Feature Specification: Editor Curation Statements

## Problem

The public Editors experience currently renders `editors.bio_ko` / `bio_en`
as the introduction to an editor's exhibition collection. A personal biography
and a curatorial statement serve different purposes and must be managed
independently.

## User Stories

### US1 — Editor maintains the collection statement

As an invited editor, I can edit the Korean and English curatorial statement
for my own collection from **My curation**, independently of my biography.

Acceptance criteria:

1. My curation shows the current Korean and English curatorial statement above
   the exhibition list.
2. Korean is required; English is optional and falls back to Korean publicly.
3. A statement-only request is allowed.
4. Statement edits and staged exhibition additions/removals are sent as one
   grouped curation request.
5. While a curation request is pending, the editor cannot submit another
   statement or exhibition change.

### US2 — Admin reviews exact proposed curation content

As an admin, I can see the proposed statement and every exhibition change
before approving or rejecting the grouped curation request.

Acceptance criteria:

1. The review card labels the content as a curatorial statement, not an editor
   biography.
2. Approval atomically applies the statement and publishes the recorded
   exhibition changes.
3. Rejection leaves the public statement unchanged and restores staged
   exhibition attribution.
4. Editors cannot call the admin review boundary.

### US3 — Public Editors experience uses the statement

As a gallr visitor, I see the editor's curatorial statement on the editor
collection page while the personal biography remains separate data.

Acceptance criteria:

1. Editor DTO/domain models expose bilingual biography and bilingual
   curatorial statement fields separately.
2. The collection banner renders the localized curatorial statement with
   English-to-Korean fallback.
3. Existing editors retain their current public introduction after migration.

### US4 — New editor onboarding captures both concepts

As an admin onboarding an editor, I enter a personal biography and a distinct
curatorial statement with unambiguous labels.

Acceptance criteria:

1. Korean biography and Korean curatorial statement are required separately.
2. The invite Edge Function validates and persists both bilingual pairs.
3. Only admins can create the editor and linked membership.

## Constraints

- One collection/curation exists per editor in the current product model.
- Existing `bio_ko` / `bio_en` semantics remain personal biography.
- New statement columns are backfilled from the existing biography so the
  shipped Editors UI does not lose copy during rollout.
- No direct table grants are added; editor and admin mutations remain RPC
  boundaries with membership-derived identity and audit evidence.
