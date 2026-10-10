package com.primenimoo.handhack.engine.capture

import com.primenimoo.handhack.engine.handshake.HandshakeParser
import com.primenimoo.handhack.engine.handshake.HandshakeTarget
import java.io.File

/**
 * The one place that decides which reader a capture file needs.
 *
 * Six containers are supported and they share nothing but the promise that a network's key
 * material can be recovered from them. Detection therefore looks at the bytes first and the
 * file name second: a `.txt` full of `.hc22000` records is read as `.hc22000`, and a `.hccapx`
 * that was renamed to `.cap` is still recognised. Nothing is decided by the extension alone,
 * because the tools that produce these files disagree about the names they use.
 *
 * ```
 *  .hc22000, .txt   WPA*01 / WPA*02 records, one per line
 *  legacy export    SSID:WPA*01<32-byte PMK in hex>
 *  .hccapx          hashcat container, 393-byte records
 *  .hccap           hashcat 3.5 and lower, 392-byte records, no magic
 *  .cap, .pcap      classic libpcap, 802.11 frames
 *  .pcapng          pcapng, 802.11 frames
 * ```
 */
object CaptureLoader {

    /** The container a file turned out to be, named for the user. */
    enum class CaptureFormat(
        val id: String,
        val label: String,
        val textBased: Boolean,
        val extensions: Set<String>,
    ) {
        HC22000("hc22000", "hc22000 (WPA*01 / WPA*02)", true, setOf("hc22000", "txt", "22000")),
        LEGACY_EXPORT("legacy", "экспорт HandHack", true, setOf("wpa", "handshake", "handshakes", "pmkid", "txt")),
        HCCAPX("hccapx", "hccapx", false, setOf("hccapx")),
        HCCAP("hccap", "hccap", false, setOf("hccap")),
        PCAP("pcap", "pcap / cap", false, setOf("pcap", "cap")),
        PCAPNG("pcapng", "pcapng", false, setOf("pcapng")),
        ;

        companion object {
            /** Every extension the picker should offer, for the file chooser MIME filter. */
            val ALL_EXTENSIONS: Set<String> = entries.flatMapTo(mutableSetOf()) { it.extensions }
        }
    }

    /**
     * What a capture yielded: the records that can be checked, and everything worth telling the
     * user about the ones that cannot.
     */
    data class Loaded(
        val targets: List<HandshakeTarget>,
        val format: CaptureFormat,
        /** Plain sentences for the UI, already written in Russian. */
        val notes: List<String>,
    ) {
        val isUsable: Boolean get() = targets.isNotEmpty()
    }

    /** How many bytes are needed to tell the formats apart. */
    private const val PROBE_BYTES = 4 * 1024

    /** Said when a text file held nothing that even looked like a record. */
    private const val NO_RECORDS = "В файле нет записей формата WPA*01 или WPA*02."

    /** Reads [file] with whichever reader its bytes call for. */
    fun load(file: File): Loaded {
        if (!file.isFile || file.length() == 0L) {
            return Loaded(emptyList(), CaptureFormat.HC22000, listOf("Файл пустой — нет данных для подбора."))
        }

        val probe = ByteArray(minOf(PROBE_BYTES.toLong(), file.length()).toInt())
        file.inputStream().use { stream ->
            var offset = 0
            while (offset < probe.size) {
                val read = stream.read(probe, offset, probe.size - offset)
                if (read <= 0) break
                offset += read
            }
        }

        return when (val format = detect(probe, file.name)) {
            CaptureFormat.HC22000 -> loadText(file)
            CaptureFormat.LEGACY_EXPORT -> loadLegacy(file)
            CaptureFormat.HCCAPX -> loadContainer(CaptureFormat.HCCAPX, HccapxReader.parse(file), file)
            CaptureFormat.HCCAP -> loadContainer(CaptureFormat.HCCAP, HccapReader.parse(file), file)
            CaptureFormat.PCAP, CaptureFormat.PCAPNG -> loadFrames(file, format)
        }
    }

