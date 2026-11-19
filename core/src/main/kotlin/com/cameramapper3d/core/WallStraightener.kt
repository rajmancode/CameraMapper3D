package com.cameramapper3d.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Optional post-processing that makes the flat parts of a scan really flat.
 *
 * World +Y is up (ARCore aligns it with gravity), so:
 *  - horizontal surfaces (floors, ceilings, table tops) become exactly level planes;
 *  - walls become exactly vertical planes, optionally at exact right angles to each other.
 *
 * Planes are found from the mesh itself (area-weighted histograms of surface orientation and
 * position), then every vertex lying within [Options.snapDistance] of a plane, and facing the same
 * way, is moved onto it. Vertices near two or three planes are moved onto their shared edge or
 * corner, which makes room edges sharp. Everything else (furniture, clutter) is left untouched.
 */
object WallStraightener {

    data class Options(
        /** How far (metres) a vertex may be moved onto a plane. */
        val snapDistance: Float = 0.04f,
        /** Snap wall directions that are within [squareToleranceDegrees] of 90° apart to exactly 90°. */
        val squareCorners: Boolean = true,
        val squareToleranceDegrees: Float = 5f,
        /** Ignore flat patches smaller than this (m²). */
        val minPlaneArea: Float = 0.25f,
    )

    /**
     * A plane n·p = d with unit normal n that vertices are moved onto. Vertices are chosen by their
     * distance to the plane as measured ([mnx], [mnz], [md]); this differs from the target only when
     * a wall was turned to make a corner square, so the whole wall turns, not just its middle.
     */
    data class Plane(
        val nx: Float, val ny: Float, val nz: Float, val d: Float, val area: Float,
        val mnx: Float = nx, val mny: Float = ny, val mnz: Float = nz, val md: Float = d,
    ) {
        val isHorizontal: Boolean get() = abs(ny) > 0.99f
        fun distance(x: Float, y: Float, z: Float) = nx * x + ny * y + nz * z - d
        fun measuredDistance(x: Float, y: Float, z: Float) = mnx * x + mny * y + mnz * z - md
    }

    class Result(val mesh: MeshData, val planes: List<Plane>, val movedVertices: Int) {
        val wallCount: Int get() = planes.count { !it.isHorizontal }
        val levelCount: Int get() = planes.count { it.isHorizontal }
    }

    private const val VERTICAL_COS = 0.26f // |ny| below this (≈ 15° from vertical): a wall candidate
    private const val HORIZONTAL_COS = 0.966f // |ny| above this (≈ 15° from level): a floor candidate
    private const val WALL_ANGLE_TOL = 10.0 * PI / 180.0
    private const val MATCH_NORMAL_COS = 0.5f // vertex normal within 60° of the plane normal

    /**
     * Direction along the scan's largest wall (radians in the horizontal plane, from +X towards +Z),
     * or null if no wall is found. Used as 0° for angle-snapped measuring.
     */
    fun mainWallHeading(mesh: MeshData): Double? {
        if (mesh.isEmpty) return null
        val biggest = findWallPlanes(TriangleInfo(mesh), Options(squareCorners = false)).maxByOrNull { it.area } ?: return null
        return atan2(biggest.nz.toDouble(), biggest.nx.toDouble()) + PI / 2
    }

