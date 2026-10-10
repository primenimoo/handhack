package com.primenimoo.handhack

import com.primenimoo.handhack.engine.AnalysisRequest
import com.primenimoo.handhack.engine.AnalysisStatus
import com.primenimoo.handhack.engine.LocalAnalysisEngine
import com.primenimoo.handhack.engine.RecoveredPassword
import com.primenimoo.handhack.engine.handshake.HandshakeParser
import com.primenimoo.handhack.engine.handshake.Hex
import com.primenimoo.handhack.engine.handshake.Pbkdf2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Exercises the whole cracking path: parse the file, derive keys, report the password. */
class HandshakeCrackTest {

    private val ssid = "HandHackTest"
    private val password = "sunflower2024"
    private val pmkidHex = Hex.encode(Pbkdf2.derive(password, ssid))

    @Test
    fun `recovers the passphrase for a captured network`() = runBlocking {
        val engine = engine(setOf("wrongpassword", password, "anotherone"))
        try {
            engine.prepare(request(file("${ssid}:${HandshakeParser.MARKER}$pmkidHex")))
            engine.start()

            val result = awaitResult(engine)
            assertEquals(AnalysisStatus.COMPLETED, result.status)
            assertEquals(listOf(RecoveredPassword(ssid, password)), result.recoveredPasswords)
            assertEquals(listOf(password), result.matchedEntries)
            assertEquals(3L, result.totalItems)
        } finally {
            engine.reset()
        }
    }

    @Test
    fun `reports no result when the dictionary has no working password`() = runBlocking {
        val engine = engine(setOf("wrongpassword", "alsowrong"))
        try {
            engine.prepare(request(file("${ssid}:${HandshakeParser.MARKER}$pmkidHex")))
            engine.start()

            val result = awaitResult(engine)
            assertEquals(AnalysisStatus.NO_RESULT, result.status)
            assertTrue(result.recoveredPasswords.isEmpty())
            assertTrue(result.failureReason!!.contains("не подошёл"))
        } finally {
            engine.reset()
        }
    }

    @Test
    fun `recovers several networks from one file`() = runBlocking {
        val other = "OfficeNet"
        val otherPass = "correct-horse"
        val file = file(
            "${ssid}:${HandshakeParser.MARKER}$pmkidHex",
            "$other:${HandshakeParser.MARKER}${Hex.encode(Pbkdf2.derive(otherPass, other))}",
        )
        val engine = engine(setOf("aaaaaaaaaa", password, otherPass))
        try {
            engine.prepare(request(file))
            engine.start()

            val result = awaitResult(engine)
            assertEquals(AnalysisStatus.COMPLETED, result.status)
            assertEquals(
                setOf(RecoveredPassword(other, otherPass), RecoveredPassword(ssid, password)),
                result.recoveredPasswords.toSet(),
            )
        } finally {
            engine.reset()
        }
    }

    @Test
    fun `skips dictionary entries that are too short to be a wpa passphrase`() = runBlocking {
        val engine = engine(setOf("short", "pass", password))
        try {
            engine.prepare(request(file("${ssid}:${HandshakeParser.MARKER}$pmkidHex")))
            // Only the single 12 character entry is eligible for verification.
            assertEquals(1L, engine.progress.value.total)

            engine.start()
            assertEquals(AnalysisStatus.COMPLETED, awaitResult(engine).status)
        } finally {
            engine.reset()
        }
    }

    @Test
    fun `explains that records without an ssid cannot be verified`() = runBlocking {
        val engine = engine(setOf(password))
        try {
            engine.prepare(request(file("${HandshakeParser.MARKER}$pmkidHex")))

            val result = engine.result.value!!
            assertEquals(AnalysisStatus.ERROR, result.status)
            assertTrue(result.errorMessage!!.contains("нет SSID"))
            // The message has to say how to fix it, not just that something is wrong.
            assertTrue(result.errorMessage!!.contains("ИмяСети:"))
        } finally {
            engine.reset()
        }
    }

