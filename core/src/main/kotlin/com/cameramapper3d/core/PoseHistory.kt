package com.cameramapper3d.core

import kotlin.math.abs

/**
 * A short history of camera poses by timestamp (nanoseconds).
 *
 * Phone depth maps often arrive a frame or more after the camera image they were computed from,
 * and their timestamps do not always match a rendered frame exactly. Pairing depth with the
 * nearest recorded pose places it much better than using the newest pose.
 */
class PoseHistory(private val capacity: Int = 90) {
    private val stamps = LongArray(capacity)
    private val poses = Array(capacity) { FloatArray(16) }
    private var count = 0
    private var next = 0

    val size: Int get() = count

    fun add(timestampNs: Long, cameraToWorld: FloatArray) {
        require(cameraToWorld.size == 16)
        stamps[next] = timestampNs
        cameraToWorld.copyInto(poses[next])
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    fun clear() {
        count = 0
        next = 0
    }

    /** The pose recorded closest in time to [timestampNs], or null if none is within [maxGapNs]. */
    fun nearest(timestampNs: Long, maxGapNs: Long): FloatArray? {
        var best = -1
        var bestGap = Long.MAX_VALUE
        for (i in 0 until count) {
            val gap = abs(stamps[i] - timestampNs)
            if (gap < bestGap) { bestGap = gap; best = i }
        }
        return if (best >= 0 && bestGap <= maxGapNs) poses[best].copyOf() else null
    }
}
