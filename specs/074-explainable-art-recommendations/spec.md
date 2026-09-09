# Specification: Explainable artist and style recommendations

## Product boundary

Gallr strengthens its free on-device recommender with reviewed, structured art
metadata and specific evidence. Every item presented as `FOR YOU` must explain
why it was selected. Catalogue metadata may be stored and published through the
existing Supabase content workflow, but taste matching, evidence selection, and
ranking remain local to the mobile device. The feature must not call a hosted
model, require an AI credential, incur per-recommendation cost, use paid
promotion, or retire the legacy catalogue/key compatibility path.

## User Story 1 — Visible recommendation evidence (P1)

As a visitor, I can see a truthful, localized `WHY THIS / 추천 이유` statement
for every personalized exhibition and understand which interest caused it.

### Acceptance criteria

1. Every exhibition shown on the `FOR YOU` screen has at least one visible,
   evidence-backed reason attached to its card; a zero-evidence candidate is not
   presented as personalized or ordered arbitrarily by ID.
2. Reasons can identify an exact artist, shared medium, style, theme, or mood,
   the saved/visited exhibition that supplied the local taste signal, a followed
   gallery, staff curation, or a time-sensitive fact.
3. At most two concise reasons are shown. They are deterministic, bilingual,
   included in card accessibility semantics, and never expose numeric scores.
4. `FOR YOU` route stops preserve and display the same primary evidence. Other
   route modes continue to use their explicit proximity, saved, or closing-soon
   objective rather than pretending to be taste matches.
5. A visitor with insufficient evidence sees an honest discovery/empty state;
   Gallr does not label a generic catalogue tie-break as personal taste.

## User Story 2 — Reviewed artist and art metadata (P1)

As staff or a gallery owner, I can attach structured artist and art descriptors
to a versioned exhibition so published catalogue records support reliable,
explainable matching.

### Acceptance criteria

1. The canonical content model supports stable bilingual artist identities,
   ordered exhibition-to-artist credits, and controlled terms grouped as
   medium, style/movement, theme/subject, and mood/tone.
2. Metadata is revisioned with the exhibition working version. Gallery-owner
   edits remain owner-scoped and become public only through the existing staff
   review/publish workflow; published versions remain immutable.
3. Staff Admin and Gallery expose sharp, accessible, bilingual-friendly inputs
   using existing repository/RPC boundaries. Unknown IDs, duplicate artists or
   terms, excessive counts, stale revisions, and unauthorized edits fail closed.
4. Only metadata attached to a published canonical version reaches the public
   catalogue. Empty metadata remains valid for existing exhibitions.
5. The change is additive: existing clients may ignore the new fields, the
   legacy catalogue projection remains readable, and no legacy credential or
   compatibility function is removed or rotated.

## User Story 3 — Reliable local artistic matching (P1)

As a visitor, recommendations favor exhibitions with artists or artistic
characteristics that match my saves and visits, while remaining diverse and
fully local.

### Acceptance criteria

1. Exact shared-artist evidence is stronger than free-text similarity. Reviewed
   shared medium, style, theme, and mood terms contribute explicitly and retain
   the matched labels needed to explain the score.
2. Existing bilingual text similarity remains a lower-priority fallback. It
   cannot produce an artist/style/tone claim without corresponding structured
   evidence.
3. The engine builds separate saved and visited taste evidence, excludes saved
   and visited candidates, preserves gallery/content diversity, and returns no
   candidate without a reason.
4. A catalogue or bookmark/visit change reuses the immutable prepared index
   where valid and reranks on the existing background dispatcher.
5. No artist, term, anchor exhibition, reason, score, profile vector, route stop,
   or other preference evidence is emitted to analytics.

## Initial controlled vocabulary

- **Medium:** painting, sculpture, photography, installation, video, digital,
  performance, drawing, printmaking, craft.
- **Style/movement:** abstract, figurative, minimalist, conceptual,
  documentary, experimental.
- **Theme/subject:** identity, memory, nature, city, technology, society.
- **Mood/tone:** quiet/meditative, energetic, playful, unsettling, intimate,
  monumental.

Terms have stable identifiers and Korean/English labels. Expanding or renaming
the vocabulary is editorial data work and must not silently reinterpret an
existing identifier.

## Analytics and privacy boundary

- Existing coarse recommendation/route counts and bands remain the only
  eligible analytics. Reason and metadata payloads are structurally excluded.
- Mobile analytics remains default-off behind release and user gates.
- The ephemeral local taste/evidence profile is never persisted or transmitted.
- Existing account sync may continue to sync the user's saves, visits, and
  follows under its current privacy contract; this feature adds no new sync.

## Success criteria

- Database tests cover versioning, ownership, staff review/publish visibility,
  validation limits, legacy compatibility, and anonymous read boundaries.
- Shared tests cover exact artist/tag matches, source anchors, evidence-only
  results, cold start, deterministic bilingual labels, diversity, index reuse,
  and analytics exclusion.
- Admin and Gallery tests cover metadata entry, malformed responses, stale
  revisions, and access denial.
- Android and iOS runtime inspection confirms visible bilingual `WHY THIS`
  evidence, accessibility semantics, and route propagation.
- No hosted model, TFLite dependency, paid API, vector database, new secret, or
  production configuration change is introduced.
