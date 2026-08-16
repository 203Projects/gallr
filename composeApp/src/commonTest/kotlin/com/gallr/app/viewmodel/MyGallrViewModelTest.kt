package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.ExhibitionVisit
import com.gallr.shared.data.model.FollowedGallery
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MyGallrViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val fixedInstant = Instant.parse("2026-08-13T01:02:03Z")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `archive is available without authentication state`() =
        runTest(dispatcher) {
            val visits = FakeVisitRepository()
            val viewModel = buildViewModel(visits = visits)
            advanceUntilIdle()

            assertEquals(MyGallrMode.ARCHIVE, viewModel.uiState.value.mode)
            assertTrue(
                viewModel.uiState.value.visits
                    .isEmpty(),
            )
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `add mode excludes exhibitions already archived`() =
        runTest(dispatcher) {
            val visits = FakeVisitRepository()
            val viewModel = buildViewModel(visits = visits)
            visits.addVisits(listOf(viewModel.visitForTest(exhibition("archived"))))
            advanceUntilIdle()

            viewModel.startAddingVisits()
            advanceUntilIdle()

            assertEquals(MyGallrMode.ADD_VISITS, viewModel.uiState.value.mode)
            assertEquals(
                listOf("other"),
                viewModel.uiState.value.availableExhibitions
                    .map { it.id },
            )
        }

    @Test
    fun `add mode excludes exhibitions that have not opened yet`() =
        runTest(dispatcher) {
            val viewModel =
                buildViewModel(
                    exhibitions =
                        listOf(
                            exhibition("opened"),
                            exhibition("future", openingDate = LocalDate(2026, 8, 14)),
                        ),
                )
            viewModel.startAddingVisits()
            advanceUntilIdle()

            assertEquals(
                listOf("opened"),
                viewModel.uiState.value.availableExhibitions
                    .map { it.id },
            )

            viewModel.toggleSelection("future")
            assertTrue(
                viewModel.uiState.value.selectedExhibitionIds
                    .isEmpty(),
            )
        }

    @Test
    fun `search matches bilingual exhibition and venue names case insensitively`() =
        runTest(dispatcher) {
            val viewModel = buildViewModel()
            viewModel.startAddingVisits()

            viewModel.setSearchQuery("GALLERY OTHER")
            advanceUntilIdle()

            assertEquals(
                listOf("other"),
                viewModel.uiState.value.availableExhibitions
                    .map { it.id },
            )

            viewModel.setSearchQuery("전시 archived")
            advanceUntilIdle()

            assertEquals(
                listOf("archived"),
                viewModel.uiState.value.availableExhibitions
                    .map { it.id },
            )
        }

    @Test
    fun `multiple selections save once and return to archive`() =
        runTest(dispatcher) {
            val visits = FakeVisitRepository()
            val viewModel = buildViewModel(visits = visits)
            viewModel.startAddingVisits()
            viewModel.toggleSelection("archived")
            viewModel.toggleSelection("other")

            assertEquals(2, viewModel.uiState.value.selectedExhibitionIds.size)
            assertTrue(viewModel.uiState.value.canSave)

            viewModel.saveSelected()
            advanceUntilIdle()

            assertEquals(MyGallrMode.ARCHIVE, viewModel.uiState.value.mode)
            assertTrue(
                viewModel.uiState.value.selectedExhibitionIds
                    .isEmpty(),
            )
            assertEquals(setOf("archived", "other"), visits.current.map { it.exhibitionId }.toSet())
            assertTrue(visits.current.all { it.createdAt == fixedInstant })
            assertEquals(
                "전시 archived",
                visits.current
                    .first { it.exhibitionId == "archived" }
                    .snapshot.nameKo,
            )
        }

    @Test
    fun `save failure preserves selection for retry`() =
        runTest(dispatcher) {
            val visits = FakeVisitRepository(failOnAdd = true)
            val viewModel = buildViewModel(visits = visits)
            viewModel.startAddingVisits()
            viewModel.toggleSelection("other")

            viewModel.saveSelected()
            advanceUntilIdle()

            assertEquals(MyGallrMode.ADD_VISITS, viewModel.uiState.value.mode)
            assertEquals(setOf("other"), viewModel.uiState.value.selectedExhibitionIds)
            assertTrue(viewModel.uiState.value.saveFailed)
            assertFalse(viewModel.uiState.value.isSaving)
        }

    @Test
    fun `removing a visit leaves other visits intact`() =
        runTest(dispatcher) {
            val visits = FakeVisitRepository()
            val viewModel = buildViewModel(visits = visits)
            visits.addVisits(
                listOf(
                    viewModel.visitForTest(exhibition("archived")),
                    viewModel.visitForTest(exhibition("other")),
                ),
            )
            advanceUntilIdle()

            viewModel.removeVisit("archived")
            advanceUntilIdle()

            assertEquals(listOf("other"), visits.current.map { it.exhibitionId })
        }

    private fun buildViewModel(
        visits: FakeVisitRepository = FakeVisitRepository(),
        exhibitions: List<Exhibition> = listOf(exhibition("archived"), exhibition("other")),
    ): MyGallrViewModel =
        MyGallrViewModel(
            visitRepository = visits,
            followedGalleryRepository = EmptyFollowedGalleryRepository(),
            exhibitionsState =
                MutableStateFlow(
                    ExhibitionListState.Success(
                        exhibitions,
                    ),
                ),
            language = MutableStateFlow(AppLanguage.EN),
            clock =
                object : Clock {
                    override fun now(): Instant = fixedInstant
                },
            recordIdFactory = { exhibition, instant -> "${exhibition.id}:${instant.toEpochMilliseconds()}" },
        )

    private fun MyGallrViewModel.visitForTest(exhibition: Exhibition): ExhibitionVisit =
        visitFromExhibition(
            exhibition = exhibition,
            createdAt = fixedInstant,
            clientRecordId = "record-${exhibition.id}",
        )

    private fun exhibition(
        id: String,
        openingDate: LocalDate = LocalDate(2026, 8, 1),
    ) = Exhibition(
        id = id,
        nameKo = "전시 $id",
        nameEn = "Exhibition $id",
        venueNameKo = "갤러리 $id",
        venueNameEn = "Gallery $id",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = openingDate,
        closingDate = LocalDate(2026, 8, 31),
        isFeatured = false,
        latitude = null,
        longitude = null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = "https://example.com/$id.jpg",
    )
}