    fun process(mesh: MeshData, options: Options = Options()): Result {
        if (mesh.isEmpty) return Result(mesh, emptyList(), 0)
        val tri = TriangleInfo(mesh)
        val planes = ArrayList<Plane>()
        planes += findLevelPlanes(tri, options)
        planes += findWallPlanes(tri, options)
        if (planes.isEmpty()) return Result(mesh, planes, 0)

        val pos = mesh.positions.copyOf()
        val n = mesh.normals
        val tol = options.snapDistance
        var moved = 0
        val matched = ArrayList<Plane>(4)
        for (v in 0 until mesh.vertexCount) {
            val x = pos[v * 3]; val y = pos[v * 3 + 1]; val z = pos[v * 3 + 2]
            matched.clear()
            for (p in planes) {
                if (abs(p.measuredDistance(x, y, z)) > tol) continue
                val facing = abs(n[v * 3] * p.mnx + n[v * 3 + 1] * p.mny + n[v * 3 + 2] * p.mnz)
                if (facing >= MATCH_NORMAL_COS) matched += p
            }
            if (matched.isEmpty()) continue
            matched.sortBy { abs(it.measuredDistance(x, y, z)) }
            // Keep the closest plane of each orientation (e.g. not both a floor and a rug on it), max 3.
            var i = 0
            while (i < matched.size) {
                val p = matched[i]
                if (matched.subList(0, i).any { q -> abs(p.nx * q.nx + p.ny * q.ny + p.nz * q.nz) > 0.9f } || i >= 3) {
                    matched.removeAt(i)
                } else {
                    i++
                }
            }
            // Alternating projections converge onto the planes' common edge/corner.
            var px = x; var py = y; var pz = z
            repeat(if (matched.size == 1) 1 else 8) {
                for (p in matched) {
                    val dd = p.distance(px, py, pz)
                    px -= dd * p.nx; py -= dd * p.ny; pz -= dd * p.nz
                }
            }
            pos[v * 3] = px; pos[v * 3 + 1] = py; pos[v * 3 + 2] = pz
            moved++
        }
        return Result(rebuild(mesh, pos), planes, moved)
    }

    /** Floors, ceilings and other level surfaces: peaks in the area-weighted histogram of height. */
    private fun findLevelPlanes(t: TriangleInfo, o: Options): List<Plane> {
        val planes = ArrayList<Plane>()
        for (up in listOf(true, false)) { // a floor and the underside of a shelf at the same height are different
            val members = (0 until t.count).filter { abs(t.ny[it]) >= HORIZONTAL_COS && (t.ny[it] > 0) == up }
            for (peak in peaks(members, { t.cy[it] }, t.area, o)) planes += Plane(0f, 1f, 0f, peak.position, peak.area)
        }
        return planes
    }

