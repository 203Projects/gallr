package com.gallr.shared.repository

import com.gallr.shared.data.model.AppLanguage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageRepositoryTest {

    @Test
    fun `observeLanguage returns Korean when no preference is saved`() = runTest {
        val repo = FakeLanguageRepository(savedLanguage = null)
        assertEquals(AppLanguage.KO, repo.observeLanguage().first())
    }

    @Test
    fun `observeLanguage returns English when English preference is saved`() = runTest {
        val repo = FakeLanguageRepository(savedLanguage = AppLanguage.EN)
        assertEquals(AppLanguage.EN, repo.observeLanguage().first())
    }

    @Test
    fun `observeLanguage returns Korean when Korean preference is saved`() = runTest {
        val repo = FakeLanguageRepository(savedLanguage = AppLanguage.KO)
        assertEquals(AppLanguage.KO, repo.observeLanguage().first())
    }

    @Test
    fun `setLanguage persists and is observable`() = runTest {
        val repo = FakeLanguageRepository(savedLanguage = null)
        repo.setLanguage(AppLanguage.EN)
        assertEquals(AppLanguage.EN, repo.observeLanguage().first())
    }
}

