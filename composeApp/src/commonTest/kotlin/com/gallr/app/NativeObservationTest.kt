package com.gallr.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NativeObservationTest {
    @Test
    fun observationEmitsCurrentAndUpdatedStateUntilCancelled() =
        runTest {
            val states = MutableStateFlow(1)
            val observed = mutableListOf<Int>()
            val observation = states.observeForNative(backgroundScope) { observed.add(it) }

            runCurrent()
            states.value = 2
            runCurrent()
            observation.cancel()
            states.value = 3
            runCurrent()

            assertEquals(listOf(1, 2), observed)
        }
}
