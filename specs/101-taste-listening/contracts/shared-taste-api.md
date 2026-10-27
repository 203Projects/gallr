# Contract: shared taste API (shared/commonMain)

Public types and functions other modules call. Everything here is pure or flow-based and has no network access.

## Repository

```kotlin
interface TastePreferenceRepository {
    val state: Flow<TastePickState>              // seed, skipped, dismissals; malformed storage → empty
    suspend fun saveSeed(seed: TasteSeed)        // replaces the seed; clears `skipped`
    suspend fun markSkipped()
    suspend fun dismiss(record: TasteDismissal)  // replaces a record for the same exhibition
    suspend fun removeDismissals(exhibitionIds: Set<String>)  // snackbar undo, or a save/visit/follow lift
    suspend fun releaseTerm(termId: String)
    suspend fun expire(today: LocalDate)
}
```

DataStore keys (private, persisted names fixed): `taste_seed`, `taste_pick_skipped`, `taste_dismissals`.
Log tag `TastePreferences`; operations `taste_preference_read_failed`, `taste_preference_write_failed`
(exception type only).

## Recommender

```kotlin
data class RecommendationContext(
    …existing fields…,
    val tasteSeed: TasteSeed = TasteSeed.EMPTY,
    val dismissals: TasteDismissals = TasteDismissals.EMPTY,
)

enum class RecommendationSignalSource { SAVED, VISITED, CHOSEN }

sealed interface RecommendationEvidence {
    …existing…
    data class ChosenTerm(val term: ArtTerm) : RecommendationEvidence
}

val ExhibitionRecommendation.hasPersonalEvidence: Boolean
```

Guarantees (each is a test in `shared/commonTest`):

1. With an empty seed and no dismissals, output is identical to today's for every existing test and fixture.
2. A chosen reviewed term on a candidate yields `ChosenTerm(term)` and contribution 0.30; a chosen detected term
   yields 0.08 × rarity.
3. A chosen cover is excluded from results and acts as an anchor with source `CHOSEN` at 0.6 × saved weights.
4. Dismissal penalty per id = `min(0.25 × count, 0.5 × BASE)` (BASE 0.35 term, 0.50 artist), summed per candidate,
   applied after positive evidence, score floored at 0; tier unchanged; zero-evidence drop decided before penalty.
5. Exhibitions with a standing dismissal are excluded from `recommend()`.
6. Reasons: at most 2; order CHOSEN, SAVED, VISITED, then kind order (artist, term, text), then follow and editorial.
7. Determinism and input-order independence hold with seeds and dismissals.

## Taste pick choices

```kotlin
fun tastePickChoices(
    catalogue: List<Exhibition>,
    featuredInHomeOrder: List<Exhibition>,
    today: LocalDate,
): TastePickChoices
```

- Terms: reviewed terms on ≥3 running or upcoming exhibitions; by count desc, then category order, then vocabulary
  order; ≤3 per category; at most 10; empty when fewer than 4 qualify.
- Covers: the first 6 of `featuredInHomeOrder` still running or upcoming.

## Taste chips

`tasteTerms()` (existing) gains `excluded: Set<String>`; dismissed term ids are excluded before the top four are
chosen. Picked term ids count as anchors for chip ranking.

## Hours

`OpeningHoursCompleteness.CONFIRMED`; `WeeklyOpeningHours.confirmed(byDay)`; the catalogue DTO maps `hours_weekly` to
it when present and falls back to `OpeningHoursParser` otherwise.
