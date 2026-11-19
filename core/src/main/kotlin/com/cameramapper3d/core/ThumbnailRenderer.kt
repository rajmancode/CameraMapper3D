package com.cameramapper3d.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Renders a small preview image of a mesh in software (no GPU needed), so thumbnails can be made
 * on any thread and tested off-device. The view matches the viewer's default: looking at the
 * centre of the model from slightly above, from the side the scan started on.
 */
object ThumbnailRenderer {
    /**
     * @return [size]×[size] pixels as packed 0xAARRGGBB, row-major, top row first.
     * @param supersample render this many times larger and average down, for smooth edges.
     */
    fun render(
        mesh: MeshData,
        size: Int = 256,
        background: Int = 0xFF1C1F24.toInt(),
        yaw: Float = 0f,
        pitch: Float = 0.45f,
        fovDegrees: Float = 50f,
        supersample: Int = 2,
    ): IntArray {
        require(size > 0 && supersample >= 1)
        val s = size * supersample
        val color = IntArray(s * s) { background }
        if (!mesh.isEmpty) rasterize(mesh, s, color, yaw, pitch, fovDegrees)
        return if (supersample == 1) color else downsample(color, s, supersample)
    }

    private fun rasterize(mesh: MeshData, s: Int, color: IntArray, yaw: Float, pitch: Float, fovDegrees: Float) {
        val b = mesh.bounds()
        val cx = (b[0] + b[3]) / 2; val cy = (b[1] + b[4]) / 2; val cz = (b[2] + b[5]) / 2
        val radius = max(1e-3f, 0.5f * sqrt((b[3] - b[0]).sq() + (b[4] - b[1]).sq() + (b[5] - b[2]).sq()))
        val halfFov = Math.toRadians(fovDegrees / 2.0).toFloat()
        val dist = radius / sin(halfFov) * 0.9f
        val ex = cx + dist * cos(pitch) * sin(yaw)
        val ey = cy + dist * sin(pitch)
        val ez = cz + dist * cos(pitch) * cos(yaw)

        // Camera basis: forward f, right r, up u.
        var fx = cx - ex; var fy = cy - ey; var fz = cz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        var rx = -fz; val ry = 0f; var rz = fx // f × (0,1,0)
        val rl = sqrt(rx * rx + rz * rz); rx /= rl; rz /= rl
        val ux = ry * fz - rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy - ry * fx

        // Light comes from over the viewer's shoulder.
        var lx = -fx + 0.3f * ux - 0.2f * rx; var ly = -fy + 0.3f * uy - 0.2f * ry; var lz = -fz + 0.3f * uz - 0.2f * rz
        val ll = sqrt(lx * lx + ly * ly + lz * lz); lx /= ll; ly /= ll; lz /= ll

        val focal = (s / 2f) / tan(halfFov)
        val n = mesh.vertexCount
        val sx = FloatArray(n); val sy = FloatArray(n); val sz = FloatArray(n)
        val shade = FloatArray(n)
        val p = mesh.positions; val nr = mesh.normals
        val near = dist * 0.05f
        for (i in 0 until n) {
            val vx = p[i * 3] - ex; val vy = p[i * 3 + 1] - ey; val vz = p[i * 3 + 2] - ez
            val z = vx * fx + vy * fy + vz * fz
            sz[i] = z
            if (z > near) {
                sx[i] = s / 2f + (vx * rx + vy * ry + vz * rz) / z * focal
                sy[i] = s / 2f - (vx * ux + vy * uy + vz * uz) / z * focal
            }
            val d = nr[i * 3] * lx + nr[i * 3 + 1] * ly + nr[i * 3 + 2] * lz
            shade[i] = 0.45f + 0.55f * abs(d) // two-sided: scans are open surfaces
        }

        val depth = FloatArray(s * s) { Float.MAX_VALUE }
        val idx = mesh.indices
        val col = mesh.colors
        for (t in 0 until mesh.triangleCount) {
            val a = idx[t * 3]; val bb = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
            if (sz[a] <= near || sz[bb] <= near || sz[c] <= near) continue
            val x0 = sx[a]; val y0 = sy[a]; val x1 = sx[bb]; val y1 = sy[bb]; val x2 = sx[c]; val y2 = sy[c]
            val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if (abs(area) < 1e-9f) continue
            val minX = max(0, min(x0, min(x1, x2)).toInt()); val maxX = min(s - 1, max(x0, max(x1, x2)).toInt() + 1)
            val minY = max(0, min(y0, min(y1, y2)).toInt()); val maxY = min(s - 1, max(y0, max(y1, y2)).toInt() + 1)
            if (minX > maxX || minY > maxY) continue
            val inv = 1f / area
            for (py in minY..maxY) {
                val yc = py + 0.5f
                for (px in minX..maxX) {
                    val xc = px + 0.5f
                    val w0 = ((x1 - xc) * (y2 - yc) - (x2 - xc) * (y1 - yc)) * inv
                    val w1 = ((x2 - xc) * (y0 - yc) - (x0 - xc) * (y2 - yc)) * inv
                    val w2 = 1f - w0 - w1
                    if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                    val z = w0 * sz[a] + w1 * sz[bb] + w2 * sz[c]
                    val pi = py * s + px
                    if (z >= depth[pi]) continue
                    depth[pi] = z
                    val l = w0 * shade[a] + w1 * shade[bb] + w2 * shade[c]
                    val r = (w0 * u8(col[a * 3]) + w1 * u8(col[bb * 3]) + w2 * u8(col[c * 3])) * l
                    val g = (w0 * u8(col[a * 3 + 1]) + w1 * u8(col[bb * 3 + 1]) + w2 * u8(col[c * 3 + 1])) * l
                    val bl = (w0 * u8(col[a * 3 + 2]) + w1 * u8(col[bb * 3 + 2]) + w2 * u8(col[c * 3 + 2])) * l
                    color[pi] = (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(bl)
                }
            }
        }
    }

    private fun downsample(src: IntArray, s: Int, k: Int): IntArray {
        val size = s / k
        val out = IntArray(size * size)
        val n = k * k
        for (y in 0 until size) for (x in 0 until size) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0 until k) for (dx in 0 until k) {
                val c = src[(y * k + dy) * s + x * k + dx]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            out[y * size + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return out
    }

    private fun u8(b: Byte) = (b.toInt() and 0xFF).toFloat()
    private fun clamp(v: Float) = v.toInt().coerceIn(0, 255)
    private fun Float.sq() = this * this
}
