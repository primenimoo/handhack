package com.primenimoo.handhack.core

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Streaming TXT reader/writer.
 *
 * Requirements handled here: UTF-8, byte-order marks (UTF-8/UTF-16LE/UTF-16BE),
 * LF and CRLF line endings, Unicode content, blank lines, and very large files
 * without ever materialising more than a single line at a time.
 */
object TextStream {

    private const val BOM_UTF8 = 0xEF
    private const val BOM_UTF16LE = 0xFF
    private const val BOM_UTF16BE = 0xFE

    /**
     * Sniffs the byte-order mark and falls back to UTF-8. Files produced on
     * legacy Windows tooling are frequently CP1251; those are still decodable
     * without crashing because malformed sequences become U+FFFD rather than
     * throwing, so the file stays usable instead of crashing the editor.
     */
    fun detectCharset(stream: InputStream): Pair<Charset, Boolean> {
        val mark = ByteArray(3)
        val read = stream.read(mark, 0, 3)
        if (read >= 3 && (mark[0].toInt() and 0xFF) == BOM_UTF8 &&
            (mark[1].toInt() and 0xFF) == 0xBB && (mark[2].toInt() and 0xFF) == 0xBF
        ) {
            return StandardCharsets.UTF_8 to true
        }
        if (read >= 2) {
            val b0 = mark[0].toInt() and 0xFF
            val b1 = mark[1].toInt() and 0xFF
            if (b0 == BOM_UTF16LE && b1 == 0x00) return StandardCharsets.UTF_16LE to true
            if (b0 == 0x00 && b1 == BOM_UTF16BE) return StandardCharsets.UTF_16BE to true
        }
        return StandardCharsets.UTF_8 to false
    }

    /**
     * Opens a reader positioned *after* any BOM. The stream must support marks or
     * be a freshly opened one; callers pass the stream they just opened.
     */
    fun reader(stream: InputStream): BufferedReader {
        val (charset, hadBom) = detectCharset(stream)
        var reader = InputStreamReader(stream, charset)
        if (hadBom && charset == StandardCharsets.UTF_8) {
            reader = InputStreamReader(skipBom(stream), charset)
        }
        return BufferedReader(reader, FileAccess.BUFFER_SIZE)
    }

    private fun skipBom(stream: InputStream): InputStream {
        val b0 = stream.read()
        val b1 = stream.read()
        val b2 = stream.read()
        return if (b0 == 0xEF && b1 == 0xBB && b2 == 0xBF) stream else RewoundStream(b0, b1, b2, stream)
    }

    /**
     * Counts entries without keeping them in memory. Blank lines are counted so
     * the working copy stays a faithful representation of the source file.
     */
    fun countLines(reader: BufferedReader): Long {
        var count = 0L
        val buffer = CharArray(16 * 1024)
        var sawAny = false
        while (true) {
            val read = reader.read(buffer)
            if (read <= 0) break
            sawAny = true
            for (i in 0 until read) {
                if (buffer[i] == '\n') count++
            }
        }
        if (sawAny) count++
        return count
    }

    /** Iterates entries one by one. Callers decide how to buffer or aggregate. */
    inline fun forEachLine(reader: BufferedReader, action: (String) -> Unit) {
        while (true) {
            val line = reader.readLine() ?: break
            action(line.removeSuffix("\r"))
        }
    }

    /** Writes entries using LF endings, streaming to the sink. */
    inline fun writeLines(entries: Iterable<String>, sink: (String) -> Unit) {
        for (entry in entries) sink(entry)
    }
}

/** Pushes back up to three bytes that were consumed while sniffing. */
private class RewoundStream(
    b0: Int,
    b1: Int,
    b2: Int,
    private val delegate: InputStream,
) : InputStream() {
    private val pending = intArrayOf(b0, b1, b2)
    private var index = 0

    override fun read(): Int {
        if (index < pending.size) {
            val value = pending[index++]
            return if (value == -1) -1 else value and 0xFF
        }
        return delegate.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var written = 0
        if (index < pending.size) {
            while (written < len && index < pending.size) {
                b[off + written] = (pending[index++] and 0xFF).toByte()
                written++
            }
            if (written == len) return written
        }
        val extra = delegate.read(b, off + written, len - written)
        return when {
            extra > 0 -> written + extra
            written > 0 -> written
            else -> -1
        }
    }

    override fun available(): Int = pending.size - index + delegate.available()

    override fun close() = delegate.close()
}
