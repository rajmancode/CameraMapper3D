package com.cameramapper3d.app

import android.os.SystemClock
import com.cameramapper3d.core.DepthFrame
import com.cameramapper3d.core.IntegrationParams
import com.cameramapper3d.core.MeshData
import com.cameramapper3d.core.SurfaceNetsMesher
import com.cameramapper3d.core.TsdfVolume
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the TSDF volume on a single background thread. The AR render thread hands it depth
 * frames (dropping them while it is busy) and reads back per-block meshes for live display.
 */
class FusionEngine(voxelSize: Float) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "fusion").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val volume = TsdfVolume(voxelSize = voxelSize)
    private val liveMesher = SurfaceNetsMesher()
    private val busy = AtomicBoolean(false)

    /** Latest mesh of every block that contains surface; read by the renderer. */
    val blockMeshes: Map<Long, MeshData> get() = meshes
    private val meshes = ConcurrentHashMap<Long, MeshData>()

    data class Stats(val frames: Int, val blocks: Int, val fuseMs: Long, val meshMs: Long, val memoryLimitReached: Boolean)

    @Volatile var stats = Stats(0, 0, 0, 0, false)
        private set

    val isBusy: Boolean get() = busy.get()

    /** Queues a frame for fusion. Returns false (and drops it) if the previous one is still being processed. */
    fun trySubmit(frame: DepthFrame, params: IntegrationParams): Boolean {
        if (stats.memoryLimitReached) return false
        if (!busy.compareAndSet(false, true)) return false
        executor.execute {
            try {
                val t0 = SystemClock.elapsedRealtime()
                volume.integrate(frame, params)
                val t1 = SystemClock.elapsedRealtime()
                for (key in volume.takeDirtyBlocks()) {
                    val m = liveMesher.meshBlock(volume, key)
                    if (m == null) meshes.remove(key) else meshes[key] = m
                }
                val t2 = SystemClock.elapsedRealtime()
                stats = Stats(volume.frameCount, volume.blockCount, t1 - t0, t2 - t1, volume.blockCount >= MAX_BLOCKS)
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    fun reset() {
        executor.execute {
            volume.clear()
            meshes.clear()
            stats = Stats(0, 0, 0, 0, false)
        }
    }

    /** Builds the final welded mesh on the fusion thread and passes it to [onDone] (on that thread). */
    fun finish(onDone: (MeshData) -> Unit) {
        executor.execute { onDone(SurfaceNetsMesher().meshAll(volume)) }
    }

    fun shutdown() {
        executor.shutdown()
    }

    companion object {
        /**
         * Each block is ~5 KB, so this caps the volume at roughly 225 MB. At 2 cm voxels that is
         * several hundred m² of observed surface (a large room or a house facade); at 4 cm, four times more.
         */
        const val MAX_BLOCKS = 45_000
    }
}
