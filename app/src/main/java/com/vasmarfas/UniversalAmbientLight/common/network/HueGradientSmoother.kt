package com.vasmarfas.UniversalAmbientLight.common.network

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns independently sampled Hue Entertainment channels into a continuous
 * ramp along each physical strip. Built for viewer-facing gradient lights
 * (OmniGlow): neighbouring segments keep following the screen, but colour
 * and brightness no longer jump from one section to the next, and the strip
 * does not collapse to a single colour.
 */
internal object HueGradientSmoother {

    private const val SIGMA = 1.05f
    private const val MIN_LINK = 0.12f
    private const val LINK_SCALE = 2.2f
    private const val GAP_SCALE = 1.8f
    private const val CHROMA_FLOOR = 16f
    private const val Y_R = 0.2126f
    private const val Y_G = 0.7152f
    private const val Y_B = 0.0722f

    fun apply(lights: List<HueEntertainmentClient.Light>, samples: IntArray): IntArray {
        require(samples.size == lights.size)
        if (lights.size <= 1) return samples.copyOf()
        val out = samples.copyOf()
        for (strip in strips(lights)) {
            if (strip.size >= 2) smoothStrip(out, strip)
        }
        return out
    }

    /** Groups nearby channels into strips and walks each along its physical path. */
    internal fun strips(lights: List<HueEntertainmentClient.Light>): List<List<Int>> {
        val n = lights.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(listOf(0))

        val nn = FloatArray(n) { i ->
            var best = Float.POSITIVE_INFINITY
            for (j in 0 until n) {
                if (i == j) continue
                val d = distance(lights[i], lights[j])
                if (d < best) best = d
            }
            best
        }
        val sortedNn = nn.sorted()
        val medianNn = sortedNn[n / 2]
        val link = max(medianNn * LINK_SCALE, MIN_LINK)

        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun union(a: Int, b: Int) {
            val pa = find(a)
            val pb = find(b)
            if (pa != pb) parent[pa] = pb
        }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (distance(lights[i], lights[j]) <= link) union(i, j)
            }
        }

        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 0 until n) {
            groups.getOrPut(find(i)) { mutableListOf() }.add(i)
        }
        val result = ArrayList<List<Int>>()
        for (group in groups.values) {
            result += splitPath(orderPath(group, lights), lights, link)
        }
        return result
    }

    private fun orderPath(group: List<Int>, lights: List<HueEntertainmentClient.Light>): List<Int> {
        if (group.size <= 2) return group.toList()
        var cu = 0f
        var cv = 0f
        for (i in group) {
            cu += lights[i].u
            cv += lights[i].v
        }
        cu /= group.size
        cv /= group.size
        val start = group.maxBy { i ->
            val du = lights[i].u - cu
            val dv = lights[i].v - cv
            du * du + dv * dv
        }
        val path = ArrayList<Int>(group.size)
        val remaining = group.toMutableSet()
        path.add(start)
        remaining.remove(start)
        while (remaining.isNotEmpty()) {
            val last = lights[path.last()]
            val next = remaining.minBy { distance(last, lights[it]) }
            path.add(next)
            remaining.remove(next)
        }
        return path
    }

    private fun splitPath(
        path: List<Int>,
        lights: List<HueEntertainmentClient.Light>,
        link: Float,
    ): List<List<Int>> {
        if (path.size <= 1) return listOf(path)
        val edges = FloatArray(path.size - 1) { i ->
            distance(lights[path[i]], lights[path[i + 1]])
        }
        val sorted = edges.sorted()
        val medianEdge = sorted[sorted.size / 2]
        val gap = max(medianEdge * GAP_SCALE, link)
        val parts = ArrayList<List<Int>>()
        var start = 0
        for (i in edges.indices) {
            if (edges[i] > gap) {
                parts += path.subList(start, i + 1).toList()
                start = i + 1
            }
        }
        parts += path.subList(start, path.size).toList()
        return parts
    }

    private fun smoothStrip(colors: IntArray, indices: List<Int>) {
        val n = indices.size
        if (n >= 2) {
            val first = colors[indices[0]]
            if (indices.all { colors[it] == first }) return
        }
        val y = FloatArray(n)
        val cr = FloatArray(n)
        val cg = FloatArray(n)
        val chromaWeight = FloatArray(n)
        for (i in 0 until n) {
            val rgb = colors[indices[i]]
            val r = ((rgb shr 16) and 0xFF).toFloat()
            val g = ((rgb shr 8) and 0xFF).toFloat()
            val b = (rgb and 0xFF).toFloat()
            y[i] = Y_R * r + Y_G * g + Y_B * b
            val sum = r + g + b
            if (sum > 0.5f) {
                cr[i] = r / sum
                cg[i] = g / sum
            } else {
                cr[i] = 1f / 3f
                cg[i] = 1f / 3f
            }
            chromaWeight[i] = ((sum - CHROMA_FLOOR) / CHROMA_FLOOR).coerceIn(0f, 1f)
        }

        val radius = 3
        val twoSigmaSq = 2f * SIGMA * SIGMA
        val smoothY = FloatArray(n)
        val smoothCr = FloatArray(n)
        val smoothCg = FloatArray(n)
        for (i in 0 until n) {
            var wY = 0f
            var sumY = 0f
            var wC = 0f
            var sumCr = 0f
            var sumCg = 0f
            val from = (i - radius).coerceAtLeast(0)
            val to = (i + radius).coerceAtMost(n - 1)
            for (j in from..to) {
                val d = (j - i).toFloat()
                val w = exp(-(d * d) / twoSigmaSq)
                wY += w
                sumY += w * y[j]
                val wc = w * (0.15f + 0.85f * chromaWeight[j])
                wC += wc
                sumCr += wc * cr[j]
                sumCg += wc * cg[j]
            }
            smoothY[i] = if (wY > 0f) sumY / wY else y[i]
            smoothCr[i] = if (wC > 0f) sumCr / wC else cr[i]
            smoothCg[i] = if (wC > 0f) sumCg / wC else cg[i]
        }

        for (i in 0 until n) {
            // Fill dark gaps up to the local average; only pull peaks halfway down
            // so the strip stays a ramp instead of flattening to one brightness.
            val blended = 0.5f * y[i] + 0.5f * smoothY[i]
            val sy = max(blended, smoothY[i])
            val scr = smoothCr[i]
            val scg = smoothCg[i]
            val scb = (1f - scr - scg).coerceAtLeast(0f)
            val denom = Y_R * scr + Y_G * scg + Y_B * scb
            val scale = if (denom > 1e-4f) sy / denom else sy
            val r = (scr * scale).roundToInt().coerceIn(0, 255)
            val g = (scg * scale).roundToInt().coerceIn(0, 255)
            val b = (scb * scale).roundToInt().coerceIn(0, 255)
            colors[indices[i]] = (r shl 16) or (g shl 8) or b
        }
    }

    private fun distance(a: HueEntertainmentClient.Light, b: HueEntertainmentClient.Light): Float {
        return hypot(a.u - b.u, a.v - b.v)
    }
}
