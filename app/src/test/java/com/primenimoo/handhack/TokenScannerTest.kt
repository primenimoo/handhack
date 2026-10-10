package com.primenimoo.handhack

import com.primenimoo.handhack.engine.TokenScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TokenScannerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun write(name: String, bytes: ByteArray): File {
        val file = temporaryFolder.newFile(name)
        file.writeBytes(bytes)
        return file
    }

    private fun collect(file: File, textBased: Boolean): List<String> = runBlocking {
        val tokens = mutableListOf<String>()
        TokenScanner.forEachToken(file, textBased) { tokens.add(it) }
        tokens
    }

    @Test
    fun `skips blank lines and trims entries`() = runBlocking {
        val file = write("plain.txt", "alpha\n\n  beta  \n\ngamma\n".toByteArray())
        assertEquals(listOf("alpha", "beta", "gamma"), collect(file, textBased = true))
    }

    @Test
    fun `handles CRLF line endings`() = runBlocking {
        val file = write("crlf.txt", "one\r\ntwo\r\nthree\r\n".toByteArray())
        assertEquals(listOf("one", "two", "three"), collect(file, textBased = true))
    }

    @Test
    fun `handles a file without a trailing newline`() = runBlocking {
        val file = write("notrail.txt", "solo".toByteArray())
        assertEquals(listOf("solo"), collect(file, textBased = true))
    }

    @Test
    fun `strips a UTF-8 byte order mark`() = runBlocking {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "кириллица\n".toByteArray(Charsets.UTF_8)
        val file = write("bom.txt", bytes)
        assertEquals(listOf("кириллица"), collect(file, textBased = true))
    }

    @Test
    fun `reads UTF-16LE with a byte order mark`() = runBlocking {
        val text = "hello\nworld\n"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val file = write("utf16.txt", bytes)
        assertEquals(listOf("hello", "world"), collect(file, textBased = true))
    }

    @Test
    fun `keeps unicode content intact`() = runBlocking {
        val file = write("unicode.txt", "ключ\nзначение\n日本語\n".toByteArray(Charsets.UTF_8))
        assertEquals(listOf("ключ", "значение", "日本語"), collect(file, textBased = true))
    }

    @Test
    fun `binary mode extracts printable runs and ignores short noise`() = runBlocking {
        val header = byteArrayOf(0xD4.toByte(), 0xC3.toByte(), 0xB2.toByte(), 0xA1.toByte(), 0x02, 0x00)
        val payload = "AA:BB:CC:DD\n".toByteArray()
        val file = write("capture.pcap", header + payload)

        val tokens = collect(file, textBased = false)
        assertTrue(tokens.contains("AA:BB:CC:DD"))
        assertTrue(tokens.none { it.length < 4 })
    }

    @Test
    fun `empty file yields no tokens`() = runBlocking {
        val file = write("empty.txt", ByteArray(0))
        assertTrue(collect(file, textBased = true).isEmpty())
    }
}