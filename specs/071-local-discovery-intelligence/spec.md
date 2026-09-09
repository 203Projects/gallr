# Specification: Local discovery intelligence

## Product boundary

Gallr recommends exhibitions and builds small neighborhood itineraries entirely
on the user's device. Recommendation inference must not call a hosted AI model,
require an API key, upload a taste profile, or incur per-request model cost.

The first model is deterministic bilingual content similarity implemented in
shared Kotlin. It is deliberately replaceable by a bundled on-device embedding
model later without changing product contracts.

## User Story 1 — Relevant exhibitions (P1)

As a visitor, I can see a short list of exhibitions related to what I saved,
visited, and followed, while still receiving useful editorial nearby choices
when I have no history.

### Acceptance criteria

1. The model runs in `shared/commonMain` with no network or platform dependency.
2. Korean and English title, venue, city, region, description, credits, and
   editor identity contribute to a normalized local content representation.
3. Bookmarks and visits are explicit positive signals. A followed gallery is a
   separate boost and does not masquerade as semantic similarity.
4. Already visited exhibitions are excluded by default. Ended and otherwise
   catalogue-invisible records are never recommended.
5. With no taste history, ranking falls back to proximity, Featured/editorial
   state, and time relevance.
6. Results are deterministic, stable by exhibition ID on ties, and contain
   localized, rule-based explanations rather than generated prose.
7. Re-ranking prevents one venue or near-duplicate content cluster from filling
   the complete result set when reasonable alternatives exist.

## User Story 2 — Curated neighborhood route (P1)

As a visitor, I can turn relevant exhibitions into a two-to-five-stop itinerary
with an ordered path, estimated total distance, and estimated total time.

### Acceptance criteria

1. The MVP uses local great-circle legs and labels all totals as estimates; it
   never presents straight-line distance as turn-by-turn road distance.
2. A provider-neutral route contract can later replace estimated legs with real
   walking/driving geometry without changing recommendation ranking.
3. Route modes include `NEIGHBORHOOD`, `FOR_YOU`, `CLOSING_SOON`, and `SAVED`.
4. The planner respects a 2–5 stop request, maximum radius, distinct venues,
   catalogue visibility, and deterministic tie-breaking.
5. Stop selection balances curation score and origin distance; ordering then
   minimizes travel across the selected stops.
6. Total time includes estimated travel plus a configurable visit duration per
   stop. Free-text opening hours are surfaced as an unverified-hours warning;
   the planner does not pretend they are machine-validated.
7. Invalid coordinates are excluded without failing the remaining route.

## Privacy and cost

- Taste vectors and route candidates remain in memory on device.
- No raw location, route trace, bookmark, visit, or follow data is sent to an AI
  provider.
- No model credential or paid inference dependency is introduced.
- Analytics integration is a separate feature and may record only allowlisted,
  coarse route/recommendation outcomes—not the local taste representation.

## Success criteria

- Shared tests demonstrate bilingual relevance, cold start, explicit-signal
  weighting, visited exclusion, diversity, route modes, distance/time totals,
  invalid-coordinate handling, and deterministic output.
- The engine remains fast enough for the current catalogue on Android and iOS
  host tests without background service infrastructure.
