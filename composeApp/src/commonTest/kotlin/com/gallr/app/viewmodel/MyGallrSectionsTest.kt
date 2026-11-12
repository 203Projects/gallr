package com.gallr.app.viewmodel

import com.gallr.app.ui.mygallr.myGallrSectionLabel
import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/** MY shows the 동선 section only while routes are released (ReleaseFeatures.ROUTES). */
class MyGallrSectionsTest {
    @Test
    fun routesSectionFollowsTheReleaseSwitch() {
        assertEquals(listOf(MyGallrSection.DIARY, MyGallrSection.FOLLOWING), myGallrSections(routesEnabled = false))
        assertEquals(MyGallrSection.entries.toList(), myGallrSections(routesEnabled = true))
    }

    /** Spec 103 US2: the 방문 section became the diary, in the section tabs and the counts row. */
    @Test
    fun theFirstSectionIsTheDiary() {
        assertEquals(MyGallrSection.DIARY, MyGallrSection.entries.first())
        assertEquals("일기", myGallrSectionLabel(MyGallrSection.DIARY, AppLanguage.KO))
        assertEquals("DIARY", myGallrSectionLabel(MyGallrSection.DIARY, AppLanguage.EN))
        assertEquals("팔로잉", myGallrSectionLabel(MyGallrSection.FOLLOWING, AppLanguage.KO))
        assertEquals("FOLLOWING", myGallrSectionLabel(MyGallrSection.FOLLOWING, AppLanguage.EN))
    }

    @Test
    fun theDiaryIsWhereMyOpens() {
        assertEquals(MyGallrSection.DIARY, MyGallrUiState().section)
    }
}
