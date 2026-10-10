package com.primenimoo.handhack

import com.primenimoo.handhack.data.history.HistoryOutcome
import com.primenimoo.handhack.engine.AnalysisStatus
import com.primenimoo.handhack.ui.theme.AccentPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class MappingTest {

    @Test
    fun `analysis status maps onto history outcomes`() {
        assertEquals(HistoryOutcome.COMPLETED, HistoryOutcome.fromStatus(AnalysisStatus.COMPLETED))
        assertEquals(HistoryOutcome.NO_RESULT, HistoryOutcome.fromStatus(AnalysisStatus.NO_RESULT))
        assertEquals(HistoryOutcome.CANCELLED, HistoryOutcome.fromStatus(AnalysisStatus.CANCELLED))
        assertEquals(HistoryOutcome.FAILED, HistoryOutcome.fromStatus(AnalysisStatus.ERROR))
        assertEquals(HistoryOutcome.FAILED, HistoryOutcome.fromStatus(AnalysisStatus.RUNNING))
    }

    @Test
    fun `history outcome round trips through its storage key`() {
        HistoryOutcome.entries.forEach { outcome ->
            assertEquals(outcome, HistoryOutcome.fromKey(outcome.storageKey))
        }
        assertEquals(HistoryOutcome.COMPLETED, HistoryOutcome.fromKey("nonsense"))
    }

    @Test
    fun `accent presets round trip through their storage key`() {
        AccentPreset.entries.forEach { preset ->
            assertEquals(preset, AccentPreset.fromKey(preset.storageKey))
        }
        assertEquals(AccentPreset.BLUE, AccentPreset.fromKey("nonsense"))
        assertEquals(AccentPreset.BLUE, AccentPreset.fromKey(null))
    }

    @Test
    fun `terminal states are the ones that stop the UI`() {
        assertEquals(true, AnalysisStatus.COMPLETED.isTerminal)
        assertEquals(true, AnalysisStatus.NO_RESULT.isTerminal)
        assertEquals(true, AnalysisStatus.CANCELLED.isTerminal)
        assertEquals(true, AnalysisStatus.ERROR.isTerminal)
        assertEquals(false, AnalysisStatus.RUNNING.isTerminal)
        assertEquals(false, AnalysisStatus.PAUSED.isTerminal)
        assertEquals(true, AnalysisStatus.RUNNING.isActive)
        assertEquals(true, AnalysisStatus.PAUSED.isActive)
        assertEquals(false, AnalysisStatus.COMPLETED.isActive)
    }
}