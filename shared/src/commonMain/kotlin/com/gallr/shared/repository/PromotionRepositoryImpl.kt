package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.data.model.PromotedExhibition
import com.gallr.shared.data.network.PromotionSource
import com.gallr.shared.util.runSuspendCatching
import kotlinx.coroutines.flow.first
import kotlin.random.Random

private val PROMOTION_INSTALLATION_KEY = stringPreferencesKey("promotion_installation_key_v1")

class PromotionRepositoryImpl(
    private val source: PromotionSource,
    private val keyStore: PromotionInstallationKeyStore,
) : PromotionRepository {
    override suspend fun getPromotedExhibition(
        cityKo: String,
        regionKo: String,
    ): Result<PromotedExhibition?> =
        runSuspendCatching {
            val city = cityKo.trim()
            val region = regionKo.trim()
            if (city.isEmpty() && region.isEmpty()) return@runSuspendCatching null
            source.fetch(keyStore.getOrCreate(), city, region)
        }
}

fun createPromotionRepository(
    enabled: Boolean,
    source: () -> PromotionSource,
    keyStore: () -> PromotionInstallationKeyStore,
): PromotionRepository =
    if (enabled) {
        PromotionRepositoryImpl(
            source = source(),
            keyStore = keyStore(),
        )
    } else {
        DisabledPromotionRepository
    }

private object DisabledPromotionRepository : PromotionRepository {
    override suspend fun getPromotedExhibition(
        cityKo: String,
        regionKo: String,
    ): Result<PromotedExhibition?> = Result.success(null)
}

class DataStorePromotionInstallationKeyStore(
    private val dataStore: DataStore<Preferences>,
    private val generate: () -> String = ::randomPromotionInstallationKey,
) : PromotionInstallationKeyStore {
    override suspend fun getOrCreate(): String {
        dataStore.data.first()[PROMOTION_INSTALLATION_KEY]?.let { return it }
        var value = ""
        dataStore.edit { preferences ->
            value = preferences[PROMOTION_INSTALLATION_KEY] ?: generate().also {
                preferences[PROMOTION_INSTALLATION_KEY] = it
            }
        }
        return value
    }
}

internal fun randomPromotionInstallationKey(): String =
    Random.nextBytes(24).joinToString("") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }
