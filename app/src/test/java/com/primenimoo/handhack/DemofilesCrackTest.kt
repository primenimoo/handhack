package com.primenimoo.handhack

import com.primenimoo.handhack.engine.capture.CaptureLoader
import com.primenimoo.handhack.engine.handshake.CaptureEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Proves the Android pipeline is a real dictionary attack and not a counter, using the exact
 * demo handshake committed to the repository root:
 *
 *   handshake-12345678.pcap     — SSID `DemoWiFi`, password `12345678` (line 41)
 *   dictionary-12345678.txt     — 41 lines, the password is the last one
 *
 * The capture is parsed the same way the app parses one, and every dictionary line is then
 * verified the same way the engine verifies one (PBKDF2-HMAC-SHA1(4096) + MIC). Finding
 * `12345678` only where the IEEE 802.11i key schedule really places it is the whole point.
 */
class DemofilesCrackTest {

    private val repoRoot: File
        get() = File(System.getProperty("user.dir")).parentFile

    @Test
    fun `the committed demo pcap parses to one eapol-mic network`() {
        val loaded = CaptureLoader.load(File(repoRoot, "handshake-12345678.pcap"))

        assertEquals(CaptureLoader.CaptureFormat.PCAP, loaded.format)
        assertEquals(1, loaded.targets.size)
        val target = loaded.targets.single()
        assertEquals("DemoWiFi", target.ssid)
        assertEquals(CaptureEvidence.EAPOL_MIC, target.evidence)
        // The parser is informational, not a verdict: a resolved network still carries the
        // "Найдено сетей" note, so usability is decided by the target, never the note list.
        assertEquals(0, loaded.notes.count { it.contains("проверить") || it.contains("не найдено") })
        // A handshake parsed, not invented: the passphrase already has to survive the key schedule.
        assertTrue(target.verify("12345678"))
        assertTrue(!target.verify("wrongpass"))
    }

    @Test
    fun `the committed demo dictionary finds the handshake at line 41`() {
        val loaded = CaptureLoader.load(File(repoRoot, "handshake-12345678.pcap"))
        val target = loaded.targets.single()

        val lines = File(repoRoot, "dictionary-12345678.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        var matchedLine = -1
        for ((index, candidate) in lines.withIndex()) {
            if (candidate.length in 8..63 && target.verify(candidate)) {
                matchedLine = index + 1
                break
            }
        }

        assertEquals("password must be exactly the last dictionary line", 41, matchedLine)
        assertEquals("12345678", lines[matchedLine - 1])

        // And the match is real, not a line-number coincidence: a slightly different passphrase
        // that is also in the file must be rejected by the key schedule.
        assertTrue(!target.verify("Football2!"))
    }
}