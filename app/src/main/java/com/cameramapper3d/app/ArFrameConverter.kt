package com.cameramapper3d.app

import android.media.Image
import com.cameramapper3d.core.DepthFrame
import com.cameramapper3d.core.Intrinsics
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder

/** Copies what fusion needs (depth, confidence, colour, intrinsics, pose) out of an ARCore frame. */
object ArFrameConverter {

    class Captured(val frame: DepthFrame, val depthTimestamp: Long)

    /**
     * @param useRawDepth use ARCore's raw depth with per-pixel confidence (sparser but more accurate)
     *   instead of the smoothed, hole-filled depth.
     * @param lastDepthTimestamp the timestamp of the previously fused depth image, so the same
     *   depth map is never fused twice.
     * @return null if no new depth is available for this frame.
     */
    fun capture(frame: Frame, useRawDepth: Boolean, lastDepthTimestamp: Long): Captured? {
        val camera = frame.camera
        val depthImage = try {
            if (useRawDepth) frame.acquireRawDepthImage16Bits() else frame.acquireDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            return null
        }

        val depthMm: ShortArray
        val confidence: ByteArray?
        val dw: Int
        val dh: Int
        val ts: Long
        depthImage.use { img ->
            ts = img.timestamp
            if (ts == lastDepthTimestamp) return null
            // Raw depth is only "new" on frames whose timestamp it shares; only then does the pose match.
            if (useRawDepth && ts != frame.timestamp) return null
            dw = img.width
            dh = img.height
            depthMm = readDepth(img)
            confidence = if (useRawDepth) {
                try {
                    frame.acquireRawDepthConfidenceImage().use { readY8(it) }
                } catch (e: NotYetAvailableException) {
                    return null
                }
            } else {
                null
            }
        }

        val ii = camera.imageIntrinsics
        val focal = ii.focalLength
        val pp = ii.principalPoint
        val dims = ii.imageDimensions
        val cpu = Intrinsics(focal[0], focal[1], pp[0], pp[1], dims[0], dims[1])

        var rgb: IntArray? = null
        var colorIntr: Intrinsics? = null
        try {
            frame.acquireCameraImage().use { img ->
                rgb = yuvToRgbHalf(img)
                colorIntr = cpu.scaledTo(img.width / 2, img.height / 2)
            }
        } catch (e: NotYetAvailableException) {
            // Fuse geometry without colour this time.
        }

        val pose = FloatArray(16)
        camera.pose.toMatrix(pose, 0) // sensor-oriented camera pose, matches the CPU image intrinsics
        return Captured(
            DepthFrame(
                depthMm = depthMm,
                depthIntrinsics = cpu.scaledTo(dw, dh),
                cameraToWorld = pose,
                confidence = confidence,
                rgb = rgb,
                colorIntrinsics = colorIntr,
            ),
            ts,
        )
    }

    private fun readDepth(img: Image): ShortArray {
        val plane = img.planes[0]
        val buf = plane.buffer.order(ByteOrder.nativeOrder())
        val w = img.width
        val h = img.height
        val out = ShortArray(w * h)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (y in 0 until h) {
            val row = y * rowStride
            for (x in 0 until w) out[y * w + x] = buf.getShort(row + x * pixelStride)
        }
        return out
    }

    private fun readY8(img: Image): ByteArray {
        val plane = img.planes[0]
        val buf = plane.buffer
        val w = img.width
        val h = img.height
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val row = y * plane.rowStride
            for (x in 0 until w) out[y * w + x] = buf.get(row + x * plane.pixelStride)
        }
        return out
    }

    /** Converts a YUV_420_888 camera image to packed RGB at half resolution (one pixel per chroma sample). */
    private fun yuvToRgbHalf(img: Image): IntArray {
        val w = img.width / 2
        val h = img.height / 2
        val yp = img.planes[0]; val up = img.planes[1]; val vp = img.planes[2]
        val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val yRow = (y * 2) * yp.rowStride
            val uRow = y * up.rowStride
            val vRow = y * vp.rowStride
            for (x in 0 until w) {
                val luma = (yb.get(yRow + x * 2 * yp.pixelStride).toInt() and 0xFF).toFloat()
                val u = (ub.get(uRow + x * up.pixelStride).toInt() and 0xFF) - 128
                val v = (vb.get(vRow + x * vp.pixelStride).toInt() and 0xFF) - 128
                val r = (luma + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (luma - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
                val b = (luma + 1.772f * u).toInt().coerceIn(0, 255)
                out[y * w + x] = (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}
