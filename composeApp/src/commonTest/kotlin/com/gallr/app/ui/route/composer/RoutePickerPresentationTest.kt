package com.gallr.app.ui.route.composer

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.Exhibition
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec 089 picker rules and copy (DR-D9, RO5). */
class RoutePickerPresentationTest {
    private val today = LocalDate(2026, 10, 8)

    @Test
    fun ctaCountsTheSelectionAndHowFullTheRouteIs() {
        val cta = pickerCta(selectedCount = 3, routeStopCount = 4, language = AppLanguage.KO)

        assertEquals(PickerCta("3개 추가 · 현재 4/10", enabled = true), cta)
        assertEquals(
            PickerCta("ADD 3 · 4/10 IN ROUTE", enabled = true),
            pickerCta(selectedCount = 3, routeStopCount = 4, language = AppLanguage.EN),
        )
    }

    @Test
    fun ctaIsDisabledWithNothingSelected() {
        assertEquals(
            PickerCta("추가할 전시를 고르세요 · 현재 7/10", enabled = false),
            pickerCta(selectedCount = 0, routeStopCount = 7, language = AppLanguage.KO),
        )
        assertEquals(
            PickerCta("SELECT EXHIBITIONS · 7/10 IN ROUTE", enabled = false),
            pickerCta(selectedCount = 0, routeStopCount = 7, language = AppLanguage.EN),
        )
    }

    @Test
    fun atTenTheCtaStatesTheLimit() {
        assertEquals(
            PickerCta("3개 추가 · 최대 10곳까지 담을 수 있어요", enabled = true),
            pickerCta(selectedCount = 3, routeStopCount = 7, language = AppLanguage.KO),
        )
        assertEquals(
            PickerCta("현재 10/10 · 최대 10곳까지 담을 수 있어요", enabled = false),
            pickerCta(selectedCount = 0, routeStopCount = 10, language = AppLanguage.KO),
        )
        assertEquals(
            PickerCta("ADD 3 · UP TO 10 STOPS", enabled = true),
            pickerCta(selectedCount = 3, routeStopCount = 7, language = AppLanguage.EN),
        )
    }

    @Test
    fun atTenUnselectedRowsAreDisabledButDeselectStillWorks() {
        val routeIds = (1..7).map { "r$it" }.toSet()
        val selected = setOf("a", "b", "c")

        assertEquals(PickerRowState.AT_CAPACITY, pickerRowState(exhibition("d"), selected, routeIds))
        assertEquals(PickerRowState.SELECTED, pickerRowState(exhibition("a"), selected, routeIds))
        assertEquals(setOf("b", "c"), toggledSelection(selected, exhibition("a"), routeIds))
        assertEquals(selected, toggledSelection(selected, exhibition("d"), routeIds))
        assertTrue(PickerRowState.SELECTED.isEnabled)
        assertFalse(PickerRowState.AT_CAPACITY.isEnabled)
    }

    @Test
    fun rowsAlreadyInTheRouteCannotBeSelected() {
        val state = pickerRowState(exhibition("r1"), emptySet(), setOf("r1"))

        assertEquals(PickerRowState.IN_ROUTE, state)
        assertFalse(state.isEnabled)
        assertEquals("이미 추가됨", pickerRowLabel(state, AppLanguage.KO))
        assertEquals("ALREADY ADDED", pickerRowLabel(state, AppLanguage.EN))
        assertEquals(emptySet(), toggledSelection(emptySet(), exhibition("r1"), setOf("r1")))
    }

    @Test
    fun exhibitionsWithoutCoordinatesSayWhyAndCannotBeSelected() {
        val unlocated = exhibition("x", located = false)
        val state = pickerRowState(unlocated, emptySet(), emptySet())

        assertEquals(PickerRowState.NO_LOCATION, state)
        assertFalse(state.isEnabled)
        assertEquals("위치 정보 없음", pickerRowLabel(state, AppLanguage.KO))
        assertEquals("NO LOCATION", pickerRowLabel(state, AppLanguage.EN))
        assertEquals(emptySet(), toggledSelection(emptySet(), unlocated, emptySet()))
    }

