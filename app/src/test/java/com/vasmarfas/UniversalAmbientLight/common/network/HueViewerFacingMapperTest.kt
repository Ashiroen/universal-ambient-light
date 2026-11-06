package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HueViewerFacingMapperTest {

    private fun light(id: Int, u: Float, v: Float) = HueEntertainmentClient.Light(id, u, v)

    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    /** TL green, TR blue, BL red, BR cyan — the user's 2×2 test pattern. */
    private fun twoByTwo(): ByteArray = byteArrayOf(
        0x00, 0xFF.toByte(), 0x00,  0x00, 0x00, 0xFF.toByte(),
        0xFF.toByte(), 0x00, 0x00,  0x00, 0xFF.toByte(), 0xFF.toByte(),
    )

    /** Positions that reproduce the upside-down / pink-ceiling bug before remap. */
    private fun buggySideStrips(): List<HueEntertainmentClient.Light> = listOf(
        light(0, 0.3f, 0.1f),
        light(1, 0.3f, 0.5f),
        light(2, 0.3f, 0.9f),
        light(3, 0.65f, 0.1f),
        light(4, 0.65f, 0.5f),
        light(5, 0.65f, 0.9f),
    )

    @Test
    fun `remap flips v and pins vertical strips to the screen edges`() {
        val remapped = HueViewerFacingMapper.remap(buggySideStrips())
        assertEquals(6, remapped.size)

        // Physical top was high v (0.9) → after flip low v (top of frame)
        assertEquals(0.1f, remapped[2].v, 1e-4f)
        assertEquals(0.1f, remapped[5].v, 1e-4f)
        // Physical bottom was low v (0.1) → after flip high v (bottom of frame)
        assertEquals(0.9f, remapped[0].v, 1e-4f)
        assertEquals(0.9f, remapped[3].v, 1e-4f)

        assertEquals(HueViewerFacingMapper.EDGE_INSET, remapped[0].u, 1e-4f)
        assertEquals(HueViewerFacingMapper.EDGE_INSET, remapped[1].u, 1e-4f)
        assertEquals(HueViewerFacingMapper.EDGE_INSET, remapped[2].u, 1e-4f)
        assertEquals(1f - HueViewerFacingMapper.EDGE_INSET, remapped[3].u, 1e-4f)
        assertEquals(1f - HueViewerFacingMapper.EDGE_INSET, remapped[4].u, 1e-4f)
        assertEquals(1f - HueViewerFacingMapper.EDGE_INSET, remapped[5].u, 1e-4f)
    }

    @Test
    fun `viewer facing samples match the screen after remap`() {
        val image = twoByTwo()
        val remapped = HueViewerFacingMapper.remap(buggySideStrips())
        val colors = IntArray(remapped.size) {
            HueEntertainmentClient.sampleEdge(image, 2, 2, remapped[it].u, remapped[it].v)
        }

        // Left physical-top (index 2, was high v) → green, not red
        assertEquals(0x00FF00, colors[2])
        // Left physical-bottom (index 0, was low v) → red, not green
        assertEquals(0xFF0000, colors[0])
        // Right physical-top (index 5) → blue, not cyan and not magenta
        assertEquals(0x0000FF, colors[5])
        assertTrue("right top must not mix red", red(colors[5]) < 16)
        // Right physical-bottom (index 3) → cyan
        assertEquals(0x00FFFF, colors[3])
    }

    @Test
    fun `neighbouring segments stay on their own half of the screen`() {
        val image = twoByTwo()
        val remapped = HueViewerFacingMapper.remap(buggySideStrips())
        val colors = IntArray(remapped.size) {
            HueEntertainmentClient.sampleEdge(image, 2, 2, remapped[it].u, remapped[it].v)
        }
        // Left strip: only green/red family, never blue-dominant
        for (i in 0..2) {
            assertTrue("left segment $i spilled to the blue half: ${colors[i].toString(16)}", blue(colors[i]) <= green(colors[i]) || blue(colors[i]) <= red(colors[i]))
            assertTrue(remapped[i].u < 0.5f)
        }
        // Right strip: only blue/cyan family, never red-dominant / magenta
        for (i in 3..5) {
            assertTrue("right segment $i picked up red: ${colors[i].toString(16)}", red(colors[i]) < 16)
            assertTrue(remapped[i].u > 0.5f)
        }
    }

    @Test
    fun `smoothed viewer facing output keeps the corrected ends`() {
        val image = twoByTwo()
        val remapped = HueViewerFacingMapper.remap(buggySideStrips())
        val samples = IntArray(remapped.size) {
            HueEntertainmentClient.sampleEdge(image, 2, 2, remapped[it].u, remapped[it].v)
        }
        val out = HueGradientSmoother.apply(remapped, samples)
        assertTrue("left top should stay green-dominant", green(out[2]) > red(out[2]) && green(out[2]) > blue(out[2]))
        assertTrue("left bottom should stay red-dominant", red(out[0]) > green(out[0]) && red(out[0]) > blue(out[0]))
        assertTrue("right top should stay blue-dominant", blue(out[5]) > red(out[5]) && blue(out[5]) > green(out[5]))
        assertTrue("right bottom should stay cyan (g and b)", green(out[3]) > 80 && blue(out[3]) > 80 && red(out[3]) < 40)
    }

    @Test
    fun `a horizontal top strip is pinned to the top edge not split left-right`() {
        val lights = listOf(
            light(0, 0.2f, 0.3f),
            light(1, 0.5f, 0.3f),
            light(2, 0.8f, 0.3f),
        )
        val remapped = HueViewerFacingMapper.remap(lights)
        // flip: v' = 0.7 → bottom half → pin to bottom inset
        for (light in remapped) {
            assertEquals(1f - HueViewerFacingMapper.EDGE_INSET, light.v, 1e-4f)
        }
        assertEquals(0.2f, remapped[0].u, 1e-4f)
        assertEquals(0.5f, remapped[1].u, 1e-4f)
        assertEquals(0.8f, remapped[2].u, 1e-4f)
    }

    @Test
    fun `classic square sample still mixes across the centre without remap`() {
        // Demonstrates the pink-ceiling bug: u=0.65 with a wide window on a larger frame
        val w = 64
        val h = 64
        val data = ByteArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 3
                val left = x < w / 2
                val top = y < h / 2
                when {
                    left && top -> { data[i] = 0; data[i + 1] = -1; data[i + 2] = 0 } // green
                    !left && top -> { data[i] = 0; data[i + 1] = 0; data[i + 2] = -1 } // blue
                    left && !top -> { data[i] = -1; data[i + 1] = 0; data[i + 2] = 0 } // red
                    else -> { data[i] = 0; data[i + 1] = -1; data[i + 2] = -1 } // cyan
                }
            }
        }
        // Mid-right u with the classic square window crosses the midline → magenta
        val buggy = HueEntertainmentClient.averageRegion(
            data, w, h,
            cx = (0.55f * (w - 1)).toInt(),
            cy = (0.9f * (h - 1)).toInt(),
            halfX = maxOf(w, h) / 8,
            halfY = maxOf(w, h) / 8,
        )
        assertTrue("wide window at u=0.55 should pick up red (magenta-ish)", red(buggy) > 20)
        assertTrue(blue(buggy) > 20)

        val fixed = HueEntertainmentClient.sampleEdge(data, w, h, 1f - HueViewerFacingMapper.EDGE_INSET, 0.1f)
        assertEquals(0, red(fixed))
        assertEquals(0, green(fixed))
        assertEquals(255, blue(fixed))
    }
}
