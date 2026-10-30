package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the [BookmarkRepository] contract against an in-memory fake implementation.
 * This validates the interface contract; [BookmarkRepositoryImpl] is separately verified
 * via integration on device.
 */
class BookmarkRepositoryTest {
    private val store = MutableStateFlow<Set<String>>(emptySet())

    private val repository: BookmarkRepository =
        object : BookmarkRepository {
            override fun observeBookmarkedIds(): Flow<Set<String>> = store

            override suspend fun addBookmark(exhibitionId: String) {
                store.value = store.value + exhibitionId
            }

            override suspend fun removeBookmark(exhibitionId: String) {
                store.value = store.value - exhibitionId
            }

            override suspend fun isBookmarked(exhibitionId: String): Boolean = exhibitionId in store.value

            override suspend fun clearAll() {
                store.value = emptySet()
            }

            override fun setMutationListener(listener: suspend () -> Unit) = Unit
        }

    @Test
    fun `observeBookmarkedIds emits empty set initially`() =
        runTest {
            assertTrue(repository.observeBookmarkedIds().first().isEmpty())
        }

    @Test
    fun `addBookmark makes exhibition appear in observed set`() =
        runTest {
            repository.addBookmark("ex-1")
            assertTrue("ex-1" in repository.observeBookmarkedIds().first())
        }

    @Test
    fun `removeBookmark removes exhibition from observed set`() =
        runTest {
            repository.addBookmark("ex-1")
            repository.removeBookmark("ex-1")
            assertFalse("ex-1" in repository.observeBookmarkedIds().first())
        }

    @Test
    fun `isBookmarked returns true after add`() =
        runTest {
            repository.addBookmark("ex-2")
            assertTrue(repository.isBookmarked("ex-2"))
        }

    @Test
    fun `isBookmarked returns false after remove`() =
        runTest {
            repository.addBookmark("ex-2")
            repository.removeBookmark("ex-2")
            assertFalse(repository.isBookmarked("ex-2"))
        }

    @Test
    fun `multiple bookmarks coexist independently`() =
        runTest {
            repository.addBookmark("a")
            repository.addBookmark("b")
            repository.addBookmark("c")
            val ids = repository.observeBookmarkedIds().first()
            assertTrue("a" in ids)
            assertTrue("b" in ids)
            assertTrue("c" in ids)
        }

    @Test
    fun `removing one bookmark does not affect others`() =
        runTest {
            repository.addBookmark("a")
            repository.addBookmark("b")
            repository.removeBookmark("a")
            assertFalse(repository.isBookmarked("a"))
            assertTrue(repository.isBookmarked("b"))
        }

    // The guest's saves feed the 내 취향 ranking: a write to another preference must not read as a changed list.
    @Test
    fun `the stored saves are not emitted again when another preference changes`() =
        runTest {
            val dataStore = InMemoryBookmarkDataStore()
            val stored = BookmarkRepositoryImpl(dataStore)
            stored.addBookmark("a")
            val emissions = mutableListOf<Set<String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                stored.observeBookmarkedIds().collect { emissions += it }
            }

            dataStore.edit { it[stringPreferencesKey("another_preference")] = "changed" }
            stored.addBookmark("b")

            assertEquals(listOf(setOf("a"), setOf("a", "b")), emissions)
        }
}

private class InMemoryBookmarkDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}
