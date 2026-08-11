package com.gallr.app.ui.tabs.map

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.map.PersonalMapMode
import kotlin.test.Test
import kotlin.test.assertEquals

class MapModeTabsTest {
    @Test
    fun `map exposes discovery and bookmark modes only`() {
        assertEquals(
            listOf(PersonalMapMode.ALL, PersonalMapMode.TO_VISIT),
            mapTabModes,
        )
    }

    @Test
    fun `saved mode is named my exhibitions in both languages`() {
        assertEquals("내 전시", mapModeLabel(PersonalMapMode.TO_VISIT, AppLanguage.KO))
        assertEquals("MY EXHIBITIONS", mapModeLabel(PersonalMapMode.TO_VISIT, AppLanguage.EN))
        assertEquals("전체 전시", mapModeLabel(PersonalMapMode.ALL, AppLanguage.KO))
        assertEquals("ALL EXHIBITIONS", mapModeLabel(PersonalMapMode.ALL, AppLanguage.EN))
    }

    @Test
    fun `saved pin legend identifies my exhibitions`() {
        assertEquals("내 전시", savedMapLegendLabel(AppLanguage.KO))
        assertEquals("MY EXHIBITIONS", savedMapLegendLabel(AppLanguage.EN))
    }
}
