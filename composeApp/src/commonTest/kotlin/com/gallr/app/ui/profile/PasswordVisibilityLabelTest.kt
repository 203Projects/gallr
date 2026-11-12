package com.gallr.app.ui.profile

import com.gallr.shared.data.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class PasswordVisibilityLabelTest {
    @Test
    fun `the password toggle says what it will do in each language`() {
        assertEquals("비밀번호 보기", passwordVisibilityToggleLabel(visible = false, AppLanguage.KO))
        assertEquals("비밀번호 숨기기", passwordVisibilityToggleLabel(visible = true, AppLanguage.KO))
        assertEquals("Show password", passwordVisibilityToggleLabel(visible = false, AppLanguage.EN))
        assertEquals("Hide password", passwordVisibilityToggleLabel(visible = true, AppLanguage.EN))
    }
}