    @Test
    fun `fails when no dictionary entry has a usable length`() = runBlocking {
        val engine = engine(setOf("a", "bb", "ccc"))
        try {
            engine.prepare(request(file("${ssid}:${HandshakeParser.MARKER}$pmkidHex")))
            val result = engine.result.value!!
            assertEquals(AnalysisStatus.ERROR, result.status)
            assertTrue(result.errorMessage!!.contains("8–63"))
        } finally {
            engine.reset()
        }
    }

    @Test
    fun `refuses a file that is not a handshake at all`() = runBlocking {
        // Valid-length candidates, so the length guard cannot mask the format check.
        val engine = engine(setOf("administrator", "guestpassword"))
        try {
            engine.prepare(request(file("admin", "root", "guest")))

            val result = engine.result.value!!
            assertEquals(AnalysisStatus.ERROR, result.status)
            assertTrue(result.errorMessage!!.contains("WPA*01"))
        } finally {
            engine.reset()
        }
    }

    @Test
    fun `cancel produces a terminal result so the ui never hangs`() = runBlocking {
        // A dictionary with no match keeps the run busy long enough to cancel it.
        val big = (1..4_000).map { "candidate$it" }.toSet() + password
        val engine = engine(big)
        try {
            engine.prepare(request(file("${ssid}:${HandshakeParser.MARKER}$pmkidHex")))
            engine.start()
            delay(120L)

            engine.cancel()

            val result = engine.result.value!!
            assertEquals(AnalysisStatus.CANCELLED, result.status)
            assertTrue(result.status.isTerminal)
            assertTrue("stage label must not stay active", engine.progress.value.stageLabel.contains("отмен"))
        } finally {
            engine.reset()
        }
    }

    @Test
    fun `cancel is safe to call twice and when nothing is running`() = runBlocking {
        val engine = engine(setOf(password))
        try {
            // Never started: must not throw and must not fabricate a result.
            engine.cancel()
            assertTrue(engine.result.value == null || engine.result.value!!.status.isTerminal)

            engine.prepare(request(file("${ssid}:${HandshakeParser.MARKER}$pmkidHex")))
            engine.start()
            engine.cancel()
            engine.cancel()

            val result = engine.result.value!!
            assertTrue(result.status.isTerminal)
            assertEquals(AnalysisStatus.CANCELLED, result.status)
        } finally {
            engine.reset()
        }
    }

    @Test
    fun `a cancelled run can be restarted cleanly`() = runBlocking {
        val engine = engine(setOf("wrongpassword1", "wrongpassword2", password))
        try {
            val target = file("${ssid}:${HandshakeParser.MARKER}$pmkidHex")
            engine.prepare(request(target))
            engine.start()
            delay(60L)
            engine.cancel()

            engine.prepare(request(target))
            engine.start()

            val result = awaitResult(engine)
            assertEquals(AnalysisStatus.COMPLETED, result.status)
            assertEquals(password, result.recoveredPasswords.single().password)
        } finally {
            engine.reset()
        }
    }

    private fun engine(dictionary: Set<String>) = LocalAnalysisEngine(
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        dictionaryProvider = { dictionary },
    )

    private fun request(file: File) = AnalysisRequest(
        fileName = file.name,
        formatLabel = "WPA*01",
        sizeBytes = file.length(),
        sourcePath = file.absolutePath,
        dictionaryPath = "memory",
        textBased = true,
        workers = 4,
    )

    private suspend fun awaitResult(engine: LocalAnalysisEngine) =
        withTimeoutOrNull(30_000L) {
            while (true) {
                val current = engine.result.value
                if (current != null && current.status.isTerminal) return@withTimeoutOrNull current
                delay(20L)
            }
            @Suppress("UNREACHABLE_CODE")
            error("result never arrived")
        } ?: error("timed out")

    private fun file(vararg lines: String): File {
        val created = File.createTempFile("crack", ".txt")
        created.deleteOnExit()
        created.writeText(lines.joinToString("\n"))
        return created
    }
}