    /** Walls: dominant horizontal directions, then the positions of walls along each direction. */
    private fun findWallPlanes(t: TriangleInfo, o: Options): List<Plane> {
        // 1) Wall directions, folded so opposite-facing walls share a direction (angle in [0, π)).
        val bins = 180
        val hist = DoubleArray(bins)
        val angle = DoubleArray(t.count)
        for (i in 0 until t.count) {
            angle[i] = Double.NaN
            if (abs(t.ny[i]) > VERTICAL_COS || t.area[i] == 0f) continue
            var a = atan2(t.nz[i].toDouble(), t.nx[i].toDouble())
            if (a < 0) a += PI
            if (a >= PI) a -= PI
            angle[i] = a
            hist[(a / PI * bins).toInt().coerceIn(0, bins - 1)] += t.area[i].toDouble()
        }
        val smooth = DoubleArray(bins) { b -> (-2..2).sumOf { hist[(b + it + bins) % bins] } }
        val dirs = ArrayList<Pair<Double, Double>>() // (angle, area)
        val used = BooleanArray(bins)
        while (true) {
            var best = -1
            for (b in 0 until bins) if (!used[b] && (best < 0 || smooth[b] > smooth[best])) best = b
            if (best < 0 || smooth[best] < o.minPlaneArea) break
            for (k in -10..10) used[(best + k + bins) % bins] = true
            // Only real peaks: the sloping sides of a noisy wall's peak are not walls of their own.
            if ((1..10).any { k -> smooth[(best + k) % bins] > smooth[best] || smooth[(best - k + bins) % bins] > smooth[best] }) continue
            // Refine to the area-weighted mean direction of nearby wall triangles (doubled angles handle the fold).
            val centre = (best + 0.5) / bins * PI
            var sx = 0.0; var sz = 0.0; var area = 0.0
            for (i in 0 until t.count) {
                val a = angle[i]
                if (a.isNaN() || foldedDiff(a, centre) > WALL_ANGLE_TOL) continue
                sx += cos(2 * a) * t.area[i]; sz += sin(2 * a) * t.area[i]; area += t.area[i]
            }
            var mean = atan2(sz, sx) / 2
            if (mean < 0) mean += PI
            dirs += mean to area
        }
        if (dirs.isEmpty()) return emptyList()

        // 2) Individual walls along each rough direction. The two faces of a thin wall face opposite
        // ways, so they are found separately and stay apart. Each wall then gets its own exact angle
        // from its own triangles (two walls 3° apart share a rough direction but not an angle).
        class Wall(val angle: Double, val cx: Float, val cz: Float, val area: Float)
        val found = ArrayList<Wall>()
        for ((dir, _) in dirs) {
            val dx = cos(dir).toFloat(); val dz = sin(dir).toFloat()
            for (facingPositive in listOf(true, false)) {
                val members = (0 until t.count).filter { i ->
                    val a = angle[i]
                    !a.isNaN() && foldedDiff(a, dir) <= WALL_ANGLE_TOL && (t.nx[i] * dx + t.nz[i] * dz >= 0) == facingPositive
                }
                for (peak in peaks(members, { t.cx[it] * dx + t.cz[it] * dz }, t.area, o)) {
                    var sx = 0.0; var sz = 0.0; var cx = 0.0; var cz = 0.0; var a = 0.0
                    for (i in peak.members) {
                        val w = t.area[i].toDouble()
                        sx += cos(2 * angle[i]) * w; sz += sin(2 * angle[i]) * w
                        cx += t.cx[i] * w; cz += t.cz[i] * w; a += w
                    }
                    var own = atan2(sz, sx) / 2
                    if (own < 0) own += PI
                    found += Wall(own, (cx / a).toFloat(), (cz / a).toFloat(), peak.area)
                }
            }
        }
        if (found.isEmpty()) return emptyList()

        // The same wall can be reached from two nearby rough directions; keep the better-supported copy.
        found.sortByDescending { it.area }
        val walls = ArrayList<Wall>()
        for (w in found) {
            val duplicate = walls.any { k ->
                val nx = cos(k.angle); val nz = sin(k.angle)
                foldedDiff(k.angle, w.angle) < 5 * PI / 180 && abs((w.cx - k.cx) * nx + (w.cz - k.cz) * nz) < o.snapDistance
            }
            if (!duplicate) walls += w
        }

        // 3) Optionally turn each wall to be exactly 0° or 90° from the biggest wall, pivoting about its middle.
        val base = walls.first().angle
        return walls.map { w ->
            var target = w.angle
            if (o.squareCorners) {
                val snapped = base + round((w.angle - base) / (PI / 2)) * PI / 2
                if (abs(w.angle - snapped) <= o.squareToleranceDegrees * PI / 180) target = snapped
            }
            val mx = cos(w.angle).toFloat(); val mz = sin(w.angle).toFloat()
            val tx = cos(target).toFloat(); val tz = sin(target).toFloat()
            Plane(tx, 0f, tz, w.cx * tx + w.cz * tz, w.area, mx, 0f, mz, w.cx * mx + w.cz * mz)
        }
    }

    private class Peak(val position: Float, val area: Float, val members: List<Int>)

