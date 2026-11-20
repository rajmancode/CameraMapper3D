package com.cameramapper3d.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Synthetic scene: a sphere (green) floating in front of a wall (red), viewed by a
 * camera with ARCore-like intrinsics from several positions.
 */
class ReconstructionTest {
    private val sphereC = floatArrayOf(0f, 0f, -2f)
    private val sphereR = 0.5f
    private val wallZ = -3f
    private val depthIntr = Intrinsics(fx = 130f, fy = 130f, cx = 80f, cy = 60f, width = 160, height = 120)
    private val colorIntr = depthIntr.scaledTo(320, 240)

    private fun lookAt(eye: FloatArray, target: FloatArray): FloatArray {
        val f = norm(sub(target, eye))
        val r = norm(cross(f, floatArrayOf(0f, 1f, 0f)))
        val u = cross(r, f)
        return floatArrayOf(
            r[0], r[1], r[2], 0f,
            u[0], u[1], u[2], 0f,
            -f[0], -f[1], -f[2], 0f,
            eye[0], eye[1], eye[2], 1f,
        )
    }

    /** Returns (t, isSphere) for a world ray, or null. */
    private fun traceScene(o: FloatArray, d: FloatArray): Pair<Float, Boolean>? {
        var best: Pair<Float, Boolean>? = null
        val oc = sub(o, sphereC)
        val b = dot(oc, d); val c = dot(oc, oc) - sphereR * sphereR; val a = dot(d, d)
        val disc = b * b - a * c
        if (disc >= 0) {
            val t = (-b - sqrt(disc)) / a
            if (t > 0) best = t to true
        }
        if (abs(d[2]) > 1e-6f) {
            val t = (wallZ - o[2]) / d[2]
            if (t > 0 && (best == null || t < best.first)) best = t to false
        }
        return best
    }

    private fun render(pose: FloatArray, intr: Intrinsics, color: Boolean): Pair<ShortArray, IntArray> {
        val depth = ShortArray(intr.width * intr.height)
        val rgb = IntArray(intr.width * intr.height)
        val eye = floatArrayOf(pose[12], pose[13], pose[14])
        for (v in 0 until intr.height) for (u in 0 until intr.width) {
            // Camera-space direction with z = -1, so the ray parameter equals optical-axis depth.
            val cx = (u - intr.cx) / intr.fx; val cy = -(v - intr.cy) / intr.fy; val cz = -1f
            val d = floatArrayOf(
                pose[0] * cx + pose[4] * cy + pose[8] * cz,
                pose[1] * cx + pose[5] * cy + pose[9] * cz,
                pose[2] * cx + pose[6] * cy + pose[10] * cz,
            )
            val hit = traceScene(eye, d) ?: continue
            depth[v * intr.width + u] = (hit.first * 1000f + 0.5f).toInt().toShort()
            if (color) rgb[v * intr.width + u] = if (hit.second) 0x20C020 else 0xD02020
        }
        return depth to rgb
    }

    private fun scan(voxel: Float = 0.02f): TsdfVolume {
        val volume = TsdfVolume(voxelSize = voxel)
        val target = floatArrayOf(0f, 0f, -2f)
        val eyes = listOf(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(0.6f, 0.1f, 0f), floatArrayOf(-0.6f, -0.1f, 0f),
            floatArrayOf(0f, 0.5f, 0.2f), floatArrayOf(0.3f, -0.4f, -0.2f),
        )
        for (eye in eyes) {
            val pose = lookAt(eye, target)
            val (depth, _) = render(pose, depthIntr, color = false)
            val (_, rgb) = render(pose, colorIntr, color = true)
            volume.integrate(
                DepthFrame(depth, depthIntr, pose, rgb = rgb, colorIntrinsics = colorIntr),
                IntegrationParams(maxDepth = 5f),
            )
        }
        return volume
    }

    private fun surfaceDistance(x: Float, y: Float, z: Float): Pair<Float, Boolean> {
        val ds = abs(sqrt((x - sphereC[0]).let { it * it } + (y - sphereC[1]).let { it * it } + (z - sphereC[2]).let { it * it }) - sphereR)
        val dw = abs(z - wallZ)
        return if (ds < dw) ds to true else dw to false
    }

    @Test
    fun blockKeysRoundTripIncludingNegativeCoordinates() {
        for ((x, y, z) in listOf(Triple(0, 0, 0), Triple(-1, 5, -7), Triple(1000, -1000, 123456), Triple(-524288, 524287, 0))) {
            val k = TsdfVolume.packKey(x, y, z)
            assertEquals(x, TsdfVolume.unpackX(k)); assertEquals(y, TsdfVolume.unpackY(k)); assertEquals(z, TsdfVolume.unpackZ(k))
        }
    }

