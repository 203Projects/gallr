package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.data.model.FollowedGallery
import com.gallr.shared.data.model.FollowedGallerySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Instant

class DataStoreFollowedGalleryRepositoryTest {
    @Test
    fun `following starts empty and survives repository reconstruction`() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val first = DataStoreFollowedGalleryRepository(store)
            assertTrue(first.observeFollowedGalleries().first().isEmpty())

            val followed = gallery("kukje")
            first.followGallery(followed)

            assertEquals(
                listOf(followed),
                DataStoreFollowedGalleryRepository(store).observeFollowedGalleries().first(),
            )
        }

    @Test
    fun `following the same gallery is idempotent and preserves original baseline`() =
        runTest {
            val repository = DataStoreFollowedGalleryRepository(InMemoryPreferencesDataStore())
            val original = gallery("kukje")
            val replacement =
                original.copy(
                    snapshot = original.snapshot.copy(nameEn = "Changed"),
                    knownExhibitionIds = setOf("new-baseline"),
                    followedAt = Instant.parse("2026-08-14T00:00:00Z"),
                )

            repository.followGallery(original)
            repository.followGallery(replacement)

            assertEquals(listOf(original), repository.observeFollowedGalleries().first())
        }

    @Test
    fun `acknowledgement unions current exhibition ids and preserves prior ids`() =
        runTest {
            val repository = DataStoreFollowedGalleryRepository(InMemoryPreferencesDataStore())
            repository.followGallery(gallery("kukje"))

            repository.acknowledgeGallery("kukje", setOf("existing", "new-one", "new-two"))

            assertEquals(
                setOf("existing", "new-one", "new-two"),
                repository
                    .observeFollowedGalleries()
                    .first()
                    .single()
                    .knownExhibitionIds,
            )
        }

    @Test
    fun `unfollow removes only the requested gallery`() =
        runTest {
            val repository = DataStoreFollowedGalleryRepository(InMemoryPreferencesDataStore())
            repository.followGallery(gallery("kukje"))
            repository.followGallery(gallery("pkm"))

            repository.unfollowGallery("kukje")

            assertEquals(listOf("pkm"), repository.observeFollowedGalleries().first().map { it.galleryKey })
        }

    @Test
    fun `stable gallery identity is assigned once and survives reconstruction`() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val repository = DataStoreFollowedGalleryRepository(store)
            repository.followGallery(gallery("kukje"))

            repository.assignGalleryId("kukje", "82100000-0000-0000-0000-000000000001")
            repository.assignGalleryId("kukje", "82100000-0000-0000-0000-000000000002")

            assertEquals(
                "82100000-0000-0000-0000-000000000001",
                DataStoreFollowedGalleryRepository(store)
                    .observeFollowedGalleries()
                    .first()
                    .single()
                    .galleryId,
            )
        }

    @Test
    fun `new exhibition alert preference is explicit and survives reconstruction`() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val repository = DataStoreFollowedGalleryRepository(store)
            repository.followGallery(gallery("kukje"))

            repository.setNewExhibitionAlertsEnabled("kukje", true)

            assertTrue(
                DataStoreFollowedGalleryRepository(store)
                    .observeFollowedGalleries()
                    .first()
                    .single()
                    .newExhibitionAlertsEnabled,
            )
        }

    @Test
    fun `malformed following archive fails instead of becoming empty`() =
        runTest {
            val store = InMemoryPreferencesDataStore()
            val key = stringPreferencesKey("my_gallr_followed_galleries_v1")
            store.updateData { preferences ->
                preferences.toMutablePreferences().apply { this[key] = "{not-json" }
            }

            assertFails {
                DataStoreFollowedGalleryRepository(store).observeFollowedGalleries().first()
            }
        }

    private fun gallery(key: String) =
        FollowedGallery(
            galleryKey = key,
            snapshot =
                FollowedGallerySnapshot(
                    nameKo = "갤러리 $key",
                    nameEn = "Gallery $key",
                    cityKo = "서울",
                    cityEn = "Seoul",
                    regionKo = "종로구",
                    regionEn = "Jongno-gu",
                ),
            knownExhibitionIds = setOf("existing"),
            followedAt = Instant.parse("2026-08-13T00:00:00Z"),
        )

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
