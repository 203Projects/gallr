package com.gallr.app.ui.detail

import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class CuratorNotePresentationTest {
    @Test
    fun `the block names itself and its tip in each language`() {
        val korean = curatorNoteCopy(AppLanguage.KO)
        val english = curatorNoteCopy(AppLanguage.EN)

        assertEquals(CuratorNoteCopy(label = "에디터의 시선", tipLabel = "관람 팁"), korean)
        assertEquals(CuratorNoteCopy(label = "EDITOR'S EYE", tipLabel = "Viewing tip"), english)
    }
}
