package com.gallr.app.ui.tabs.map

import androidx.compose.ui.unit.dp
import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec 097 contracts/map-pins.md: the accessible target over each drawn pin. */
class MapPinTargetPresentationTest {
    @Test
    fun aSinglePinReadsItsExhibitionAndACountPinSaysItZoomsIn() {
        assertEquals("도시의 소리", pinTargetLabel(singleName = "도시의 소리", count = 1, language = AppLanguage.KO))
        assertEquals("전시 3개 · 확대", pinTargetLabel(singleName = null, count = 3, language = AppLanguage.KO))
        assertEquals("3 EXHIBITIONS · ZOOM IN", pinTargetLabel(singleName = null, count = 3, language = AppLanguage.EN))
    }

    @Test
    fun aLoneSinglePinKeepsTheWideTargetAndCrowdedOrCountPinsTakeThePinWidth() {
        val density = 2f
        assertEquals(104.dp, pinTargetWidth(isSingle = true, nearestNeighbourDxPx = 300f, density = density))
        assertEquals(52.dp, pinTargetWidth(isSingle = true, nearestNeighbourDxPx = 150f, density = density))
        assertEquals(104.dp, pinTargetWidth(isSingle = true, nearestNeighbourDxPx = null, density = density))
        assertEquals(52.dp, pinTargetWidth(isSingle = false, nearestNeighbourDxPx = null, density = density))
    }
}
