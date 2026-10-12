package com.gallr.app.ui.detail

import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class CuratorNotePresentationTest {
    @Test
    fun `the block names itself and its tip in each language`() {
        val korean = curatorNoteCopy(AppLanguage.KO, reviewerName = "김지원")
        val english = curatorNoteCopy(AppLanguage.EN, reviewerName = "Jiwon Kim")

        assertEquals("큐레이터의 시선", korean.label)
        assertEquals("관람 팁", korean.tipLabel)
        assertEquals("AI 초안 · 김지원 검수", korean.disclosure)
        assertEquals("CURATOR'S EYE", english.label)
        assertEquals("Viewing tip", english.tipLabel)
        assertEquals("Drafted with AI, reviewed by Jiwon Kim", english.disclosure)
    }
}
