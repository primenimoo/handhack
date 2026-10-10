package com.primenimoo.handhack

import com.primenimoo.handhack.core.FileAccess
import com.primenimoo.handhack.engine.LocalAnalysisEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class FormatHelpersTest {

    @Test
    fun `formatSize uses binary units`() {
        assertEquals("512 B", FileAccess.formatSize(512))
        assertEquals("1 KB", FileAccess.formatSize(1024))
        assertEquals("1,5 MB".replace(',', '.'), FileAccess.formatSize((1.5 * 1024 * 1024).toLong()))
        assertEquals("—", FileAccess.formatSize(-1))
    }

    @Test
    fun `formatCount groups thousands with a thin space`() {
        assertEquals("999", FileAccess.formatCount(999))
        assertEquals("1\u202F000", FileAccess.formatCount(1000))
        assertEquals("1\u202F234\u202F567", FileAccess.formatCount(1234567))
    }

    @Test
    fun `formatDuration switches to minutes`() {
        assertEquals("00:07", FileAccess.formatDuration(7_000))
        assertEquals("02:05", FileAccess.formatDuration(125_000))
        assertEquals("—", FileAccess.formatDuration(-1))
    }

    @Test
    fun `suggestedWorkers respects the performance preference`() {
        assertEquals(2, LocalAnalysisEngine.suggestedWorkers("eco", 8, 8))
        assertEquals(4, LocalAnalysisEngine.suggestedWorkers("balanced", 8, 8))
        assertEquals(7, LocalAnalysisEngine.suggestedWorkers("maximum", 8, 8))
        assertEquals(3, LocalAnalysisEngine.suggestedWorkers("limited", 3, 8))
        assertEquals(4, LocalAnalysisEngine.suggestedWorkers("auto", 1, 8))
    }

    @Test
    fun `suggestedWorkers never returns zero on a single core device`() {
        assertTrue(LocalAnalysisEngine.suggestedWorkers("eco", 1, 1) >= 1)
        assertTrue(LocalAnalysisEngine.suggestedWorkers("auto", 1, 1) >= 1)
    }
}