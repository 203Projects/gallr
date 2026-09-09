# Plan: Explainable artist and style recommendations

## Architecture

### Canonical metadata

- Add private, RLS-enabled `content.artists` and controlled
  `content.art_taxonomy_terms` tables. Term identifiers and meanings are
  immutable; semantic changes create a new identifier.
- Add `content.exhibition_version_artists` and
  `content.exhibition_version_terms`. Artist links preserve ordered bilingual
  snapshots; an owner suggestion may have no canonical artist ID while private,
  but publication rejects unresolved suggestions.
- Keep current Admin and owner save signatures. Optional `artists` and
  `art_term_ids` patch keys are presence-sensitive: omission preserves current
  links, while explicit empty arrays clear them atomically under the existing
  version/revision guard.
- Use the existing owner submission and staff publish lifecycle as approval.
  Do not add a second metadata review queue.
- Extend `public.exhibition_catalog_v2` with bounded `artists` and `art_terms`
  JSON arrays derived only from the published version. Keep
  `public.exhibitions` and all legacy readers/keys unchanged.

### Shared mobile model

- Add default-empty `ExhibitionArtist` and `ArtTerm` metadata to `Exhibition`
  and optional/default-empty nested DTOs. Canonical-v2 selects the new arrays;
  the legacy source deliberately does not, so rollback remains compatible.
- Preserve the current catalogue integrity checksum contract for older
  canonical-v2 clients. Metadata is additive and must not make an active older
  app reject the catalogue. Add bounded DB validation and DTO validation for
  the metadata envelope itself.
- Bump the prepared local feature schema. Structured artist and term indices
  are immutable catalogue state and invalidate reuse when metadata changes.

### Evidence and scoring

- Replace bare reason enums with typed `RecommendationEvidence` carrying only
  the minimum local evidence required for presentation: source kind
  (saved/visited), canonical artist or controlled term, or a factual generic
  signal. No evidence type is serializable into analytics.
- Exact artist overlap leads scoring, followed by exact controlled term overlap;
  bilingual TF-IDF remains a lower-weight generic similarity fallback. A text
  match can never claim a specific artist/style/tone.
- Normalize group-show artist overlap so one shared artist among many does not
  receive a solo-show-strength boost. Drop candidates with no evidence before
  score ordering and diversity selection.
- Exhibition-level style/mood terms are described as shared exhibition
  characteristics, not as permanent claims about an artist. A later reviewed
  artist-term relation would be required for that stronger claim.

### Presentation and routes

- Render `WHY THIS / 추천 이유` and one or two localized evidence statements
  inside the existing sharp exhibition card. Merge the primary evidence into
  card accessibility semantics; do not expose the internal score.
- Snapshot selected recommendation evidence into a `FOR YOU` route result so
  route reranking cannot change the explanation for an already-built route.
  Other route modes retain their explicit route objective and do not receive
  personal-taste evidence.
- Follow `DESIGN.md`: monochrome surfaces, rectangular controls, 8pt spacing,
  no decorative color, no chips, and 44dp minimum interactive targets.

### Admin and Gallery

- Extend domain/repository contracts with nullable `artMetadata`: `null` means
  the connected server does not support the contract; supported-empty is an
  explicit metadata object containing empty arrays.
- Add bounded artist search and controlled taxonomy lookup contracts. Gallery
  owners may select canonical artists or enter a bilingual unresolved
  suggestion; staff can resolve/create identities before publication.
- Use ordered lists with explicit move/remove controls and grouped checkbox
  fieldsets. Avoid drag-and-drop and duplicate component state trees.
- Keep all persistence behind existing Supabase adapters and optimistic
  concurrency flows. Browser clients never access canonical tables directly.

## Validation limits

- Maximum 32 artist credits per exhibition.
- Maximum 16 controlled terms total and six per category.
- Artist search requires a bounded query and returns at most 20 results.
- Artist labels are trimmed, bilingual-fallback capable, and bounded to 200
  characters. Duplicate canonical artist IDs and duplicate term IDs are
  rejected server-side.

## Constitution Check

- **I Spec-first:** `spec.md`, this plan, and `tasks.md` precede code changes.
- **II Test-first:** DB, shared, Compose, Admin, and Gallery contract tests are
  added and observed failing before each implementation surface.
- **III Simplicity:** reuse exhibition versions, patch RPCs, owner review, and
  the existing Kotlin recommender; no new service, queue, vector store, model,
  dependency, or review workflow is introduced.
- **IV Incremental:** metadata storage/publication, shared matching, and visible
  evidence remain independently testable commits in a stacked PR.
- **V Observability:** existing redacted save/publish logs remain the operation
  boundary; no artist names, terms, or user preference evidence enter logs.
- **VI Shared-first:** recommendation contracts and scoring live in
  `shared/commonMain`; Compose owns presentation/orchestration only; native
  hosts remain unchanged. **PASS.**

## Complexity tracking

| Decision | Justification | Simpler alternative rejected |
| --- | --- | --- |
| Stable artist table plus version links | Exact identity and ordered group-show credits are required for truthful same-artist evidence. | Parsing names from free-form credits is ambiguous and cannot support truthful reasons. |
| Controlled taxonomy relation | Stable identifiers provide explainable cross-language matches and prevent arbitrary owner labels. | Free-text tags create duplicates, mistranslations, and unreviewable claims. |
| Additive metadata arrays on canonical-v2 | One atomic catalogue fetch is needed for offline local ranking while an active older app must continue reading the same catalogue. | A second mobile endpoint can drift from the catalogue and adds retry/cache complexity. |

## Verification

1. Migration-lineage validator before SQL, then clean local replay, pgTAP,
   database lint, advisors, and metadata concurrency tests.
2. Focused shared red tests, then shared ktlint/allTests and catalogue contract
   verification for both canonical-v2 and legacy sources.
3. Focused Compose presentation/accessibility/route/analytics tests, then
   Compose ktlint/allTests and Android lint/assembly.
4. Admin and Gallery typecheck, focused tests, full tests, and production build.
5. iOS simulator Kotlin tests, host build, and bilingual runtime accessibility
   inspection of cards and personalized routes.
6. Independent review for security, recommendation truthfulness, privacy, and
   backward compatibility before opening the stacked PR.
