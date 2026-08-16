# Data model: My Gallr guest archive

## ExhibitionVisitSnapshot

- `nameKo: String`
- `nameEn: String`
- `venueNameKo: String`
- `venueNameEn: String`
- `openingDate: LocalDate`
- `closingDate: LocalDate`
- `coverImageUrl: String?`

The snapshot is immutable and is rendered with the same Korean-first and English-fallback behavior as `Exhibition`.

## ExhibitionVisit

- `clientRecordId: String`
- `exhibitionId: String`
- `snapshot: ExhibitionVisitSnapshot`
- `createdAt: Instant`

### Invariants

- `clientRecordId` and `exhibitionId` are non-blank.
- One repository contains at most one record for an `exhibitionId`.
- `createdAt` determines newest-first archive ordering for this slice.
- Adding existing exhibition IDs is idempotent and never replaces the original snapshot.

## PersistedVisitArchiveV1

- `schemaVersion: Int = 1`
- `visits: List<ExhibitionVisit>`

The payload is serialized under `my_gallr_exhibition_visits_v1`. Unknown JSON fields are ignored for forward compatibility; malformed required fields fail the read.

## MyGallrUiState

- `visits: List<ExhibitionVisit>`
- `catalogue: List<Exhibition>`
- `searchQuery: String`
- `selectedExhibitionIds: Set<String>`
- `language: AppLanguage`
- `mode: MyGallrMode` (`ARCHIVE`, `ADD_VISITS`)
- `isLoading: Boolean`
- `isSaving: Boolean`
- `loadFailed: Boolean`
- `saveFailed: Boolean`

The ViewModel derives filtered candidates from catalogue, language, archived IDs, query, and selection. Composables receive only immutable state and event callbacks.
