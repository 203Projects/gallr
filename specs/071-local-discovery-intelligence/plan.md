# Plan: Local discovery intelligence

## Architecture

### Shared domain

- `LocalExhibitionRecommender`: prepares one immutable catalogue index containing
  normalized character n-gram vectors and diversity features, then reuses that
  index for signal-only reranking. Exact sorted-catalogue equality controls
  invalidation; no user taste state is cached or persisted.
- `ExhibitionRecommendationIndex`: read-only prepared boundary safe for repeated
  and concurrent reranking as bookmarks, visits, follows, origin, or date change.
- `RecommendationContext`: bookmarks, visit IDs, followed gallery IDs/keys,
  optional origin, language, date, radius, and result limit.
- `ExhibitionRecommendation`: exhibition, normalized component scores, and
  deterministic explanation reasons.
- `NeighborhoodRoutePlanner`: selects and orders 2–5 coordinate-backed stops
  from recommendations or saved exhibitions.
- `RouteEstimate`: ordered stops, estimated legs, total kilometers, travel
  minutes, visit minutes, and warnings.
- `RouteLegEstimator`: synchronous local boundary used only for offline route
  estimates.
- `DirectionsRouteProvider`: separate suspendable whole-route boundary for a
  future real directions adapter with geometry and authoritative durations.

### Application/UI follow-up

A later story wires the engine into a ViewModel and a DESIGN.md-compliant mobile
surface. This delivery first stabilizes and verifies the shared contracts.

## Model strategy

Use stable code-point n-grams with modern Hangul composition and common Latin
diacritic folding rather than whitespace-only tokens, so Korean and Latin text
work without a language-specific tokenizer. Build inverse document frequency
from the catalogue, then use
cosine similarity between each candidate and a weighted profile document from
saved/visited records. Explicit boosts for followed gallery, editorial state,
proximity, and timing remain independently inspectable.

No external dependency or bundled neural model is required for this phase.
Index preparation and reranking remain synchronous pure shared functions; the
future ViewModel must invoke both on `Dispatchers.Default`, keep the last prepared
index, and offer it back to `prepare` when a refreshed catalogue arrives.

## Constitution check

- **Spec-First:** explicit user stories and acceptance criteria precede code.
- **Test-First:** pure model and route tests must fail before implementation.
- **Shared-First:** all reusable ranking, distance, and route decisions live in
  `shared/commonMain`; UI only presents immutable results.
- **Simplicity:** current catalogue size does not justify an inference runtime,
  vector database, background service, or paid model API.
- **Observability:** future orchestration logs stable operation names only; the
  pure engine contains no logging or personal-data side effects.

## Verification

1. Focused red/green Android host tests.
2. `./gradlew shared:ktlintCheck shared:allTests`.
3. Android and iOS compilation through the existing product-surface gate before
   UI integration.
