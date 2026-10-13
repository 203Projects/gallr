# Contract: 상황별 collections (revision 2, 2026-10-12)

## Shared (`shared/commonMain`, `home/HomeSituations.kt`, `home/HomeFeedBuilder.kt`)

```kotlin
data class HomeSituation(
    val key: String, val titleKo: String, val titleEn: String,
    val tagsKo: List<String>, val tagsEn: List<String>, val termIds: Set<String>,
)
val HOME_SITUATIONS: List<HomeSituation>
```

| Key | Title | Term ids |
|---|---|---|
| `situation:quiet` | 조용히 보고 싶은 날 / A QUIET VISIT | `mood:quiet-meditative`, `style:minimalist` |
| `situation:photo` | 사진 찍기 좋은 공간 / SPACES TO PHOTOGRAPH | `medium:installation`, `mood:monumental`, `medium:digital` |
| `situation:together` | 둘이서 천천히 / SLOWLY, FOR TWO | `mood:intimate` |
| `situation:lift` | 기분 전환이 필요할 때 / FOR A LIFT | `mood:playful`, `mood:energetic` |
| `situation:strange` | 낯선 감각을 찾는다면 / SOMETHING STRANGE | `mood:unsettling`, `style:experimental` |

- An exhibition answers a situation when any of its effective art terms (reviewed metadata first,
  otherwise detected from its own text, `effectiveArtTerms()`) is in the bundle.
- A situation becomes a `HomeCollectionKind.SITUATION` collection from three exhibitions, in
  catalogue order, at most ten, carrying its tags. Situations keep the table's order and sit after
  the timing collections and before neighbourhoods and themes.
- A term a shown situation covers is not also a theme tile.
- `situation:family` (아이·가족과 함께 / WITH CHILDREN AND FAMILY) has no term bundle: it gathers only
  exhibitions carrying `VisitTag.FAMILY`. `HomeFeedBuilderTest` fails if any other situation names
  children or family, or if this one gains a term bundle.

## App

`themeTiles` puts situations in the 상황별·주제별 rail. A tile's lower line is
`#tag #tag · N개 전시` / `#TAG #TAG · N EXHIBITIONS`; its headline breaks at the space nearest its
middle (`balancedHeadline`) so a Korean headline never splits inside a word. Tapping opens the
standard collection list.

## Evidence it gathers enough

On the open catalogue of 2026-10-12 (52 exhibitions), by the vocabulary's keywords: quiet 11, photo
15, together 7, lift 5, strange 10.

## Visit terms (FR-022)

| Term id | Name | App tag | Home |
|---|---|---|---|
| `visit:free-admission` | 무료 관람 / Free admission | `VisitTag.FREE_ADMISSION` | 무료 관람 curation, from two exhibitions |
| `visit:family` | 아이·가족과 함께 / Good with children and family | `VisitTag.FAMILY` | 아이·가족과 함께 situation, from three |

- Database: `visit` is the fifth value of `content.art_taxonomy_category`; the terms are rows in
  `content.art_taxonomy_terms` under the same immutability guard, id format and limits as art terms
  (pgTAP 065). They travel in a published exhibition's `art_terms` with `category = 'visit'`.
- App: `List<ArtTermDto>.toDomainVisitTags()` reads them into `Exhibition.visitTags`;
  `toDomainArtTerms()` already drops categories it does not know, so they are never art terms, taste
  terms, pick reasons or theme tiles. A visit term a build does not know is dropped.
- Admin and gallery workspace: a Visit information group in the controlled descriptors editor.
- Deploy order: Admin and the gallery workspace before migration `20261012130100`, because their
  deployed validators reject an unknown category.
