package com.cameramapper3d.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A synthetic 4 × 2.5 × 3 m room, turned 20° and moved off the origin, with ±1 cm bumpy surfaces,
 * one wall 3° out of square and a ball standing in the middle.
 */
class WallStraightenerTest {
    private val rng = Random(42)
    private val yaw = 20.0 * PI / 180
    private val offset = floatArrayOf(1f, 0.3f, -2f)

    private class Builder {
        val pos = ArrayList<Float>()
        val tris = ArrayList<Int>()
        val isBall = ArrayList<Boolean>()
        fun vertex(p: FloatArray, ball: Boolean): Int { pos += p[0]; pos += p[1]; pos += p[2]; isBall += ball; return isBall.size - 1 }
    }

    private fun place(x: Float, y: Float, z: Float): FloatArray {
        val c = cos(yaw).toFloat(); val s = sin(yaw).toFloat()
        return floatArrayOf(c * x + s * z + offset[0], y + offset[1], -s * x + c * z + offset[2])
    }

    /** A grid over (u, v) in [0,1]², mapped to the room by [at]; triangles face [inside]. */
    private fun grid(b: Builder, nu: Int, nv: Int, noiseAxis: Int, at: (Float, Float) -> FloatArray) {
        val first = b.isBall.size
        for (j in 0..nv) for (i in 0..nu) {
            val p = at(i.toFloat() / nu, j.toFloat() / nv)
            p[noiseAxis] += (rng.nextFloat() * 2 - 1) * 0.01f
            b.vertex(place(p[0], p[1], p[2]), ball = false)
        }
        for (j in 0 until nv) for (i in 0 until nu) {
            val a = first + j * (nu + 1) + i
            b.tris += listOf(a, a + 1, a + nu + 2, a, a + nu + 2, a + nu + 1)
        }
    }

    private fun room(): Pair<MeshData, BooleanArray> {
        val b = Builder()
        val skew = tan(3.0 * PI / 180).toFloat()
        grid(b, 80, 60, 1) { u, v -> floatArrayOf(u * 4, 0f, v * 3) } // floor
        grid(b, 80, 60, 1) { u, v -> floatArrayOf(u * 4, 2.5f, v * 3) } // ceiling
        grid(b, 60, 50, 0) { u, v -> floatArrayOf(0f, v * 2.5f, u * 3) } // x = 0
        grid(b, 60, 50, 0) { u, v -> floatArrayOf(4f + (u * 3 - 1.5f) * skew, v * 2.5f, u * 3) } // x ≈ 4, 3° off
        grid(b, 80, 50, 2) { u, v -> floatArrayOf(u * 4, v * 2.5f, 0f) } // z = 0
        grid(b, 80, 50, 2) { u, v -> floatArrayOf(u * 4, v * 2.5f, 3f) } // z = 3
        // A ball (r = 0.4) in the middle of the room.
        val first = b.isBall.size
        val rings = 24; val segs = 32
        for (j in 0..rings) for (i in 0..segs) {
            val th = PI * j / rings; val ph = 2 * PI * i / segs
            b.vertex(place(2f + 0.4f * (sin(th) * cos(ph)).toFloat(), 1.25f + 0.4f * cos(th).toFloat(), 1.5f + 0.4f * (sin(th) * sin(ph)).toFloat()), ball = true)
        }
        for (j in 0 until rings) for (i in 0 until segs) {
            val a = first + j * (segs + 1) + i
            b.tris += listOf(a, a + 1, a + segs + 2, a, a + segs + 2, a + segs + 1)
        }

        // Orient triangles: room surfaces face the room centre, the ball faces outwards.
        val p = b.pos.toFloatArray()
        val idx = b.tris.toIntArray()
        val centre = place(2f, 1.25f, 1.5f)
        for (t in 0 until idx.size / 3) {
            val (i0, i1, i2) = Triple(idx[t * 3], idx[t * 3 + 1], idx[t * 3 + 2])
            val n = cross(sub(p, i1, i0), sub(p, i2, i0))
            val toCentre = floatArrayOf(centre[0] - p[i0 * 3], centre[1] - p[i0 * 3 + 1], centre[2] - p[i0 * 3 + 2])
            val facing = n[0] * toCentre[0] + n[1] * toCentre[1] + n[2] * toCentre[2]
            if ((facing < 0) != b.isBall[i0]) { idx[t * 3 + 1] = i2; idx[t * 3 + 2] = i1 }
        }
        val normals = FloatArray(p.size)
        for (t in 0 until idx.size / 3) {
            val n = cross(sub(p, idx[t * 3 + 1], idx[t * 3]), sub(p, idx[t * 3 + 2], idx[t * 3]))
            for (k in 0 until 3) for (a in 0 until 3) normals[idx[t * 3 + k] * 3 + a] += n[a]
        }
        MeshData.normalizeInPlace(normals)
        return MeshData(p, normals, ByteArray(p.size) { 0x80.toByte() }, idx) to b.isBall.toBooleanArray()
    }

