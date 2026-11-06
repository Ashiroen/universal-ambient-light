package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HueGradientSmootherTest {

    private fun light(id: Int, u: Float, v: Float) = HueEntertainmentClient.Light(id, u, v)

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
    private fun luma(c: Int) = 0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)

    @Test
    fun `left and right TV strips stay separate`() {
        val lights = listOf(
            light(0, 0f, 0.15f),
            light(1, 0f, 0.5f),
            light(2, 0f, 0.85f),
            light(3, 1f, 0.15f),
            light(4, 1f, 0.5f),
            light(5, 1f, 0.85f),
        )
        val strips = HueGradientSmoother.strips(lights)
        assertEquals(2, strips.size)
        assertEquals(3, strips[0].size)
        assertEquals(3, strips[1].size)
        assertTrue(strips[0].all { it in 0..2 } || strips[0].all { it in 3..5 })
        assertTrue(strips[1].all { it in 0..2 } || strips[1].all { it in 3..5 })
    }

    @Test
    fun `a dark segment next to a bright neighbour is lifted but not matched`() {
        val lights = listOf(
            light(0, 0f, 0.2f),
            light(1, 0f, 0.5f),
            light(2, 0f, 0.8f),
        )
        val samples = intArrayOf(0, 0xFF0000, 0xFF0000)
        val out = HueGradientSmoother.apply(lights, samples)
        assertTrue("dark end should gain some light, was ${out[0]}", luma(out[0]) > 20f)
        assertTrue("bright neighbour should stay clearly brighter", luma(out[1]) > luma(out[0]) + 12f)
        assertTrue("bright neighbour should keep most of its red", red(out[1]) > 160)
        assertTrue("lifted segment should pick up the neighbour's red", red(out[0]) > green(out[0]) && red(out[0]) > blue(out[0]))
        assertNotEquals(out[0], out[1])
        assertNotEquals(out[1], out[2])
    }

    @Test
    fun `neighbouring hues blend instead of jumping`() {
        val lights = listOf(
            light(0, 0f, 0.2f),
            light(1, 0f, 0.5f),
            light(2, 0f, 0.8f),
        )
        val samples = intArrayOf(0xFF0000, 0x0000FF, 0x0000FF)
        val out = HueGradientSmoother.apply(lights, samples)
        assertTrue("first segment should keep more red than blue", red(out[0]) > blue(out[0]))
        assertTrue("last segment should keep more blue than red", blue(out[2]) > red(out[2]))
        assertTrue("middle should mix red into the blue side", red(out[1]) > 8)
        assertTrue("middle should mix blue into the red side", blue(out[1]) > 8)
        assertNotEquals(out[0], out[1])
        assertNotEquals(out[1], out[2])
    }

    @Test
    fun `a uniform strip stays a uniform colour`() {
        val lights = listOf(
            light(0, 0f, 0.2f),
            light(1, 0f, 0.5f),
            light(2, 0f, 0.8f),
        )
        val red = 0xCC3311
        val out = HueGradientSmoother.apply(lights, intArrayOf(red, red, red))
        assertEquals(red, out[0])
        assertEquals(red, out[1])
        assertEquals(red, out[2])
    }

    @Test
    fun `a brightness ramp stays a ramp`() {
        val lights = listOf(
            light(0, 0f, 0.2f),
            light(1, 0f, 0.5f),
            light(2, 0f, 0.8f),
        )
        val samples = intArrayOf(rgb(20, 20, 20), rgb(110, 110, 110), rgb(220, 220, 220))
        val out = HueGradientSmoother.apply(lights, samples)
        assertTrue(luma(out[0]) < luma(out[1]))
        assertTrue(luma(out[1]) < luma(out[2]))
        assertTrue(luma(out[2]) - luma(out[0]) > 80f)
    }

    @Test
    fun `a single channel is left unchanged`() {
        val lights = listOf(light(0, 0.5f, 0.5f))
        val samples = intArrayOf(0x112233)
        assertEquals(0x112233, HueGradientSmoother.apply(lights, samples)[0])
    }
}