    /**
     * Finds planes along one axis: peaks in the area-weighted histogram of [value] over the triangles
     * in [members], each refined to the area-weighted mean of the triangles within the snap distance.
     */
    private fun peaks(members: List<Int>, value: (Int) -> Float, area: FloatArray, o: Options): List<Peak> {
        if (members.isEmpty()) return emptyList()
        val bin = 0.01f
        val values = FloatArray(members.size) { value(members[it]) }
        val lo = values.min()
        val n = ((values.max() - lo) / bin).toInt() + 1
        val hist = DoubleArray(n)
        for (k in members.indices) hist[((values[k] - lo) / bin).toInt().coerceIn(0, n - 1)] += area[members[k]].toDouble()
        val window = DoubleArray(n) { b -> var s = 0.0; for (k in -2..2) if (b + k in 0 until n) s += hist[b + k]; s }
        val radius = (o.snapDistance / bin).toInt().coerceAtLeast(1)
        val used = BooleanArray(n)
        val out = ArrayList<Peak>()
        while (true) {
            var best = -1
            for (b in 0 until n) if (!used[b] && (best < 0 || window[b] > window[best])) best = b
            if (best < 0 || window[best] < o.minPlaneArea) break
            for (k in -2 * radius..2 * radius) if (best + k in 0 until n) used[best + k] = true
            if ((1..2 * radius).any { k -> (best + k < n && window[best + k] > window[best]) || (best - k >= 0 && window[best - k] > window[best]) }) continue
            val centre = lo + (best + 0.5f) * bin
            var sum = 0.0; var total = 0.0
            val support = ArrayList<Int>()
            for (k in members.indices) {
                if (abs(values[k] - centre) > o.snapDistance) continue
                val a = area[members[k]]
                sum += values[k] * a; total += a; support += members[k]
            }
            if (total > 0) out += Peak((sum / total).toFloat(), total.toFloat(), support)
        }
        return out
    }

    private fun foldedDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % PI
        return minOf(d, PI - d)
    }

    /** New mesh with moved positions, recomputed normals and collapsed triangles removed. */
    internal fun rebuild(src: MeshData, pos: FloatArray): MeshData {
        val keep = IntList(src.indices.size)
        val normals = FloatArray(pos.size)
        for (t in 0 until src.triangleCount) {
            val a = src.indices[t * 3]; val b = src.indices[t * 3 + 1]; val c = src.indices[t * 3 + 2]
            val ux = pos[b * 3] - pos[a * 3]; val uy = pos[b * 3 + 1] - pos[a * 3 + 1]; val uz = pos[b * 3 + 2] - pos[a * 3 + 2]
            val vx = pos[c * 3] - pos[a * 3]; val vy = pos[c * 3 + 1] - pos[a * 3 + 1]; val vz = pos[c * 3 + 2] - pos[a * 3 + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            if (nx * nx + ny * ny + nz * nz < 1e-14f) continue // collapsed onto an edge
            keep.push(a); keep.push(b); keep.push(c)
            for (v in intArrayOf(a, b, c)) { normals[v * 3] += nx; normals[v * 3 + 1] += ny; normals[v * 3 + 2] += nz }
        }
        // Vertices that lost all their triangles keep their old normal.
        for (v in 0 until src.vertexCount) {
            if (normals[v * 3] == 0f && normals[v * 3 + 1] == 0f && normals[v * 3 + 2] == 0f) {
                normals[v * 3] = src.normals[v * 3]; normals[v * 3 + 1] = src.normals[v * 3 + 1]; normals[v * 3 + 2] = src.normals[v * 3 + 2]
            }
        }
        MeshData.normalizeInPlace(normals)
        return MeshData(pos, normals, src.colors.copyOf(), keep.toArray())
    }

    /** Per-triangle unit normal, centroid and area. */
    private class TriangleInfo(mesh: MeshData) {
        val count = mesh.triangleCount
        val nx = FloatArray(count); val ny = FloatArray(count); val nz = FloatArray(count)
        val cx = FloatArray(count); val cy = FloatArray(count); val cz = FloatArray(count)
        val area = FloatArray(count)

        init {
            val p = mesh.positions
            for (t in 0 until count) {
                val a = mesh.indices[t * 3] * 3; val b = mesh.indices[t * 3 + 1] * 3; val c = mesh.indices[t * 3 + 2] * 3
                val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
                val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
                val x = uy * vz - uz * vy; val y = uz * vx - ux * vz; val z = ux * vy - uy * vx
                val len = sqrt(x * x + y * y + z * z)
                area[t] = len / 2
                if (len > 0) { nx[t] = x / len; ny[t] = y / len; nz[t] = z / len }
                cx[t] = (p[a] + p[b] + p[c]) / 3; cy[t] = (p[a + 1] + p[b + 1] + p[c + 1]) / 3; cz[t] = (p[a + 2] + p[b + 2] + p[c + 2]) / 3
            }
        }
    }
}
