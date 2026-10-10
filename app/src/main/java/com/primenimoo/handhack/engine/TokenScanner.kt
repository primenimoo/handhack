package com.primenimoo.handhack.engine

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Turns a file into a stream of candidate records.
 *
 * Text files yield one record per non-blank line (LF and CRLF are both handled).
 * Binary files yield printable ASCII runs, which is what makes the same engine
 * usable for captures and raw dumps without a second implementation.
 */
object TokenScanner {

    private const val MIN_BINARY_TOKEN = 4
    private const val MAX_BINARY_TOKEN = 64
    private const val READ_BUFFER = 64 * 1024

    suspend fun forEachToken(file: File, textBased: Boolean, sink: suspend (String) -> Unit) {
        if (textBased) {
            forEachLine(file, sink)
        } else {
            forEachBinaryToken(file, sink)
        }
    }

    private suspend fun forEachLine(file: File, sink: suspend (String) -> Unit) {
        file.inputStream().buffered(READ_BUFFER).use { input ->
            val reader = bufferedReader(input)
            while (true) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) sink(trimmed)
            }
        }
    }

    private fun bufferedReader(input: java.io.InputStream): BufferedReader {
        val (charset, bomLength) = detect(input)
        val stream = if (bomLength > 0) SkipBomStream(input, bomLength) else input
        return BufferedReader(InputStreamReader(stream, charset), READ_BUFFER)
    }

    /** Returns the charset and the length of the byte order mark to skip (0 if none). */
    private fun detect(input: java.io.InputStream): Pair<Charset, Int> {
        input.mark(4)
        val head = ByteArray(3)
        val read = input.read(head, 0, 3)
        if (read >= 3 &&
            (head[0].toInt() and 0xFF) == 0xEF &&
            (head[1].toInt() and 0xFF) == 0xBB &&
            (head[2].toInt() and 0xFF) == 0xBF
        ) {
            input.reset()
            return StandardCharsets.UTF_8 to 3
        }
        if (read >= 2) {
            val b0 = head[0].toInt() and 0xFF
            val b1 = head[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                input.reset()
                return StandardCharsets.UTF_16LE to 2
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                input.reset()
                return StandardCharsets.UTF_16BE to 2
            }
        }
        if (read > 0) input.reset()
        return StandardCharsets.UTF_8 to 0
    }

    private suspend fun forEachBinaryToken(file: File, sink: suspend (String) -> Unit) {
        file.inputStream().buffered(READ_BUFFER).use { input ->
            val buffer = ByteArray(READ_BUFFER)
            val token = StringBuilder(MAX_BINARY_TOKEN)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                for (i in 0 until read) {
                    val b = buffer[i].toInt() and 0xFF
                    if (b in 0x21..0x7E) {
                        if (token.length < MAX_BINARY_TOKEN) token.append(b.toChar())
                    } else {
                        emit(token, sink)
                    }
                }
            }
            emit(token, sink)
        }
    }

    private suspend fun emit(token: StringBuilder, sink: suspend (String) -> Unit) {
        if (token.length >= MIN_BINARY_TOKEN) sink(token.toString())
        token.setLength(0)
    }
}

/**
 * Drops exactly [bomLength] leading bytes. The length matters: a UTF-8 mark is
 * three bytes while UTF-16 marks are two, and eating one byte too many would
 * corrupt the first character of the file.
 */
private class SkipBomStream(
    private val delegate: java.io.InputStream,
    private val bomLength: Int,
) : java.io.InputStream() {

    private val pending = ByteArray(bomLength)
    private var pendingIndex = 0
    private var skipped = false

    private fun fillPending() {
        if (skipped) return
        skipped = true
        var read = 0
        while (read < bomLength) {
            val count = delegate.read(pending, read, bomLength - read)
            if (count <= 0) break
            read += count
        }
        pendingIndex = read
    }

    override fun read(): Int {
        fillPending()
        if (pendingIndex > 0) return pending[pendingIndex++].toInt() and 0xFF
        return delegate.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        fillPending()
        var written = 0
        if (pendingIndex > 0) {
            while (written < len && pendingIndex < pending.size) {
                b[off + written] = pending[pendingIndex++]
                written++
            }
            if (written == len) return written
        }
        val extra = delegate.read(b, off + written, len - written)
        return when {
            extra > 0 -> written + extra
            // Never report 0: InputStreamReader treats it as a stream error.
            written > 0 -> written
            else -> -1
        }
    }

    override fun close() = delegate.close()
}
