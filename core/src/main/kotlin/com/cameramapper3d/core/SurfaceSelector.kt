package com.cameramapper3d.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lets the user pick surfaces (a wall, a floor) by tapping them, then flattens the picked surfaces.
 *
 * A selection grows from the tapped triangle across connected triangles that face roughly the same
 * way, so it follows a wall that the scan has bent or bowed but stops at room corners. Optionally it
 * also stops where the colour changes, to keep a wall apart from a cupboard or picture against it.
 *
 * Flattening fits one plane per selection (exactly upright for walls, exactly level for floors and
 * ceilings, since world +Y is up) and moves every selected vertex onto it, however far it was.
 * Vertices shared by two or three selections go onto their common edge or corner.
 */
class SurfaceSelector(private val mesh: MeshData) {

    data class Options(
        /** Neighbouring triangles may differ by up to this angle (follows gentle curves). */
        val neighbourAngleDegrees: Float = 20f,
        /** No triangle may face more than this far from the selection's average direction (stops at corners). */
        val maxAngleDegrees: Float = 35f,
        /** Only grow into triangles of similar colour. */
        val matchColour: Boolean = false,
        /** RGB distance (0..441) allowed from the selection's average colour when [matchColour] is on. */
        val colourTolerance: Float = 45f,
    )

    // Smoothed per-triangle normal (mean of its vertex normals), colour and area.
    private val tn = FloatArray(mesh.triangleCount * 3)
    private val tc = FloatArray(mesh.triangleCount * 3)
    private val ta = FloatArray(mesh.triangleCount)

    // Triangles around each vertex (compressed adjacency).
    private val vtStart = IntArray(mesh.vertexCount + 1)
    private val vtList = IntArray(mesh.indices.size)

