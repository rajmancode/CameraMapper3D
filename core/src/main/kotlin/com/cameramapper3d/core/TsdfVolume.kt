package com.cameramapper3d.core

import kotlin.math.floor
import kotlin.math.min

/**
 * A sparse, colour-carrying truncated signed distance field (TSDF).
 *
 * Space is divided into voxels of [voxelSize] metres, grouped into blocks of
 * [BLOCK]³ voxels that are only allocated near observed surfaces, so memory grows with
 * the scanned surface area rather than with the size of the site.
 *
 * Voxel (gx, gy, gz) stores the field sampled at world position (gx, gy, gz) * voxelSize.
 * Positive values are free space (in front of a surface), negative values are behind it.
 *
 * Not thread-safe: integrate and mesh from a single thread.
 */
class TsdfVolume(
    val voxelSize: Float = 0.02f,
    truncationVoxels: Float = 4f,
    val maxWeight: Int = 64,
) {
    init {
        require(voxelSize > 0f)
        require(truncationVoxels >= 1f)
        require(maxWeight in 1..255)
    }

    val truncation: Float = voxelSize * truncationVoxels
    val blockSize: Float = voxelSize * BLOCK

    private val blocks = HashMap<Long, VoxelBlock>()
    private val dirty = HashSet<Long>()

    /** Number of depth frames fused so far. */
    var frameCount: Int = 0
        private set

    val blockCount: Int get() = blocks.size

    fun block(key: Long): VoxelBlock? = blocks[key]

    fun blockKeys(): Set<Long> = blocks.keys

    /** Returns the keys of blocks whose mesh may have changed since the last call, and clears the set. */
    fun takeDirtyBlocks(): List<Long> {
        val out = dirty.toList()
        dirty.clear()
        return out
    }

    fun clear() {
        blocks.clear()
        dirty.clear()
        frameCount = 0
    }

    /**
     * Fuses one depth frame into the volume.
     * @return number of blocks touched.
     */
    fun integrate(frame: DepthFrame, params: IntegrationParams = IntegrationParams()): Int {
        val m = frame.cameraToWorld
        // Rotation (column-major) and translation of camera->world.
        val r00 = m[0]; val r10 = m[1]; val r20 = m[2]
        val r01 = m[4]; val r11 = m[5]; val r21 = m[6]
        val r02 = m[8]; val r12 = m[9]; val r22 = m[10]
        val tx = m[12]; val ty = m[13]; val tz = m[14]

        val di = frame.depthIntrinsics
        val dw = di.width
        val dh = di.height
        val depth = frame.depthMm
        val conf = frame.confidence
        val minConf = params.minConfidence
        val minD = params.minDepth
        val maxD = params.maxDepth

        // 1) Allocate every block within the truncation band of an observed surface point.
        val touched = HashSet<Long>()
        val trunc = truncation
        val invBlock = 1f / blockSize
        for (v in 0 until dh) {
            for (u in 0 until dw) {
                val idx = v * dw + u
                val d = (depth[idx].toInt() and 0xFFFF) * 0.001f
                if (d < minD || d > maxD) continue
                if (conf != null && (conf[idx].toInt() and 0xFF) < minConf) continue
                val xc = (u - di.cx) / di.fx * d
                val yc = -(v - di.cy) / di.fy * d
                val zc = -d
                val wx = r00 * xc + r01 * yc + r02 * zc + tx
                val wy = r10 * xc + r11 * yc + r12 * zc + ty
                val wz = r20 * xc + r21 * yc + r22 * zc + tz
                val bx0 = floor((wx - trunc) * invBlock).toInt(); val bx1 = floor((wx + trunc) * invBlock).toInt()
                val by0 = floor((wy - trunc) * invBlock).toInt(); val by1 = floor((wy + trunc) * invBlock).toInt()
                val bz0 = floor((wz - trunc) * invBlock).toInt(); val bz1 = floor((wz + trunc) * invBlock).toInt()
                for (bz in bz0..bz1) for (by in by0..by1) for (bx in bx0..bx1) touched.add(packKey(bx, by, bz))
            }
        }
        if (touched.isEmpty()) return 0

        // 2) Update every voxel of every touched block by projecting it into the depth image.
        val ci = frame.colorIntrinsics
        val rgb = frame.rgb
        val colorBand = trunc * 0.5f
        val vs = voxelSize
        val maxW = maxWeight
        for (key in touched) {
            val block = blocks.getOrPut(key) { VoxelBlock() }
            val baseX = unpackX(key) * BLOCK
            val baseY = unpackY(key) * BLOCK
            val baseZ = unpackZ(key) * BLOCK
            var i = 0
            for (k in 0 until BLOCK) for (j in 0 until BLOCK) for (ii in 0 until BLOCK) {
                val idx = i++
                val px = (baseX + ii) * vs - tx
                val py = (baseY + j) * vs - ty
                val pz = (baseZ + k) * vs - tz
                // world -> camera: R^T * (p - t)
                val xc = r00 * px + r10 * py + r20 * pz
                val yc = r01 * px + r11 * py + r21 * pz
                val zc = r02 * px + r12 * py + r22 * pz
                val dist = -zc
                if (dist <= minD) continue
                val uf = di.fx * xc / dist + di.cx
                val vf = -di.fy * yc / dist + di.cy
                val ui = (uf + 0.5f).toInt()
                val vi = (vf + 0.5f).toInt()
                if (uf < -0.5f || vf < -0.5f || ui >= dw || vi >= dh) continue
                val pIdx = vi * dw + ui
                val meas = (depth[pIdx].toInt() and 0xFFFF) * 0.001f
                if (meas < minD || meas > maxD) continue
                if (conf != null && (conf[pIdx].toInt() and 0xFF) < minConf) continue
                val sdf = meas - dist
                if (sdf < -trunc) continue // hidden behind the observed surface
                val tsdf = min(1f, sdf / trunc)

                val w = block.weight[idx].toInt() and 0xFF
                val old = block.tsdf[idx] * INV_TSDF_SCALE
                val nw = w + 1
                val fused = (old * w + tsdf) / nw
                block.tsdf[idx] = (fused * TSDF_SCALE).toInt().toShort()
                block.weight[idx] = min(nw, maxW).toByte()

                if (rgb != null && ci != null && sdf < colorBand && sdf > -colorBand) {
                    val cu = (ci.fx * xc / dist + ci.cx + 0.5f).toInt()
                    val cv = (-ci.fy * yc / dist + ci.cy + 0.5f).toInt()
                    if (cu >= 0 && cv >= 0 && cu < ci.width && cv < ci.height) {
                        val c = rgb[cv * ci.width + cu]
                        val cw = block.colorWeight[idx].toInt() and 0xFF
                        val ncw = cw + 1
                        val o = idx * 3
                        block.rgb[o] = ((((block.rgb[o].toInt() and 0xFF) * cw) + ((c shr 16) and 0xFF)) / ncw).toByte()
                        block.rgb[o + 1] = ((((block.rgb[o + 1].toInt() and 0xFF) * cw) + ((c shr 8) and 0xFF)) / ncw).toByte()
                        block.rgb[o + 2] = ((((block.rgb[o + 2].toInt() and 0xFF) * cw) + (c and 0xFF)) / ncw).toByte()
                        block.colorWeight[idx] = min(ncw, maxW).toByte()
                    }
                }
            }
        }

        // 3) A block's mesh reads one voxel layer of its neighbours, so neighbours go stale too.
        for (key in touched) {
            dirty.add(key)
            val bx = unpackX(key); val by = unpackY(key); val bz = unpackZ(key)
            for (dz in -1..1) for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0 && dz == 0) continue
                val n = packKey(bx + dx, by + dy, bz + dz)
                if (blocks.containsKey(n)) dirty.add(n)
            }
        }
        frameCount++
        return touched.size
    }

    companion object {
        /** Voxels per block edge. */
        const val BLOCK = 8
        const val BLOCK_VOXELS = BLOCK * BLOCK * BLOCK
        internal const val TSDF_SCALE = 32767f
        internal const val INV_TSDF_SCALE = 1f / 32767f

        private const val BITS = 21
        private const val MASK = (1L shl BITS) - 1
        private const val OFFSET = 1 shl (BITS - 1)

        fun packKey(bx: Int, by: Int, bz: Int): Long =
            ((bx + OFFSET).toLong() and MASK) or
                (((by + OFFSET).toLong() and MASK) shl BITS) or
                (((bz + OFFSET).toLong() and MASK) shl (2 * BITS))

        fun unpackX(key: Long): Int = (key and MASK).toInt() - OFFSET
        fun unpackY(key: Long): Int = ((key shr BITS) and MASK).toInt() - OFFSET
        fun unpackZ(key: Long): Int = ((key shr (2 * BITS)) and MASK).toInt() - OFFSET

        /** Index of voxel (x, y, z) inside a block, x fastest. */
        fun voxelIndex(x: Int, y: Int, z: Int): Int = (z * BLOCK + y) * BLOCK + x
    }
}

/** Storage for [TsdfVolume.BLOCK]³ voxels. TSDF is stored as fixed point in [-1, 1]. */
class VoxelBlock {
    val tsdf = ShortArray(TsdfVolume.BLOCK_VOXELS) { TsdfVolume.TSDF_SCALE.toInt().toShort() }
    val weight = ByteArray(TsdfVolume.BLOCK_VOXELS)
    val rgb = ByteArray(TsdfVolume.BLOCK_VOXELS * 3) { 0x80.toByte() }
    val colorWeight = ByteArray(TsdfVolume.BLOCK_VOXELS)

    fun tsdfAt(index: Int): Float = tsdf[index] * TsdfVolume.INV_TSDF_SCALE
    fun weightAt(index: Int): Int = weight[index].toInt() and 0xFF
}
