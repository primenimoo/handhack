package com.primenimoo.handhack

import com.primenimoo.handhack.engine.handshake.HandshakeParser
import com.primenimoo.handhack.engine.handshake.Hex
import com.primenimoo.handhack.engine.handshake.Pbkdf2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Vectors are cross-checked against an independent implementation and the published
 * RFC 6070 / IEEE 802.11i suites, so a failure points at the implementation.
 */
class Pbkdf2Test {

    @Test
    fun `matches the RFC 6070 vectors`() {
        // RFC 6070 asks for 20 byte keys while WPA needs 32, so compare the prefix.
        assertEquals("0c60c80f961f0e71f3a9b524af6012062fe037a6", prefix(Pbkdf2.derive("password", "salt", 1), 20))
        assertEquals("ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957", prefix(Pbkdf2.derive("password", "salt", 2), 20))
        assertEquals("4b007901b765489abead49d926f721d065a429c1", prefix(Pbkdf2.derive("password", "salt", 4096), 20))
    }

    private fun prefix(bytes: ByteArray, count: Int): String = Hex.encode(bytes).substring(0, count * 2)

    @Test
    fun `matches the IEEE 802_11i pairwise master key vector`() {
        val pmk = Pbkdf2.derive("password", "IEEE", 4096)
        assertEquals("f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e", Hex.encode(pmk))
        assertEquals(32, pmk.size)
    }

    @Test
    fun `matches the captured pmkid for the reference network`() {
        val pmkid = Pbkdf2.derive("sunflower2024", "HandHackTest", 4096)
        assertEquals("540c37d11905811790137c56ff17551c30c7340bc4c06a9c1fd41f016d594f06", Hex.encode(pmkid))
    }

    @Test
    fun `derives keys longer than one hash block across block boundaries`() {
        // RFC 6070 asks for 25 bytes, so three SHA1 blocks must be produced and the
        // third one truncated. This catches off-by-one block index errors.
        val derived = Pbkdf2.derive("passwordPASSWORDpassword", "saltSALTsaltSALTsaltSALTsaltSALTsalt", 4096)
        assertEquals(32, derived.size)
        assertEquals("3d2eec4fe41c849b80c8d83662c0e44a8b291a964cf2f07038", Hex.encode(derived).substring(0, 50))
    }

    @Test
    fun `encodes non ascii passphrases as utf8`() {
        val password = "пароль"
        val ssid = "МояСеть"
        val fromString = Pbkdf2.derive(password, ssid, 4096)
        val fromBytes = Pbkdf2.derive(password.toByteArray(Charsets.UTF_8), ssid.toByteArray(Charsets.UTF_8), 4096)
        assertEquals(Hex.encode(fromBytes), Hex.encode(fromString))
        assertTrue(fromString.any { it.toInt() != 0 })
    }

    @Test
    fun `accepts only the correct passphrase`() {
        val ssid = "HandHackTest"
        val pmkid = Pbkdf2.derive("sunflower2024", ssid)
        assertTrue(Pbkdf2.matches("sunflower2024", ssid, pmkid))
        assertFalse(Pbkdf2.matches("sunflower2025", ssid, pmkid))
        assertFalse(Pbkdf2.matches("sunflowe2024", ssid, pmkid))
        assertFalse(Pbkdf2.matches("sunflower2024 ", ssid, pmkid))
    }

    @Test
    fun `constant time compare rejects wrong lengths`() {
        assertTrue(Pbkdf2.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(Pbkdf2.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(Pbkdf2.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
    }

    @Test
    fun `hex round trips and rejects malformed input`() {
        val bytes = ByteArray(32) { (it * 7).toByte() }
        assertEquals(bytes.toList(), Hex.decode(Hex.encode(bytes))?.toList())
        assertNull(Hex.decode("zz"))
        assertNull(Hex.decode("abc"))
        assertNull(Hex.decode(""))
    }

    @Test
    fun `parses wifite records with inline ssid separators`() {
        val hex = Hex.encode(Pbkdf2.derive("sunflower2024", "HandHackTest"))
        val file = temp(
            "HomeNet:WPA*01$hex",
            "",
            "OtherNet\tWPA*01$hex",
            "# a comment",
            "OfficeNet WPA*01$hex",
        )
        val parsed = HandshakeParser.parse(file)
        assertEquals(3, parsed.size)
        assertEquals("HomeNet", parsed[0].ssid)
        assertEquals("OtherNet", parsed[1].ssid)
        assertEquals("OfficeNet", parsed[2].ssid)
        assertEquals(hex, parsed[0].verifierHex)
    }

    @Test
    fun `falls back to the ssid written on the previous line`() {
        val hex = Hex.encode(Pbkdf2.derive("sunflower2024", "HandHackTest"))
        val file = temp("HandHackTest", "WPA*01$hex")
        val parsed = HandshakeParser.parse(file)
        assertEquals(1, parsed.size)
        assertEquals("HandHackTest", parsed[0].ssid)
    }

    @Test
    fun `drops records without an ssid because they cannot be verified`() {
        val hex = Hex.encode(Pbkdf2.derive("sunflower2024", "HandHackTest"))
        assertTrue(HandshakeParser.parse(temp("WPA*01$hex")).isEmpty())
    }

    @Test
    fun `drops malformed hash payloads`() {
        assertTrue(HandshakeParser.parse(temp("HomeNet:WPA*01deadbeef")).isEmpty())
        assertTrue(HandshakeParser.parse(temp("HomeNet:WPA*01nothexatall")).isEmpty())
    }

    @Test
    fun `detects handshake files and ignores plain text`() {
        val hex = Hex.encode(Pbkdf2.derive("sunflower2024", "HandHackTest"))
        assertTrue(HandshakeParser.isHandshakeFile(temp("HandHackTest:WPA*01$hex")))
        assertFalse(HandshakeParser.isHandshakeFile(temp("just some text", "and more")))
    }

    @Test
    fun `passphrase length rules follow the standard`() {
        val target = HandshakeParser.parse(
            temp("Net:" + Hex.encode(Pbkdf2.derive("password", "Net")).let { "WPA*01$it" }),
        ).single()
        assertFalse(target.accepts("short"))       // 5 chars, below the WPA minimum
        assertTrue(target.accepts("password"))     // 8 chars, the minimum
        assertTrue(target.accepts("a".repeat(63))) // 63 chars, the maximum
        assertFalse(target.accepts("a".repeat(64)))
    }

    private fun temp(vararg lines: String): File {
        val file = File.createTempFile("handshake", ".txt")
        file.deleteOnExit()
        file.writeText(lines.joinToString("\n"))
        return file
    }
}