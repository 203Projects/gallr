package com.gallr.shared.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the [BookmarkRepository] contract against an in-memory fake implementation.
 * This validates the interface contract; [BookmarkRepositoryImpl] is separately verified
 * via integration on device.
 */
class BookmarkRepositoryTest {

    private val store = MutableStateFlow<Set<String>>(emptySet())

    private val repository: BookmarkRepository = object : BookmarkRepository {
        override fun observeBookmarkedIds(): Flow<Set<String>> = store
        override suspend fun addBookmark(exhibitionId: String) {
            store.value = store.value + exhibitionId
        }
        override suspend fun removeBookmark(exhibitionId: String) {
            store.value = store.value - exhibitionId
        }
        override suspend fun isBookmarked(exhibitionId: String): Boolean =
            exhibitionId in store.value
        override suspend fun clearAll() {
            store.value = emptySet()
        }
        override fun setMutationListener(listener: suspend () -> Unit) = Unit
    }

    @Test
    fun `observeBookmarkedIds emits empty set initially`() = runTest {
        assertTrue(repository.observeBookmarkedIds().first().isEmpty())
    }

    @Test
    fun `addBookmark makes exhibition appear in observed set`() = runTest {
        repository.addBookmark("ex-1")
        assertTrue("ex-1" in repository.observeBookmarkedIds().first())
    }

    @Test
    fun `removeBookmark removes exhibition from observed set`() = runTest {
        repository.addBookmark("ex-1")
        repository.removeBookmark("ex-1")
        assertFalse("ex-1" in repository.observeBookmarkedIds().first())
    }

    @Test
    fun `isBookmarked returns true after add`() = runTest {
        repository.addBookmark("ex-2")
        assertTrue(repository.isBookmarked("ex-2"))
    }

    @Test
    fun `isBookmarked returns false after remove`() = runTest {
        repository.addBookmark("ex-2")
        repository.removeBookmark("ex-2")
        assertFalse(repository.isBookmarked("ex-2"))
    }

    @Test
    fun `multiple bookmarks coexist independently`() = runTest {
        repository.addBookmark("a")
        repository.addBookmark("b")
        repository.addBookmark("c")
        val ids = repository.observeBookmarkedIds().first()
        assertTrue("a" in ids)
        assertTrue("b" in ids)
        assertTrue("c" in ids)
    }

    @Test
    fun `removing one bookmark does not affect others`() = runTest {
        repository.addBookmark("a")
        repository.addBookmark("b")
        repository.removeBookmark("a")
        assertFalse(repository.isBookmarked("a"))
        assertTrue(repository.isBookmarked("b"))
    }
}
