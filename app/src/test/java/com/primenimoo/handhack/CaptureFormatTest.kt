package com.primenimoo.handhack

import com.primenimoo.handhack.engine.capture.CaptureContainer
import com.primenimoo.handhack.engine.capture.CaptureLoader
import com.primenimoo.handhack.engine.capture.EapolKey
import com.primenimoo.handhack.engine.capture.MicTarget
import com.primenimoo.handhack.engine.capture.PcapReader
import com.primenimoo.handhack.engine.handshake.CaptureEvidence
import com.primenimoo.handhack.engine.handshake.Hex
import com.primenimoo.handhack.engine.handshake.Pbkdf2
import com.primenimoo.handhack.engine.handshake.PmkVerifier
import com.primenimoo.handhack.engine.handshake.Prf
import com.primenimoo.handhack.engine.handshake.Ptk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Checks the key schedule against values this project did not produce, then proves that every
 * supported container can carry a real handshake and be read back.
 *
 * The expected values come from an independent oracle: PBKDF2 from the platform provider, and
 * PRF-512, the PMKID and the MIC built directly on HMAC-SHA1 as IEEE 802.11i defines them. The
 * `password`/`IEEE` pair is the vector published in the standard, so a regression in the
 * derivation cannot be hidden behind a self-consistent test.
 */
class CaptureFormatTest {

    private val ssid = "HandHackLab"
    private val passphrase = "correcthorse1"
    private val ssidBytes = ssid.toByteArray(Charsets.UTF_8)

    private val authenticator = Hex.decode("1831bfbfb954")!!
    private val supplicant = Hex.decode("f854f67780cb")!!
    private val authenticatorNonce =
        Hex.decode("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")!!
    private val supplicantNonce =
        Hex.decode("2122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40")!!

    private val pmk = Hex.decode("031cc89c376d8b215ccaff9a24b3849740ba2774bb58d128a7d29722999d7724")!!
    private val pmkid = Hex.decode("34445a8ed3d4a876d2dc0586867c3d2d")!!
    private val keyConfirmationKey = Hex.decode("53cc8933ef579773792e261c80bda167")!!

    // --- the key schedule, against values computed elsewhere --------------------------------

    @Test
    fun `pbkdf2 matches the vector published in the standard`() {
        assertEquals(
            "f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e",
            Hex.encode(Pbkdf2.derive("password", "IEEE")),
        )
    }

    @Test
    fun `pbkdf2 matches the oracle for the lab network`() {
        assertEquals(Hex.encode(pmk), Hex.encode(Pbkdf2.derive(passphrase, ssid)))
    }

    @Test
    fun `a non-ascii passphrase is derived from its utf-8 bytes`() {
        assertEquals(
            "f6d2fa8312debf4945b0cca89479a045cb136ed836b000f16ce590c42142debd",
            Hex.encode(Pbkdf2.derive("пароль123".toByteArray(Charsets.UTF_8), ssidBytes)),
        )
    }

    @Test
    fun `the same characters in another encoding are a different passphrase`() {
        // "пароль123" as Windows-1251 bytes. The platform key factory folds a password to its low
        // byte on Android, so this is exactly the case that silently breaks there; pinning both
        // values keeps the derivation byte-exact.
        val windows1251 = byteArrayOf(
            0xEF.toByte(), 0xE0.toByte(), 0xEB.toByte(), 0xEE.toByte(),
            0xEB.toByte(), 0xF2.toByte(), 0x31, 0x32, 0x33,
        )
        assertEquals(
            "c53439bbe710661719d84bea001c21bed877b1c90d48b2573dbfd6c72d96f1e3",
            Hex.encode(Pbkdf2.derive(windows1251, ssidBytes)),
        )
    }

    @Test
    fun `ptk expansion reproduces the oracle key confirmation key`() {
        val kck = Ptk.deriveKeyConfirmationKey(
            pmk,
            authenticator,
            supplicant,
            authenticatorNonce,
            supplicantNonce,
            Prf.KEY_DESCRIPTOR_CCMP,
        )
        assertEquals(Hex.encode(keyConfirmationKey), Hex.encode(kck))
    }

