package com.primenimoo.handhack.engine.capture

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

/**
 * One link-layer frame lifted out of a capture container.
 *
 * [data] starts at the 802.11 header, with any radiotap header already removed, so the
 * wireless parser does not have to care how the capture was stored.
 */
class CapturedFrame(
    val data: ByteArray,
    val timestampSeconds: Long,
    val timestampFraction: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is CapturedFrame &&
            data.contentEquals(other.data) &&
            timestampSeconds == other.timestampSeconds

    override fun hashCode(): Int = 31 * data.contentHashCode() + timestampSeconds.hashCode()
}

/** What the container header of a capture file says, before any frame is read. */
data class CaptureContainer(val kind: Kind, val linkType: Int, val bigEndian: Boolean, val nanoseconds: Boolean) {
    enum class Kind { PCAP, PCAPNG, UNKNOWN }
}

/**
 * Reader for the two packet capture containers: classic libpcap and pcapng.
 *
 * `.cap` and `.pcap` share the classic header, `.pcapng` uses the block based one. Both are
 * walked by their declared lengths, never by scanning for a frame pattern, so a truncated
 * file stops cleanly instead of producing invented data.
 *
 * Endianness is decided by the magic as it is written, not by a comparison that only works for
 * one byte order: the four classic magics and the pcapng byte order magic are all recognised,
 * and every multi-byte field of a following block is read in the order the header declares.
 */
object PcapReader {

    const val LINKTYPE_IEEE802_11 = 105
    const val LINKTYPE_IEEE802_11_RADIOTAP = 127

// Read as the four bytes they are on disk, keyed by the integer a little-endian read produces,
// so no byte array is ever compared by identity and no host byte order can creep in.
private class PcapVariant(val bigEndian: Boolean, val nanoseconds: Boolean)

private val PCAP_MAGICS = mapOf(
    // The integer is what the four magic bytes read as when taken little-endian: a file whose
    // bytes spell 0xa1b2c3d4 that way was written little-endian, and vice versa.
    0xA1B2C3D4.toInt() to PcapVariant(bigEndian = false, nanoseconds = false),
    0xD4C3B2A1.toInt() to PcapVariant(bigEndian = true, nanoseconds = false),
    0xA1B23C4D.toInt() to PcapVariant(bigEndian = false, nanoseconds = true),
    0x4D3CB2A1.toInt() to PcapVariant(bigEndian = true, nanoseconds = true),
)

private fun magicOf(head: ByteArray): PcapVariant? =
    if (head.size < 4) null else PCAP_MAGICS[le32(head, 0).toInt()]

private val PCAPNG_BYTE_ORDER_MAGIC_SWAPPED = byteArrayOf(0x4D, 0x3C, 0x2B, 0x1A)
    private const val PCAPNG_SHB = 0x0A0D0D0AL
    private const val PCAPNG_IDB = 0x00000001L
    private const val PCAPNG_SPB = 0x00000003L
    private const val PCAPNG_EPB = 0x00000006L

    private const val MAX_FRAME_BYTES = 65_535
    private const val MAX_BLOCK_BYTES = 16 * 1024 * 1024

    /** True when the bytes start with a container header we understand. */
    fun isCaptureContainer(head: ByteArray): Boolean = detect(head).kind != CaptureContainer.Kind.UNKNOWN

    /** Reads only the container header, so a format can be named before the file is walked. */
    fun detect(head: ByteArray): CaptureContainer {
        if (head.size < 4) return CaptureContainer(CaptureContainer.Kind.UNKNOWN, -1, false, false)

        magicOf(head)?.let { magic ->
            if (head.size < 24) return CaptureContainer(CaptureContainer.Kind.UNKNOWN, -1, magic.bigEndian, magic.nanoseconds)
            val linkType = if (magic.bigEndian) be32(head, 20).toInt() else le32(head, 20).toInt()
            return CaptureContainer(CaptureContainer.Kind.PCAP, linkType, magic.bigEndian, magic.nanoseconds)
        }

        // A pcapng file opens with a section header whose block type is the byte pair 0A 0D 0D
        // 0A in either byte order; its link type only becomes known once the interface
        // description blocks are walked, so it is reported as unknown here.
        val first = head.copyOfRange(0, 4)
        if (first.contentEquals(byteArrayOf(0x0A, 0x0D, 0x0D, 0x0A.toByte()))) {
            val bom = if (head.size >= 12) head.copyOfRange(8, 12) else ByteArray(0)
            val bigEndian = bom.contentEquals(PCAPNG_BYTE_ORDER_MAGIC_SWAPPED)
            return CaptureContainer(CaptureContainer.Kind.PCAPNG, -1, bigEndian, false)
        }

        return CaptureContainer(CaptureContainer.Kind.UNKNOWN, -1, false, false)
    }