    init {
        val p = mesh.positions; val n = mesh.normals; val c = mesh.colors
        for (t in 0 until mesh.triangleCount) {
            var nx = 0f; var ny = 0f; var nz = 0f; var r = 0f; var g = 0f; var b = 0f
            for (k in 0 until 3) {
                val v = mesh.indices[t * 3 + k]
                nx += n[v * 3]; ny += n[v * 3 + 1]; nz += n[v * 3 + 2]
                r += c[v * 3].toInt() and 0xFF; g += c[v * 3 + 1].toInt() and 0xFF; b += c[v * 3 + 2].toInt() and 0xFF
            }
            val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-9f } ?: 1f
            tn[t * 3] = nx / l; tn[t * 3 + 1] = ny / l; tn[t * 3 + 2] = nz / l
            tc[t * 3] = r / 3; tc[t * 3 + 1] = g / 3; tc[t * 3 + 2] = b / 3
            val a = mesh.indices[t * 3] * 3; val bb = mesh.indices[t * 3 + 1] * 3; val cc = mesh.indices[t * 3 + 2] * 3
            val ux = p[bb] - p[a]; val uy = p[bb + 1] - p[a + 1]; val uz = p[bb + 2] - p[a + 2]
            val vx = p[cc] - p[a]; val vy = p[cc + 1] - p[a + 1]; val vz = p[cc + 2] - p[a + 2]
            val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
            ta[t] = 0.5f * sqrt(cx * cx + cy * cy + cz * cz)
        }
        for (v in mesh.indices) vtStart[v + 1]++
        for (i in 1..mesh.vertexCount) vtStart[i] += vtStart[i - 1]
        val fill = vtStart.copyOf()
        for (t in 0 until mesh.triangleCount) for (k in 0 until 3) {
            val v = mesh.indices[t * 3 + k]
            vtList[fill[v]++] = t
        }
    }

    /** Returns the triangles of the surface containing [seed], in no particular order. */
    fun select(seed: Int, options: Options = Options()): IntArray {
        require(seed in 0 until mesh.triangleCount)
        val neighbourCos = cos(Math.toRadians(options.neighbourAngleDegrees.toDouble())).toFloat()
        val maxCos = cos(Math.toRadians(options.maxAngleDegrees.toDouble())).toFloat()
        val colourTol2 = options.colourTolerance * options.colourTolerance

        val inRegion = BooleanArray(mesh.triangleCount)
        val queue = IntList(1024)
        val out = IntList(1024)
        // Running area-weighted averages of the selection's direction and colour.
        var mx = 0f; var my = 0f; var mz = 0f; var mr = 0f; var mg = 0f; var mb = 0f; var area = 0f

        fun add(t: Int) {
            inRegion[t] = true
            queue.push(t); out.push(t)
            val w = ta[t] + 1e-9f
            mx += tn[t * 3] * w; my += tn[t * 3 + 1] * w; mz += tn[t * 3 + 2] * w
            mr += tc[t * 3] * w; mg += tc[t * 3 + 1] * w; mb += tc[t * 3 + 2] * w
            area += w
        }
        add(seed)
        var head = 0
        while (head < queue.size) {
            val u = queue[head++]
            val ml = sqrt(mx * mx + my * my + mz * mz).takeIf { it > 1e-12f } ?: 1f
            for (k in 0 until 3) {
                val v = mesh.indices[u * 3 + k]
                for (j in vtStart[v] until vtStart[v + 1]) {
                    val t = vtList[j]
                    if (inRegion[t]) continue
                    val local = tn[t * 3] * tn[u * 3] + tn[t * 3 + 1] * tn[u * 3 + 1] + tn[t * 3 + 2] * tn[u * 3 + 2]
                    if (local < neighbourCos) continue
                    val global = (tn[t * 3] * mx + tn[t * 3 + 1] * my + tn[t * 3 + 2] * mz) / ml
                    if (global < maxCos) continue
                    if (options.matchColour) {
                        val dr = tc[t * 3] - mr / area; val dg = tc[t * 3 + 1] - mg / area; val db = tc[t * 3 + 2] - mb / area
                        if (dr * dr + dg * dg + db * db > colourTol2) continue
                    }
                    add(t)
                }
            }
        }
        return out.toArray()
    }

    /** The surface's plane, n·p = d. Walls are exactly upright, floors and ceilings exactly level. */
    data class Plane(val nx: Float, val ny: Float, val nz: Float, val d: Float) {
        val isWall: Boolean get() = ny == 0f
        val isLevel: Boolean get() = nx == 0f && nz == 0f
        fun distance(x: Float, y: Float, z: Float) = nx * x + ny * y + nz * z - d
    }

    class Result(val mesh: MeshData, val planes: List<Plane>, val movedVertices: Int)

    /**
     * Flattens each selection onto its own plane.
     * @param squareCorners turn walls that are within 10° of square to the first selected wall to exactly 90°.
     */
    fun flatten(selections: List<IntArray>, squareCorners: Boolean): Result {
        val planes = ArrayList<Plane>()
        var baseWallAngle = Double.NaN
        for (sel in selections) {
            var plane = fitPlane(sel)
            if (plane.isWall && squareCorners) {
                val angle = atan2(plane.nz.toDouble(), plane.nx.toDouble())
                if (baseWallAngle.isNaN()) {
                    baseWallAngle = angle
                } else {
                    val snapped = baseWallAngle + round((angle - baseWallAngle) / (PI / 2)) * PI / 2
                    if (abs(angle - snapped) <= 10 * PI / 180) {
                        // Turn the wall about its middle.
                        val (cx, _, cz) = centroid(sel)
                        val nx = cos(snapped).toFloat(); val nz = sin(snapped).toFloat()
                        plane = Plane(nx, 0f, nz, nx * cx + nz * cz)
                    }
                }
            }
            planes += plane
        }

        // Which selections each vertex belongs to.
        val member = HashMap<Int, MutableList<Int>>()
        selections.forEachIndexed { s, sel ->
            for (t in sel) for (k in 0 until 3) {
                val list = member.getOrPut(mesh.indices[t * 3 + k]) { ArrayList(2) }
                if (list.lastOrNull() != s) list += s
            }
        }
        val pos = mesh.positions.copyOf()
        for ((v, sels) in member) {
            val ps = ArrayList<Plane>(3)
            for (s in sels) {
                val p = planes[s]
                if (ps.size < 3 && ps.none { q -> abs(p.nx * q.nx + p.ny * q.ny + p.nz * q.nz) > 0.9f }) ps += p
            }
            var x = pos[v * 3]; var y = pos[v * 3 + 1]; var z = pos[v * 3 + 2]
            repeat(if (ps.size == 1) 1 else 10) {
                for (p in ps) { val d = p.distance(x, y, z); x -= d * p.nx; y -= d * p.ny; z -= d * p.nz }
            }
            pos[v * 3] = x; pos[v * 3 + 1] = y; pos[v * 3 + 2] = z
        }
        return Result(WallStraightener.rebuild(mesh, pos), planes, member.size)
    }

    /** A mesh of just the given triangles, all in one colour, for highlighting a selection on screen. */
    fun highlight(selection: IntArray, rgb: Int): MeshData {
        val remap = HashMap<Int, Int>()
        val pos = FloatList(); val nrm = FloatList(); val col = ByteList()
        val idx = IntArray(selection.size * 3)
        for ((i, t) in selection.withIndex()) for (k in 0 until 3) {
            val v = mesh.indices[t * 3 + k]
            idx[i * 3 + k] = remap.getOrPut(v) {
                for (a in 0 until 3) { pos.push(mesh.positions[v * 3 + a]); nrm.push(mesh.normals[v * 3 + a]) }
                col.push((rgb shr 16).toByte()); col.push((rgb shr 8).toByte()); col.push(rgb.toByte())
                remap.size
            }
        }
        return MeshData(pos.toArray(), nrm.toArray(), col.toArray(), idx)
    }

    private fun centroid(sel: IntArray): Triple<Float, Float, Float> {
        var x = 0.0; var y = 0.0; var z = 0.0; var a = 0.0
        val p = mesh.positions
        for (t in sel) {
            val w = ta[t] + 1e-9
            for (k in 0 until 3) {
                val v = mesh.indices[t * 3 + k] * 3
                x += p[v] * w / 3; y += p[v + 1] * w / 3; z += p[v + 2] * w / 3
            }
            a += w
        }
        return Triple((x / a).toFloat(), (y / a).toFloat(), (z / a).toFloat())
    }

    /** Least-squares plane through the selection, made exactly upright or level when it nearly is. */
    private fun fitPlane(sel: IntArray): Plane {
        var mx = 0f; var my = 0f; var mz = 0f
        for (t in sel) { mx += tn[t * 3] * ta[t]; my += tn[t * 3 + 1] * ta[t]; mz += tn[t * 3 + 2] * ta[t] }
        val ml = sqrt(mx * mx + my * my + mz * mz).takeIf { it > 1e-12f } ?: 1f
        mx /= ml; my /= ml; mz /= ml
        val (cx, cy, cz) = centroid(sel)

        // Area-weighted covariance of the triangle corners about the centroid.
        val cov = DoubleArray(9)
        val p = mesh.positions
        for (t in sel) {
            val w = ta[t] + 1e-9
            for (k in 0 until 3) {
                val v = mesh.indices[t * 3 + k] * 3
                val d = doubleArrayOf((p[v] - cx).toDouble(), (p[v + 1] - cy).toDouble(), (p[v + 2] - cz).toDouble())
                for (i in 0 until 3) for (j in 0 until 3) cov[i * 3 + j] += w * d[i] * d[j]
            }
        }
        return when {
            abs(my) > 0.9f -> Plane(0f, 1f, 0f, cy) // floor, ceiling, table top
            abs(my) < 0.4f -> {
                // Wall: best-fitting upright plane = smallest-spread direction in the horizontal plane.
                val xx = cov[0]; val xz = cov[2]; val zz = cov[8]
                val theta = 0.5 * atan2(2 * xz, xx - zz) // direction of largest spread
                var nx = -sin(theta).toFloat(); var nz = cos(theta).toFloat()
                if (nx * mx + nz * mz < 0) { nx = -nx; nz = -nz }
                Plane(nx, 0f, nz, nx * cx + nz * cz)
            }
            else -> {
                // Sloped surface (e.g. an attic ceiling): general least-squares plane.
                var (nx, ny, nz) = smallestEigenvector(cov)
                if (nx * mx + ny * my + nz * mz < 0) { nx = -nx; ny = -ny; nz = -nz }
                Plane(nx, ny, nz, nx * cx + ny * cy + nz * cz)
            }
        }
    }

    /**
     * Eigenvector of the smallest eigenvalue of a symmetric positive semi-definite 3×3 matrix:
     * power iteration on (trace·I − A), whose largest eigenvector is A's smallest.
     */
    private fun smallestEigenvector(m: DoubleArray): Triple<Float, Float, Float> {
        val tr = m[0] + m[4] + m[8]
        val b = DoubleArray(9) { i -> (if (i % 4 == 0) tr else 0.0) - m[i] }
        var x = 0.577; var y = 0.577; var z = 0.577
        repeat(200) {
            val nx = b[0] * x + b[1] * y + b[2] * z
            val ny = b[3] * x + b[4] * y + b[5] * z
            val nz = b[6] * x + b[7] * y + b[8] * z
            val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-300 } ?: return Triple(0f, 1f, 0f)
            x = nx / l; y = ny / l; z = nz / l
        }
        return Triple(x.toFloat(), y.toFloat(), z.toFloat())
    }
}
