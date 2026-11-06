package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HueEntertainmentClientTest {

    @Test
    fun `the message starts with the protocol name`() {
        val message = HueEntertainmentClient.message(1)
        assertEquals("HueStream", String(message, 0, 9, Charsets.US_ASCII))
    }

    @Test
    fun `the protocol version is major 1 minor 0`() {
        assertArrayEquals(byteArrayOf(1, 0), HueEntertainmentClient.message(1).copyOfRange(9, 11))
    }

    @Test
    fun `every light takes nine bytes after the header`() {
        assertEquals(16 + 3 * 9, HueEntertainmentClient.message(3).size)
    }

    @Test
    fun `the light id is written big endian`() {
        val message = HueEntertainmentClient.message(1)
        HueEntertainmentClient.writeLight(message, 0, 0x0102, 0)
        assertArrayEquals(byteArrayOf(0, 1, 2), message.copyOfRange(16, 19))
    }

    @Test
    fun `full red reaches the top of the 16 bit range`() {
        val message = HueEntertainmentClient.message(1)
        HueEntertainmentClient.writeLight(message, 0, 5, 0xFF0000)
        assertArrayEquals(byteArrayOf(-1, -1, 0, 0, 0, 0), message.copyOfRange(19, 25))
    }

    @Test
    fun `the second light goes right after the first`() {
        val message = HueEntertainmentClient.message(2)
        HueEntertainmentClient.writeLight(message, 1, 7, 0)
        assertEquals(7, message[16 + 9 + 2].toInt())
    }

    @Test
    fun `the client key turns from hex into bytes`() {
        assertArrayEquals(
            byteArrayOf(0x0A, 0xFF.toByte(), 0x10),
            HueEntertainmentClient.hexToBytes("0aFF10")
        )
    }

    @Test
    fun `the v2 protocol version is major 2 minor 0`() {
        assertArrayEquals(byteArrayOf(2, 0), HueEntertainmentClient.messageV2(1).copyOfRange(9, 11))
    }

    @Test
    fun `every v2 channel takes seven bytes after the header`() {
        assertEquals(16 + 3 * 7, HueEntertainmentClient.messageV2(3).size)
    }

    @Test
    fun `a v2 channel writes its id and full red`() {
        val message = HueEntertainmentClient.messageV2(1)
        HueEntertainmentClient.writeChannel(message, 0, 4, 0xFF0000)
        assertEquals(4, message[16].toInt())
        assertArrayEquals(byteArrayOf(-1, -1, 0, 0, 0, 0), message.copyOfRange(17, 23))
    }

    @Test
    fun `a left floor channel maps to the bottom left of the frame`() {
        val point = HueEntertainmentClient.screenPoint(-1f, -1f)
        assertEquals(0f, point[0])
        assertEquals(1f, point[1])
    }

    @Test
    fun `a centre ceiling channel maps to the top centre of the frame`() {
        val point = HueEntertainmentClient.screenPoint(0f, 1f)
        assertEquals(0.5f, point[0])
        assertEquals(0f, point[1])
    }
}