    @Test
    fun straightensWallsFloorAndCeiling() {
        val (mesh, isBall) = room()
        val result = WallStraightener.process(mesh, WallStraightener.Options(snapDistance = 0.04f, squareCorners = true))
        println("planes: ${result.wallCount} walls, ${result.levelCount} level; moved ${result.movedVertices}/${mesh.vertexCount}")
        assertEquals(4, result.wallCount)
        assertEquals(2, result.levelCount)

        // Walls are vertical and at right angles to each other; levels are level.
        val walls = result.planes.filter { !it.isHorizontal }
        for (w in walls) assertEquals(0f, w.ny)
        for (a in walls) for (b in walls) {
            val d = abs(a.nx * b.nx + a.nz * b.nz)
            assertTrue(d < 1e-4f || d > 1 - 1e-4f, "walls should be parallel or square, |cos| = $d")
        }

        // Nearly every room vertex now lies exactly on a plane; before, they were up to 1 cm (or, on the
        // skewed wall, up to 8 cm) off.
        val out = result.mesh
        var onPlane = 0; var roomVerts = 0
        for (v in 0 until out.vertexCount) {
            if (isBall[v]) continue
            roomVerts++
            val x = out.positions[v * 3]; val y = out.positions[v * 3 + 1]; val z = out.positions[v * 3 + 2]
            if (result.planes.any { abs(it.distance(x, y, z)) < 1e-4f }) onPlane++
        }
        assertTrue(onPlane > roomVerts * 0.97, "only $onPlane / $roomVerts room vertices are on a plane")

        // The ball is not flattened.
        for (v in 0 until out.vertexCount) if (isBall[v]) for (a in 0 until 3) {
            assertEquals(mesh.positions[v * 3 + a], out.positions[v * 3 + a], "ball vertex $v moved")
        }
        // Colours and vertex count are kept.
        assertEquals(mesh.vertexCount, out.vertexCount)
        assertTrue(out.triangleCount > mesh.triangleCount * 0.95)
    }

    @Test
    fun withoutSquaringTheSkewedWallKeepsItsAngle() {
        val (mesh, _) = room()
        val result = WallStraightener.process(mesh, WallStraightener.Options(squareCorners = false))
        val walls = result.planes.filter { !it.isHorizontal }
        assertEquals(4, walls.size)
        // One pair of walls is now 3° from parallel instead of exactly parallel.
        var closest = 1f
        for (a in walls) for (b in walls) if (a !== b) {
            val d = abs(a.nx * b.nx + a.nz * b.nz)
            if (d > 0.9f) closest = minOf(closest, d)
        }
        assertEquals(cos(3.0 * PI / 180).toFloat(), closest, 0.002f)
    }

    @Test
    fun meshWithoutFlatSurfacesIsUnchanged() {
        val (mesh, isBall) = room()
        // Keep only the ball's triangles.
        val ballTris = (0 until mesh.triangleCount).filter { isBall[mesh.indices[it * 3]] }
        val ball = MeshData(mesh.positions, mesh.normals, mesh.colors, ballTris.flatMap { listOf(mesh.indices[it * 3], mesh.indices[it * 3 + 1], mesh.indices[it * 3 + 2]) }.toIntArray())
        val result = WallStraightener.process(ball)
        assertEquals(0, result.movedVertices)
        assertTrue(result.planes.isEmpty())
        assertEquals(0, WallStraightener.process(MeshData.EMPTY).movedVertices)
    }

    private fun sub(p: FloatArray, a: Int, b: Int) = floatArrayOf(p[a * 3] - p[b * 3], p[a * 3 + 1] - p[b * 3 + 1], p[a * 3 + 2] - p[b * 3 + 2])
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
}
