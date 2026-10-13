# Tasks: Gallery Profile Images

**Input**: Design documents from `/specs/089-gallery-profile-images/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Required by Constitution Principle II (Test-First). Each test task must fail
before its implementation task starts.

## Phase 1: Setup

- [x] T001 Run `node scripts/staging-rehearsal/lib/validate-migration-lineage.mjs` and record the next migration timestamp for `supabase/migrations/<timestamp>_gallery_profile_images.sql`

## Phase 2: Foundational (backend contract + shared data path)

- [x] T002 Write failing pgTAP suite `supabase/tests/database/050_gallery_profile_images.test.sql`: table exists with RLS and no client grants; bucket `gallery-profile-images` is public, jpeg-only, 256 KiB limit; `anon`/`authenticated` can execute `public.list_gallery_profile_images()`; RPC returns only active, unmerged galleries with names, kind, storage_path, credit; check constraints reject bad kind, bad path, credit/attribution mismatch; anon cannot select/insert the table
- [x] T003 Create migration `supabase/migrations/<timestamp>_gallery_profile_images.sql` (table, constraints, RLS, revokes, bucket insert `on conflict do nothing`, `stable security definer` RPC with `set search_path = ''`, grants) until T002 passes
- [x] T004 [P] Write failing tests `shared/src/commonTest/kotlin/com/gallr/shared/data/model/GalleryProfileImagesTest.kt`: id match wins; name-key match with whitespace/case normalization; ambiguous name key → null; no partial matches; EMPTY returns null
- [x] T005 [P] Write failing tests `shared/src/commonTest/kotlin/com/gallr/shared/data/network/GalleryProfileImageApiClientTest.kt` (Ktor `MockEngine`): posts to `rpc/list_gallery_profile_images`; builds public object URL; drops rows with unknown kind or blank path/id; keeps credit
- [x] T006 Implement `shared/src/commonMain/kotlin/com/gallr/shared/data/model/GalleryProfileImage.kt` (`GalleryProfileImage`, `Kind`, `GalleryProfileImages` index reusing `galleryKey`) until T004 passes
- [x] T007 Implement `shared/src/commonMain/kotlin/com/gallr/shared/data/network/dto/GalleryProfileImageDto.kt` and `shared/src/commonMain/kotlin/com/gallr/shared/data/network/GalleryProfileImageApiClient.kt` until T005 passes
- [x] T008 Write failing test then implement `shared/src/commonMain/kotlin/com/gallr/shared/repository/GalleryProfileImageRepository.kt` + `GalleryProfileImageRepositoryImpl.kt` returning `Result<GalleryProfileImages>` via `runSuspendCatching` (test in `shared/src/commonTest/kotlin/com/gallr/shared/repository/GalleryProfileImageRepositoryTest.kt`)
- [x] T009 Wire the repository at both composition roots: `androidApp/src/main/kotlin/com/gallr/app/MainActivity.kt`, `composeApp/src/iosMain/kotlin/com/gallr/app/MainViewController.kt`, new `App(...)` parameter in `composeApp/src/commonMain/kotlin/com/gallr/app/App.kt` that loads once into a `StateFlow<GalleryProfileImages>` (initial EMPTY; failure logged via `AppLog.tagged("GalleryProfileImages")`, stays EMPTY)

**Checkpoint**: Backend contract and shared data path exist; UI unchanged.

## Phase 3: User Story 4 — Operators publish the curated set (P1)

**Independent test**: `prepare` + `sql` against staging produce stored squares and rows; rerun creates no duplicates.

- [x] T010 [P] [US4] Add reviewed `scripts/gallery-profile-images/approved-manifest.json` (76 entries) including author `credit` for every attribution-requiring photo, taken from its Wikimedia Commons file page / KOGL source
- [x] T011 [P] [US4] Write failing `node --test` suite `scripts/gallery-profile-images/gallery-profile-images.test.mjs`: manifest validation (duplicates, bad UUID, bad kind, non-http URL, credit/licence mismatch, empty); `sips` argument builder for logo vs photo; SVG/non-image rejection; SQL generator escaping, idempotent upsert, gallery-existence assertion, deterministic order
- [x] T012 [US4] Implement `scripts/gallery-profile-images/gallery-profile-images.mjs` (`validate`, `prepare <manifest> <outDir>`, `sql <bundle>`) until T011 passes; `prepare` writes outside the checkout and reports skips
- [x] T013 [US4] Write `scripts/gallery-profile-images/README.md`: staging-first runbook, 1Password-injected service key for Storage upload, `supabase db query --linked --project-ref` for SQL, production only after explicit approval, rollback (delete rows; objects are content-addressed)

## Phase 4: User Story 1 — Followed galleries show images (P1) 🎯 MVP

**Independent test**: My Gallr shows the image for a followed gallery with a curated image and the monogram otherwise, including on load failure.

- [x] T014 [US1] Write failing tests in `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/MyGallrViewModelTest.kt`: `FollowedGalleryUi.profileImageUrl` resolved by galleryId, by name key, null when absent; updates when the profile StateFlow emits after the catalogue
- [x] T015 [US1] Add `galleryProfileImages: StateFlow<GalleryProfileImages>` to `MyGallrViewModel` + factory and resolve `profileImageUrl` for followed rows in `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/MyGallrViewModel.kt` until T014 passes
- [x] T016 [US1] Create `composeApp/src/commonMain/kotlin/com/gallr/app/ui/components/GalleryAvatar.kt` (56dp, `RectangleShape`, 1dp outline, `AsyncImage` `ContentScale.Crop`, monogram shown while loading and on error) replacing `GalleryMonogram` from `ui/mygallr/AddGalleriesScreen.kt`
- [x] T017 [US1] Use `GalleryAvatar` in `FollowedGalleryRow` in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/mygallr/MyGallrScreen.kt` and pass the StateFlow from `App.kt`

