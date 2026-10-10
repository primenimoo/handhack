package com.primenimoo.handhack.engine.handshake

import java.io.File

/**
 * Parser for the compact text export HandHack itself has always accepted.
 *
 * Supported line shapes, with the SSID either on the same line or on the line above:
 * ```
 * HomeNet:WPA*01aabbcc...
 * HomeNet<TAB>WPA*01aabbcc...
 * WPA*01aabbcc...            (SSID unknown — cannot be verified)
 * ```
 * The value after the marker is the 32-byte PMK, so [PmkVerifier.Pmk] is used. Lines that
 * are not handshake records are ignored, so mixed files still work.
 *
 * Real captures are handled by `com.primenimoo.handhack.engine.capture.CaptureLoader`.
 */
object HandshakeParser {

    const val MARKER = "WPA*01"
    const val MARKER_WPA02 = "WPA*02"

    /** Both markers this parser recognises inside a line. */
    private val MARKERS = listOf(MARKER, MARKER_WPA02)

    fun isHandshakeFile(file: File): Boolean = parse(file).isNotEmpty()

    /**
     * Number of handshake records in the file, whether or not they carry an SSID.
     *
     * The difference between this and [parse] size is exactly the number of records
     * that look like handshakes but cannot be verified.
     */
    fun countRecords(file: File): Int {
        var count = 0
        file.forEachLineCompat { line ->
            if (MARKERS.any { line.contains(it) }) count++
        }
        return count
    }

    fun parse(file: File): List<HandshakeTarget> {
        val results = mutableListOf<HandshakeTarget>()
        var pendingSsid: String? = null
        file.forEachLineCompat { rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachLineCompat

            val marker = MARKERS.firstOrNull { line.contains(it) }
            if (marker == null) {
                // Not a handshake line: remember it as the SSID for a following record.
                pendingSsid = line.substringBefore(':').trim().takeIf { it.isNotEmpty() }
                return@forEachLineCompat
            }

            val hashIndex = line.indexOf(marker)
            val hex = line.substring(hashIndex + marker.length)
                .substringBefore(' ')
                .substringBefore('\t')
                .trim()
            val bytes = Hex.decode(hex)
            if (bytes == null || bytes.size != Pbkdf2.KEY_LENGTH_BYTES) return@forEachLineCompat

            val inlineSsid = line.substring(0, hashIndex)
                .trim()
                .trimEnd(':', '\t', ' ', '=')
            val ssid = inlineSsid.ifEmpty { pendingSsid.orEmpty() }
            if (ssid.isEmpty()) return@forEachLineCompat

            results += HandshakeTarget(
                ssidBytes = ssid.toByteArray(Charsets.UTF_8),
                verifier = PmkVerifier.Pmk(bytes),
                rawLine = line,
                evidence = CaptureEvidence.PMK,
            )
            pendingSsid = null
        }
        return results
    }
}

internal inline fun File.forEachLineCompat(action: (String) -> Unit) {
    useLines(Charsets.UTF_8) { lines -> lines.forEach { action(it) } }
}