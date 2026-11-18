package com.cameramapper3d.core

import com.cameramapper3d.core.TsdfVolume.Companion.BLOCK
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Extracts the zero level set of a [TsdfVolume] as a triangle mesh using Naive Surface Nets.
 *
 * One vertex is placed in every voxel cell the surface passes through (at the mean of the
 * edge crossings), and one quad is emitted for every voxel edge the surface crosses,
 * joining the four cells around that edge. Each block can be meshed on its own, which keeps
 * live updates incremental; neighbouring block meshes share identical border vertices,
 * which [MeshData.merge] welds back together.
 *
 * @param minWeight voxels observed fewer times than this are treated as unknown (filters noise).
 * @param maxJump crossings between samples whose values differ by more than this are ignored.
 *   This removes the spurious surfaces TSDFs produce where +1 (far free space) meets
 *   negative values from another surface.
 */
class SurfaceNetsMesher(
    val minWeight: Int = 2,
    val maxJump: Float = 1.0f,
) {
    private val p = BLOCK + 2 // padded samples per axis: local -1..BLOCK
    private val c = BLOCK + 1 // cells per axis: local -1..BLOCK-1

    // Scratch buffers, reused between blocks (the mesher is single-threaded like the volume).
    private val f = FloatArray(p * p * p)
    private val w = IntArray(p * p * p)
    private val col = IntArray(p * p * p)
    private val cellPos = FloatArray(c * c * c * 3)
    private val cellNrm = FloatArray(c * c * c * 3)
    private val cellCol = IntArray(c * c * c)
    private val cellHas = BooleanArray(c * c * c)
    private val cellOut = IntArray(c * c * c)
    private val neighbours = arrayOfNulls<VoxelBlock>(27)

    /** Meshes every allocated block and welds the result. */
    fun meshAll(volume: TsdfVolume): MeshData {
        val parts = ArrayList<MeshData>()
        for (key in volume.blockKeys().toList()) meshBlock(volume, key)?.let(parts::add)
        return MeshData.merge(parts, weld = true)
    }

    /** Meshes one block, or returns null when it contains no surface. */
    fun meshBlock(volume: TsdfVolume, key: Long): MeshData? {
        val bx = TsdfVolume.unpackX(key); val by = TsdfVolume.unpackY(key); val bz = TsdfVolume.unpackZ(key)
        volume.block(key) ?: return null
        for (dz in -1..1) for (dy in -1..1) for (dx in -1..1) {
            neighbours[(dz + 1) * 9 + (dy + 1) * 3 + (dx + 1)] = volume.block(TsdfVolume.packKey(bx + dx, by + dy, bz + dz))
        }
        if (!gatherSamples()) return null

        val vs = volume.voxelSize
        val originX = bx * BLOCK; val originY = by * BLOCK; val originZ = bz * BLOCK
        computeCells(originX, originY, originZ, vs)

        val pos = FloatList(); val nrm = FloatList(); val cl = ByteList(); val idx = IntList()
        cellOut.fill(-1)
        fun out(cell: Int): Int {
            var o = cellOut[cell]
            if (o < 0) {
                o = pos.size / 3
                cellOut[cell] = o
                pos.push(cellPos[cell * 3]); pos.push(cellPos[cell * 3 + 1]); pos.push(cellPos[cell * 3 + 2])
                nrm.push(cellNrm[cell * 3]); nrm.push(cellNrm[cell * 3 + 1]); nrm.push(cellNrm[cell * 3 + 2])
                val rgb = cellCol[cell]
                cl.push((rgb shr 16).toByte()); cl.push((rgb shr 8).toByte()); cl.push(rgb.toByte())
            }
            return o
        }

        // Edges whose origin voxel lies inside this block (local 0..BLOCK-1) belong to this block.
        val q = IntArray(4)
        for (z in 0 until BLOCK) for (y in 0 until BLOCK) for (x in 0 until BLOCK) {
            val s0 = sIdx(x, y, z)
            if (w[s0] < minWeight) continue
            val f0 = f[s0]
            for (axis in 0 until 3) {
                val s1 = when (axis) { 0 -> sIdx(x + 1, y, z); 1 -> sIdx(x, y + 1, z); else -> sIdx(x, y, z + 1) }
                if (w[s1] < minWeight) continue
                val f1 = f[s1]
                if ((f0 < 0f) == (f1 < 0f) || abs(f0 - f1) > maxJump) continue
                // Four cells around the edge, in counter-clockwise order about +axis.
                var ok = true
                for (n in 0 until 4) {
                    val db = if (n == 1 || n == 2) -1 else 0
                    val dc = if (n >= 2) -1 else 0
                    val cell = when (axis) {
                        0 -> cIdx(x, y + db, z + dc)
                        1 -> cIdx(x + dc, y, z + db)
                        else -> cIdx(x + db, y + dc, z)
                    }
                    if (!cellHas[cell]) { ok = false; break }
                    q[n] = cell
                }
                if (!ok) continue
                val a = out(q[0]); val b = out(q[1]); val cc = out(q[2]); val d = out(q[3])
                // That ordering faces +axis; the surface should face towards free space (larger f).
                if (f1 > f0) {
                    idx.push(a); idx.push(b); idx.push(cc)
                    idx.push(a); idx.push(cc); idx.push(d)
                } else {
                    idx.push(a); idx.push(cc); idx.push(b)
                    idx.push(a); idx.push(d); idx.push(cc)
                }
            }
        }
        if (idx.size == 0) return null
        return MeshData(pos.toArray(), nrm.toArray(), cl.toArray(), idx.toArray())
    }

    /** Padded sample index for local voxel coords in -1..BLOCK. */
    private fun sIdx(x: Int, y: Int, z: Int) = ((z + 1) * p + (y + 1)) * p + (x + 1)

    /** Cell index for local cell coords (min corner) in -1..BLOCK-1. */
    private fun cIdx(x: Int, y: Int, z: Int) = ((z + 1) * c + (y + 1)) * c + (x + 1)

    /** Copies this block plus a one-voxel border into the scratch arrays. Returns false if there is no sign change. */
    private fun gatherSamples(): Boolean {
        var hasPos = false; var hasNeg = false
        var i = 0
        for (lz in -1..BLOCK) {
            val oz = if (lz < 0) -1 else if (lz >= BLOCK) 1 else 0
            val vz = lz - oz * BLOCK
            for (ly in -1..BLOCK) {
                val oy = if (ly < 0) -1 else if (ly >= BLOCK) 1 else 0
                val vy = ly - oy * BLOCK
                for (lx in -1..BLOCK) {
                    val ox = if (lx < 0) -1 else if (lx >= BLOCK) 1 else 0
                    val vx = lx - ox * BLOCK
                    val blk = neighbours[(oz + 1) * 9 + (oy + 1) * 3 + (ox + 1)]
                    if (blk == null) {
                        f[i] = 1f; w[i] = 0; col[i] = 0x808080
                    } else {
                        val vi = TsdfVolume.voxelIndex(vx, vy, vz)
                        val value = blk.tsdfAt(vi)
                        val weight = blk.weightAt(vi)
                        f[i] = value; w[i] = weight
                        col[i] = ((blk.rgb[vi * 3].toInt() and 0xFF) shl 16) or
                            ((blk.rgb[vi * 3 + 1].toInt() and 0xFF) shl 8) or
                            (blk.rgb[vi * 3 + 2].toInt() and 0xFF)
                        if (weight >= minWeight) { if (value < 0f) hasNeg = true else hasPos = true }
                    }
                    i++
                }
            }
        }
        return hasPos && hasNeg
    }

    private fun computeCells(originX: Int, originY: Int, originZ: Int, vs: Float) {
        val cornerIdx = IntArray(8)
        for (z in -1 until BLOCK) for (y in -1 until BLOCK) for (x in -1 until BLOCK) {
            val cell = cIdx(x, y, z)
            cellHas[cell] = false
            var valid = true
            for (k in 0 until 8) {
                val s = sIdx(x + (k and 1), y + ((k shr 1) and 1), z + ((k shr 2) and 1))
                if (w[s] < minWeight) { valid = false; break }
                cornerIdx[k] = s
            }
            if (!valid) continue

            var sx = 0f; var sy = 0f; var sz = 0f
            var sr = 0f; var sg = 0f; var sb = 0f
            var n = 0
            for (e in 0 until 12) {
                val ka = EDGE_A[e]; val kb = EDGE_B[e]
                val fa = f[cornerIdx[ka]]; val fb = f[cornerIdx[kb]]
                if ((fa < 0f) == (fb < 0f) || abs(fa - fb) > maxJump) continue
                val t = fa / (fa - fb)
                sx += (ka and 1) + t * ((kb and 1) - (ka and 1))
                sy += ((ka shr 1) and 1) + t * (((kb shr 1) and 1) - ((ka shr 1) and 1))
                sz += ((ka shr 2) and 1) + t * (((kb shr 2) and 1) - ((ka shr 2) and 1))
                val ca = col[cornerIdx[ka]]; val cb = col[cornerIdx[kb]]
                sr += lerp((ca shr 16) and 0xFF, (cb shr 16) and 0xFF, t)
                sg += lerp((ca shr 8) and 0xFF, (cb shr 8) and 0xFF, t)
                sb += lerp(ca and 0xFF, cb and 0xFF, t)
                n++
            }
            if (n == 0) continue
            val inv = 1f / n
            cellHas[cell] = true
            cellPos[cell * 3] = (originX + x + sx * inv) * vs
            cellPos[cell * 3 + 1] = (originY + y + sy * inv) * vs
            cellPos[cell * 3 + 2] = (originZ + z + sz * inv) * vs
            cellCol[cell] = (clampByte(sr * inv) shl 16) or (clampByte(sg * inv) shl 8) or clampByte(sb * inv)

            // Gradient of the trilinear field at the cell centre points towards free space.
            var gx = 0f; var gy = 0f; var gz = 0f
            for (k in 0 until 8) {
                val v = f[cornerIdx[k]]
                gx += if (k and 1 != 0) v else -v
                gy += if (k and 2 != 0) v else -v
                gz += if (k and 4 != 0) v else -v
            }
            val len = sqrt(gx * gx + gy * gy + gz * gz)
            if (len > 1e-9f) {
                cellNrm[cell * 3] = gx / len; cellNrm[cell * 3 + 1] = gy / len; cellNrm[cell * 3 + 2] = gz / len
            } else {
                cellNrm[cell * 3] = 0f; cellNrm[cell * 3 + 1] = 1f; cellNrm[cell * 3 + 2] = 0f
            }
        }
    }

    private fun lerp(a: Int, b: Int, t: Float) = a + (b - a) * t
    private fun clampByte(v: Float): Int = v.toInt().coerceIn(0, 255)

    private companion object {
        // Cube corners are numbered by bits (x = bit0, y = bit1, z = bit2); these are the 12 edges.
        val EDGE_A = intArrayOf(0, 2, 4, 6, 0, 1, 4, 5, 0, 1, 2, 3)
        val EDGE_B = intArrayOf(1, 3, 5, 7, 2, 3, 6, 7, 4, 5, 6, 7)
    }
}
