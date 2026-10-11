package com.gallr.app.ui.components

import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class BackButtonLabelTest {
    @Test
    fun `the back control is announced as a word in each language`() {
        assertEquals("뒤로", backButtonLabel(AppLanguage.KO))
        assertEquals("Back", backButtonLabel(AppLanguage.EN))
    }
}
