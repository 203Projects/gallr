package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.FollowedGallerySnapshot
import com.gallr.shared.data.model.GalleryProfileImage
import com.gallr.shared.data.model.GalleryProfileImages
import com.gallr.shared.data.model.galleryKey
import com.gallr.shared.repository.FollowedGalleryRepository
import com.gallr.shared.repository.VisitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MyGallrFollowingViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val fixedInstant = Instant.parse("2026-08-14T01:02:03Z")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `gallery candidates group exhibitions by normalized bilingual venue`() =
        runTest(dispatcher) {
            val catalogue =
                MutableStateFlow<ExhibitionListState>(
                    ExhibitionListState.Success(
                        listOf(
                            exhibition("kukje-one", "국제갤러리", "KUKJE GALLERY"),
                            exhibition(
                                "kukje-two",
                                " 국제갤러리 ",
                                "kukje  gallery",
                                cityKo = "부산",
                                cityEn = "Busan",
                            ),
                            exhibition("pkm-one", "PKM 갤러리", "PKM Gallery"),
                        ),
                    ),
                )
            val viewModel = buildViewModel(catalogue = catalogue)
            viewModel.startAddingGalleries()
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.galleryCandidates.size)
            val kukje =
                viewModel.uiState.value.galleryCandidates.first {
                    it.snapshot.nameEn
                        .lowercase()
                        .contains("kukje")
                }
            assertEquals(
                "Multiple locations",
                kukje.snapshot.localizedLocation(AppLanguage.EN),
            )
            assertEquals(
                setOf("kukje-one", "kukje-two"),
                kukje.exhibitions.mapTo(mutableSetOf()) { it.id },
            )
        }

    @Test
    fun `stable gallery identity groups renamed catalogue venues`() =
        runTest(dispatcher) {
            val galleryId = "82100000-0000-0000-0000-000000000001"
            val viewModel =
                buildViewModel(
                    catalogue =
                        MutableStateFlow(
                            ExhibitionListState.Success(
                                listOf(
                                    exhibition("old", "국제갤러리", "Kukje Gallery", galleryId = galleryId),
                                    exhibition("new", "국제 갤러리", "Kukje", galleryId = galleryId),
                                ),
                            ),
                        ),
                )
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.galleryCandidates.size)
            assertEquals(
                galleryId,
                viewModel.uiState.value.galleryCandidates
                    .single()
                    .galleryId,
            )
        }

    @Test
    fun `gallery search matches English name while Korean is displayed`() =
        runTest(dispatcher) {
            val viewModel = buildViewModel()
            viewModel.startAddingGalleries()

            viewModel.setGallerySearchQuery("pkm gallery")
            advanceUntilIdle()

            assertEquals(
                listOf("PKM Gallery"),
                viewModel.uiState.value.availableGalleryCandidates
                    .map { it.snapshot.nameEn },
            )
        }

    @Test
    fun `following records current exhibitions as baseline without a new marker`() =
        runTest(dispatcher) {
            val following = FakeFollowedGalleryRepository()
            val viewModel = buildViewModel(following = following)
            viewModel.startAddingGalleries()
            val kukje =
                viewModel.uiState.value.galleryCandidates
                    .first { it.snapshot.nameEn == "Kukje Gallery" }
            viewModel.toggleGallerySelection(kukje.galleryKey)

            viewModel.saveSelectedGalleries()
            advanceUntilIdle()

            assertEquals(MyGallrMode.ARCHIVE, viewModel.uiState.value.mode)
            assertEquals(setOf("kukje-one"), following.current.single().knownExhibitionIds)
            assertEquals(
                "82100000-0000-0000-0000-000000000001",
                following.current.single().galleryId,
            )
            assertTrue(
                viewModel.uiState.value.followedGalleries
                    .single()
                    .unseenExhibitions
                    .isEmpty(),
            )
        }

    @Test
    fun `legacy name-key follow is upgraded when one stable catalogue identity matches`() =
        runTest(dispatcher) {
            val legacy =
                FollowedGallery(
                    galleryKey =
                        com.gallr.shared.data.model
                            .galleryKey("국제갤러리", "Kukje Gallery"),
                    snapshot =
                        com.gallr.shared.data.model.FollowedGallerySnapshot(
                            nameKo = "국제갤러리",
                            nameEn = "Kukje Gallery",
                            cityKo = "서울",
                            cityEn = "Seoul",
                            regionKo = "종로구",
                            regionEn = "Jongno-gu",
                        ),
                    knownExhibitionIds = setOf("kukje-one"),
                    followedAt = fixedInstant,
                )
            val following = FakeFollowedGalleryRepository(listOf(legacy))

            buildViewModel(following = following)
            advanceUntilIdle()

            assertEquals(
                "82100000-0000-0000-0000-000000000001",
                following.current.single().galleryId,
            )
        }

    @Test
    fun `later catalogue exhibition becomes unseen and acknowledgement clears it`() =
        runTest(dispatcher) {
            val catalogue =
                MutableStateFlow<ExhibitionListState>(
                    ExhibitionListState.Success(listOf(exhibition("kukje-one", "국제갤러리", "Kukje Gallery"))),
                )
            val following = FakeFollowedGalleryRepository()
            val viewModel = buildViewModel(catalogue = catalogue, following = following)
            viewModel.startAddingGalleries()
            val key =
                viewModel.uiState.value.galleryCandidates
                    .single()
                    .galleryKey
            viewModel.toggleGallerySelection(key)
            viewModel.saveSelectedGalleries()
            advanceUntilIdle()

            catalogue.value =
                ExhibitionListState.Success(
                    listOf(
                        exhibition("kukje-one", "국제갤러리", "Kukje Gallery"),
                        exhibition("kukje-new", "국제갤러리", "Kukje Gallery", openingDay = 20),
                    ),
                )
            advanceUntilIdle()

            val followed =
                viewModel.uiState.value.followedGalleries
                    .single()
            assertEquals(listOf("kukje-new"), followed.unseenExhibitions.map { it.id })
            assertEquals("kukje-new", followed.latestRelevantExhibition?.id)

            viewModel.acknowledgeGallery(key)
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.followedGalleries
                    .single()
                    .unseenExhibitions
                    .isEmpty(),
            )
            assertEquals(setOf("kukje-one", "kukje-new"), following.current.single().knownExhibitionIds)
        }

    @Test
    fun `follow save failure preserves selection for retry`() =
        runTest(dispatcher) {
            val following = FakeFollowedGalleryRepository(failOnFollow = true)
            val viewModel = buildViewModel(following = following)
            viewModel.startAddingGalleries()
            val key =
                viewModel.uiState.value.galleryCandidates
                    .first()
                    .galleryKey
            viewModel.toggleGallerySelection(key)

            viewModel.saveSelectedGalleries()
            advanceUntilIdle()

            assertEquals(MyGallrMode.ADD_GALLERIES, viewModel.uiState.value.mode)
            assertEquals(setOf(key), viewModel.uiState.value.selectedGalleryKeys)
            assertTrue(viewModel.uiState.value.followSaveFailed)
            assertFalse(viewModel.uiState.value.isSavingFollows)
        }

    @Test
    fun `followed galleries show their curated image or none`() =
        runTest(dispatcher) {
            val kukjeId = "82100000-0000-0000-0000-000000000001"
            val images = MutableStateFlow(GalleryProfileImages(listOf(profileImage(kukjeId, "국제갤러리", "Kukje Gallery"))))
            val following =
                FakeFollowedGalleryRepository(
                    initial =
                        listOf(
                            followedGallery(galleryKey("국제갤러리", "Kukje Gallery"), "국제갤러리", "Kukje Gallery", kukjeId),
                            followedGallery(galleryKey("PKM 갤러리", "PKM Gallery"), "PKM 갤러리", "PKM Gallery", null),
                        ),
                )
            val viewModel = buildViewModel(following = following, images = images)
            advanceUntilIdle()

            val urls =
                viewModel.uiState.value.followedGalleries
                    .associate { it.snapshot.nameEn to it.profileImageUrl }
            assertEquals(imageUrl(kukjeId), urls["Kukje Gallery"])
            assertNull(urls["PKM Gallery"])
        }

    @Test
    fun `followed gallery with nothing on view still shows its curated image`() =
        runTest(dispatcher) {
            val quietId = "82100000-0000-0000-0000-000000000099"
            val quietKey = galleryKey("조용한 갤러리", "Quiet Gallery")
            val quietImage = profileImage(quietId, "조용한 갤러리", "Quiet Gallery")
            val images = MutableStateFlow(GalleryProfileImages(listOf(quietImage)))
            val following =
                FakeFollowedGalleryRepository(
                    initial = listOf(followedGallery(quietKey, "조용한 갤러리", "Quiet Gallery", null)),
                )
            val viewModel = buildViewModel(following = following, images = images)
            advanceUntilIdle()

            val quiet =
                viewModel.uiState.value.followedGalleries
                    .single()
            assertTrue(quiet.currentExhibitions.isEmpty())
            assertEquals(imageUrl(quietId), quiet.profileImageUrl)
        }

    @Test
    fun `gallery candidates match curated images by name when exhibitions lack a gallery id`() =
        runTest(dispatcher) {
            val images = MutableStateFlow(GalleryProfileImages.EMPTY)
            val viewModel =
                buildViewModel(
                    catalogue =
                        MutableStateFlow(
                            ExhibitionListState.Success(
                                listOf(exhibition("pkm-one", "PKM 갤러리", "PKM Gallery", galleryId = null)),
                            ),
                        ),
                    images = images,
                )
            viewModel.startAddingGalleries()
            advanceUntilIdle()
            assertNull(
                viewModel.uiState.value.availableGalleryCandidates
                    .single()
                    .profileImageUrl,
            )

            images.value = GalleryProfileImages(listOf(profileImage("pkm-id", " pkm 갤러리", "PKM  gallery")))
            advanceUntilIdle()

            assertEquals(
                imageUrl("pkm-id"),
                viewModel.uiState.value.availableGalleryCandidates
                    .single()
                    .profileImageUrl,
            )
        }

    private fun buildViewModel(
        catalogue: MutableStateFlow<ExhibitionListState> =
            MutableStateFlow(
                ExhibitionListState.Success(
                    listOf(
                        exhibition("kukje-one", "국제갤러리", "Kukje Gallery"),
                        exhibition("pkm-one", "PKM 갤러리", "PKM Gallery"),
                    ),
                ),
            ),
        following: FakeFollowedGalleryRepository = FakeFollowedGalleryRepository(),
        images: MutableStateFlow<GalleryProfileImages> = MutableStateFlow(GalleryProfileImages.EMPTY),
    ): MyGallrViewModel =
        MyGallrViewModel(
            visitRepository = EmptyVisitRepository(),
            followedGalleryRepository = following,
            exhibitionsState = catalogue,
            language = MutableStateFlow(AppLanguage.KO),
            galleryProfileImagesState = images,
            clock =
                object : Clock {
                    override fun now(): Instant = fixedInstant
                },
        )

    private fun imageUrl(galleryId: String) =
        "https://example.supabase.co/storage/v1/object/public/gallery-profile-images/$galleryId/a.jpg"

    private fun profileImage(
        galleryId: String,
        nameKo: String,
        nameEn: String,
    ) = GalleryProfileImage(
        galleryId = galleryId,
        nameKo = nameKo,
        nameEn = nameEn,
        kind = GalleryProfileImage.Kind.LOGO,
        imageUrl = imageUrl(galleryId),
        credit = null,
    )

    private fun followedGallery(
        key: String,
        nameKo: String,
        nameEn: String,
        galleryId: String?,
    ) = FollowedGallery(
        galleryKey = key,
        snapshot = FollowedGallerySnapshot(nameKo, nameEn, "서울", "Seoul", "종로구", "Jongno-gu"),
        knownExhibitionIds = emptySet(),
        followedAt = fixedInstant,
        galleryId = galleryId,
    )

    private fun exhibition(
        id: String,
        venueKo: String,
        venueEn: String,
        openingDay: Int = 1,
        cityKo: String = "서울",
        cityEn: String = "Seoul",
        galleryId: String? =
            if (venueEn.lowercase().contains("kukje")) {
                "82100000-0000-0000-0000-000000000001"
            } else {
                "82100000-0000-0000-0000-000000000002"
            },
    ) = Exhibition(
        id = id,
        nameKo = "전시 $id",
        nameEn = "Exhibition $id",
        venueNameKo = venueKo,
        venueNameEn = venueEn,
        cityKo = cityKo,
        cityEn = cityEn,
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 8, openingDay),
        closingDate = LocalDate(2026, 9, 30),
        isFeatured = false,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        galleryId = galleryId,
    )
}