private class EmptyFollowedGalleryRepository : FollowedGalleryRepository {
    override fun observeFollowedGalleries(): Flow<List<FollowedGallery>> = MutableStateFlow(emptyList())

    override suspend fun followGalleries(galleries: List<FollowedGallery>) = Unit

    override suspend fun unfollowGallery(galleryKey: String) = Unit

    override suspend fun acknowledgeGallery(
        galleryKey: String,
        currentExhibitionIds: Set<String>,
    ) = Unit
}

private class FakeVisitRepository(
    private val failOnAdd: Boolean = false,
) : VisitRepository {
    private val visits = MutableStateFlow<List<ExhibitionVisit>>(emptyList())
    val current: List<ExhibitionVisit>
        get() = visits.value

    override fun observeVisits(): Flow<List<ExhibitionVisit>> = visits

    override suspend fun addVisits(visits: List<ExhibitionVisit>) {
        if (failOnAdd) error("expected failure")
        val existing = this.visits.value.mapTo(mutableSetOf()) { it.exhibitionId }
        this.visits.value = this.visits.value + visits.filter { existing.add(it.exhibitionId) }
    }

    override suspend fun removeVisit(exhibitionId: String) {
        visits.value = visits.value.filterNot { it.exhibitionId == exhibitionId }
    }
}