    /**
     * Decides the format from the bytes. The two text formats are told apart by whether the first
     * record carries a network name of its own, and `.hccap` is recognised by its shape because
     * the format has no magic number to look for.
     */
    fun detect(probe: ByteArray, fileName: String = ""): CaptureFormat {
        if (probe.isEmpty()) return CaptureFormat.HC22000

        if (PcapReader.isCaptureContainer(probe)) {
            return if (PcapReader.detect(probe).kind == CaptureContainer.Kind.PCAPNG) {
                CaptureFormat.PCAPNG
            } else {
                CaptureFormat.PCAP
            }
        }
        if (HccapxReader.sniff(probe)) return CaptureFormat.HCCAPX
        if (HccapReader.sniff(probe)) return CaptureFormat.HCCAP

        val firstRecord = firstMeaningfulLine(probe)
        if (firstRecord != null) {
            // A real `.hc22000` record has all nine fields; the legacy export is a marker glued
            // to one hex blob, so a line without the field separators belongs to the old format.
            if (Hc22000Parser.looksLikeRecord(firstRecord) &&
                firstRecord.count { it == '*' } >= Hc22000Parser.PLAIN_FIELDS - 1
            ) {
                return CaptureFormat.HC22000
            }
            if (firstRecord.contains(HandshakeParser.MARKER) || firstRecord.contains(HandshakeParser.MARKER_WPA02)) {
                return CaptureFormat.LEGACY_EXPORT
            }
        }
        return CaptureFormat.HC22000
    }

    private fun loadText(file: File): Loaded {
        val result = Hc22000Parser.parseFile(file)
        val notes = result.rejected
            .groupBy { it.reason }
            .map { (reason, records) -> "${reason.message} (${records.size})" }
            .toMutableList()
        if (notes.isEmpty()) notes += NO_RECORDS
        return Loaded(result.targets, CaptureFormat.HC22000, notes)
    }

    private fun loadLegacy(file: File): Loaded {
        val targets = HandshakeParser.parse(file)
        val notes = mutableListOf<String>()
        val withoutSsid = HandshakeParser.countRecords(file) - targets.size
        if (withoutSsid > 0) {
            notes += "В $withoutSsid записях нет SSID — проверить их невозможно. " +
                "Укажите имя сети перед записью в виде «ИмяСети:${HandshakeParser.MARKER}…»."
        }
        if (notes.isEmpty()) notes += NO_RECORDS
        return Loaded(targets, CaptureFormat.LEGACY_EXPORT, notes)
    }

    private fun loadContainer(
        format: CaptureFormat,
        targets: List<HandshakeTarget>,
        file: File,
    ): Loaded {
        val notes = mutableListOf<String>()
        if (targets.isEmpty()) {
            notes += "В файле не найдено ни одной пригодной записи ${format.label}."
            val size = file.length()
            val whole = size % if (format == CaptureFormat.HCCAPX) {
                HccapxReader.RECORD_LENGTH
            } else {
                HccapReader.RECORD_LENGTH
            }
            if (whole != 0L) {
                notes += "Размер файла не кратен длине записи, возможно он повреждён."
            }
        }
        return Loaded(targets, format, notes)
    }

    private fun loadFrames(file: File, format: CaptureFormat): Loaded {
        val frames = try {
            PcapReader.read(file)
        } catch (_: UnsupportedLinkTypeException) {
            return Loaded(
                emptyList(),
                format,
                listOf(
                    "Capture сохранён не как радио-трафик 802.11. " +
                        "Поддерживаются только записи с самими кадрами Wi-Fi.",
                ),
            )
        }

        val summary = WirelessCaptureParser.parse(frames)
        val notes = summary.notes.toMutableList()
        if (summary.targets.isNotEmpty()) {
            notes += "Найдено сетей: ${summary.networkCount}, EAPOL-Key сообщений: ${summary.eapolCount}."
        }
        return Loaded(summary.targets, format, notes)
    }

    /** First line that is not blank and not a comment, decoded as UTF-8. */
    private fun firstMeaningfulLine(probe: ByteArray): String? {
        val text = String(probe, Charsets.UTF_8)
        for (line in text.lineSequence()) {
            val trimmed = line.trim().removePrefix("\uFEFF")
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue
            return trimmed
        }
        return null
    }
}