private class FakeFollowedGalleryRepository(
    initial: List<FollowedGallery> = emptyList(),
    private val failOnFollow: Boolean = false,
) : FollowedGalleryRepository {
    private val galleries = MutableStateFlow(initial)
    val current: List<FollowedGallery>
        get() = galleries.value

    override fun observeFollowedGalleries(): Flow<List<FollowedGallery>> = galleries

    override suspend fun followGalleries(galleries: List<FollowedGallery>) {
        if (failOnFollow) error("expected failure")
        val existingKeys = this.galleries.value.mapTo(mutableSetOf()) { it.galleryKey }
        this.galleries.value = this.galleries.value + galleries.filter { existingKeys.add(it.galleryKey) }
    }

    override suspend fun unfollowGallery(galleryKey: String) {
        galleries.value = galleries.value.filterNot { it.galleryKey == galleryKey }
    }

    override suspend fun acknowledgeGallery(
        galleryKey: String,
        currentExhibitionIds: Set<String>,
    ) {
        galleries.value =
            galleries.value.map { gallery ->
                if (gallery.galleryKey == galleryKey) {
                    gallery.copy(knownExhibitionIds = gallery.knownExhibitionIds + currentExhibitionIds)
                } else {
                    gallery
                }
            }
    }

    override suspend fun assignGalleryId(
        galleryKey: String,
        galleryId: String,
    ) {
        galleries.value =
            galleries.value.map { gallery ->
                if (gallery.galleryKey == galleryKey && gallery.galleryId == null) {
                    gallery.copy(galleryId = galleryId)
                } else {
                    gallery
                }
            }
    }
}

private class EmptyVisitRepository : VisitRepository {
    override fun observeVisits(): Flow<List<com.gallr.shared.data.model.ExhibitionVisit>> =
        MutableStateFlow(emptyList())

    override suspend fun addVisits(visits: List<com.gallr.shared.data.model.ExhibitionVisit>) = Unit

    override suspend fun removeVisit(exhibitionId: String) = Unit
}
