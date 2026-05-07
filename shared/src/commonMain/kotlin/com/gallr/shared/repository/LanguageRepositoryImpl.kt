package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.data.model.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val LANGUAGE_KEY = stringPreferencesKey("app_language")

class LanguageRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : LanguageRepository {

    override fun observeLanguage(): Flow<AppLanguage> =
        dataStore.data.map { prefs ->
            when (prefs[LANGUAGE_KEY]) {
                "ko" -> AppLanguage.KO
                "en" -> AppLanguage.EN
                else -> AppLanguage.KO
            }
        }

    override suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { prefs ->
            prefs[LANGUAGE_KEY] = when (language) {
                AppLanguage.KO -> "ko"
                AppLanguage.EN -> "en"
            }
        }
    }
}
