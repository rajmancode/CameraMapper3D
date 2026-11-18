package com.cameramapper3d.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * An indexed triangle mesh in metres with per-vertex normals and sRGB colours.
 *
 * @param positions xyz per vertex.
 * @param normals unit normal per vertex (xyz).
 * @param colors rgb per vertex, 0..255 stored in signed bytes.
 * @param indices three vertex indices per triangle, counter-clockwise when seen from outside.
 */
class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val colors: ByteArray,
    val indices: IntArray,
) {
    init {
        require(positions.size % 3 == 0 && normals.size == positions.size && colors.size == positions.size)
        require(indices.size % 3 == 0)
    }

    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
    val isEmpty: Boolean get() = indices.isEmpty()

    /** Axis-aligned bounds as [minX, minY, minZ, maxX, maxY, maxZ]; zeros for an empty mesh. */
    fun bounds(): FloatArray {
        if (vertexCount == 0) return FloatArray(6)
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until vertexCount) for (a in 0 until 3) {
            val v = positions[i * 3 + a]
            b[a] = min(b[a], v)
            b[a + 3] = max(b[a + 3], v)
        }
        return b
    }

    /** Total surface area in m². */
    fun surfaceArea(): Double {
        var area = 0.0
        val p = positions
        for (t in 0 until triangleCount) {
            val a = indices[t * 3] * 3; val b = indices[t * 3 + 1] * 3; val c = indices[t * 3 + 2] * 3
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
            val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
            area += 0.5 * sqrt((cx * cx + cy * cy + cz * cz).toDouble())
        }
        return area
    }

    companion object {
        val EMPTY = MeshData(FloatArray(0), FloatArray(0), ByteArray(0), IntArray(0))

        /**
         * Concatenates meshes. When [weld] is true, vertices with bit-identical positions are merged
         * (block meshes duplicate their border vertices) and their normals averaged.
         */
        fun merge(meshes: List<MeshData>, weld: Boolean = true): MeshData {
            val totalV = meshes.sumOf { it.vertexCount }
            val totalI = meshes.sumOf { it.indices.size }
            if (!weld) {
                val pos = FloatArray(totalV * 3); val nrm = FloatArray(totalV * 3); val col = ByteArray(totalV * 3)
                val idx = IntArray(totalI)
                var vo = 0; var io = 0
                for (m in meshes) {
                    m.positions.copyInto(pos, vo * 3); m.normals.copyInto(nrm, vo * 3); m.colors.copyInto(col, vo * 3)
                    for (i in m.indices.indices) idx[io + i] = m.indices[i] + vo
                    vo += m.vertexCount; io += m.indices.size
                }
                return MeshData(pos, nrm, col, idx)
            }
            val lookup = HashMap<PosKey, Int>(totalV * 2)
            val pos = FloatList(totalV * 3); val nrm = FloatList(totalV * 3); val col = ByteList(totalV * 3)
            val idx = IntArray(totalI)
            var io = 0
            for (m in meshes) {
                val remap = IntArray(m.vertexCount)
                for (v in 0 until m.vertexCount) {
                    val x = m.positions[v * 3]; val y = m.positions[v * 3 + 1]; val z = m.positions[v * 3 + 2]
                    val key = PosKey(x.toRawBits(), y.toRawBits(), z.toRawBits())
                    val existing = lookup[key]
                    if (existing != null) {
                        remap[v] = existing
                        nrm.add(existing * 3, m.normals[v * 3]); nrm.add(existing * 3 + 1, m.normals[v * 3 + 1]); nrm.add(existing * 3 + 2, m.normals[v * 3 + 2])
                    } else {
                        val ni = pos.size / 3
                        lookup[key] = ni
                        remap[v] = ni
                        pos.push(x); pos.push(y); pos.push(z)
                        nrm.push(m.normals[v * 3]); nrm.push(m.normals[v * 3 + 1]); nrm.push(m.normals[v * 3 + 2])
                        col.push(m.colors[v * 3]); col.push(m.colors[v * 3 + 1]); col.push(m.colors[v * 3 + 2])
                    }
                }
                for (i in m.indices.indices) idx[io + i] = remap[m.indices[i]]
                io += m.indices.size
            }
            val normals = nrm.toArray()
            normalizeInPlace(normals)
            return MeshData(pos.toArray(), normals, col.toArray(), idx)
        }

        internal fun normalizeInPlace(n: FloatArray) {
            var i = 0
            while (i < n.size) {
                val l = sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2])
                if (l > 1e-12f) { n[i] /= l; n[i + 1] /= l; n[i + 2] /= l } else { n[i] = 0f; n[i + 1] = 1f; n[i + 2] = 0f }
                i += 3
            }
        }
    }

    private data class PosKey(val x: Int, val y: Int, val z: Int)
}

/** Minimal growable float array to avoid boxing. */
internal class FloatList(initial: Int = 64) {
    private var data = FloatArray(max(initial, 4))
    var size = 0
        private set

    fun push(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun add(index: Int, v: Float) { data[index] += v }

    fun toArray(): FloatArray = data.copyOf(size)
}

internal class ByteList(initial: Int = 64) {
    private var data = ByteArray(max(initial, 4))
    var size = 0
        private set

    fun push(v: Byte) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): ByteArray = data.copyOf(size)
}

internal class IntList(initial: Int = 64) {
    private var data = IntArray(max(initial, 4))
    var size = 0
        private set

    fun push(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): IntArray = data.copyOf(size)
}
