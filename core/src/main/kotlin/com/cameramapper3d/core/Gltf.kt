package com.cameramapper3d.core

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** A decoded picture, for reading texture colours. Pixels are 0xAARRGGBB, row-major, top row first. */
class DecodedImage(val width: Int, val height: Int, val argb: IntArray)

/** Turns PNG/JPEG bytes into pixels. Supplied by the platform (Android's BitmapFactory, ImageIO on a PC). */
fun interface ImageDecoder {
    fun decode(bytes: ByteArray): DecodedImage?
}

/** sRGB ⇄ linear conversion. glTF stores vertex colours linearly; ScanMesh keeps sRGB like the camera. */
internal object ColorSpace {
    private val toLinear = FloatArray(256) { srgbToLinear(it / 255f) }
    fun linear(srgbByte: Int): Float = toLinear[srgbByte and 0xFF]
    fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    fun linearToSrgbByte(l: Float): Int {
        val c = l.coerceIn(0f, 1f)
        val s = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
        return (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}

/**
 * Writes binary glTF 2.0 (.glb) with per-vertex colours. GLB is the most widely supported 3D format
 * that keeps colour: Windows 3D Viewer, Blender, macOS/iOS and Android 3D viewers, game engines and
 * web viewers all show the colours without any extra setup. Units are metres and +Y is up, as glTF
 * requires.
 */
object GlbWriter {
    fun write(mesh: MeshData, out: OutputStream, name: String = "Scan") {
        if (mesh.isEmpty) throw IOException("Nothing to export: the scan has no surfaces")
        val v = mesh.vertexCount
        val indices = withoutDegenerateTriangles(mesh)
        val iCount = indices.size
        val posBytes = v * 12; val nrmBytes = v * 12; val colBytes = v * 12; val idxBytes = iCount * 4
        val binLength = posBytes + nrmBytes + colBytes + idxBytes // all multiples of 4
        val b = mesh.bounds()
        fun f(x: Float) = if (x.isFinite()) x.toString() else "0"

        val json = """{"asset":{"version":"2.0","generator":"ScanMesh by RajmanTech"},""" +
            """"scene":0,"scenes":[{"nodes":[0]}],""" +
            """"nodes":[{"mesh":0,"name":${Json.quote(name)}}],""" +
            """"meshes":[{"name":${Json.quote(name)},"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"COLOR_0":2},"indices":3,"material":0,"mode":4}]}],""" +
            """"materials":[{"name":"Scan colours","pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1],"metallicFactor":0,"roughnessFactor":1},"doubleSided":true}],""" +
            """"buffers":[{"byteLength":$binLength}],""" +
            """"bufferViews":[""" +
            """{"buffer":0,"byteOffset":0,"byteLength":$posBytes,"target":34962},""" +
            """{"buffer":0,"byteOffset":$posBytes,"byteLength":$nrmBytes,"target":34962},""" +
            """{"buffer":0,"byteOffset":${posBytes + nrmBytes},"byteLength":$colBytes,"target":34962},""" +
            """{"buffer":0,"byteOffset":${posBytes + nrmBytes + colBytes},"byteLength":$idxBytes,"target":34963}],""" +
            """"accessors":[""" +
            """{"bufferView":0,"componentType":5126,"count":$v,"type":"VEC3","min":[${f(b[0])},${f(b[1])},${f(b[2])}],"max":[${f(b[3])},${f(b[4])},${f(b[5])}]},""" +
            """{"bufferView":1,"componentType":5126,"count":$v,"type":"VEC3"},""" +
            """{"bufferView":2,"componentType":5126,"count":$v,"type":"VEC3"},""" +
            """{"bufferView":3,"componentType":5125,"count":$iCount,"type":"SCALAR"}]}"""
        var jsonBytes = json.toByteArray(Charsets.UTF_8)
        if (jsonBytes.size % 4 != 0) jsonBytes += ByteArray(4 - jsonBytes.size % 4) { ' '.code.toByte() }

        val bos = BufferedOutputStream(out, 1 shl 16)
        val head = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(0x46546C67).putInt(2).putInt(12 + 8 + jsonBytes.size + 8 + binLength)
        head.putInt(jsonBytes.size).putInt(0x4E4F534A)
        bos.write(head.array())
        bos.write(jsonBytes)
        val binHead = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(binLength).putInt(0x004E4942)
        bos.write(binHead.array())

        val buf = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
        fun flushIfFull() { if (buf.remaining() < 16) { bos.write(buf.array(), 0, buf.position()); buf.clear() } }
        for (x in mesh.positions) { buf.putFloat(x); flushIfFull() }
        for (x in mesh.normals) { buf.putFloat(x); flushIfFull() }
        for (c in mesh.colors) { buf.putFloat(ColorSpace.linear(c.toInt())); flushIfFull() }
        for (i in indices) { buf.putInt(i); flushIfFull() }
        bos.write(buf.array(), 0, buf.position())
        bos.flush()
    }

    /** Drops zero-area triangles (harmless, but viewers and validators flag them). */
    private fun withoutDegenerateTriangles(mesh: MeshData): IntArray {
        val p = mesh.positions
        val out = IntList(mesh.indices.size)
        for (t in 0 until mesh.triangleCount) {
            val a = mesh.indices[t * 3]; val b = mesh.indices[t * 3 + 1]; val c = mesh.indices[t * 3 + 2]
            if (a == b || b == c || a == c) continue
            val ux = p[b * 3] - p[a * 3]; val uy = p[b * 3 + 1] - p[a * 3 + 1]; val uz = p[b * 3 + 2] - p[a * 3 + 2]
            val vx = p[c * 3] - p[a * 3]; val vy = p[c * 3 + 1] - p[a * 3 + 1]; val vz = p[c * 3 + 2] - p[a * 3 + 2]
            val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
            if (cx * cx + cy * cy + cz * cz == 0f) continue
            out.push(a); out.push(b); out.push(c)
        }
        if (out.size == 0) throw IOException("Nothing to export: the scan has no surfaces")
        return out.toArray()
    }
}

/**
 * Reads binary glTF 2.0 (.glb) meshes from ScanMesh and other apps: all node transforms in the
 * scene, triangle primitives, vertex colours, and base-colour textures (sampled at each vertex,
 * using [ImageDecoder]) so models coloured by a photo texture keep their colour.
 */
object GlbReader {
    fun read(input: InputStream, images: ImageDecoder? = null): MeshData {
        val data = input.readBytes()
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (data.size < 20 || bb.getInt(0) != 0x46546C67) throw IOException("Not a GLB file")
        if (bb.getInt(4) != 2) throw IOException("Only glTF 2.0 files are supported")
        var json: Map<String, Any?> = emptyMap()
        var bin: ByteBuffer? = null
        var p = 12
        while (p + 8 <= data.size) {
            val len = bb.getInt(p); val type = bb.getInt(p + 4)
            if (len < 0 || p + 8 + len > data.size) throw IOException("The GLB file is damaged")
            when (type) {
                0x4E4F534A -> json = Json.parse(String(data, p + 8, len, Charsets.UTF_8)).obj()
                0x004E4942 -> if (bin == null) bin = ByteBuffer.wrap(data, p + 8, len).slice().order(ByteOrder.LITTLE_ENDIAN)
            }
            p += 8 + len
        }
        return Model(json, bin, images).toMesh()
    }

    private class Model(val g: Map<String, Any?>, val bin: ByteBuffer?, val images: ImageDecoder?) {
        val accessors = g["accessors"].arr()
        val views = g["bufferViews"].arr()
        val buffers = g["buffers"].arr().map { b -> lazy { bufferData(b.obj()) } }
        val decoded = HashMap<Int, DecodedImage?>()

        val pos = FloatList(); val nrm = FloatList(); val col = ByteList(); val idx = IntList()
        /** Set when any primitive has no normals; then all normals are recomputed from the faces. */
        var missingNormals = false

        fun toMesh(): MeshData {
            val unsupported = g["extensionsRequired"].arr().map { it.toString() }
                .filter { it == "KHR_draco_mesh_compression" || it == "EXT_meshopt_compression" || it == "KHR_mesh_quantization" }
            if (unsupported.isNotEmpty()) {
                throw IOException("This GLB is compressed (${unsupported.joinToString()}). Export it again without compression.")
            }
            val nodes = g["nodes"].arr()
            val scenes = g["scenes"].arr()
            val roots = scenes.getOrNull(g["scene"].int(0))?.obj()?.get("nodes").arr().map { it.int() }
            if (roots.isNotEmpty() || nodes.isNotEmpty()) {
                val start = roots.ifEmpty { nodes.indices.filter { i -> nodes.none { n -> n.obj()["children"].arr().any { it.int() == i } } } }
                for (r in start) visit(nodes, r, identity(), 0)
            } else {
                g["meshes"].arr().forEach { addMesh(it.obj(), identity()) }
            }
            val n = pos.size / 3
            return MeshImporter.finish(pos.toArray(), if (missingNormals) null else nrm.toArray(), col.toArray().takeIf { it.size == n * 3 }, idx.toArray())
        }

        private fun visit(nodes: List<Any?>, i: Int, parent: DoubleArray, depth: Int) {
            if (depth > 64) throw IOException("The GLB scene is nested too deeply")
            val node = nodes.getOrNull(i)?.obj() ?: return
            val world = mul(parent, localMatrix(node))
            node["mesh"].int().takeIf { it >= 0 }?.let { g["meshes"].arr().getOrNull(it)?.obj()?.let { m -> addMesh(m, world) } }
            for (c in node["children"].arr()) visit(nodes, c.int(), world, depth + 1)
        }

        private fun addMesh(mesh: Map<String, Any?>, m: DoubleArray) {
            // Normals use the inverse-transpose; a mirroring transform flips the winding.
            val nm = normalMatrix(m)
            val flip = det3(m) < 0
            for (prim in mesh["primitives"].arr().map { it.obj() }) {
                val mode = prim["mode"].int(4)
                if (mode !in 4..6) continue // points and lines carry no surface
                val attr = prim["attributes"].obj()
                val pa = attr["POSITION"].int().takeIf { it >= 0 } ?: continue
                val p = read(pa, 3)
                val count = p.size / 3
                val n = attr["NORMAL"].int().takeIf { it >= 0 }?.let { read(it, 3) }
                val colours = vertexColours(prim, attr, count)
                val base = pos.size / 3
                for (v in 0 until count) {
                    val x = p[v * 3].toDouble(); val y = p[v * 3 + 1].toDouble(); val z = p[v * 3 + 2].toDouble()
                    pos.push((m[0] * x + m[4] * y + m[8] * z + m[12]).toFloat())
                    pos.push((m[1] * x + m[5] * y + m[9] * z + m[13]).toFloat())
                    pos.push((m[2] * x + m[6] * y + m[10] * z + m[14]).toFloat())
                    if (n == null) {
                        repeat(3) { nrm.push(0f) }
                    } else {
                        val a = n[v * 3].toDouble(); val b = n[v * 3 + 1].toDouble(); val c = n[v * 3 + 2].toDouble()
                        var tx = nm[0] * a + nm[3] * b + nm[6] * c; var ty = nm[1] * a + nm[4] * b + nm[7] * c; var tz = nm[2] * a + nm[5] * b + nm[8] * c
                        val l = sqrt(tx * tx + ty * ty + tz * tz).takeIf { it > 1e-12 } ?: 1.0
                        tx /= l; ty /= l; tz /= l
                        nrm.push(tx.toFloat()); nrm.push(ty.toFloat()); nrm.push(tz.toFloat())
                    }
                    for (k in 0 until 3) col.push(colours[v * 3 + k])
                }
                if (n == null) missingNormals = true
                val raw = prim["indices"].int().takeIf { it >= 0 }?.let { readIndices(it) } ?: IntArray(count) { it }
                for (i in raw) if (i < 0 || i >= count) throw IOException("A GLB face refers to a missing vertex")
                fun tri(a: Int, b: Int, c: Int) {
                    if (a == b || b == c || a == c) return
                    idx.push(base + a)
                    if (flip) { idx.push(base + c); idx.push(base + b) } else { idx.push(base + b); idx.push(base + c) }
                }
                when (mode) {
                    4 -> for (t in 0 until raw.size / 3) tri(raw[t * 3], raw[t * 3 + 1], raw[t * 3 + 2])
                    5 -> for (t in 0 until raw.size - 2) if (t % 2 == 0) tri(raw[t], raw[t + 1], raw[t + 2]) else tri(raw[t + 1], raw[t], raw[t + 2])
                    6 -> for (t in 1 until raw.size - 1) tri(raw[0], raw[t], raw[t + 1])
                }
            }
        }

        /** sRGB colour per vertex from the material colour, its texture and the vertex colours. */
        private fun vertexColours(prim: Map<String, Any?>, attr: Map<String, Any?>, count: Int): ByteArray {
            val material = g["materials"].arr().getOrNull(prim["material"].int())?.obj()
            val pbr = material?.get("pbrMetallicRoughness").obj()
            val factor = pbr["baseColorFactor"].arr().map { it.num(1.0).toFloat() }.takeIf { it.size >= 3 } ?: listOf(1f, 1f, 1f, 1f)
            val vc = attr["COLOR_0"].int().takeIf { it >= 0 }?.let { a -> read(a, -1) to componentsOf(a) }
            var texture: DecodedImage? = null
            var uv: FloatArray? = null
            val texInfo = pbr["baseColorTexture"].obj()
            if (texInfo.isNotEmpty() && images != null) {
                val set = texInfo["texCoord"].int(0)
                val uvAcc = attr["TEXCOORD_$set"].int()
                val source = g["textures"].arr().getOrNull(texInfo["index"].int())?.obj()?.get("source").int()
                if (uvAcc >= 0 && source >= 0) {
                    texture = decoded.getOrPut(source) { imageBytes(source)?.let { b -> try { images.decode(b) } catch (e: Exception) { null } } }
                    if (texture != null) uv = read(uvAcc, 2)
                }
            }
            if (vc == null && texture == null && material == null) return ByteArray(count * 3) { 0xB4.toByte() }
            val out = ByteArray(count * 3)
            for (v in 0 until count) {
                var r = factor[0]; var gg = factor[1]; var b = factor[2]
                if (texture != null && uv != null) {
                    val t = sample(texture, uv[v * 2], uv[v * 2 + 1])
                    r *= ColorSpace.linear(t shr 16); gg *= ColorSpace.linear(t shr 8); b *= ColorSpace.linear(t)
                }
                if (vc != null) {
                    val (c, comps) = vc
                    r *= c[v * comps]; gg *= c[v * comps + 1]; b *= c[v * comps + 2]
                }
                out[v * 3] = ColorSpace.linearToSrgbByte(r).toByte()
                out[v * 3 + 1] = ColorSpace.linearToSrgbByte(gg).toByte()
                out[v * 3 + 2] = ColorSpace.linearToSrgbByte(b).toByte()
            }
            return out
        }

        private fun sample(img: DecodedImage, u: Float, v: Float): Int {
            val fu = u - kotlin.math.floor(u); val fv = v - kotlin.math.floor(v) // repeat wrapping
            val x = (fu * img.width).toInt().coerceIn(0, img.width - 1)
            val y = (fv * img.height).toInt().coerceIn(0, img.height - 1)
            return img.argb[y * img.width + x]
        }

        private fun imageBytes(i: Int): ByteArray? {
            val img = g["images"].arr().getOrNull(i)?.obj() ?: return null
            val view = img["bufferView"].int()
            if (view >= 0) {
                val (buf, off, len, _) = viewSlice(view)
                return ByteArray(len).also { a -> for (k in 0 until len) a[k] = buf.get(off + k) }
            }
            return dataUri(img["uri"] as? String)
        }

        private fun componentsOf(accessor: Int): Int = when (accessors.getOrNull(accessor)?.obj()?.get("type")) {
            "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> throw IOException("Unsupported GLB accessor type")
        }

        /** Reads an accessor as floats (normalised integers become 0..1 or -1..1). [expect] = components, or -1 for any. */
        private fun read(accessor: Int, expect: Int): FloatArray {
            val a = accessors.getOrNull(accessor)?.obj() ?: throw IOException("Missing GLB accessor $accessor")
            if (a.containsKey("sparse")) throw IOException("Sparse GLB accessors are not supported")
            val comps = componentsOf(accessor)
            if (expect > 0 && comps != expect) throw IOException("Unexpected GLB accessor layout")
            val count = a["count"].int(0)
            val type = a["componentType"].int()
            val normalized = a["normalized"] == true || (type != 5126 && expect < 0) // colours are always normalised
            val out = FloatArray(count * comps)
            val viewIndex = a["bufferView"].int()
            if (viewIndex < 0) return out // all zeros, as the spec says
            val (buf, off, len, stride) = viewSlice(viewIndex)
            val size = sizeOf(type)
            val step = if (stride > 0) stride else size * comps
            val start = off + a["byteOffset"].int(0)
            if (count > 0 && start - off + (count - 1) * step + size * comps > len) throw IOException("A GLB accessor runs past its data")
            for (e in 0 until count) for (c in 0 until comps) {
                val at = start + e * step + c * size
                out[e * comps + c] = when (type) {
                    5126 -> buf.getFloat(at)
                    5121 -> (buf.get(at).toInt() and 0xFF).let { if (normalized) it / 255f else it.toFloat() }
                    5123 -> (buf.getShort(at).toInt() and 0xFFFF).let { if (normalized) it / 65535f else it.toFloat() }
                    5120 -> buf.get(at).toInt().let { if (normalized) maxOf(it / 127f, -1f) else it.toFloat() }
                    5122 -> buf.getShort(at).toInt().let { if (normalized) maxOf(it / 32767f, -1f) else it.toFloat() }
                    5125 -> (buf.getInt(at).toLong() and 0xFFFFFFFFL).toFloat()
                    else -> throw IOException("Unsupported GLB component type $type")
                }
            }
            return out
        }

        private fun readIndices(accessor: Int): IntArray {
            val a = accessors.getOrNull(accessor)?.obj() ?: throw IOException("Missing GLB accessor $accessor")
            val count = a["count"].int(0)
            val type = a["componentType"].int()
            val (buf, off, _, stride) = viewSlice(a["bufferView"].int())
            val size = sizeOf(type)
            val step = if (stride > 0) stride else size
            val start = off + a["byteOffset"].int(0)
            return IntArray(count) { e ->
                val at = start + e * step
                when (type) {
                    5121 -> buf.get(at).toInt() and 0xFF
                    5123 -> buf.getShort(at).toInt() and 0xFFFF
                    5125 -> buf.getInt(at)
                    else -> throw IOException("Unsupported GLB index type $type")
                }
            }
        }

        private data class Slice(val buf: ByteBuffer, val offset: Int, val length: Int, val stride: Int)

        private fun viewSlice(i: Int): Slice {
            val v = views.getOrNull(i)?.obj() ?: throw IOException("Missing GLB bufferView $i")
            val buf = buffers.getOrNull(v["buffer"].int(0))?.value ?: throw IOException("Missing GLB buffer")
            val off = v["byteOffset"].int(0); val len = v["byteLength"].int(0)
            if (off < 0 || len < 0 || off + len > buf.limit()) throw IOException("A GLB bufferView runs past its buffer")
            return Slice(buf, off, len, v["byteStride"].int(0))
        }

        private fun bufferData(b: Map<String, Any?>): ByteBuffer {
            val uri = b["uri"] as? String
            if (uri == null) return bin ?: throw IOException("The GLB has no binary data")
            val bytes = dataUri(uri) ?: throw IOException("This model keeps its data in a separate file; export it as a single .glb instead")
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }

        private fun dataUri(uri: String?): ByteArray? {
            if (uri == null || !uri.startsWith("data:")) return null
            val comma = uri.indexOf(',')
            if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) return null
            return Base64.getDecoder().decode(uri.substring(comma + 1))
        }

        private fun sizeOf(type: Int) = when (type) {
            5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> throw IOException("Unsupported GLB component type $type")
        }

        // --- 4×4 column-major matrices ---

        private fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)

        private fun localMatrix(node: Map<String, Any?>): DoubleArray {
            node["matrix"].arr().takeIf { it.size == 16 }?.let { m -> return DoubleArray(16) { m[it].num() } }
            val t = node["translation"].arr().map { it.num() }.takeIf { it.size == 3 } ?: listOf(0.0, 0.0, 0.0)
            val r = node["rotation"].arr().map { it.num() }.takeIf { it.size == 4 } ?: listOf(0.0, 0.0, 0.0, 1.0)
            val s = node["scale"].arr().map { it.num() }.takeIf { it.size == 3 } ?: listOf(1.0, 1.0, 1.0)
            val (x, y, z, w) = r
            val rm = doubleArrayOf(
                1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0.0,
                2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0.0,
                2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0.0,
                0.0, 0.0, 0.0, 1.0,
            )
            for (c in 0 until 3) for (k in 0 until 3) rm[c * 4 + k] *= s[c]
            rm[12] = t[0]; rm[13] = t[1]; rm[14] = t[2]
            return rm
        }

        private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(16) { i ->
            val col = i / 4; val row = i % 4
            (0 until 4).sumOf { k -> a[k * 4 + row] * b[col * 4 + k] }
        }

        private fun det3(m: DoubleArray) =
            m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])

        /** Inverse-transpose of the upper 3×3, column-major (3×3). */
        private fun normalMatrix(m: DoubleArray): DoubleArray {
            val a = m[0]; val b = m[4]; val c = m[8]; val d = m[1]; val e = m[5]; val f = m[9]; val gg = m[2]; val h = m[6]; val i = m[10]
            val det = det3(m).takeIf { abs(it) > 1e-30 } ?: 1.0
            // Cofactor matrix / det = inverse-transpose; stored column-major.
            return doubleArrayOf(
                (e * i - f * h) / det, -(b * i - c * h) / det, (b * f - c * e) / det,
                -(d * i - f * gg) / det, (a * i - c * gg) / det, -(a * f - c * d) / det,
                (d * h - e * gg) / det, -(a * h - b * gg) / det, (a * e - b * d) / det,
            )
        }
    }
}
