package com.primenimoo.handhack

import com.primenimoo.handhack.data.dictionary.DictionaryCacheMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The startup cache is what makes a warm launch quick, so its invalidation rules matter:
 * a marker that lies about the working copy would show a stale "default dictionary"
 * badge forever.
 */
class DictionaryCacheMarkerTest {

    @Test
    fun `round trips through its serialized form`() {
        val marker = DictionaryCacheMarker(
            workingSize = 141_693L,
            workingStamp = 1_700_000_000_000L,
            entryCount = 13_846L,
            isDefault = true,
        )

        val parsed = DictionaryCacheMarker.parse(marker.serialize())

        assertEquals(marker, parsed)
    }

    @Test
    fun `keeps a false default flag`() {
        val marker = DictionaryCacheMarker(100L, 5L, 3L, isDefault = false)
        assertFalse(DictionaryCacheMarker.parse(marker.serialize())!!.isDefault)
    }

    @Test
    fun `matches the file it was written for`() {
        val file = workingFile("alpha\nbeta\ngamma")
        val marker = DictionaryCacheMarker.of(file, entryCount = 3L, isDefault = true)

        assertTrue(marker.matches(file))
    }

    @Test
    fun `stops matching once the working copy is rewritten`() {
        val file = workingFile("alpha\nbeta")
        val marker = DictionaryCacheMarker.of(file, entryCount = 2L, isDefault = true)

        // Any real write changes both the size and the modification stamp.
        Thread.sleep(1100L)
        file.writeText("alpha\nbeta\ngamma\ndelta")

        assertFalse(marker.matches(file))
    }

    @Test
    fun `does not match a file that disappeared`() {
        val file = workingFile("alpha")
        val marker = DictionaryCacheMarker.of(file, entryCount = 1L, isDefault = true)

        file.delete()

        assertFalse(marker.matches(file))
    }

    @Test
    fun `refuses a marker written by another schema version`() {
        val stale = DictionaryCacheMarker(10L, 20L, 30L, true).serialize()
            .replace("version=1", "version=0")

        assertNull(DictionaryCacheMarker.parse(stale))
    }

    @Test
    fun `refuses truncated and malformed markers`() {
        assertNull(DictionaryCacheMarker.parse(null))
        assertNull(DictionaryCacheMarker.parse(""))
        assertNull(DictionaryCacheMarker.parse("   "))
        assertNull(DictionaryCacheMarker.parse("version=1\nsize=10"))
        assertNull(DictionaryCacheMarker.parse("garbage without separators"))
        assertNull(DictionaryCacheMarker.parse("version=1\nsize=x\nstamp=1\ncount=1\ndefault=true"))
        assertNull(DictionaryCacheMarker.parse("version=1\nsize=1\nstamp=1\ncount=1\ndefault=yes"))
    }

    @Test
    fun `refuses negative metadata`() {
        assertNull(
            DictionaryCacheMarker.parse("version=1\nsize=-5\nstamp=1\ncount=1\ndefault=true"),
        )
    }

    private fun workingFile(content: String): File {
        val file = File.createTempFile("dictionary", ".txt")
        file.deleteOnExit()
        file.writeText(content)
        return file
    }
}