package com.cameramapper3d.core

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.sqrt


/** Opens GLB, PLY or OBJ meshes from ScanMesh or other apps and scanners. Units are assumed to be metres. */
object MeshImporter {
    /**
     * Reads a GLB, PLY or OBJ file, detecting which from its first bytes.
     * @param images decodes texture pictures, so textured GLB models keep their colours.
     */
    fun read(input: InputStream, images: ImageDecoder? = null): MeshData {
        val stream = BufferedInputStream(input, 1 shl 16)
        stream.mark(4)
        val magic = ByteArray(4)
        var n = 0 // (InputStream.readNBytes is not available on older Android)
        while (n < 4) { val r = stream.read(magic, n, 4 - n); if (r < 0) break; n += r }
        stream.reset()
        val head = String(magic, 0, n, Charsets.US_ASCII)
        return when {
            head == "glTF" -> GlbReader.read(stream, images)
            head.startsWith("ply") -> PlyReader.read(stream)
            else -> ObjReader.read(stream)
        }
    }

    internal fun finish(pos: FloatArray, normals: FloatArray?, colors: ByteArray?, idx: IntArray): MeshData {
        if (idx.isEmpty()) throw IOException("The file has no surfaces (it may be a point cloud).")
        val n = normals ?: computeNormals(pos, idx)
        if (normals != null) {
            // Leave normals that are already unit length untouched; fix any that are not.
            var i = 0
            while (i < n.size) {
                val l = sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2])
                if (abs(l - 1f) > 1e-3f) {
                    if (l > 1e-9f) { n[i] /= l; n[i + 1] /= l; n[i + 2] /= l } else { n[i] = 0f; n[i + 1] = 1f; n[i + 2] = 0f }
                }
                i += 3
            }
        }
        val c = colors ?: ByteArray(pos.size) { 0xB4.toByte() }
        return MeshData(pos, n, c, idx)
    }

    /** Area-weighted vertex normals from the triangles (winding decides the side). */
    fun computeNormals(pos: FloatArray, idx: IntArray): FloatArray {
        val n = FloatArray(pos.size)
        for (t in 0 until idx.size / 3) {
            val a = idx[t * 3] * 3; val b = idx[t * 3 + 1] * 3; val c = idx[t * 3 + 2] * 3
            val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
            val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
            for (v in intArrayOf(a, b, c)) { n[v] += cx; n[v + 1] += cy; n[v + 2] += cz }
        }
        MeshData.normalizeInPlace(n)
        return n
    }
}

/**
 * Reads PLY meshes: ASCII and binary (either byte order), any property types, optional normals and
 * colours (uchar 0–255 or float 0–1), and polygon faces (split into triangles). Extra elements and
 * properties are skipped.
 */
object PlyReader {
    private class Prop(val name: String, val type: String, val listCountType: String? = null)
    private class Element(val name: String, val count: Int, val props: MutableList<Prop> = ArrayList())

    fun read(input: InputStream): MeshData {
        val din = DataInputStream(if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16))
        val header = ArrayList<String>()
        while (true) {
            val line = readLine(din) ?: throw IOException("Unexpected end of PLY header")
            header += line.trim()
            if (line.trim() == "end_header") break
            if (header.size > 10_000) throw IOException("PLY header too long")
        }
        if (header.first() != "ply") throw IOException("Not a PLY file")
        val format = header.firstOrNull { it.startsWith("format ") }?.split(Regex("\\s+"))?.getOrNull(1)
            ?: throw IOException("PLY format line missing")
        val elements = ArrayList<Element>()
        for (l in header) {
            val p = l.split(Regex("\\s+"))
            when {
                p[0] == "element" && p.size >= 3 -> elements += Element(p[1], p[2].toIntOrNull() ?: throw IOException("Bad element count"))
                p[0] == "property" && p.size >= 3 && elements.isNotEmpty() -> elements.last().props +=
                    if (p[1] == "list" && p.size >= 5) Prop(p[4], p[3], p[2]) else Prop(p[2], p[1])
            }
        }
        val reader: ValueReader = when (format) {
            "ascii" -> AsciiValues(din)
            "binary_little_endian" -> BinaryValues(din, littleEndian = true)
            "binary_big_endian" -> BinaryValues(din, littleEndian = false)
            else -> throw IOException("Unknown PLY format '$format'")
        }