    @Test
    fun `ptk expansion does not depend on the order the addresses arrive in`() {
        val forward = Ptk.deriveKeyConfirmationKey(
            pmk, authenticator, supplicant, authenticatorNonce, supplicantNonce, Prf.KEY_DESCRIPTOR_CCMP,
        )
        val reversed = Ptk.deriveKeyConfirmationKey(
            pmk, supplicant, authenticator, supplicantNonce, authenticatorNonce, Prf.KEY_DESCRIPTOR_CCMP,
        )
        assertArrayEquals(forward, reversed)
    }

    @Test
    fun `pmkid matches the truncated hmac of the standard`() {
        assertEquals(
            Hex.encode(pmkid),
            Hex.encode(PmkVerifier.derivePmkid(pmk, authenticator, supplicant)),
        )
    }

    // --- one synthetic handshake, written into every container -----------------------------

    @Test
    fun `the synthesised message 2 accepts the right passphrase and no other`() {
        val target = micTarget()

        assertEquals(CaptureEvidence.EAPOL_MIC, target.evidence)
        assertEquals(Hex.encode(authenticator), target.bssidHex)
        assertEquals(Hex.encode(supplicant), target.stationHex)
        assertTrue(target.verify(passphrase))
        assertFalse(target.verify("wrongpassword"))
        // A candidate of the wrong length must never reach the key schedule at all.
        assertFalse(target.verify("short"))
        assertFalse(target.verify("x".repeat(64)))
    }

