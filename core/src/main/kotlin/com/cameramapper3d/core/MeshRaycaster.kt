package com.cameramapper3d.core

import kotlin.math.abs
import kotlin.math.sqrt

/** A point picked on a mesh surface. */
data class RayHit(val distance: Float, val x: Float, val y: Float, val z: Float, val triangle: Int)

/**
 * Finds where a ray first hits a mesh (Möller–Trumbore). Triangles are bucketed into a
 * uniform grid so taps stay fast on meshes with hundreds of thousands of triangles.
 */
class MeshRaycaster(private val mesh: MeshData, targetTrianglesPerCell: Int = 32) {
    private val min = FloatArray(3)
    private val cellSize: Float
    private val dims = IntArray(3)
    private val cellStart: IntArray
    private val cellTris: IntArray

    init {
        val b = mesh.bounds()
        val eps = 1e-4f
        for (a in 0 until 3) min[a] = b[a] - eps
        val ext = FloatArray(3) { b[it + 3] - b[it] + 2 * eps }
        val volume = ext[0].toDouble() * ext[1] * ext[2]
        val cells = (mesh.triangleCount / targetTrianglesPerCell).coerceIn(1, 2_000_000)
        cellSize = maxOf(Math.cbrt(volume / cells).toFloat(), ext.max() / 256f, 1e-3f)
        for (a in 0 until 3) dims[a] = maxOf(1, (ext[a] / cellSize).toInt() + 1)
        val nCells = dims[0] * dims[1] * dims[2]

        // Two passes (count, then fill) into compact arrays.
        val counts = IntArray(nCells + 1)
        forEachTriangleCell { _, cell -> counts[cell + 1]++ }
        for (i in 1..nCells) counts[i] += counts[i - 1]
        cellStart = counts.copyOf()
        cellTris = IntArray(counts[nCells])
        val fill = counts.copyOf()
        forEachTriangleCell { tri, cell -> cellTris[fill[cell]++] = tri }
    }

    private inline fun forEachTriangleCell(action: (Int, Int) -> Unit) {
        val p = mesh.positions
        for (t in 0 until mesh.triangleCount) {
            var lo0 = Int.MAX_VALUE; var lo1 = Int.MAX_VALUE; var lo2 = Int.MAX_VALUE
            var hi0 = Int.MIN_VALUE; var hi1 = Int.MIN_VALUE; var hi2 = Int.MIN_VALUE
            for (k in 0 until 3) {
                val v = mesh.indices[t * 3 + k] * 3
                val c0 = cellCoord(p[v], 0); val c1 = cellCoord(p[v + 1], 1); val c2 = cellCoord(p[v + 2], 2)
                if (c0 < lo0) lo0 = c0; if (c0 > hi0) hi0 = c0
                if (c1 < lo1) lo1 = c1; if (c1 > hi1) hi1 = c1
                if (c2 < lo2) lo2 = c2; if (c2 > hi2) hi2 = c2
            }
            for (z in lo2..hi2) for (y in lo1..hi1) for (x in lo0..hi0) action(t, (z * dims[1] + y) * dims[0] + x)
        }
    }

    private fun cellCoord(v: Float, axis: Int): Int = ((v - min[axis]) / cellSize).toInt().coerceIn(0, dims[axis] - 1)

    /**
     * Casts a ray from ([ox], [oy], [oz]) along ([dx], [dy], [dz]) (need not be normalised).
     * Returns the nearest hit; [RayHit.distance] is in world units (metres) from the origin.
     */
    fun cast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float): RayHit? {
        if (mesh.isEmpty) return null
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-12f) return null
        val o = floatArrayOf(ox, oy, oz)
        val d = floatArrayOf(dx / len, dy / len, dz / len)

        // Clip the ray to the grid bounds.
        var tEnter = 0f
        var tExit = Float.MAX_VALUE
        for (a in 0 until 3) {
            val lo = min[a]; val hi = min[a] + dims[a] * cellSize
            if (abs(d[a]) < 1e-12f) {
                if (o[a] < lo || o[a] > hi) return null
            } else {
                var t0 = (lo - o[a]) / d[a]; var t1 = (hi - o[a]) / d[a]
                if (t0 > t1) { val tmp = t0; t0 = t1; t1 = tmp }
                if (t0 > tEnter) tEnter = t0
                if (t1 < tExit) tExit = t1
            }
        }
        if (tEnter > tExit) return null

        // 3D DDA through the grid; the first cell containing a hit closer than the cell's exit wins.
        val start = FloatArray(3) { o[it] + d[it] * (tEnter + 1e-6f) }
        val cell = IntArray(3) { cellCoord(start[it], it) }
        val step = IntArray(3) { if (d[it] > 0) 1 else -1 }
        val tMax = FloatArray(3)
        val tDelta = FloatArray(3)
        for (a in 0 until 3) {
            if (abs(d[a]) < 1e-12f) { tMax[a] = Float.MAX_VALUE; tDelta[a] = Float.MAX_VALUE; continue }
            val boundary = min[a] + (cell[a] + if (step[a] > 0) 1 else 0) * cellSize
            tMax[a] = (boundary - o[a]) / d[a]
            tDelta[a] = cellSize / abs(d[a])
        }

        var best: RayHit? = null
        while (true) {
            val ci = (cell[2] * dims[1] + cell[1]) * dims[0] + cell[0]
            for (k in cellStart[ci] until cellStart[ci + 1]) {
                val tri = cellTris[k]
                val t = intersect(tri, o, d)
                if (t > 0f && (best == null || t < best.distance)) {
                    best = RayHit(t, o[0] + d[0] * t, o[1] + d[1] * t, o[2] + d[2] * t, tri)
                }
            }
            val axis = if (tMax[0] < tMax[1]) (if (tMax[0] < tMax[2]) 0 else 2) else (if (tMax[1] < tMax[2]) 1 else 2)
            if (best != null && best.distance <= tMax[axis]) return best
            if (tMax[axis] > tExit) return best
            cell[axis] += step[axis]
            if (cell[axis] < 0 || cell[axis] >= dims[axis]) return best
            tMax[axis] += tDelta[axis]
        }
    }

    private fun intersect(tri: Int, o: FloatArray, d: FloatArray): Float {
        val p = mesh.positions
        val a = mesh.indices[tri * 3] * 3; val b = mesh.indices[tri * 3 + 1] * 3; val c = mesh.indices[tri * 3 + 2] * 3
        val e1x = p[b] - p[a]; val e1y = p[b + 1] - p[a + 1]; val e1z = p[b + 2] - p[a + 2]
        val e2x = p[c] - p[a]; val e2y = p[c + 1] - p[a + 1]; val e2z = p[c + 2] - p[a + 2]
        val px = d[1] * e2z - d[2] * e2y; val py = d[2] * e2x - d[0] * e2z; val pz = d[0] * e2y - d[1] * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (abs(det) < 1e-12f) return -1f
        val inv = 1f / det
        val tx = o[0] - p[a]; val ty = o[1] - p[a + 1]; val tz = o[2] - p[a + 2]
        val u = (tx * px + ty * py + tz * pz) * inv
        if (u < 0f || u > 1f) return -1f
        val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
        val v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv
        if (v < 0f || u + v > 1f) return -1f
        return (e2x * qx + e2y * qy + e2z * qz) * inv
    }
}
