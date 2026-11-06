package com.vasmarfas.UniversalAmbientLight.common.network

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class HueClientTest {

    @Test
    fun `white lands next to the D65 white point`() {
        val xy = HueClient.rgbToXy(255, 255, 255)
        assertEquals(0.3227f, xy[0], 0.001f)
    }

    @Test
    fun `white keeps the y of the white point`() {
        val xy = HueClient.rgbToXy(255, 255, 255)
        assertEquals(0.3290f, xy[1], 0.001f)
    }

    @Test
    fun `black does not divide by zero`() {
        assertArrayEquals(floatArrayOf(0.3127f, 0.329f, 0f), HueClient.rgbToXy(0, 0, 0), 0f)
    }

    @Test
    fun `a point outside the gamut moves to the nearest edge`() {
        // Прямой угол в нуле: ближайшая к (1, 1) точка гипотенузы - её середина
        val triangle = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), HueClient.clampToGamut(1f, 1f, triangle), 0.0001f)
    }

    @Test
    fun `a point inside the gamut stays as it is`() {
        val triangle = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        assertArrayEquals(floatArrayOf(0.2f, 0.3f), HueClient.clampToGamut(0.2f, 0.3f, triangle), 0f)
    }

    @Test
    fun `pair follows an HTTP 301 and keeps the POST body`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var postedOnTarget = false
        server.createContext("/api") { exchange ->
            val location = "http://127.0.0.1:${server.address.port}/paired"
            exchange.responseHeaders.add("Location", location)
            exchange.sendResponseHeaders(301, -1)
            exchange.close()
        }
        server.createContext("/paired") { exchange ->
            val posted = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            postedOnTarget = posted.contains("generateclientkey")
            val reply = """[{"success":{"username":"tester","clientkey":"abc"}}]"""
            val bytes = reply.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val creds = HueClient.pair("127.0.0.1:${server.address.port}", "phone")
            assertEquals("tester", creds.username)
            assertEquals("abc", creds.clientKey)
            assertTrue(postedOnTarget)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `toHttps rewrites a plain HTTP bridge URL`() {
        assertEquals(
            "https://192.168.1.2/api",
            HueClient.toHttps("http://192.168.1.2/api"),
        )
    }
}
