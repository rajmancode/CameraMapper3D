package com.cameramapper3d.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One connected mesh: a 3 m wide floor running into a 2.5 m high wall. The wall bows by 8 cm (the
 * kind of bend tracking drift puts into a real scan), has ±5 mm noise and a blue poster on it.
 */
class SurfaceSelectorTest {
    private val nu = 60
    private val nFloor = 40
    private val nWall = 50
    private val poster = 0x2040C0
    private val wallColour = 0xE0D8C8
    private val floorColour = 0x806040

    private class Scene(val mesh: MeshData, val kind: IntArray) // per triangle: 0 floor, 1 wall, 2 poster

    private fun scene(): Scene {
        val rng = Random(7)
        val nv = nFloor + nWall
        val pos = FloatArray((nu + 1) * (nv + 1) * 3)
        val col = ByteArray(pos.size)
        for (j in 0..nv) for (i in 0..nu) {
            val u = i.toFloat() / nu
            val k = (j * (nu + 1) + i) * 3
            val noise = (rng.nextFloat() * 2 - 1) * 0.005f
            val c: Int
            if (j <= nFloor) { // floor: z from 2 m down to the wall at z = 0
                pos[k] = u * 3; pos[k + 1] = if (j == nFloor) 0f else noise; pos[k + 2] = 2f * (nFloor - j) / nFloor
                c = floorColour
            } else {
                val y = 2.5f * (j - nFloor) / nWall
                pos[k] = u * 3; pos[k + 1] = y; pos[k + 2] = -0.08f * sin(PI * u).toFloat() + noise
                c = if (u in 0.4f..0.6f && y in 0.8f..1.6f) poster else wallColour
            }
            col[k] = (c shr 16).toByte(); col[k + 1] = (c shr 8).toByte(); col[k + 2] = c.toByte()
        }
        val idx = IntArray(nu * nv * 6)
        val kind = IntArray(nu * nv * 2)
        var t = 0
        for (j in 0 until nv) for (i in 0 until nu) {
            val a = j * (nu + 1) + i
            for (tri in listOf(intArrayOf(a, a + 1, a + nu + 2), intArrayOf(a, a + nu + 2, a + nu + 1))) {
                tri.copyInto(idx, t * 3)
                val u = (i + 0.5f) / nu; val y = 2.5f * (j + 0.5f - nFloor) / nWall
                kind[t] = when { j < nFloor -> 0; u in 0.41f..0.59f && y in 0.82f..1.58f -> 2; else -> 1 }
                t++
            }
        }
        // Vertex normals from the faces (the grid winding faces up on the floor and into the room on the wall).
        val normals = FloatArray(pos.size)
        for (tt in 0 until idx.size / 3) {
            val a = idx[tt * 3] * 3; val b = idx[tt * 3 + 1] * 3; val c = idx[tt * 3 + 2] * 3
            val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
            val n = floatArrayOf(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
            for (v in intArrayOf(a, b, c)) for (q in 0 until 3) normals[v + q] += n[q]
        }
        MeshData.normalizeInPlace(normals)
        return Scene(MeshData(pos, normals, col, idx), kind)
    }

    private fun seedOn(s: Scene, kind: Int, u: Float): Int =
        (0 until s.mesh.triangleCount).first { t ->
            s.kind[t] == kind && abs(s.mesh.positions[s.mesh.indices[t * 3] * 3] - u * 3) < 0.05f &&
                s.mesh.positions[s.mesh.indices[t * 3] * 3 + 1] > 1.0f.takeIf { kind != 0 }.let { it ?: -1f }
        }

    @Test
    fun selectionFollowsTheBowedWallButStopsAtTheFloor() {
        val s = scene()
        val sel = SurfaceSelector(s.mesh).select(seedOn(s, 1, 0.2f)).toSet()
        val wall = (0 until s.mesh.triangleCount).filter { s.kind[it] != 0 }
        val floorInterior = (0 until s.mesh.triangleCount).filter { s.kind[it] == 0 && s.mesh.positions[s.mesh.indices[it * 3] * 3 + 2] > 0.15f }
        val wallHit = wall.count { it in sel }
        println("wall selection: $wallHit / ${wall.size} wall triangles, ${floorInterior.count { it in sel }} floor")
        assertTrue(wallHit > wall.size * 0.97, "selected $wallHit of ${wall.size} wall triangles")
        assertEquals(0, floorInterior.count { it in sel }, "the floor must not be selected")
    }

    @Test
    fun matchingColourLeavesThePosterOut() {
        val s = scene()
        val selector = SurfaceSelector(s.mesh)
        val seed = seedOn(s, 1, 0.2f)
        val posterTris = (0 until s.mesh.triangleCount).filter { s.kind[it] == 2 }
        val plainWall = (0 until s.mesh.triangleCount).filter { s.kind[it] == 1 }

        val byShape = selector.select(seed).toSet()
        assertTrue(posterTris.all { it in byShape }, "without colour matching the poster is part of the wall")

        val byColour = selector.select(seed, SurfaceSelector.Options(matchColour = true)).toSet()
        val posterIn = posterTris.count { it in byColour }
        val wallIn = plainWall.count { it in byColour }
        println("colour selection: poster $posterIn / ${posterTris.size}, wall $wallIn / ${plainWall.size}")
        assertTrue(posterIn < posterTris.size * 0.1, "poster should be left out ($posterIn selected)")
        assertTrue(wallIn > plainWall.size * 0.9, "rest of the wall should still be selected ($wallIn)")
    }

    @Test
    fun flatteningMakesTheBowedWallFlatAndUpright() {
        val s = scene()
        val selector = SurfaceSelector(s.mesh)
        val wallSel = selector.select(seedOn(s, 1, 0.2f))
        val result = selector.flatten(listOf(wallSel), squareCorners = false)
        val plane = result.planes.single()
        assertTrue(plane.isWall)
        assertEquals(1f, plane.nz, 0.01f) // faces into the room
        val out = result.mesh.positions
        val wallVerts = wallSel.flatMap { t -> (0 until 3).map { s.mesh.indices[t * 3 + it] } }.toSet()
        for (v in wallVerts) assertEquals(0f, plane.distance(out[v * 3], out[v * 3 + 1], out[v * 3 + 2]), 1e-4f)
        // The floor (away from the corner) is not touched.
        for (v in 0 until s.mesh.vertexCount) if (v !in wallVerts) for (a in 0 until 3) assertEquals(s.mesh.positions[v * 3 + a], out[v * 3 + a])
    }

    @Test
    fun flatteningWallAndFloorMakesASharpCorner() {
        val s = scene()
        val selector = SurfaceSelector(s.mesh)
        val wall = selector.select(seedOn(s, 1, 0.2f))
        val floor = selector.select(seedOn(s, 0, 0.5f))
        val result = selector.flatten(listOf(wall, floor), squareCorners = true)
        val (wp, fp) = result.planes
        assertTrue(wp.isWall && fp.isLevel)
        // Vertices in both selections lie on the corner line.
        val inWall = wall.flatMap { t -> (0 until 3).map { s.mesh.indices[t * 3 + it] } }.toSet()
        val inFloor = floor.flatMap { t -> (0 until 3).map { s.mesh.indices[t * 3 + it] } }.toSet()
        val both = inWall intersect inFloor
        assertTrue(both.isNotEmpty())
        val out = result.mesh.positions
        for (v in both) {
            assertEquals(0f, wp.distance(out[v * 3], out[v * 3 + 1], out[v * 3 + 2]), 1e-4f)
            assertEquals(0f, fp.distance(out[v * 3], out[v * 3 + 1], out[v * 3 + 2]), 1e-4f)
        }
    }

    @Test
    fun floatingPiecesAreRemoved() {
        val s = scene()
        // Add a 10 cm floating fragment 1 m above the floor.
        val m = s.mesh
        val extraPos = floatArrayOf(1f, 1f, 1f, 1.1f, 1f, 1f, 1f, 1.1f, 1f)
        val big = MeshData(
            m.positions + extraPos, m.normals + FloatArray(9) { if (it % 3 == 2) 1f else 0f }, m.colors + ByteArray(9),
            m.indices + intArrayOf(m.vertexCount, m.vertexCount + 1, m.vertexCount + 2),
        )
        val result = MeshCleaner.removeSmallPieces(big, minArea = 0.1f)
        assertEquals(1, result.removedPieces)
        assertEquals(m.triangleCount, result.mesh.triangleCount)
        assertEquals(m.vertexCount, result.mesh.vertexCount)
        assertEquals(0.005, result.removedArea, 1e-4)
        // Nothing to remove the second time; and the largest piece is kept even if it is small.
        assertEquals(0, MeshCleaner.removeSmallPieces(result.mesh, 0.1f).removedPieces)
        assertEquals(m.triangleCount, MeshCleaner.removeSmallPieces(big, minArea = 1000f).mesh.triangleCount)
    }
}
