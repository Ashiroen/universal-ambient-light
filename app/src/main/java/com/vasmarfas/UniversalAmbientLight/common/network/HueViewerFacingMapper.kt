package com.vasmarfas.UniversalAmbientLight.common.network

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Remaps Entertainment channel positions for viewer-facing OmniGlow strips.
 *
 * Classic [HueEntertainmentClient.screenPoint] keeps behind-TV mapping. In
 * viewer-facing mode the capture often lines up upside-down relative to the
 * physical strip, and mid-side u samples with a wide window bleed across the
 * screen centre (magenta ceiling segments). This mapper flips v, groups
 * channels into physical strips, and pins each strip to the nearest screen
 * edge so sampling stays on the correct half of the frame.
 */
internal object HueViewerFacingMapper {

    const val EDGE_INSET = 0.08f

    fun remap(lights: List<HueEntertainmentClient.Light>): List<HueEntertainmentClient.Light> {
        if (lights.isEmpty()) return lights
        val flipped = lights.map { light ->
            HueEntertainmentClient.Light(light.id, light.u.coerceIn(0f, 1f), (1f - light.v).coerceIn(0f, 1f))
        }
        val out = flipped.toMutableList()
        val claimed = BooleanArray(flipped.size)
        val tol = axisTolerance(flipped)

        for (col in clusterBy(flipped, tol) { it.u }) {
            val du = extent(col, flipped) { it.u }
            val dv = extent(col, flipped) { it.v }
            if (col.size >= 2 && dv >= du) {
                val meanU = col.map { flipped[it].u }.average().toFloat()
                val pinU = if (meanU < 0.5f) EDGE_INSET else 1f - EDGE_INSET
                for (i in col) {
                    out[i] = HueEntertainmentClient.Light(flipped[i].id, pinU, flipped[i].v)
                    claimed[i] = true
                }
            }
        }

        for (row in clusterBy(flipped, tol) { it.v }) {
            val members = row.filter { !claimed[it] }
            if (members.size < 2) continue
            val du = extent(members, flipped) { it.u }
            val dv = extent(members, flipped) { it.v }
            if (du > dv) {
                val meanV = members.map { flipped[it].v }.average().toFloat()
                val pinV = if (meanV < 0.5f) EDGE_INSET else 1f - EDGE_INSET
                for (i in members) {
                    out[i] = HueEntertainmentClient.Light(flipped[i].id, flipped[i].u, pinV)
                    claimed[i] = true
                }
            }
        }

        for (i in flipped.indices) {
            if (claimed[i]) continue
            out[i] = pinSingleton(flipped[i])
        }
        return out
    }

    private fun pinSingleton(light: HueEntertainmentClient.Light): HueEntertainmentClient.Light {
        val dLeft = light.u
        val dRight = 1f - light.u
        val dTop = light.v
        val dBottom = 1f - light.v
        val nearest = minOf(dLeft, dRight, dTop, dBottom)
        return when (nearest) {
            dLeft -> HueEntertainmentClient.Light(light.id, EDGE_INSET, light.v)
            dRight -> HueEntertainmentClient.Light(light.id, 1f - EDGE_INSET, light.v)
            dTop -> HueEntertainmentClient.Light(light.id, light.u, EDGE_INSET)
            else -> HueEntertainmentClient.Light(light.id, light.u, 1f - EDGE_INSET)
        }
    }

    private fun extent(
        indices: List<Int>,
        lights: List<HueEntertainmentClient.Light>,
        axis: (HueEntertainmentClient.Light) -> Float,
    ): Float {
        var min = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        for (i in indices) {
            val v = axis(lights[i])
            if (v < min) min = v
            if (v > maxV) maxV = v
        }
        return maxV - min
    }

    private fun axisTolerance(lights: List<HueEntertainmentClient.Light>): Float {
        val n = lights.size
        if (n <= 1) return 0.06f
        val nn = FloatArray(n) { i ->
            var best = Float.POSITIVE_INFINITY
            for (j in 0 until n) {
                if (i == j) continue
                val d = hypot(lights[i].u - lights[j].u, lights[i].v - lights[j].v)
                if (d < best) best = d
            }
            best
        }
        return max(nn.sorted()[n / 2] * 0.6f, 0.06f)
    }

    /** Union-find groups that share nearly the same value on [axis]. */
    private fun clusterBy(
        lights: List<HueEntertainmentClient.Light>,
        tol: Float,
        axis: (HueEntertainmentClient.Light) -> Float,
    ): List<List<Int>> {
        val n = lights.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var i = x
            while (parent[i] != i) {
                parent[i] = parent[parent[i]]
                i = parent[i]
            }
            return i
        }
        fun union(a: Int, b: Int) {
            val pa = find(a)
            val pb = find(b)
            if (pa != pb) parent[pa] = pb
        }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (abs(axis(lights[i]) - axis(lights[j])) <= tol) union(i, j)
            }
        }
        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 0 until n) {
            groups.getOrPut(find(i)) { mutableListOf() }.add(i)
        }
        return groups.values.toList()
    }
}
