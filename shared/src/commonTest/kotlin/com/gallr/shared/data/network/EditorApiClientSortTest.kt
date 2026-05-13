package com.gallr.shared.data.network

import com.gallr.shared.data.model.Editor
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorApiClientSortTest {

    private fun editor(id: String) = Editor(
        id = id,
        nameKo = id, nameEn = id,
        titleKo = id, titleEn = id,
        bioKo = id, bioEn = id,
        isActive = true,
        activeFrom = LocalDate(2026, 1, 1),
        activeTo = null,
    )

    @Test
    fun `empty list returns empty`() {
        assertTrue(promoteHouseEditor(emptyList()).isEmpty())
    }

    @Test
    fun `list without house editor is unchanged`() {
        val input = listOf(editor("a"), editor("b"), editor("c"))
        val result = promoteHouseEditor(input)
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
    }

    @Test
    fun `house editor at the end is promoted to position 0`() {
        val input = listOf(editor("a"), editor("b"), editor(Editor.HOUSE_EDITOR_ID))
        val result = promoteHouseEditor(input)
        assertEquals(listOf(Editor.HOUSE_EDITOR_ID, "a", "b"), result.map { it.id })
    }

    @Test
    fun `house editor in the middle is promoted to position 0 preserving order of rest`() {
        val input = listOf(editor("a"), editor(Editor.HOUSE_EDITOR_ID), editor("b"))
        val result = promoteHouseEditor(input)
        assertEquals(listOf(Editor.HOUSE_EDITOR_ID, "a", "b"), result.map { it.id })
    }
}
