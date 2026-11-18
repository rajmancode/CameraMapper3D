package com.cameramapper3d.app

import android.opengl.Matrix
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Turntable camera around a target point, with world +Y up (ARCore's gravity-aligned up). */
class OrbitCamera {
    private val target = floatArrayOf(0f, 0f, 0f)
    private var distance = 3f
    private var yaw = 0f
    private var pitch = 0.45f
    private var sceneRadius = 1f
    private var width = 1
    private var height = 1

    @Synchronized
    fun fit(bounds: FloatArray) {
        for (a in 0 until 3) target[a] = (bounds[a] + bounds[a + 3]) / 2f
        val dx = bounds[3] - bounds[0]; val dy = bounds[4] - bounds[1]; val dz = bounds[5] - bounds[2]
        sceneRadius = max(0.1f, kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) / 2f)
        distance = sceneRadius * 2.2f
        yaw = 0f
        pitch = 0.45f
    }

    @Synchronized
    fun setViewport(w: Int, h: Int) { width = max(1, w); height = max(1, h) }

    @Synchronized
    fun rotate(dxPx: Float, dyPx: Float) {
        yaw -= dxPx * 0.008f
        pitch = (pitch + dyPx * 0.008f).coerceIn(-1.5f, 1.5f)
    }

    @Synchronized
    fun zoom(scale: Float) {
        distance = (distance / scale).coerceIn(sceneRadius * 0.05f, sceneRadius * 20f)
    }

    @Synchronized
    fun pan(dxPx: Float, dyPx: Float) {
        val k = distance / height * 1.0f
        val right = floatArrayOf(cos(yaw), 0f, -sin(yaw))
        val up = floatArrayOf(-sin(pitch) * sin(yaw), cos(pitch), -sin(pitch) * cos(yaw))
        for (a in 0 until 3) target[a] += (-dxPx * right[a] + dyPx * up[a]) * k
    }

    /** Writes projection * view into [out]. */
    @Synchronized
    fun viewProjection(out: FloatArray) {
        val eye = eye()
        val view = FloatArray(16)
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], target[0], target[1], target[2], 0f, 1f, 0f)
        val proj = FloatArray(16)
        val near = max(0.01f, distance * 0.02f)
        Matrix.perspectiveM(proj, 0, 50f, width.toFloat() / height, near, distance + sceneRadius * 30f)
        Matrix.multiplyMM(out, 0, proj, 0, view, 0)
    }

    /** Returns [ox, oy, oz, dx, dy, dz] for the ray under the screen pixel (x, y). */
    fun rayThrough(x: Float, y: Float): FloatArray {
        val vp = FloatArray(16)
        val w: Int; val h: Int
        synchronized(this) { viewProjection(vp); w = width; h = height }
        val inv = FloatArray(16)
        Matrix.invertM(inv, 0, vp, 0)
        val nx = 2f * x / w - 1f
        val ny = 1f - 2f * y / h
        val near = unproject(inv, nx, ny, -1f)
        val far = unproject(inv, nx, ny, 1f)
        return floatArrayOf(near[0], near[1], near[2], far[0] - near[0], far[1] - near[1], far[2] - near[2])
    }

    private fun unproject(inv: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, inv, 0, floatArrayOf(x, y, z, 1f), 0)
        return floatArrayOf(out[0] / out[3], out[1] / out[3], out[2] / out[3])
    }

    private fun eye(): FloatArray = floatArrayOf(
        target[0] + distance * cos(pitch) * sin(yaw),
        target[1] + distance * sin(pitch),
        target[2] + distance * cos(pitch) * cos(yaw),
    )
}