        var pos = FloatArray(0); var nrm: FloatArray? = null; var col: ByteArray? = null
        val idx = IntList()
        var vertexCount = 0
        for (el in elements) {
            when (el.name) {
                "vertex" -> {
                    vertexCount = el.count
                    val names = el.props.map { it.name }
                    fun at(vararg options: String) = options.firstNotNullOfOrNull { o -> names.indexOf(o).takeIf { it >= 0 } } ?: -1
                    val ix = at("x"); val iy = at("y"); val iz = at("z")
                    if (ix < 0 || iy < 0 || iz < 0) throw IOException("PLY vertices have no x/y/z")
                    val inx = at("nx"); val iny = at("ny"); val inz = at("nz")
                    val ir = at("red", "r", "diffuse_red"); val ig = at("green", "g", "diffuse_green"); val ib = at("blue", "b", "diffuse_blue")
                    val hasN = inx >= 0 && iny >= 0 && inz >= 0
                    val hasC = ir >= 0 && ig >= 0 && ib >= 0
                    pos = FloatArray(el.count * 3)
                    if (hasN) nrm = FloatArray(el.count * 3)
                    if (hasC) col = ByteArray(el.count * 3)
                    val values = DoubleArray(el.props.size)
                    for (v in 0 until el.count) {
                        for ((k, prop) in el.props.withIndex()) {
                            if (prop.listCountType != null) {
                                repeat(reader.read(prop.listCountType).toInt()) { reader.read(prop.type) }
                                values[k] = 0.0
                            } else {
                                values[k] = reader.read(prop.type)
                            }
                        }
                        pos[v * 3] = values[ix].toFloat(); pos[v * 3 + 1] = values[iy].toFloat(); pos[v * 3 + 2] = values[iz].toFloat()
                        if (hasN) { nrm!![v * 3] = values[inx].toFloat(); nrm[v * 3 + 1] = values[iny].toFloat(); nrm[v * 3 + 2] = values[inz].toFloat() }
                        if (hasC) for ((a, i) in intArrayOf(ir, ig, ib).withIndex()) {
                            val isFloat = el.props[i].type.startsWith("float") || el.props[i].type.startsWith("double")
                            val c = if (isFloat) values[i] * 255.0 else values[i]
                            col!![v * 3 + a] = c.toInt().coerceIn(0, 255).toByte()
                        }
                    }
                }
                "face" -> {
                    val li = el.props.indexOfFirst { it.listCountType != null && (it.name == "vertex_indices" || it.name == "vertex_index") }
                    if (li < 0) throw IOException("PLY faces have no vertex_indices")
                    for (f in 0 until el.count) {
                        for ((k, prop) in el.props.withIndex()) {
                            if (prop.listCountType == null) { reader.read(prop.type); continue }
                            val n = reader.read(prop.listCountType).toInt()
                            if (k != li) { repeat(n) { reader.read(prop.type) }; continue }
                            val list = IntArray(n) { reader.read(prop.type).toInt() }
                            for (v in list) if (v < 0 || v >= vertexCount) throw IOException("Face index out of range")
                            for (j in 1 until n - 1) { idx.push(list[0]); idx.push(list[j]); idx.push(list[j + 1]) } // fan
                        }
                    }
                }
                else -> for (i in 0 until el.count) for (prop in el.props) {
                    if (prop.listCountType != null) repeat(reader.read(prop.listCountType).toInt()) { reader.read(prop.type) }
                    else reader.read(prop.type)
                }
            }
        }
        return MeshImporter.finish(pos, nrm, col, idx.toArray())
    }

    private interface ValueReader { fun read(type: String): Double }

    private class BinaryValues(private val din: DataInputStream, private val littleEndian: Boolean) : ValueReader {
        override fun read(type: String): Double = try {
            when (type) {
                "char", "int8" -> din.readByte().toDouble()
                "uchar", "uint8" -> din.readUnsignedByte().toDouble()
                "short", "int16" -> s16().toDouble()
                "ushort", "uint16" -> (s16().toInt() and 0xFFFF).toDouble()
                "int", "int32" -> s32().toDouble()
                "uint", "uint32" -> (s32().toLong() and 0xFFFFFFFFL).toDouble()
                "float", "float32" -> Float.fromBits(s32()).toDouble()
                "double", "float64" -> Double.fromBits(s64())
                else -> throw IOException("Unknown PLY property type '$type'")
            }
        } catch (e: EOFException) {
            throw IOException("The PLY file ends early")
        }
        private fun s16(): Short = din.readShort().let { if (littleEndian) java.lang.Short.reverseBytes(it) else it }
        private fun s32(): Int = din.readInt().let { if (littleEndian) Integer.reverseBytes(it) else it }
        private fun s64(): Long = din.readLong().let { if (littleEndian) java.lang.Long.reverseBytes(it) else it }
    }

    private class AsciiValues(private val din: DataInputStream) : ValueReader {
        private var tokens: List<String> = emptyList()
        private var next = 0
        override fun read(type: String): Double {
            while (next >= tokens.size) {
                val line = readLine(din) ?: throw IOException("The PLY file ends early")
                tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                next = 0
            }
            return tokens[next++].toDoubleOrNull() ?: throw IOException("Bad number in PLY file")
        }
    }

    private fun readLine(din: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = din.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 1_000_000) throw IOException("PLY line too long")
        }
    }
}

/**
 * Reads Wavefront OBJ meshes: "v x y z [r g b]" vertices (colours 0–1), and faces in any of the
 * "f a b c", "a/t", "a//n", "a/t/n" forms, including negative (relative) indices and polygons.
 * Normals are recomputed from the faces; materials and textures are ignored.
 */
object ObjReader {
    fun read(input: InputStream): MeshData {
        val pos = FloatList(); val col = ByteList()
        var anyColour = false
        val idx = IntList()
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.startsWith("v ")) {
                    val p = line.split(Regex("\\s+"))
                    if (p.size < 4) throw IOException("Bad OBJ vertex: $line")
                    for (k in 1..3) pos.push(p[k].toFloatOrNull() ?: throw IOException("Bad OBJ vertex: $line"))
                    if (p.size >= 7) {
                        anyColour = true
                        for (k in 4..6) col.push(((p[k].toFloatOrNull() ?: 0.7f) * 255f).toInt().coerceIn(0, 255).toByte())
                    } else {
                        repeat(3) { col.push(0xB4.toByte()) }
                    }
                } else if (line.startsWith("f ")) {
                    val vertexCount = pos.size / 3
                    val refs = line.split(Regex("\\s+")).drop(1).map { tok ->
                        val i = tok.substringBefore('/').toIntOrNull() ?: throw IOException("Bad OBJ face: $line")
                        val v = if (i < 0) vertexCount + i else i - 1
                        if (v < 0 || v >= vertexCount) throw IOException("OBJ face index out of range: $line")
                        v
                    }
                    for (j in 1 until refs.size - 1) { idx.push(refs[0]); idx.push(refs[j]); idx.push(refs[j + 1]) }
                }
            }
        }
        return MeshImporter.finish(pos.toArray(), null, if (anyColour) col.toArray() else null, idx.toArray())
    }
}

