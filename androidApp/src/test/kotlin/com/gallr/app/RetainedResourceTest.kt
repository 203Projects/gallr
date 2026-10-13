package com.gallr.app

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The network clients must live exactly as long as the app's ViewModels. A configuration change (dark mode, font
 * scale, locale) recreates the activity but keeps its ViewModelStore; closing the clients then left every retained
 * ViewModel with closed clients whose requests never reached the server.
 */
class RetainedResourceTest {
    private class FakeClients {
        var closed = 0
    }

    @Test
    fun `a recreated activity gets the same open clients`() {
        val store = ViewModelStore()
        var created = 0
        val provide = {
            retained(store) {
                created += 1
                FakeClients()
            }
        }

        val first = provide()
        val afterRecreation = provide()

        assertSame(first, afterRecreation)
        assertEquals(1, created)
        assertEquals(0, first.closed, "a configuration change must not close the clients")
    }

    @Test
    fun `the clients close once when the activity finishes for good`() {
        val store = ViewModelStore()
        val clients = retained(store) { FakeClients() }

        store.clear()

        assertEquals(1, clients.closed, "closed exactly once")
    }

    private fun retained(
        store: ViewModelStore,
        create: () -> FakeClients,
    ): FakeClients {
        val factory = viewModelFactory { initializer { RetainedResource(create()) { it.closed += 1 } } }

        @Suppress("UNCHECKED_CAST")
        val holder = ViewModelProvider.create(store, factory)[RetainedResource::class] as RetainedResource<FakeClients>
        return holder.value
    }
}
