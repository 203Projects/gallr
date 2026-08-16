package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.observability.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val FOLLOWED_GALLERIES_KEY = stringPreferencesKey("my_gallr_followed_galleries_v1")
private const val FOLLOWED_GALLERIES_SCHEMA_VERSION = 1
private val followedGalleryLog = AppLog.tagged("DataStoreFollowedGalleryRepository")

@Serializable
private data class FollowedGalleriesPayload(
    val schemaVersion: Int = FOLLOWED_GALLERIES_SCHEMA_VERSION,
    val galleries: List<FollowedGallery>,
)

class DataStoreFollowedGalleryRepository(
    private val dataStore: DataStore<Preferences>,
) : FollowedGalleryRepository {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    override fun observeFollowedGalleries(): Flow<List<FollowedGallery>> =
        dataStore.data.map { preferences ->
            try {
                decode(preferences[FOLLOWED_GALLERIES_KEY]).sortedByDescending { it.followedAt }
            } catch (error: Exception) {
                followedGalleryLog.error("decode_followed_galleries", error)
                throw error
            }
        }

    override suspend fun followGalleries(galleries: List<FollowedGallery>) {
        if (galleries.isEmpty()) return

        try {
            dataStore.edit { preferences ->
                val current = decode(preferences[FOLLOWED_GALLERIES_KEY])
                val existingKeys = current.mapTo(mutableSetOf()) { it.galleryKey }
                val additions = galleries.filter { existingKeys.add(it.galleryKey) }
                preferences[FOLLOWED_GALLERIES_KEY] = encode(current + additions)
            }
        } catch (error: Exception) {
            followedGalleryLog.error("follow_gallery", error)
            throw error
        }
    }

    override suspend fun unfollowGallery(galleryKey: String) {
        try {
            dataStore.edit { preferences ->
                val current = decode(preferences[FOLLOWED_GALLERIES_KEY])
                preferences[FOLLOWED_GALLERIES_KEY] =
                    encode(current.filterNot { it.galleryKey == galleryKey })
            }
        } catch (error: Exception) {
            followedGalleryLog.error("unfollow_gallery", error)
            throw error
        }
    }

    override suspend fun acknowledgeGallery(
        galleryKey: String,
        currentExhibitionIds: Set<String>,
    ) {
        if (currentExhibitionIds.isEmpty()) return

        try {
            dataStore.edit { preferences ->
                val current = decode(preferences[FOLLOWED_GALLERIES_KEY])
                preferences[FOLLOWED_GALLERIES_KEY] =
                    encode(
                        current.map { gallery ->
                            if (gallery.galleryKey == galleryKey) {
                                gallery.copy(
                                    knownExhibitionIds = gallery.knownExhibitionIds + currentExhibitionIds,
                                )
                            } else {
                                gallery
                            }
                        },
                    )
            }
        } catch (error: Exception) {
            followedGalleryLog.error("acknowledge_gallery", error)
            throw error
        }
    }

    override suspend fun assignGalleryId(
        galleryKey: String,
        galleryId: String,
    ) {
        require(galleryId.isNotBlank()) { "galleryId must not be blank" }
        try {
            dataStore.edit { preferences ->
                val current = decode(preferences[FOLLOWED_GALLERIES_KEY])
                preferences[FOLLOWED_GALLERIES_KEY] =
                    encode(
                        current.map { gallery ->
                            if (gallery.galleryKey == galleryKey && gallery.galleryId == null) {
                                gallery.copy(galleryId = galleryId)
                            } else {
                                gallery
                            }
                        },
                    )
            }
        } catch (error: Exception) {
            followedGalleryLog.error("assign_gallery_id", error)
            throw error
        }
    }

    override suspend fun setNewExhibitionAlertsEnabled(
        galleryKey: String,
        enabled: Boolean,
    ) {
        try {
            dataStore.edit { preferences ->
                val current = decode(preferences[FOLLOWED_GALLERIES_KEY])
                preferences[FOLLOWED_GALLERIES_KEY] =
                    encode(
                        current.map { gallery ->
                            if (gallery.galleryKey == galleryKey) {
                                gallery.copy(newExhibitionAlertsEnabled = enabled)
                            } else {
                                gallery
                            }
                        },
                    )
            }
        } catch (error: Exception) {
            followedGalleryLog.error("set_gallery_alert_preference", error)
            throw error
        }
    }

    private fun decode(encoded: String?): List<FollowedGallery> {
        if (encoded == null) return emptyList()
        val payload = json.decodeFromString<FollowedGalleriesPayload>(encoded)
        require(payload.schemaVersion == FOLLOWED_GALLERIES_SCHEMA_VERSION) {
            "Unsupported followed galleries schema"
        }
        return payload.galleries
    }

    private fun encode(galleries: List<FollowedGallery>): String =
        json.encodeToString(FollowedGalleriesPayload(galleries = galleries))
}