    /** Header of [file], or an unknown container when it is too short or not a capture. */
    fun describe(file: File): CaptureContainer {
        if (file.length() < 4) return CaptureContainer(CaptureContainer.Kind.UNKNOWN, -1, false, false)
        return RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(24)
            val read = readFully(raf, head)
            detect(if (read == head.size) head else head.copyOf(read))
        }
    }

    /**
     * Yields every frame stored in [file], stopping at the first record that does not
     * make sense. A file that yields nothing is reported by returning an empty list.
     */
    fun read(file: File): List<CapturedFrame> {
        if (file.length() < 4) return emptyList()
        return RandomAccessFile(file, "r").use { raf ->
            // Detection needs the whole 24-byte global header, not just the magic, so the probe
            // is read once and then the file is rewound to where each reader expects to start.
            val head = ByteArray(24)
            val read = readFully(raf, head)
            if (read < 4) return emptyList()
            val probe = if (read == head.size) head else head.copyOf(read)
            when (detect(probe).kind) {
                CaptureContainer.Kind.PCAP -> {
                    raf.seek(4)
                    readPcap(raf, probe.copyOfRange(0, 4))
                }
                CaptureContainer.Kind.PCAPNG -> {
                    raf.seek(0)
                    readPcapng(raf)
                }
                CaptureContainer.Kind.UNKNOWN -> emptyList()
            }
        }
    }

    private fun readPcap(raf: RandomAccessFile, magic: ByteArray): List<CapturedFrame> {
        val header = magicOf(magic) ?: return emptyList()
        val bigEndian = header.bigEndian
        val nanoseconds = header.nanoseconds
        val rest = ByteArray(20)
        if (readFully(raf, rest) != rest.size) return emptyList()
        val network = if (bigEndian) be32(rest, 16).toInt() else le32(rest, 16).toInt()
        if (!isSupportedLinkType(network)) throw UnsupportedLinkTypeException(network)

        val frames = mutableListOf<CapturedFrame>()
        val record = ByteArray(16)
        while (true) {
            if (readFully(raf, record) != record.size) break

            val seconds = if (bigEndian) be32(record, 0) else le32(record, 0)
            val fraction = if (bigEndian) be32(record, 4) else le32(record, 4)
            val capturedLength = if (bigEndian) be32(record, 8) else le32(record, 8)

            if (capturedLength <= 0 || capturedLength > MAX_FRAME_BYTES) break
            val captured = capturedLength.toInt()
            val payload = ByteArray(captured)
            if (readFully(raf, payload) != captured) break

            frames += CapturedFrame(
                data = stripRadiotap(payload, network),
                timestampSeconds = seconds,
                timestampFraction = if (nanoseconds) fraction / 1000L else fraction,
            )
        }
        return frames
    }

    private fun readPcapng(raf: RandomAccessFile): List<CapturedFrame> {
        val frames = mutableListOf<CapturedFrame>()
        val linkTypes = mutableMapOf<Int, Int>()
        var bigEndian = false

        while (true) {
            val blockStart = raf.filePointer
            val head = ByteArray(8)
            if (readFully(raf, head) != head.size) break

            val isSectionHeader = head.copyOfRange(0, 4)
                .contentEquals(byteArrayOf(0x0A, 0x0D, 0x0D, 0x0A.toByte()))
            if (isSectionHeader) {
                // The byte order magic sits right after the block length and decides how this
                // block and every later one is read, so it is peeked before the length is used.
                val bom = peek(raf, blockStart + 8, 4) ?: break
                bigEndian = bom.contentEquals(PCAPNG_BYTE_ORDER_MAGIC_SWAPPED)
            }

            val blockType = if (bigEndian) be32(head, 0) else le32(head, 0)
            val blockLength = if (bigEndian) be32(head, 4) else le32(head, 4)
            if (blockLength < 12 || blockLength > MAX_BLOCK_BYTES) break

            val body = ByteArray((blockLength - 12L).toInt())
            if (readFully(raf, body) != body.size) break
            val trailer = ByteArray(4)
            if (readFully(raf, trailer) != trailer.size) break

            when (blockType) {
                PCAPNG_SHB -> {
                    // A new section can switch byte order, so re-read the magic from the body.
                    bigEndian = body.copyOfRange(0, 4).contentEquals(PCAPNG_BYTE_ORDER_MAGIC_SWAPPED)
                    linkTypes.clear()
                }

                PCAPNG_IDB -> {
                    val id = linkTypes.size
                    linkTypes[id] = if (bigEndian) be16(body, 0) else le16(body, 0)
                }

                PCAPNG_EPB -> {
                    val interfaceId = if (bigEndian) be32(body, 0) else le32(body, 0)
                    val linkType = linkTypes[interfaceId.toInt()] ?: continue
                    if (!isSupportedLinkType(linkType)) continue
                    // interface id (4), timestamp high (4), timestamp low (4),
                    // captured length (4), original length (4), then the padded packet.
                    val capturedLength = if (bigEndian) be32(body, 12) else le32(body, 12)
                    if (capturedLength <= 0 || capturedLength > MAX_FRAME_BYTES) continue
                    val captured = capturedLength.toInt()
                    val offset = 20
                    if (offset + captured > body.size) continue
                    frames += CapturedFrame(
                        data = stripRadiotap(body.copyOfRange(offset, offset + captured), linkType),
                        timestampSeconds = 0L,
                        timestampFraction = 0L,
                    )
                }

                PCAPNG_SPB -> {
                    val linkType = linkTypes[0] ?: continue
                    if (!isSupportedLinkType(linkType)) continue
                    if (body.isEmpty()) continue
                    frames += CapturedFrame(
                        data = stripRadiotap(body.copyOfRange(0, body.size), linkType),
                        timestampSeconds = 0L,
                        timestampFraction = 0L,
                    )
                }
            }
        }
        return frames
    }

    /** Reads [size] bytes at [offset] without moving the caller's position. */
    private fun peek(raf: RandomAccessFile, offset: Long, size: Int): ByteArray? {
        val here = raf.filePointer
        raf.seek(offset)
        val buffer = ByteArray(size)
        val read = readFully(raf, buffer)
        raf.seek(here)
        return if (read == size) buffer else null
    }

    private fun isSupportedLinkType(linkType: Int): Boolean =
        linkType == LINKTYPE_IEEE802_11 || linkType == LINKTYPE_IEEE802_11_RADIOTAP

    /** Removes the radiotap preamble so callers always see the 802.11 header. */
    private fun stripRadiotap(frame: ByteArray, linkType: Int): ByteArray {
        if (linkType != LINKTYPE_IEEE802_11_RADIOTAP) return frame
        if (frame.size < 4) return frame
        val length = le16(frame, 2)
        if (length < 8 || length > frame.size) return frame
        return frame.copyOfRange(length, frame.size)
    }

    /** Reads exactly [target] bytes, or returns how many were actually available. */
    private fun readFully(raf: RandomAccessFile, target: ByteArray): Int {
        var offset = 0
        while (offset < target.size) {
            val read = try {
                raf.read(target, offset, target.size - offset)
            } catch (_: EOFException) {
                -1
            }
            if (read <= 0) return offset
            offset += read
        }
        return offset
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun be16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun be32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
}

/** The container is readable but was not recorded as raw 802.11 traffic. */
class UnsupportedLinkTypeException(val linkType: Int) :
    Exception("Unsupported link type $linkType")