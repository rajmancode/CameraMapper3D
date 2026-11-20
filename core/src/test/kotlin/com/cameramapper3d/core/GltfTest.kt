package com.cameramapper3d.core

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GltfTest {
    private val pcDecoder = ImageDecoder { bytes ->
        ImageIO.read(ByteArrayInputStream(bytes))?.let { img ->
            DecodedImage(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
        }
    }

    private fun colouredMesh(): MeshData = MeshData(
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f),
        floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f),
        byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, 30, 60, 90), // red, green, blue, dark blue-grey
        intArrayOf(0, 2, 1, 0, 3, 2),
    )

    /** Builds a GLB from a JSON string and binary chunk, as other apps would. */
    private fun glb(json: String, bin: ByteArray): ByteArray {
        var j = json.toByteArray()
        if (j.size % 4 != 0) j += ByteArray(4 - j.size % 4) { 32 }
        var b = bin
        if (b.size % 4 != 0) b += ByteArray(4 - b.size % 4)
        val out = ByteBuffer.allocate(12 + 8 + j.size + 8 + b.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67).putInt(2).putInt(out.capacity())
        out.putInt(j.size).putInt(0x4E4F534A).put(j)
        out.putInt(b.size).putInt(0x004E4942).put(b)
        return out.array()
    }

    private fun floats(vararg f: Float) = ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> f.forEach { b.putFloat(it) } }.array()
    private fun rgb(m: MeshData, v: Int) = Triple(m.colors[v * 3].toInt() and 0xFF, m.colors[v * 3 + 1].toInt() and 0xFF, m.colors[v * 3 + 2].toInt() and 0xFF)

    @Test
    fun glbRoundTripKeepsColours() {
        val m = colouredMesh()
        val bytes = ByteArrayOutputStream().also { GlbWriter.write(m, it, name = "Kitchen \"test\"") }.toByteArray()
        File("build/roundtrip-test.glb").writeBytes(bytes) // checked with the Khronos glTF validator
        val back = MeshImporter.read(ByteArrayInputStream(bytes))
        assertContentEquals(m.positions, back.positions)
        assertContentEquals(m.indices, back.indices)
        for (i in m.normals.indices) assertEquals(m.normals[i], back.normals[i], 1e-6f)
        for (i in m.colors.indices) assertTrue(abs((m.colors[i].toInt() and 0xFF) - (back.colors[i].toInt() and 0xFF)) <= 1, "colour $i")
    }

    @Test
    fun colourSpaceConversionIsReversible() {
        for (c in 0..255) assertEquals(c, ColorSpace.linearToSrgbByte(ColorSpace.linear(c)))
        // Mid-grey in sRGB is about 21% in linear light: storing it unconverted would make scans look washed out.
        assertEquals(0.216f, ColorSpace.linear(128), 0.001f)
    }

    @Test
    fun nodeTransformsAreApplied() {
        // A triangle modelled in centimetres with Z up (as CAD tools often do), under a parent that
        // converts to metres/Y-up, and a child offset of 1 m in X.
        val json = """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
            "nodes":[{"rotation":[-0.7071068,0,0,0.7071068],"scale":[0.01,0.01,0.01],"children":[1]},{"translation":[100,0,0],"mesh":0}],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],
            "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[100,100,200]}],
            "bufferViews":[{"buffer":0,"byteLength":36}],"buffers":[{"byteLength":36}]}"""
        val m = MeshImporter.read(ByteArrayInputStream(glb(json, floats(0f, 0f, 0f, 100f, 0f, 0f, 0f, 0f, 200f))))
        // Point (0,0,200 cm) is 2 m up after the conversion; the child offset moves everything 1 m along X.
        val expected = floatArrayOf(1f, 0f, 0f, 2f, 0f, 0f, 1f, 2f, 0f)
        for (i in expected.indices) assertEquals(expected[i], m.positions[i], 1e-5f)
        assertEquals(1, m.triangleCount)
        assertEquals(0xB4, rgb(m, 0).first) // no material: default grey
    }

    @Test
    fun textureColoursAreSampledPerVertex() {
        // 2×2 texture: red, green / blue, white.
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, 0xFF0000); img.setRGB(1, 0, 0x00FF00); img.setRGB(0, 1, 0x0000FF); img.setRGB(1, 1, 0xFFFFFF)
        val png = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        val pos = floats(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f)
        val uv = floats(0.25f, 0.25f, 0.75f, 0.25f, 0.75f, 0.75f, 0.25f, 0.75f) // one texel each
        val idx = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply { listOf(0, 1, 2, 0, 2, 3).forEach { putShort(it.toShort()) } }.array()
        val bin = pos + uv + idx + png
        val json = """{"asset":{"version":"2.0"},"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1},"indices":2,"material":0}]}],
            "materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0},"baseColorFactor":[1,1,1,1]}}],
            "textures":[{"source":0}],"images":[{"bufferView":3,"mimeType":"image/png"}],
            "accessors":[{"bufferView":0,"componentType":5126,"count":4,"type":"VEC3"},{"bufferView":1,"componentType":5126,"count":4,"type":"VEC2"},
              {"bufferView":2,"componentType":5123,"count":6,"type":"SCALAR"}],
            "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":48},{"buffer":0,"byteOffset":48,"byteLength":32},
              {"buffer":0,"byteOffset":80,"byteLength":12},{"buffer":0,"byteOffset":92,"byteLength":${png.size}}],
            "buffers":[{"byteLength":${bin.size}}]}"""
        val m = MeshImporter.read(ByteArrayInputStream(glb(json, bin)), pcDecoder)
        assertEquals(Triple(255, 0, 0), rgb(m, 0))
        assertEquals(Triple(0, 255, 0), rgb(m, 1))
        assertEquals(Triple(255, 255, 255), rgb(m, 2))
        assertEquals(Triple(0, 0, 255), rgb(m, 3))
        // Without a decoder the model still loads, in the material colour.
        assertEquals(Triple(255, 255, 255), rgb(MeshImporter.read(ByteArrayInputStream(glb(json, bin))), 0))
    }

    @Test
    fun byteColoursAndTriangleStrips() {
        val pos = floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f)
        val colours = byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1, 0x7F, 0x7F, 0x7F, -1) // RGBA ubyte
        val json = """{"asset":{"version":"2.0"},"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
            "meshes":[{"primitives":[{"attributes":{"POSITION":0,"COLOR_0":1},"mode":5}]}],
            "accessors":[{"bufferView":0,"componentType":5126,"count":4,"type":"VEC3"},{"bufferView":1,"componentType":5121,"normalized":true,"count":4,"type":"VEC4"}],
            "bufferViews":[{"buffer":0,"byteLength":48},{"buffer":0,"byteOffset":48,"byteLength":16}],"buffers":[{"byteLength":64}]}"""
        val m = MeshImporter.read(ByteArrayInputStream(glb(json, pos + colours)))
        assertEquals(2, m.triangleCount) // a 4-vertex strip is 2 triangles
        assertEquals(Triple(255, 0, 0), rgb(m, 0))
        assertEquals(Triple(0, 255, 0), rgb(m, 1))
        assertEquals(1.0, m.surfaceArea(), 1e-6)
        // The strip's second triangle keeps the same facing as the first.
        assertTrue((0 until 4).all { m.normals[it * 3 + 2] > 0.99f })
    }

    @Test
    fun compressedFilesGetAClearMessage() {
        val json = """{"asset":{"version":"2.0"},"extensionsRequired":["KHR_draco_mesh_compression"],"extensionsUsed":["KHR_draco_mesh_compression"]}"""
        val e = assertFailsWith<IOException> { MeshImporter.read(ByteArrayInputStream(glb(json, ByteArray(0)))) }
        assertTrue(e.message!!.contains("compressed"))
    }
}
