package com.gallr.app.viewmodel

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import com.gallr.shared.data.model.map.MapScopeId
import com.gallr.shared.data.model.map.MapScopeKind
import com.gallr.shared.data.model.map.PersonalMapMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalMapViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val today = LocalDate(2026, 8, 9)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `initial all state opens Seoul directly`() =
        runTest(dispatcher) {
            val exhibitions =
                MutableStateFlow<ExhibitionListState>(
                    ExhibitionListState.Success(
                        listOf(
                            exhibition("seoul", cityKo = "서울", cityEn = "Seoul"),
                            exhibition("busan", cityKo = "부산", cityEn = "Busan", latitude = 35.18, longitude = 129.08),
                        ),
                    ),
                )
            val viewModel =
                PersonalMapViewModel(
                    exhibitionsState = exhibitions,
                    bookmarkedIds = MutableStateFlow(emptySet()),
                    language = MutableStateFlow(AppLanguage.EN),
                    todayProvider = { today },
                )
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertEquals(MapScopeKind.CITY, viewModel.uiState.value.activeScope.kind)
            assertEquals("city:KR:seoul", viewModel.uiState.value.activeScope.id.value)
            assertEquals("seoul", assertNotNull(viewModel.uiState.value.geometry).key)
            assertEquals(
                listOf("seoul"),
                viewModel.uiState.value.resultExhibitions
                    .map { it.id },
            )
            assertEquals(25, viewModel.uiState.value.childSummaries.size)
        }

    @Test
    fun `same Seoul venue coordinate exposes every grouped exhibition`() =
        runTest(dispatcher) {
            val viewModel =
                PersonalMapViewModel(
                    exhibitionsState =
                        MutableStateFlow(
                            ExhibitionListState.Success(
                                listOf(exhibition("a"), exhibition("b"), exhibition("c", latitude = 37.59)),
                            ),
                        ),
                    bookmarkedIds = MutableStateFlow(setOf("a")),
                    language = MutableStateFlow(AppLanguage.KO),
                    todayProvider = { today },
                )
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            val grouped =
                viewModel.uiState.value.projection.marks
                    .single { it.itemIds.size == 2 }
            viewModel.selectMark(grouped.id)
            advanceUntilIdle()

            assertEquals(
                listOf("a", "b"),
                viewModel.uiState.value.selectedExhibitions
                    .map { it.id },
            )
            assertEquals("종로구", viewModel.uiState.value.selectedDistrictLabel)
        }

    @Test
    fun `selecting a district label focuses one of its exhibition marks`() =
        runTest(dispatcher) {
            val jongno = exhibition("jongno")
            val yongsan =
                exhibition("yongsan", latitude = 37.53, longitude = 126.97).copy(
                    regionKo = "용산구",
                    regionEn = "Yongsan-gu",
                )
            val viewModel =
                PersonalMapViewModel(
                    exhibitionsState = MutableStateFlow(ExhibitionListState.Success(listOf(jongno, yongsan))),
                    bookmarkedIds = MutableStateFlow(emptySet()),
                    language = MutableStateFlow(AppLanguage.KO),
                    todayProvider = { today },
                )
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            val yongsanScope =
                viewModel.uiState.value.childSummaries
                    .single { it.scope.labelKo == "용산구" }
                    .scope
            viewModel.selectChildArea(yongsanScope.id)
            advanceUntilIdle()

            assertEquals("용산구", viewModel.uiState.value.selectedDistrictLabel)
            assertEquals(
                listOf("yongsan"),
                viewModel.uiState.value.selectedExhibitions
                    .map { it.id },
            )
        }

    @Test
    fun `district editor toggles multiple districts and exposes their combined exhibition count`() =
        runTest(dispatcher) {
            val jongno = exhibition("jongno")
            val yongsan =
                exhibition("yongsan", latitude = 37.53, longitude = 126.97).copy(
                    regionKo = "용산구",
                    regionEn = "Yongsan-gu",
                )
            val viewModel =
                PersonalMapViewModel(
                    exhibitionsState = MutableStateFlow(ExhibitionListState.Success(listOf(jongno, yongsan))),
                    bookmarkedIds = MutableStateFlow(emptySet()),
                    language = MutableStateFlow(AppLanguage.KO),
                    todayProvider = { today },
                )
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            val districts =
                viewModel.uiState.value.childSummaries
                    .associateBy { it.scope.labelKo }
            viewModel.toggleDistrictSelection(districts.getValue("종로구").scope.id)
            viewModel.toggleDistrictSelection(districts.getValue("용산구").scope.id)
            advanceUntilIdle()

            assertEquals(
                setOf(districts.getValue("종로구").scope.id, districts.getValue("용산구").scope.id),
                viewModel.uiState.value.selectedDistrictIds,
            )
            assertEquals(2, viewModel.uiState.value.selectedDistrictExhibitionCount)

            viewModel.toggleDistrictSelection(districts.getValue("종로구").scope.id)
            advanceUntilIdle()
            assertEquals(setOf(districts.getValue("용산구").scope.id), viewModel.uiState.value.selectedDistrictIds)
            assertEquals(1, viewModel.uiState.value.selectedDistrictExhibitionCount)
        }

    @Test
    fun `district selection save remains on the direct Seoul map`() =
        runTest(dispatcher) {
            val viewModel =
                PersonalMapViewModel(
                    exhibitionsState = MutableStateFlow(ExhibitionListState.Success(listOf(exhibition("jongno")))),
                    bookmarkedIds = MutableStateFlow(emptySet()),
                    language = MutableStateFlow(AppLanguage.KO),
                    todayProvider = { today },
                )
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            val jongno =
                viewModel.uiState.value.childSummaries
                    .single { it.scope.labelKo == "종로구" }
                    .scope

            viewModel.toggleDistrictSelection(jongno.id)
            viewModel.saveDistrictSelection()
            advanceUntilIdle()

            assertEquals(MapScopeKind.CITY, viewModel.uiState.value.activeScope.kind)
            assertEquals("city:KR:seoul", viewModel.uiState.value.activeScope.id.value)
            assertEquals(setOf(jongno.id), viewModel.uiState.value.selectedDistrictIds)

            viewModel.clearDistrictSelection()
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.selectedDistrictIds
                    .isEmpty(),
            )
            assertEquals(0, viewModel.uiState.value.selectedDistrictExhibitionCount)
        }

    @Test
    fun `to visit mode contains saved unvisited items while visited stays empty before diary support`() =
        runTest(dispatcher) {
            val viewModel =
                PersonalMapViewModel(
                    exhibitionsState =
                        MutableStateFlow(
                            ExhibitionListState.Success(
                                listOf(exhibition("saved"), exhibition("other", latitude = 37.59)),
                            ),
                        ),
                    bookmarkedIds = MutableStateFlow(setOf("saved")),
                    language = MutableStateFlow(AppLanguage.EN),
                    todayProvider = { today },
                )
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertEquals(setOf("saved"), viewModel.uiState.value.savedExhibitionIds)

            viewModel.setMode(PersonalMapMode.TO_VISIT)
            advanceUntilIdle()
            assertEquals(
                1,
                viewModel.uiState.value.projection.marks
                    .sumOf { it.itemIds.size },
            )

            viewModel.setMode(PersonalMapMode.VISITED)
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.projection.marks
                    .isEmpty(),
            )
        }

    @Test
    fun `loading empty and generic city states preserve an equivalent map path`() =
        runTest(dispatcher) {
            val exhibitions = MutableStateFlow<ExhibitionListState>(ExhibitionListState.Loading)
            val language = MutableStateFlow(AppLanguage.KO)
            val viewModel =
                PersonalMapViewModel(
                    exhibitionsState = exhibitions,
                    bookmarkedIds = MutableStateFlow(emptySet()),
                    language = language,
                    todayProvider = { today },
                )
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isLoading)
            assertEquals("seoul", assertNotNull(viewModel.uiState.value.geometry).key)

            exhibitions.value =
                ExhibitionListState.Success(
                    listOf(exhibition("busan", cityKo = "부산", cityEn = "Busan", latitude = 35.18, longitude = 129.08)),
                )
            advanceUntilIdle()
            viewModel.openScope(MapScopeId("city:KR:busan"))
            advanceUntilIdle()

            assertEquals("부산", viewModel.uiState.value.activeScope.labelKo)
            assertEquals(
                "city-constellation:city:KR:busan",
                assertNotNull(viewModel.uiState.value.geometry).key,
            )
            assertEquals(
                listOf("busan"),
                viewModel.uiState.value.resultExhibitions
                    .map { it.id },
            )

            language.value = AppLanguage.EN
            exhibitions.value = ExhibitionListState.Success(emptyList())
            viewModel.goToParent()
            advanceUntilIdle()
            assertEquals(AppLanguage.EN, viewModel.uiState.value.language)
            assertTrue(
                viewModel.uiState.value.resultExhibitions
                    .isEmpty(),
            )
        }

    private fun exhibition(
        id: String,
        cityKo: String = "서울",
        cityEn: String = "Seoul",
        latitude: Double = 37.57,
        longitude: Double = 126.98,
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "공유 장소",
        venueNameEn = "Shared Venue",
        cityKo = cityKo,
        cityEn = cityEn,
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = LocalDate(2026, 8, 1),
        closingDate = LocalDate(2026, 8, 31),
        isFeatured = false,
        latitude = latitude,
        longitude = longitude,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        countryCode = "KR",
    )
}
