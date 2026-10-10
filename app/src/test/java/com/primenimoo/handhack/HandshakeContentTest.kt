package com.primenimoo.handhack

import com.primenimoo.handhack.engine.handshake.HandshakeParser
import com.primenimoo.handhack.engine.handshake.Hex
import com.primenimoo.handhack.engine.handshake.Pbkdf2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Content validation rules, exercised on the raw bytes the detector sees.
 *
 * HandHack accepts exactly one format, so these tests pin down what must be refused.
 */
class HandshakeContentTest {

    private val pmkidHex = Hex.encode(Pbkdf2.derive("sunflower2024", "HandHackTest"))

    @Test
    fun `accepts a well formed handshake export`() {
        val file = temp("HandHackTest:${HandshakeParser.MARKER}$pmkidHex")
        assertEquals(1, HandshakeParser.parse(file).size)
        assertEquals(1, HandshakeParser.countRecords(file))
    }

    @Test
    fun `accepts a handshake whose extension is unusual`() {
        // Content decides, not the file name: a .bin holding real records still works.
        val file = File.createTempFile("capture", ".bin")
        file.deleteOnExit()
        file.writeText("HandHackTest:${HandshakeParser.MARKER}$pmkidHex")
        assertEquals(1, HandshakeParser.parse(file).size)
    }

    @Test
    fun `rejects a text file with no records at all`() {
        val file = temp("just", "some", "ordinary text")
        assertEquals(0, HandshakeParser.parse(file).size)
        assertEquals(0, HandshakeParser.countRecords(file))
    }

    @Test
    fun `reports records that carry no ssid`() {
        val file = temp("${HandshakeParser.MARKER}$pmkidHex")
        assertEquals(0, HandshakeParser.parse(file).size)
        assertEquals(1, HandshakeParser.countRecords(file))
    }

    @Test
    fun `rejects a truncated hash`() {
        val file = temp("HandHackTest:${HandshakeParser.MARKER}540c37d1")
        assertEquals(0, HandshakeParser.parse(file).size)
    }

    @Test
    fun `rejects a hash of the wrong length`() {
        val file = temp("HandHackTest:${HandshakeParser.MARKER}${pmkidHex.dropLast(2)}")
        assertEquals(0, HandshakeParser.parse(file).size)
    }

    @Test
    fun `rejects non hex characters in the payload`() {
        val file = temp("HandHackTest:${HandshakeParser.MARKER}${"zz".repeat(32)}")
        assertEquals(0, HandshakeParser.parse(file).size)
    }

    @Test
    fun `rejects an empty file`() {
        val file = temp()
        assertEquals(0, HandshakeParser.parse(file).size)
    }

    @Test
    fun `counts every record when several networks are present`() {
        val second = Hex.encode(Pbkdf2.derive("correct-horse", "OfficeNet"))
        val file = temp(
            "HomeNet:${HandshakeParser.MARKER}$pmkidHex",
            "OfficeNet:${HandshakeParser.MARKER}$second",
            "Broken:${HandshakeParser.MARKER}deadbeef",
        )
        assertEquals(3, HandshakeParser.countRecords(file))
        assertEquals(2, HandshakeParser.parse(file).size)
    }

    @Test
    fun `handles a byte order mark and windows line endings`() {
        val file = File.createTempFile("bom", ".txt")
        file.deleteOnExit()
        file.writeBytes("\uFEFFHomeNet:${HandshakeParser.MARKER}$pmkidHex\r\n".toByteArray(Charsets.UTF_8))
        val parsed = HandshakeParser.parse(file)
        assertEquals(1, parsed.size)
        assertEquals("HomeNet", parsed[0].ssid)
    }

    @Test
    fun `ignores comments and blank lines`() {
        val file = temp(
            "# exported by the lab capture rig",
            "",
            "// second comment style",
            "HomeNet:${HandshakeParser.MARKER}$pmkidHex",
        )
        assertEquals(1, HandshakeParser.parse(file).size)
    }

    @Test
    fun `detects binary payloads so a renamed image cannot slip through`() {
        val fake = File.createTempFile("photo", ".txt")
        fake.deleteOnExit()
        // PNG magic plus arbitrary bytes: a .txt name, but obviously not text.
        fake.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64) { 0x7F })
        val bytes = fake.readBytes()
        assertTrue("NUL or PNG signature expected in the probe", bytes.any { it == 0.toByte() } || bytes.size > 8)
        assertEquals(0, HandshakeParser.parse(fake).size)
    }

    @Test
    fun `is not fooled by a handshake string inside an archive listing`() {
        val file = temp("HomeNet:${HandshakeParser.MARKER}$pmkidHex", "HomeNet:archive/member.txt")
        val parsed = HandshakeParser.parse(file)
        assertEquals(1, parsed.size)
        assertFalse(parsed.any { it.ssid.contains('/') })
    }

    private fun temp(vararg lines: String): File {
        val file = File.createTempFile("handshake", ".txt")
        file.deleteOnExit()
        file.writeText(lines.joinToString("\n"))
        return file
    }
}