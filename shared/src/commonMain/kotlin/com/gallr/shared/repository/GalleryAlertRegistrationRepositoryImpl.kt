package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.data.model.RemotePushAddress
import com.gallr.shared.data.network.GalleryAlertCommandSource
import com.gallr.shared.data.network.GalleryAlertInstallationState
import com.gallr.shared.util.runSuspendCatching
import kotlinx.coroutines.flow.first
import kotlin.random.Random

private val GALLERY_ALERT_INSTALLATION_ID = stringPreferencesKey("gallery_alert_installation_id_v1")
private val GALLERY_ALERT_INSTALLATION_SECRET = stringPreferencesKey("gallery_alert_installation_secret_v1")
private val GALLERY_ALERT_INSTALLATION_REVISION = intPreferencesKey("gallery_alert_installation_revision_v1")
private val GALLERY_ALERT_PUSH_TOKEN_REVISION = intPreferencesKey("gallery_alert_push_token_revision_v1")

data class GalleryAlertInstallationLocalState(
    val installationId: String,
    val installationSecret: String,
    val installationRevision: Int,
    val pushTokenRevision: Int,
)

interface GalleryAlertInstallationStateStore {
    suspend fun getOrCreate(): GalleryAlertInstallationLocalState

    suspend fun updateRevisions(
        installationRevision: Int? = null,
        pushTokenRevision: Int? = null,
    )
}

class DataStoreGalleryAlertInstallationStateStore(
    private val dataStore: DataStore<Preferences>,
    private val generateInstallationId: () -> String = ::randomInstallationId,
    private val generateInstallationSecret: () -> String = ::randomInstallationSecret,
) : GalleryAlertInstallationStateStore {
    override suspend fun getOrCreate(): GalleryAlertInstallationLocalState {
        val existing = dataStore.data.first()
        val existingId = existing[GALLERY_ALERT_INSTALLATION_ID]
        val existingSecret = existing[GALLERY_ALERT_INSTALLATION_SECRET]
        if (existingId != null && existingSecret != null) return existing.toState(existingId, existingSecret)

        var state: GalleryAlertInstallationLocalState? = null
        dataStore.edit { preferences ->
            val installationId = preferences[GALLERY_ALERT_INSTALLATION_ID] ?: generateInstallationId()
            val installationSecret =
                preferences[GALLERY_ALERT_INSTALLATION_SECRET] ?: generateInstallationSecret()
            preferences[GALLERY_ALERT_INSTALLATION_ID] = installationId
            preferences[GALLERY_ALERT_INSTALLATION_SECRET] = installationSecret
            state = preferences.toState(installationId, installationSecret)
        }
        return checkNotNull(state)
    }

    override suspend fun updateRevisions(
        installationRevision: Int?,
        pushTokenRevision: Int?,
    ) {
        dataStore.edit { preferences ->
            installationRevision?.let { preferences[GALLERY_ALERT_INSTALLATION_REVISION] = it }
            pushTokenRevision?.let { preferences[GALLERY_ALERT_PUSH_TOKEN_REVISION] = it }
        }
    }

    private fun Preferences.toState(
        installationId: String,
        installationSecret: String,
    ) = GalleryAlertInstallationLocalState(
        installationId = installationId,
        installationSecret = installationSecret,
        installationRevision = this[GALLERY_ALERT_INSTALLATION_REVISION] ?: 0,
        pushTokenRevision = this[GALLERY_ALERT_PUSH_TOKEN_REVISION] ?: 0,
    )
}

class GalleryAlertRegistrationRepositoryImpl(
    private val source: GalleryAlertCommandSource,
    private val stateStore: GalleryAlertInstallationStateStore,
) : GalleryAlertRegistrationRepository {
    override suspend fun enableGallery(
        galleryId: String,
        address: RemotePushAddress,
        locale: String,
    ): Result<Unit> =
        runSuspendCatching {
            require(galleryId.isNotBlank()) { "galleryId must not be blank" }
            require(locale.isNotBlank()) { "locale must not be blank" }
            val local = stateStore.getOrCreate()
            val installation =
                source.registerInstallation(
                    installationId = local.installationId,
                    installationSecret = local.installationSecret,
                    platform = address.platform,
                    locale = locale,
                    expectedRevision = local.installationRevision,
                )
            stateStore.updateRevisions(installationRevision = installation.revision)

            val token =
                source.registerPushToken(
                    installationId = local.installationId,
                    installationSecret = local.installationSecret,
                    address = address,
                    expectedRevision = local.pushTokenRevision,
                )
            check(token.status == "active") { "Push address was not activated" }
            stateStore.updateRevisions(pushTokenRevision = token.revision)

            val updated =
                source.setSubscription(
                    installationId = local.installationId,
                    installationSecret = local.installationSecret,
                    galleryId = galleryId,
                    enabled = true,
                    expectedRevision = installation.subscriptionRevision(galleryId),
                )
            check(updated.subscriptionEnabled(galleryId) == true) {
                "Gallery alert subscription was not enabled"
            }
        }

    override suspend fun disableGallery(
        galleryId: String,
        platform: String,
        locale: String,
    ): Result<Unit> =
        runSuspendCatching {
            require(galleryId.isNotBlank()) { "galleryId must not be blank" }
            require(platform in setOf("android", "ios")) { "Unsupported push platform" }
            require(locale.isNotBlank()) { "locale must not be blank" }
            val local = stateStore.getOrCreate()
            val installation =
                source.registerInstallation(
                    installationId = local.installationId,
                    installationSecret = local.installationSecret,
                    platform = platform,
                    locale = locale,
                    expectedRevision = local.installationRevision,
                )
            stateStore.updateRevisions(installationRevision = installation.revision)
            val updated =
                source.setSubscription(
                    installationId = local.installationId,
                    installationSecret = local.installationSecret,
                    galleryId = galleryId,
                    enabled = false,
                    expectedRevision = installation.subscriptionRevision(galleryId),
                )
            check(updated.subscriptionEnabled(galleryId) == false) {
                "Gallery alert subscription was not disabled"
            }
        }
}

private fun GalleryAlertInstallationState.subscriptionRevision(galleryId: String): Int =
    subscriptions.firstOrNull { it.galleryId == galleryId }?.revision ?: 0

private fun GalleryAlertInstallationState.subscriptionEnabled(galleryId: String): Boolean? =
    subscriptions.firstOrNull { it.galleryId == galleryId }?.enabled

internal fun randomInstallationId(): String {
    val bytes = Random.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}

internal fun randomInstallationSecret(): String =
    Random.nextBytes(32).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