    @Test
    fun reconstructsSphereAndWallAccurately() {
        val vs = 0.02f
        val volume = scan(vs)
        assertEquals(5, volume.frameCount)
        val mesh = SurfaceNetsMesher().meshAll(volume)
        assertTrue(mesh.triangleCount > 5000, "expected a dense mesh, got ${mesh.triangleCount} triangles")

        val errors = FloatArray(mesh.vertexCount)
        var sphereVerts = 0; var goodNormals = 0
        var redWall = 0; var wallVerts = 0
        for (i in 0 until mesh.vertexCount) {
            val x = mesh.positions[i * 3]; val y = mesh.positions[i * 3 + 1]; val z = mesh.positions[i * 3 + 2]
            val (err, onSphere) = surfaceDistance(x, y, z)
            errors[i] = err
            if (onSphere) {
                sphereVerts++
                val n = floatArrayOf(mesh.normals[i * 3], mesh.normals[i * 3 + 1], mesh.normals[i * 3 + 2])
                if (dot(n, norm(floatArrayOf(x - sphereC[0], y - sphereC[1], z - sphereC[2]))) > 0.7f) goodNormals++
            } else {
                wallVerts++
                val r = mesh.colors[i * 3].toInt() and 0xFF; val g = mesh.colors[i * 3 + 1].toInt() and 0xFF
                if (r > 150 && g < 100) redWall++
            }
        }
        errors.sort()
        val p95 = errors[(errors.size * 0.95).toInt()]
        println("vertices=${mesh.vertexCount} tris=${mesh.triangleCount} median=${errors[errors.size / 2]} p95=$p95 max=${errors.last()}")
        assertTrue(errors[errors.size / 2] < vs * 0.25f, "median error ${errors[errors.size / 2]}")
        assertTrue(p95 < vs * 0.75f, "95th percentile error $p95")
        assertTrue(sphereVerts > 500 && wallVerts > 500)
        assertTrue(goodNormals > sphereVerts * 0.95, "normals should face out of the sphere ($goodNormals/$sphereVerts)")
        assertTrue(redWall > wallVerts * 0.9, "wall should be red ($redWall/$wallVerts)")

        // Triangle winding must agree with the vertex normals (outward = towards the cameras).
        var agree = 0
        for (t in 0 until mesh.triangleCount) {
            val ia = mesh.indices[t * 3]; val ib = mesh.indices[t * 3 + 1]; val ic = mesh.indices[t * 3 + 2]
            val pa = vec(mesh.positions, ia); val pb = vec(mesh.positions, ib); val pc = vec(mesh.positions, ic)
            val fn = cross(sub(pb, pa), sub(pc, pa))
            if (dot(fn, vec(mesh.normals, ia)) > 0) agree++
        }
        assertTrue(agree > mesh.triangleCount * 0.98, "winding agrees for $agree/${mesh.triangleCount}")

        // Front of the sphere faces the cameras, so its visible cap's area is roughly that of a hemisphere.
        val wallArea = mesh.surfaceArea()
        assertTrue(wallArea > 1.0, "area $wallArea")
    }

    @Test
    fun incrementalBlockMeshesMatchFullMesh() {
        val volume = scan()
        val mesher = SurfaceNetsMesher()
        val dirty = volume.takeDirtyBlocks()
        assertEquals(volume.blockCount, dirty.size)
        val parts = dirty.mapNotNull { mesher.meshBlock(volume, it) }
        val welded = MeshData.merge(parts, weld = true)
        val full = mesher.meshAll(volume)
        assertEquals(full.vertexCount, welded.vertexCount)
        assertEquals(full.triangleCount, welded.triangleCount)
        // Welding should remove the duplicated block-border vertices.
        assertTrue(parts.sumOf { it.vertexCount } > welded.vertexCount)
        assertTrue(volume.takeDirtyBlocks().isEmpty())
    }

