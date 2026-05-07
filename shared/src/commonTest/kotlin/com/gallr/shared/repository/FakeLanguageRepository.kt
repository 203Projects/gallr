package com.gallr.shared.repository

import com.gallr.shared.data.model.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeLanguageRepository(
    savedLanguage: AppLanguage? = null,
) : LanguageRepository {
    private val flow = MutableStateFlow(savedLanguage ?: AppLanguage.KO)
    override fun observeLanguage(): Flow<AppLanguage> = flow
    override suspend fun setLanguage(language: AppLanguage) { flow.value = language }
}