    @Test
    fun `the message 2 mic is what hmac-sha1 over the message produces`() {
        val message = messageTwo()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(keyConfirmationKey, "HmacSHA1"))
        mac.update(message.copyOf().also { it.fill(0, PmkVerifier.MIC_OFFSET, PmkVerifier.MIC_OFFSET + 16) })
        val expected = mac.doFinal().copyOfRange(0, PmkVerifier.MIC_LENGTH)
        assertArrayEquals(expected, message.copyOfRange(PmkVerifier.MIC_OFFSET, PmkVerifier.MIC_OFFSET + 16))
    }

    @Test
    fun `hc22000 pmkid record round trips`() {
        val line = "WPA*01*${Hex.encode(pmkid)}*${Hex.encode(authenticator)}*" +
            "${Hex.encode(supplicant)}*${Hex.encode(ssidBytes)}***"

        val loaded = CaptureLoader.load(write("capture.hc22000", line))

        assertEquals(CaptureLoader.CaptureFormat.HC22000, loaded.format)
        assertEquals(1, loaded.targets.size)
        assertEquals(CaptureEvidence.PMKID, loaded.targets.single().evidence)
        assertTrue(loaded.targets.single().verify(passphrase))
        assertFalse(loaded.targets.single().verify("wrongpassword"))
    }

    @Test
    fun `hc22000 mic record round trips`() {
        val loaded = CaptureLoader.load(write("capture.hc22000", micRecordLine()))

        assertEquals(CaptureLoader.CaptureFormat.HC22000, loaded.format)
        assertEquals(CaptureEvidence.EAPOL_MIC, loaded.targets.single().evidence)
        assertTrue(loaded.targets.single().verify(passphrase))
        assertFalse(loaded.targets.single().verify("wrongpassword"))
    }

    @Test
    fun `hccapx round trips`() {
        val loaded = CaptureLoader.load(write("capture.hccapx", hccapxRecord(messageTwo())))

        assertEquals(CaptureLoader.CaptureFormat.HCCAPX, loaded.format)
        assertEquals(ssid, loaded.targets.single().ssid)
        assertEquals(Hex.encode(authenticator), loaded.targets.single().bssidHex)
        assertEquals(Hex.encode(supplicant), loaded.targets.single().stationHex)
        assertTrue(loaded.targets.single().verify(passphrase))
        assertFalse(loaded.targets.single().verify("wrongpassword"))
    }

    @Test
    fun `hccap round trips`() {
        val loaded = CaptureLoader.load(write("capture.hccap", hccapRecord(messageTwo())))

        assertEquals(CaptureLoader.CaptureFormat.HCCAP, loaded.format)
        assertTrue(loaded.targets.single().verify(passphrase))
        assertFalse(loaded.targets.single().verify("wrongpassword"))
    }

    @Test
    fun `classic pcap round trips and is recognised whatever the file is called`() {
        val bytes = pcapBytes()
        // The bytes decide the format, so a container stored under a misleading name still works.
        val renamed = write("actually-a-hccapx.cap", bytes)

        for (candidate in listOf(write("capture.pcap", bytes), renamed)) {
            val loaded = CaptureLoader.load(candidate)
            assertEquals(CaptureLoader.CaptureFormat.PCAP, loaded.format)
            assertEquals(ssid, loaded.targets.single().ssid)
            assertTrue(loaded.targets.single().verify(passphrase))
            assertFalse(loaded.targets.single().verify("wrongpassword"))
        }
    }

    @Test
    fun `a big endian classic pcap is read as well`() {
        val loaded = CaptureLoader.load(write("capture.pcap", pcapBytes(bigEndian = true)))

        assertEquals(CaptureLoader.CaptureFormat.PCAP, loaded.format)
        assertTrue(loaded.targets.single().verify(passphrase))
    }

    @Test
    fun `a radiotap capture has its preamble removed before parsing`() {
        val loaded = CaptureLoader.load(write("monitor.pcap", pcapBytes(radiotapFrames(), linkType = 127)))

        assertEquals(CaptureLoader.CaptureFormat.PCAP, loaded.format)
        assertEquals(1, loaded.targets.size)
        assertTrue(loaded.targets.single().verify(passphrase))
    }

    @Test
    fun `pcapng round trips`() {
        val loaded = CaptureLoader.load(write("capture.pcapng", pcapngBytes()))

        assertEquals(CaptureLoader.CaptureFormat.PCAPNG, loaded.format)
        assertTrue(loaded.targets.single().verify(passphrase))
        assertFalse(loaded.targets.single().verify("wrongpassword"))
    }

    @Test
    fun `a capture without message 2 is refused with a reason instead of a wrong answer`() {
        val partial = pcapBytes(listOf(beacon(), dataFrame(messageOne())))
        val loaded = CaptureLoader.load(write("capture.pcap", partial))

        assertTrue(loaded.targets.isEmpty())
        assertTrue(loaded.notes.any { it.contains("message 2") })
    }

    @Test
    fun `a capture without the network name is refused with a reason`() {
        val anonymous = pcapBytes(listOf(dataFrame(messageOne()), dataFrame(messageTwo())))
        val loaded = CaptureLoader.load(write("capture.pcap", anonymous))

        assertTrue(loaded.targets.isEmpty())
        assertTrue(loaded.notes.any { it.contains("имя сети") })
    }

    @Test
    fun `a capture stored as ethernet frames is refused with a reason`() {
        val loaded = CaptureLoader.load(write("capture.pcap", pcapBytes(frames(), linkType = 1)))

        assertTrue(loaded.targets.isEmpty())
        assertTrue(loaded.notes.any { it.contains("802.11") })
    }

    @Test
    fun `a truncated capture stops cleanly at the broken record`() {
        val full = pcapBytes()
        val loaded = CaptureLoader.load(write("capture.pcap", full.copyOfRange(0, full.size - 40)))

        // The beacon and message 1 survive; there is simply no message 2 left to check against.
        assertTrue(loaded.targets.isEmpty())
        assertTrue(loaded.notes.isNotEmpty())
    }

    @Test
    fun `an unrelated binary file is refused`() {
        val loaded = CaptureLoader.load(write("photo.png", ByteArray(2048) { (it * 31 % 251).toByte() }))

        assertTrue(loaded.targets.isEmpty())
        assertTrue(loaded.notes.isNotEmpty())
    }

    @Test
    fun `every supported extension is offered and every format claims one`() {
        val extensions = CaptureLoader.CaptureFormat.entries.flatMap { it.extensions }
        assertTrue(extensions.containsAll(listOf("hc22000", "hccapx", "hccap", "pcap", "cap", "pcapng")))
        assertEquals(
            extensions.toSet(),
            CaptureLoader.CaptureFormat.ALL_EXTENSIONS,
        )
        assertTrue(CaptureLoader.CaptureFormat.entries.all { it.extensions.isNotEmpty() && it.label.isNotBlank() })
    }

    @Test
    fun `each container is read by the reader its bytes call for`() {
        val cases = listOf(
            "capture.hc22000" to (CaptureLoader.CaptureFormat.HC22000 to micRecordLine().toByteArray()),
            "capture.hccapx" to (CaptureLoader.CaptureFormat.HCCAPX to hccapxRecord(messageTwo())),
            "capture.hccap" to (CaptureLoader.CaptureFormat.HCCAP to hccapRecord(messageTwo())),
            "capture.pcap" to (CaptureLoader.CaptureFormat.PCAP to pcapBytes()),
            "capture.cap" to (CaptureLoader.CaptureFormat.PCAP to pcapBytes()),
            "capture.pcapng" to (CaptureLoader.CaptureFormat.PCAPNG to pcapngBytes()),
        )

        for ((name, case) in cases) {
            val (expected, payload) = case
            val loaded = CaptureLoader.load(write(name, payload))
            assertEquals("format of $name", expected, loaded.format)
            assertEquals("targets of $name", 1, loaded.targets.size)
        }
    }

    @Test
    fun `the container header is read rather than guessed`() {
        val classic = PcapReader.detect(pcapBytes().copyOfRange(0, 24))
        assertEquals(CaptureContainer.Kind.PCAP, classic.kind)
        assertEquals(105, classic.linkType)
        assertFalse(classic.bigEndian)
        assertFalse(classic.nanoseconds)

        val bigEndian = PcapReader.detect(pcapBytes(bigEndian = true).copyOfRange(0, 24))
        assertEquals(CaptureContainer.Kind.PCAP, bigEndian.kind)
        assertTrue(bigEndian.bigEndian)

        assertEquals(CaptureContainer.Kind.PCAPNG, PcapReader.detect(pcapngBytes().copyOfRange(0, 24)).kind)
        assertEquals(
            CaptureContainer.Kind.UNKNOWN,
            PcapReader.detect("hello world, not a capture at all".toByteArray()).kind,
        )
    }

    // --- fixtures ------------------------------------------------------------------------

    private fun micTarget() = MicTarget.build(
        ssid = ssidBytes,
        mic = messageTwo().copyOfRange(PmkVerifier.MIC_OFFSET, PmkVerifier.MIC_OFFSET + PmkVerifier.MIC_LENGTH),
        authenticator = authenticator,
        supplicant = supplicant,
        authenticatorNonce = authenticatorNonce,
        supplicantNonce = supplicantNonce,
        keyDescriptorType = Prf.KEY_DESCRIPTOR_CCMP,
        message = messageTwo(),
        rawLabel = "test",
    )!!

    /**
     * The access point's first message: it introduces the authenticator nonce, so the PTK can
     * be rebuilt once the supplicant answers. It carries no MIC of its own.
     */
    private fun messageOne(): ByteArray = eapolKey(
        keyInfo = keyInfo(keyType = 1, ack = true, secure = true),
        nonce = authenticatorNonce,
        replayCounter = 1L,
    )

    /** The supplicant's answer, with a MIC computed over itself with that field zeroed. */
    private fun messageTwo(): ByteArray {
        val kck = Ptk.deriveKeyConfirmationKey(
            pmk, authenticator, supplicant, authenticatorNonce, supplicantNonce, Prf.KEY_DESCRIPTOR_CCMP,
        )
        val withoutMic = eapolKey(
            keyInfo = keyInfo(keyType = 2, install = true, mic = true, secure = true, encrypted = true),
            nonce = supplicantNonce,
            replayCounter = 2L,
        )
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(kck, "HmacSHA1"))
        val mic = mac.doFinal(withoutMic).copyOfRange(0, PmkVerifier.MIC_LENGTH)

        val withMic = withoutMic.copyOf()
        System.arraycopy(mic, 0, withMic, PmkVerifier.MIC_OFFSET, mic.size)
        return withMic
    }

    private fun keyInfo(
        keyType: Int,
        install: Boolean = false,
        ack: Boolean = false,
        mic: Boolean = false,
        secure: Boolean = false,
        encrypted: Boolean = false,
    ): Int {
        var value = 1 // AES: bit 0 of the key information field
        value = value or (keyType shl 1)
        if (install) value = value or 0x08
        if (ack) value = value or 0x10
        if (mic) value = value or 0x20
        if (secure) value = value or 0x40
        if (encrypted) value = value or 0x80
        return value
    }

    /** A bare EAPOL-Key message with the MIC field left zeroed, as the standard has it on send. */
    private fun eapolKey(keyInfo: Int, nonce: ByteArray, replayCounter: Long): ByteArray {
        val frame = ByteArray(EapolKey.FIXED_LENGTH)
        frame[0] = 0x01 // EAPOL version
        frame[1] = 0x03 // EAPOL-Key
        frame[4] = Prf.KEY_DESCRIPTOR_CCMP.toByte()
        frame[5] = (keyInfo and 0xFF).toByte()
        frame[6] = ((keyInfo shr 8) and 0xFF).toByte()
        // The key length stays zero: a CCMP pairwise key has no fixed length.
        for (i in 0 until 8) frame[9 + i] = ((replayCounter shr ((7 - i) * 8)) and 0xFF).toByte()
        System.arraycopy(nonce, 0, frame, EapolKey.NONCE_OFFSET, nonce.size)
        val bodyLength = frame.size - 4
        frame[2] = (bodyLength and 0xFF).toByte()
        frame[3] = ((bodyLength shr 8) and 0xFF).toByte()
        return frame
    }

    private fun beacon(): ByteArray {
        val header = macHeader(0x0080, BROADCAST, authenticator, authenticator)
        val fixed = ByteArray(12) // timestamp, beacon interval, capability: nothing reads them
        val element = byteArrayOf(0x00, ssidBytes.size.toByte()) + ssidBytes
        return header + fixed + element
    }

    private fun dataFrame(eapol: ByteArray): ByteArray =
        macHeader(0x0208, supplicant, authenticator, authenticator) + SNAP + eapol

    private fun radiotapFrames(): List<ByteArray> =
        listOf(beacon(), dataFrame(messageOne()), dataFrame(messageTwo())).map { RADIOTAP + it }

    private fun frames(): List<ByteArray> =
        listOf(beacon(), dataFrame(messageOne()), dataFrame(messageTwo()))

    private fun macHeader(frameControl: Int, a1: ByteArray, a2: ByteArray, a3: ByteArray): ByteArray {
        val header = ByteArray(24)
        header[0] = (frameControl and 0xFF).toByte()
        header[1] = ((frameControl shr 8) and 0xFF).toByte()
        System.arraycopy(a1, 0, header, 4, 6)
        System.arraycopy(a2, 0, header, 10, 6)
        System.arraycopy(a3, 0, header, 16, 6)
        return header
    }

    private fun pcapBytes(
        frames: List<ByteArray> = frames(),
        linkType: Int = 105,
        bigEndian: Boolean = false,
    ): ByteArray {
        val out = ArrayList<Byte>(frames.sumOf { it.size } + 64)
        fun put(value: Int, width: Int) {
            for (i in 0 until width) {
                val shift = if (bigEndian) (width - 1 - i) * 8 else i * 8
                out.add(((value ushr shift) and 0xFF).toByte())
            }
        }

        if (bigEndian) {
            out.addAll(listOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte()))
        } else {
            out.addAll(listOf(0xD4.toByte(), 0xC3.toByte(), 0xB2.toByte(), 0xA1.toByte()))
        }
        put(2, 2)
        put(4, 2)
        put(0, 4) // time zone
        put(0, 4) // timestamp accuracy
        put(0x00040000, 4)
        put(linkType, 4)

        for ((index, frame) in frames.withIndex()) {
            put(1_700_000_000 + index, 4)
            put(500_000, 4)
            put(frame.size, 4)
            put(frame.size, 4)
            out.addAll(frame.toList())
        }
        return out.toByteArray()
    }

    private fun pcapngBytes(): ByteArray {
        val out = ArrayList<Byte>()
        fun put32(value: Int) {
            for (i in 0 until 4) out.add((value ushr (i * 8) and 0xFF).toByte())
        }

        fun put16(value: Int) {
            out.add((value and 0xFF).toByte())
            out.add((value ushr 8 and 0xFF).toByte())
        }

        // Section header: block type, total length, byte order magic, version, section length.
        put32(0x0A0D0D0A)
        put32(28)
        out.addAll(listOf(0x1A, 0x2B, 0x3C, 0x4D))
        put16(1)
        put16(0)
        put32(0)
        put32(0)
        put32(28)

        // Interface description: its link type is the reason this block exists.
        put32(1)
        put32(20)
        put16(105)
        put16(0)
        put32(0xFFFF)
        put32(20)

        for ((index, frame) in frames().withIndex()) {
            val padded = ((frame.size + 3) / 4) * 4
            val total = 32 + padded
            put32(6)
            put32(total)
            put32(0) // interface
            put32(0) // timestamp, high
            put32(1_700_000_000 + index)
            put32(frame.size)
            put32(frame.size)
            out.addAll(frame.toList())
            repeat(padded - frame.size) { out.add(0) }
            put32(total)
        }
        return out.toByteArray()
    }

    private fun micRecordLine(): String {
        val message = messageTwo()
        val mic = message.copyOfRange(PmkVerifier.MIC_OFFSET, PmkVerifier.MIC_OFFSET + PmkVerifier.MIC_LENGTH)
        return "WPA*02*${Hex.encode(mic)}*${Hex.encode(authenticator)}*${Hex.encode(supplicant)}" +
            "*${Hex.encode(ssidBytes)}*${Hex.encode(authenticatorNonce)}*${Hex.encode(message)}*00"
    }

    private fun hccapxRecord(message: ByteArray): ByteArray {
        val record = ByteArray(393)
        putLe32(record, 0, 0x58504348)
        putLe32(record, 4, 4)
        record[8] = 0x02 // message pair: messages 2 and 1
        record[9] = ssidBytes.size.toByte()
        System.arraycopy(ssidBytes, 0, record, 10, ssidBytes.size)
        record[42] = Prf.KEY_DESCRIPTOR_CCMP.toByte()
        System.arraycopy(message, PmkVerifier.MIC_OFFSET, record, 43, PmkVerifier.MIC_LENGTH)
        System.arraycopy(authenticator, 0, record, 59, 6)
        System.arraycopy(authenticatorNonce, 0, record, 65, 32)
        System.arraycopy(supplicant, 0, record, 97, 6)
        System.arraycopy(supplicantNonce, 0, record, 103, 32)
        putLe16(record, 135, message.size)
        System.arraycopy(message, 0, record, 137, message.size)
        return record
    }

    private fun hccapRecord(message: ByteArray): ByteArray {
        val record = ByteArray(392)
        System.arraycopy(ssidBytes, 0, record, 0, ssidBytes.size)
        System.arraycopy(authenticator, 0, record, 36, 6)
        System.arraycopy(supplicant, 0, record, 42, 6)
        System.arraycopy(authenticatorNonce, 0, record, 48, 32)
        System.arraycopy(supplicantNonce, 0, record, 80, 32)
        System.arraycopy(message, 0, record, 112, message.size)
        putLe32(record, 368, message.size)
        putLe32(record, 372, Prf.KEY_DESCRIPTOR_CCMP)
        System.arraycopy(message, PmkVerifier.MIC_OFFSET, record, 376, PmkVerifier.MIC_LENGTH)
        return record
    }

    private fun putLe16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putLe32(target: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) target[offset + i] = ((value ushr (i * 8)) and 0xFF).toByte()
    }

    private fun write(name: String, content: String): File =
        write(name, content.toByteArray(Charsets.UTF_8))

    private fun write(name: String, content: ByteArray): File =
        File(tempFolder, name).apply { writeBytes(content) }

    private val tempFolder: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "handhack-capture-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private companion object {
        val BROADCAST = ByteArray(6) { 0xFF.toByte() }

        /** The shortest legal radiotap header: version, padding, length. */
        val RADIOTAP = byteArrayOf(0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00)

        val SNAP = byteArrayOf(
            0xAA.toByte(), 0xAA.toByte(), 0x03, 0x00, 0x00, 0x00, 0x88.toByte(), 0x8E.toByte(),
        )
    }
}