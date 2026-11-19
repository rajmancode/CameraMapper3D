package com.cameramapper3d.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MeshImportTest {
    /** A 1 m square in the floor (two triangles facing up), coloured. */
    private fun square(): MeshData = MeshData(
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f),
        floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f),
        byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120),
        intArrayOf(0, 2, 1, 0, 3, 2),
    )

    private fun read(text: String) = MeshImporter.read(ByteArrayInputStream(text.toByteArray()))

    @Test
    fun scanMeshPlyRoundTripsExactly() {
        val m = square()
        val bytes = ByteArrayOutputStream().also { PlyWriter.write(m, it) }.toByteArray()
        val back = MeshImporter.read(ByteArrayInputStream(bytes))
        assertContentEquals(m.positions, back.positions)
        assertContentEquals(m.normals, back.normals)
        assertContentEquals(m.colors, back.colors)
        assertContentEquals(m.indices, back.indices)
    }

    @Test
    fun objRoundTrip() {
        val m = square()
        val bytes = ByteArrayOutputStream().also { ObjWriter.write(m, it) }.toByteArray()
        val back = MeshImporter.read(ByteArrayInputStream(bytes))
        assertContentEquals(m.indices, back.indices)
        for (i in m.positions.indices) assertEquals(m.positions[i], back.positions[i], 1e-5f)
        for (i in m.colors.indices) assertTrue(abs((m.colors[i].toInt() and 0xFF) - (back.colors[i].toInt() and 0xFF)) <= 1)
        // Normals are recomputed from the winding: the square faces up.
        for (v in 0 until back.vertexCount) assertEquals(1f, back.normals[v * 3 + 1], 1e-5f)
    }

    @Test
    fun asciiPlyWithQuadsFloatColoursAndExtras() {
        val mesh = read(
            """
            ply
            format ascii 1.0
            comment made by another scanner
            element vertex 4
            property double x
            property double y
            property double z
            property float red
            property float green
            property float blue
            property float alpha
            property float confidence
            element face 1
            property list uchar int vertex_indices
            property uchar flags
            element edge 1
            property int vertex1
            property int vertex2
            end_header
            0 0 0 1 0 0 1 0.5
            1 0 0 0 1 0 1 0.5
            1 0 1 0 0 1 1 0.5
            0 0 1 1 1 1 1 0.5
            4 0 3 2 1 7
            0 1
            """.trimIndent(),
        )
        assertEquals(4, mesh.vertexCount)
        assertEquals(2, mesh.triangleCount) // the quad is split in two
        assertEquals(255, mesh.colors[0].toInt() and 0xFF)
        assertEquals(255, mesh.colors[4].toInt() and 0xFF) // green of vertex 1
        assertEquals(1f, mesh.surfaceArea().toFloat(), 1e-5f)
        assertEquals(1f, mesh.normals[1], 1e-5f) // winding 0,3,2 faces up
    }

    @Test
    fun bigEndianBinaryPlyWithUshortColoursSkipped() {
        val header = """
            ply
            format binary_big_endian 1.0
            element vertex 3
            property float x
            property float y
            property float z
            property ushort intensity
            element face 1
            property list uchar uint vertex_indices
            end_header
            """.trimIndent() + "\n"
        val body = ByteBuffer.allocate(3 * 14 + 1 + 12).order(ByteOrder.BIG_ENDIAN)
        for (v in listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(2f, 0f, 0f), floatArrayOf(0f, 2f, 0f))) {
            v.forEach { body.putFloat(it) }; body.putShort(1234)
        }
        body.put(3); body.putInt(0); body.putInt(1); body.putInt(2)
        val mesh = MeshImporter.read(ByteArrayInputStream(header.toByteArray() + body.array()))
        assertEquals(2f, mesh.positions[3])
        assertEquals(2f, mesh.positions[7])
        assertEquals(2.0, mesh.surfaceArea(), 1e-6)
        assertEquals(0xB4, mesh.colors[0].toInt() and 0xFF) // no colours: default grey
    }

    @Test
    fun objFaceFormsAndNegativeIndices() {
        val mesh = read(
            """
            # from some CAD tool
            mtllib room.mtl
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            vt 0 0
            vn 0 0 1
            usemtl wall
            f 1/1/1 2/1/1 3/1/1 4/1/1
            f -4//1 -2//1 -1//1
            """.trimIndent(),
        )
        assertEquals(4, mesh.vertexCount)
        assertEquals(3, mesh.triangleCount) // quad → 2, plus one triangle
        assertContentEquals(intArrayOf(0, 1, 2, 0, 2, 3, 0, 2, 3), mesh.indices)
    }

    @Test
    fun badFilesGiveClearErrors() {
        val cloud = assertFailsWith<IOException> {
            read("ply\nformat ascii 1.0\nelement vertex 1\nproperty float x\nproperty float y\nproperty float z\nend_header\n0 0 0\n")
        }
        assertTrue(cloud.message!!.contains("point cloud"))
        assertFailsWith<IOException> { read("v 0 0 0\nf 1 2 3\n") } // face refers to missing vertices
        assertFailsWith<IOException> {
            read("ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\nelement face 1\nproperty list uchar int vertex_indices\nend_header\n0 0 0\n1 0 0\n")
        }
    }
}
