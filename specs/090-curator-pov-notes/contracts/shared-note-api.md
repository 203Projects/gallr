# Contract: shared note read path and app surfaces

## PostgREST reads (`CuratorNoteApiClient`)

| Call | Request | Notes |
|---|---|---|
| `getPublished(limit = 30)` | `GET {rest}/curator_notes_published?select=*&order=approved_at.desc&limit=30` | Publishable key headers from `SupabaseApiHeaders`; `ignoreUnknownKeys` |
| `getForExhibition(id)` | `GET {rest}/curator_notes_published?select=*&exhibition_id=eq.{id}&limit=1` | Empty array → `null` |

Row shape (`CuratorNoteDto`):

```json
{
  "exhibition_id": "…", "version_number": 3,
  "hook_ko": "…", "hook_en": "",
  "insights_ko": [{ "text": "…", "grounding": ["…"] }], "insights_en": [],
  "tip_ko": "…", "tip_en": "",
  "reviewer_name_ko": "…", "reviewer_name_en": "…",
  "approved_at": "2026-10-12T03:10:00+00:00", "closing_date": "2026-11-30"
}
```

Malformed required fields (`exhibition_id`, `hook_ko`, `approved_at`) skip the row and count a
`curator_note_row_skipped` log; optional fields default to empty. `grounding` is ignored by the
app.

## Repository

```kotlin
interface CuratorNoteRepository {
    suspend fun getPublishedNotes(): Result<List<CuratorNote>>
    suspend fun getNote(exhibitionId: String): Result<CuratorNote?>
}
```

Failures are contained with `runCatching`; `HomeViewModel` treats a failed notes read as an empty
section and logs `curator_notes_load_failed`; `CuratorNoteViewModel` maps it to `Error` and the
detail screen renders nothing for the block.

## Home section (`CuratorEyePager`)

- Placed after the hero and before the For You rail, with the section header
  `큐레이터의 시선` / `CURATOR'S EYE` and the counter `01 / 06` style from `pagerCounter`.
- Card: cover (`AsyncImage`, the no-image treatment of the hero), hook line
  (`headlineSmall`), one insight line (`bodyMedium`, two lines max, ellipsis), `<이름> 검수` /
  `Reviewed by <name>` (`labelSmall`). 0dp corners; the grain wash of the hero with the collection
  strength; accent only on the active counter.
- Pager: `HorizontalPager`, `contentPadding` so the next card peeks by `GallrSpacing.xl`,
  `beyondViewportPageCount = 1`; `reduceMotion` disables the snap animation.
- Impression: `exhibition_impression` with `discovery_kind = curator_note`, `position_bucket` from
  the page index, once per card per tab visit. Tap: `exhibition_opened` with the same kind, then
  `onOpenExhibition(exhibition, scrollToNote = true)`.
- Accessibility: each card is one semantics node reading "큐레이터의 시선, <hook>, <insight>,
  <name> 검수, 3/6"; the counter is not read twice.

## Detail block (`CuratorNoteBlock`)

- Rendered between the description and the Thoughts section when `CuratorNoteUiState.Ready` has a
  note; nothing in `Loading`, `Error` or `Ready(null)`.
- Label `큐레이터의 시선` (`labelLarge`, uppercase in EN), hook (`titleLarge`), insight paragraphs
  (`bodyLarge`), tip prefixed `관람 팁` / `Viewing tip` (`bodyMedium` medium weight), then the
  disclosure line `AI 초안 · <이름> 검수` / `Drafted with AI, reviewed by <name>` (`labelSmall`,
  `onSurfaceVariant`).
- `scrollToNote = true` scrolls the detail column so the label is at the top inset once the block
  has been laid out; it never scrolls when the block is absent.

## Analytics changes

- Shared: `DiscoveryKind.CURATOR_NOTE` (`curator_note`).
- Edge `mobile-analytics`: add `curator_note` to `DISCOVERY_KINDS`; handler test.
- Database: extend `mobile_analytics_discovery_kind` check constraint in the feature migration;
  pgTAP case.
