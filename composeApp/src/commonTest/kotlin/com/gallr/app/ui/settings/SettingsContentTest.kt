package com.gallr.app.ui.settings

import com.gallr.shared.data.model.AppLanguage
import com.gallr.shared.data.model.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsContentTest {
    @Test
    fun `authenticated settings keep the verified section and row order`() {
        val sections = settingsSections(
            lang = AppLanguage.EN,
            themeMode = ThemeMode.SYSTEM,
            notificationsEnabled = true,
            version = "1.7.7",
            isAuthenticated = true,
        )

        assertEquals(
            listOf("PREFERENCES", "SUPPORT", "ABOUT", "ACCOUNT"),
            sections.map { it.label },
        )
        assertEquals(
            listOf(SettingsRowId.LANGUAGE, SettingsRowId.APPEARANCE, SettingsRowId.NOTIFICATIONS),
            sections[0].rows.map { it.id },
        )
        assertEquals(
            listOf(
                SettingsRowId.SEND_FEEDBACK,
                SettingsRowId.REPORT_INCORRECT_EXHIBITION,
                SettingsRowId.SHARE_GALLR,
                SettingsRowId.INSTAGRAM,
            ),
            sections[1].rows.map { it.id },
        )
        assertEquals(
            listOf(
                SettingsRowId.ABOUT_GALLR,
                SettingsRowId.PRIVACY_POLICY,
                SettingsRowId.VERSION,
            ),
            sections[2].rows.map { it.id },
        )
        assertEquals(
            listOf(SettingsRowId.SIGN_OUT, SettingsRowId.DELETE_ACCOUNT),
            sections[3].rows.map { it.id },
        )
    }

    @Test
    fun `anonymous settings omit account actions`() {
        val sections = settingsSections(
            lang = AppLanguage.EN,
            themeMode = ThemeMode.LIGHT,
            notificationsEnabled = false,
            version = "1.7.7",
            isAuthenticated = false,
        )

        assertFalse(sections.any { section -> section.rows.any { it.id == SettingsRowId.SIGN_OUT } })
        assertFalse(sections.any { section -> section.rows.any { it.id == SettingsRowId.DELETE_ACCOUNT } })
    }

    @Test
    fun `current values are explicit and localized`() {
        val sections = settingsSections(
            lang = AppLanguage.KO,
            themeMode = ThemeMode.DARK,
            notificationsEnabled = false,
            version = "1.7.7",
            isAuthenticated = true,
        )
        val rows = sections.flatMap { it.rows }.associateBy { it.id }

        assertEquals("한국어", rows.getValue(SettingsRowId.LANGUAGE).value)
        assertEquals("다크", rows.getValue(SettingsRowId.APPEARANCE).value)
        assertEquals("꺼짐", rows.getValue(SettingsRowId.NOTIFICATIONS).value)
        assertEquals("1.7.7", rows.getValue(SettingsRowId.VERSION).value)
        assertTrue(rows.getValue(SettingsRowId.LANGUAGE).isDisclosure)
        assertFalse(rows.getValue(SettingsRowId.VERSION).isDisclosure)
    }
}
