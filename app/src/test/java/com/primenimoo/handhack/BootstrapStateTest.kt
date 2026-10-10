package com.primenimoo.handhack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The startup screen is driven entirely by [BootstrapState], so its contract is pinned here. */
class BootstrapStateTest {

    @Test
    fun `starts in the cache check phase`() {
        val state = BootstrapState()
        assertEquals(BootstrapPhase.CheckingCache, state.phase)
        assertFalse(state.isReady)
    }

    @Test
    fun `is ready only in the ready phase`() {
        assertTrue(BootstrapState(phase = BootstrapPhase.Ready).isReady)
        assertFalse(BootstrapState(phase = BootstrapPhase.LoadingHistory).isReady)
        assertFalse(BootstrapState(phase = BootstrapPhase.PreparingDictionary).isReady)
        assertFalse(BootstrapState(phase = BootstrapPhase.LoadingCache).isReady)
    }

    @Test
    fun `every phase exposes a human readable label`() {
        BootstrapPhase.entries.forEach { phase ->
            val label = BootstrapState(phase = phase).label
            assertTrue("phase $phase needs a label", label.isNotBlank())
        }
    }

    @Test
    fun `records whether the launch reused the cache`() {
        assertTrue(BootstrapState(phase = BootstrapPhase.LoadingCache, fromCache = true).fromCache)
        assertFalse(BootstrapState(phase = BootstrapPhase.PreparingDictionary).fromCache)
    }

    @Test
    fun `a failed startup still finishes ready so the ui is never stuck`() {
        val state = BootstrapState(
            phase = BootstrapPhase.Ready,
            progress = 1f,
            failureMessage = "Не удалось подготовить приложение",
        )

        assertTrue(state.isReady)
        assertEquals("Запуск с ошибками", state.label)
    }

    @Test
    fun `progress advances monotonically through the sequence`() {
        val phases = listOf(
            BootstrapPhase.CheckingCache to 0f,
            BootstrapPhase.PreparingDictionary to 0.1f,
            BootstrapPhase.LoadingCache to 0.45f,
            BootstrapPhase.LoadingHistory to 0.75f,
            BootstrapPhase.Ready to 1f,
        )

        phases.zipWithNext { (phaseA, progressA), (_, progressB) ->
            assertTrue("$progressA -> $progressB must not go backwards", progressB >= progressA)
        }
    }
}