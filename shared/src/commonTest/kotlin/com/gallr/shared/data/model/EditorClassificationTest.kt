package com.gallr.shared.data.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorClassificationTest {

    private val today = LocalDate(2026, 5, 12)

    private fun editor(
        isActive: Boolean = true,
        activeFrom: LocalDate = LocalDate(2026, 1, 1),
        activeTo: LocalDate? = null,
    ) = Editor(
        id = "test-editor",
        nameKo = "k", nameEn = "e",
        titleKo = "k", titleEn = "e",
        bioKo = "k", bioEn = "e",
        isActive = isActive,
        activeFrom = activeFrom,
        activeTo = activeTo,
    )

    @Test
    fun `active with open-ended window is currently active`() {
        assertTrue(editor(isActive = true, activeTo = null).isCurrentlyActive(today))
    }

    @Test
    fun `active with future activeTo is currently active`() {
        assertTrue(editor(isActive = true, activeTo = today.plus(7, DateTimeUnit.DAY)).isCurrentlyActive(today))
    }

    @Test
    fun `active with activeTo equal to today is currently active (inclusive)`() {
        assertTrue(editor(isActive = true, activeTo = today).isCurrentlyActive(today))
    }

    @Test
    fun `active with past activeTo is NOT currently active`() {
        assertFalse(editor(isActive = true, activeTo = today.plus(-1, DateTimeUnit.DAY)).isCurrentlyActive(today))
    }

    @Test
    fun `inactive flag overrides any date window`() {
        assertFalse(editor(isActive = false, activeTo = null).isCurrentlyActive(today))
        assertFalse(editor(isActive = false, activeFrom = LocalDate(2020, 1, 1), activeTo = LocalDate(2099, 1, 1)).isCurrentlyActive(today))
    }

    @Test
    fun `future activeFrom is NOT yet currently active`() {
        assertFalse(editor(isActive = true, activeFrom = today.plus(1, DateTimeUnit.DAY)).isCurrentlyActive(today))
    }
}