    @Test
    fun lowConfidencePixelsAreIgnored() {
        val volume = TsdfVolume()
        val pose = lookAt(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, -2f))
        val (depth, _) = render(pose, depthIntr, color = false)
        val conf = ByteArray(depth.size) { 10 }
        assertEquals(0, volume.integrate(DepthFrame(depth, depthIntr, pose, confidence = conf)))
        assertEquals(0, volume.blockCount)
        conf.fill(200.toByte())
        assertTrue(volume.integrate(DepthFrame(depth, depthIntr, pose, confidence = conf)) > 0)
    }

    @Test
    fun raycastHitsTheFrontOfTheSphere() {
        val mesh = SurfaceNetsMesher().meshAll(scan())
        val rc = MeshRaycaster(mesh)
        val hit = assertNotNull(rc.cast(0f, 0f, 0f, 0f, 0f, -1f))
        assertEquals(-1.5f, hit.z, 0.02f)
        assertEquals(1.5f, hit.distance, 0.02f)
        // Off to the side, the ray misses the sphere and hits the wall.
        val wallHit = assertNotNull(rc.cast(0f, 0f, 0f, 0.4f, 0f, -1f))
        assertEquals(wallZ, wallHit.z, 0.02f)
        // Measuring between two picked points.
        val a = assertNotNull(rc.cast(0f, 0f, 0f, -0.3f, 0f, -1f))
        val b = assertNotNull(rc.cast(0f, 0f, 0f, 0.3f, 0f, -1f))
        val dist = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
        assertEquals(0.6f * 3f, dist, 0.04f) // both on the wall at z=-3, x = ±0.9
        // Pointing away from everything.
        assertEquals(null, rc.cast(0f, 0f, 0f, 0f, 0f, 1f))

        // The grid-accelerated result equals a brute-force scan.
        for (i in 0 until 50) {
            val dx = (i % 10 - 5) * 0.1f; val dy = (i / 10 - 2) * 0.1f
            val fast = rc.cast(0f, 0f, 0f, dx, dy, -1f)
            val slow = MeshRaycaster(mesh, targetTrianglesPerCell = Int.MAX_VALUE).cast(0f, 0f, 0f, dx, dy, -1f)
            assertEquals(slow?.distance ?: -1f, fast?.distance ?: -1f, 1e-5f)
        }
    }

    @Test
    fun plyRoundTripAndObjExport() {
        val mesh = SurfaceNetsMesher().meshAll(scan(0.04f))
        val bytes = ByteArrayOutputStream().also { PlyWriter.write(mesh, it, comment = "test") }.toByteArray()
        val back = PlyReader.read(ByteArrayInputStream(bytes))
        assertContentEquals(mesh.positions, back.positions)
        assertContentEquals(mesh.normals, back.normals)
        assertContentEquals(mesh.colors, back.colors)
        assertContentEquals(mesh.indices, back.indices)

        // A realistic coloured export, checked with the Khronos glTF validator: core/build/scan-test.glb
        java.io.File("build/scan-test.glb").outputStream().use { GlbWriter.write(mesh, it, name = "Synthetic scan") }

        val obj = ByteArrayOutputStream().also { ObjWriter.write(mesh, it) }.toString(Charsets.US_ASCII)
        val lines = obj.lines()
        assertEquals(mesh.vertexCount, lines.count { it.startsWith("v ") })
        assertEquals(mesh.vertexCount, lines.count { it.startsWith("vn ") })
        assertEquals(mesh.triangleCount, lines.count { it.startsWith("f ") })
        assertEquals(7, lines.first { it.startsWith("v ") }.split(' ').size)
    }

    @Test
    fun thumbnailShowsTheScan() {
        val bg = 0xFF1C1F24.toInt()
        val empty = ThumbnailRenderer.render(MeshData.EMPTY, size = 32, background = bg)
        assertEquals(32 * 32, empty.size)
        assertTrue(empty.all { it == bg })

        val mesh = SurfaceNetsMesher().meshAll(scan(0.04f))
        val size = 128
        val img = ThumbnailRenderer.render(mesh, size = size, background = bg)
        assertEquals(size * size, img.size)
        fun rgb(x: Int, y: Int) = img[y * size + x].let { Triple((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF) }
        // The green sphere is in the middle, in front of the red wall.
        val (r, g, _) = rgb(size / 2, size / 2)
        assertTrue(g > r + 40, "centre should be the green sphere, was r=$r g=$g")
        val covered = img.count { it != bg }
        assertTrue(covered > size * size / 4, "the scan should fill a good part of the thumbnail ($covered px)")
        assertTrue(img.any { val c = it; ((c shr 16) and 0xFF) > ((c shr 8) and 0xFF) + 60 }, "red wall should be visible")

        // Save it so a person can look at it: core/build/thumbnail-test.png
        val out = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, size, size, img, 0, size)
        javax.imageio.ImageIO.write(out, "png", java.io.File("build/thumbnail-test.png"))
    }

    @Test
    fun straighteningAFusedScanFlattensTheWallOnly() {
        val mesh = SurfaceNetsMesher().meshAll(scan())
        val result = WallStraightener.process(mesh)
        println("fused scan: ${result.wallCount} walls, ${result.levelCount} level, moved ${result.movedVertices}/${mesh.vertexCount}")
        val wall = result.planes.single { !it.isHorizontal } // the back wall; there is no floor in this scene
        assertEquals(1f, abs(wall.nz), 1e-3f)
        assertEquals(wallZ, wall.d / wall.nz, 0.005f)
        var onWall = 0; var wallVerts = 0
        for (i in 0 until mesh.vertexCount) {
            val x = mesh.positions[i * 3]; val y = mesh.positions[i * 3 + 1]; val z = mesh.positions[i * 3 + 2]
            val (_, onSphere) = surfaceDistance(x, y, z)
            val ox = result.mesh.positions[i * 3]; val oy = result.mesh.positions[i * 3 + 1]; val oz = result.mesh.positions[i * 3 + 2]
            if (onSphere) {
                // Sphere vertices more than a snap distance from the wall must not move.
                if (abs(z - wallZ) > 0.05f) assertTrue(x == ox && y == oy && z == oz, "sphere vertex $i moved")
            } else {
                wallVerts++
                if (abs(wall.distance(ox, oy, oz)) < 1e-4f) onWall++
            }
        }
        assertTrue(onWall > wallVerts * 0.95, "$onWall / $wallVerts wall vertices are on the plane")
    }

    /** Mean error of the reconstructed wall beside the sphere after far, biased views then a few close ones. */
    private fun wallErrorAfterCloseUpPass(params: IntegrationParams): Float {
        val volume = TsdfVolume(voxelSize = 0.02f)
        val target = floatArrayOf(1.2f, 0f, -3f) // a patch of wall to the right of the sphere
        // Far views (4.8 m) whose depth reads 4 cm too far, as far phone depth tends to.
        repeat(12) { k ->
            val pose = lookAt(floatArrayOf(1.2f + (k % 3 - 1) * 0.1f, (k / 3 - 1.5f) * 0.1f, 1.8f), target)
            val (depth, _) = render(pose, depthIntr, color = false)
            for (i in depth.indices) if (depth[i] > 0) depth[i] = (depth[i] + 40).toShort()
            volume.integrate(DepthFrame(depth, depthIntr, pose), params)
        }
        // A short close-up pass (1.2 m) with accurate depth.
        repeat(3) { k ->
            val pose = lookAt(floatArrayOf(1.2f + (k - 1) * 0.1f, 0f, -1.8f), target)
            val (depth, _) = render(pose, depthIntr, color = false)
            volume.integrate(DepthFrame(depth, depthIntr, pose), params)
        }
        val mesh = SurfaceNetsMesher().meshAll(volume)
        var sum = 0.0; var n = 0
        for (i in 0 until mesh.vertexCount) {
            val x = mesh.positions[i * 3]; val y = mesh.positions[i * 3 + 1]; val z = mesh.positions[i * 3 + 2]
            if (abs(x - 1.2f) < 0.3f && abs(y) < 0.3f && z < -2.8f) { sum += (z - wallZ); n++ }
        }
        assertTrue(n > 100, "wall patch should be reconstructed ($n vertices)")
        return abs((sum / n).toFloat())
    }

    @Test
    fun closeUpPassCorrectsWhatWasSeenFromFarAway() {
        val equal = wallErrorAfterCloseUpPass(IntegrationParams(maxDepth = 6f, referenceDistance = 0f))
        val weighted = wallErrorAfterCloseUpPass(IntegrationParams(maxDepth = 6f))
        println("wall error after close-up pass: every look equal ${equal * 100} cm, weighted ${weighted * 100} cm")
        assertTrue(equal > 0.03f, "with equal weights the far error should mostly remain ($equal)")
        assertTrue(weighted < 0.01f, "close views should pull the wall back near its true position ($weighted)")
    }

    @Test
    fun observationWeights() {
        val p = IntegrationParams()
        assertEquals(1f, p.observationWeight(1.0f, -1), 1e-6f)
        assertEquals(1f, p.observationWeight(2.0f, -1), 1e-6f)
        assertEquals(0.0625f, p.observationWeight(4.0f, -1), 1e-6f)
        assertEquals(0.05f, p.observationWeight(10f, -1), 1e-6f) // never below the floor
        assertEquals(1f, p.observationWeight(1.0f, 255), 1e-6f)
        assertEquals(0.25f, p.observationWeight(1.0f, 0), 1e-6f)
        assertEquals(1f, IntegrationParams(referenceDistance = 0f, weightByConfidence = false).observationWeight(5f, 0), 1e-6f)
    }

    private fun vec(a: FloatArray, i: Int) = floatArrayOf(a[i * 3], a[i * 3 + 1], a[i * 3 + 2])
    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun norm(a: FloatArray): FloatArray { val l = sqrt(dot(a, a)); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
}