## Phase 5: User Story 2 — Add Galleries candidates show images (P2)

**Independent test**: Add Galleries shows images for name-matched candidates; similar-but-different names get none.

- [x] T018 [US2] Write failing tests for `GalleryCandidate.profileImageUrl` (name-only match, ambiguous key → null) in `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/MyGallrViewModelTest.kt`
- [x] T019 [US2] Resolve `profileImageUrl` for candidates in `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/MyGallrViewModel.kt` and render `GalleryAvatar` in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/mygallr/AddGalleriesScreen.kt`

## Phase 6: User Story 3 — Credit on gallery detail (P3)

**Independent test**: Detail screen shows the image and a credit only for attribution-requiring images.

- [x] T020 [US3] Write failing tests in `composeApp/src/commonTest/kotlin/com/gallr/app/viewmodel/GalleryDetailViewModelTest.kt` for `GalleryDetailUiState.profileImage` (id/name match, credit passthrough, null when absent)
- [x] T021 [US3] Add profile StateFlow to `GalleryDetailViewModel` + factory in `composeApp/src/commonMain/kotlin/com/gallr/app/viewmodel/GalleryDetailViewModel.kt` until T020 passes
- [x] T022 [US3] Show `GalleryAvatar` beside the header name and a `labelSmall` `onSurfaceVariant` credit line (`사진:` / `Photo:` + credit) in `composeApp/src/commonMain/kotlin/com/gallr/app/ui/gallery/GalleryDetailScreen.kt`

## Phase 7: Polish & verification

- [x] T023 Run `./gradlew shared:ktlintCheck composeApp:ktlintCheck androidApp:ktlintCheck shared:allTests composeApp:testAndroidHostTest androidApp:lintDebug androidApp:assembleDebug`
- [x] T024 Run the database gate from `.github/workflows/database-tests.yml` against a disposable local stack (clean replay + pgTAP + advisors)
- [x] T025 Add a one-line `089-gallery-profile-images` entry to "Recent Changes" in `CLAUDE.md`; update `DESIGN.md` component notes to record the gallery avatar (square image, monogram fallback)
- [ ] T026 Staging publish per `scripts/gallery-profile-images/README.md` and manual check on Android/iOS against staging; production publish only after explicit user approval

## Dependencies

- Phase 2 blocks all stories. US4 (data) and US1 (UI) can proceed in parallel after Phase 2; US1 is visible only once US4 has published to the target environment.
- US2 and US3 depend on T016 (`GalleryAvatar`) and the Phase 2 StateFlow.

## Parallel examples

- T004 ‖ T005 ‖ T002 (different suites); T010 ‖ T011 ‖ T014.

## Implementation strategy

MVP = Phase 2 + US4 + US1 (followed galleries show images). Then US2, US3, polish.