    @Test
    fun selectableRowsHaveNoExtraLabel() {
        val state = pickerRowState(exhibition("a"), emptySet(), emptySet())

        assertEquals(PickerRowState.SELECTABLE, state)
        assertNull(pickerRowLabel(state, AppLanguage.KO))
        assertEquals(setOf("a"), toggledSelection(emptySet(), exhibition("a"), emptySet()))
    }

    @Test
    fun endedShowsAreExcludedAndTheHelperSaysSo() {
        val ended = exhibition("ended", closingDate = LocalDate(2026, 10, 7))
        val lastDay = exhibition("last-day", closingDate = today)
        val upcoming = exhibition("upcoming", openingDate = LocalDate(2026, 10, 20))
        val farAhead = exhibition("far-ahead", openingDate = LocalDate(2026, 12, 1))

        // The List tab's catalogue window: running now, or opening within two weeks.
        assertEquals(
            listOf("last-day", "upcoming"),
            pickerCandidates(listOf(ended, lastDay, upcoming, farAhead), today).map(Exhibition::id),
        )
        assertEquals("현재·예정 전시만 보여요", pickerHelperText(AppLanguage.KO))
        assertEquals("CURRENT AND UPCOMING SHOWS ONLY", pickerHelperText(AppLanguage.EN))
    }

    @Test
    fun searchMatchesNamesAndVenuesInBothLanguagesWithinTheChosenDistrict() {
        val samcheong = exhibition("빛의 정원").copy(venueNameEn = "Samcheong Hall", regionKo = "종로구")
        val hannam = exhibition("Light Garden").copy(regionKo = "용산구")
        val candidates = listOf(samcheong, hannam)

        assertEquals(listOf("빛의 정원"), pickerResults(candidates, query = " samcheong ", region = null).map { it.id })
        assertEquals(listOf("Light Garden"), pickerResults(candidates, query = "light", region = null).map { it.id })
        assertEquals(listOf("빛의 정원"), pickerResults(candidates, query = "", region = "종로구").map { it.id })
        assertEquals(candidates, pickerResults(candidates, query = "", region = null))
    }

    @Test
    fun districtChipsFilterByTheKoreanNameAndReadInTheAppLanguage() {
        val samcheong = exhibition("samcheong").copy(regionKo = "종로구", regionEn = "Jongno-gu")
        val hannam = exhibition("hannam").copy(regionKo = "용산구", regionEn = "Yongsan-gu")
        val unnamed = exhibition("unnamed").copy(regionKo = "안산시", regionEn = "")
        val candidates = listOf(samcheong, hannam, unnamed, samcheong.copy(id = "again"))

        val korean = pickerRegions(candidates, AppLanguage.KO)
        val english = pickerRegions(candidates, AppLanguage.EN)
        assertEquals(listOf("종로구", "용산구", "안산시"), korean.map(PickerRegion::label))
        assertEquals(listOf("Jongno-gu", "Yongsan-gu", "안산시"), english.map(PickerRegion::label))
        assertEquals(listOf("종로구", "용산구", "안산시"), english.map(PickerRegion::key))
    }

    @Test
    fun selectedRowsAnnounceTheirState() {
        assertEquals("선택됨", pickerSelectedAnnouncement(AppLanguage.KO))
        assertEquals("Selected", pickerSelectedAnnouncement(AppLanguage.EN))
    }

    private fun exhibition(
        id: String,
        located: Boolean = true,
        openingDate: LocalDate = LocalDate(2026, 9, 1),
        closingDate: LocalDate = LocalDate(2026, 11, 30),
    ) = Exhibition(
        id = id,
        nameKo = id,
        nameEn = id,
        venueNameKo = "장소 $id",
        venueNameEn = "Venue $id",
        cityKo = "서울",
        cityEn = "Seoul",
        regionKo = "종로구",
        regionEn = "Jongno-gu",
        openingDate = openingDate,
        closingDate = closingDate,
        isFeatured = false,
        latitude = if (located) 37.57 else null,
        longitude = if (located) 126.98 else null,
        descriptionKo = "",
        descriptionEn = "",
        addressKo = "",
        addressEn = "",
        coverImageUrl = null,
        hours = null,
        galleryId = "gallery-$id",
    )
}
