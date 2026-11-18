package com.cameramapper3d.core

/**
 * Pinhole camera intrinsics in pixel units for an image of [width] x [height].
 * Image convention: u grows to the right, v grows downwards.
 */
data class Intrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
) {
    /** Returns the intrinsics for the same camera at a different image resolution. */
    fun scaledTo(newWidth: Int, newHeight: Int): Intrinsics {
        val sx = newWidth.toFloat() / width
        val sy = newHeight.toFloat() / height
        return Intrinsics(fx * sx, fy * sy, cx * sx, cy * sy, newWidth, newHeight)
    }
}

/**
 * One depth observation ready to be fused into a [TsdfVolume].
 *
 * Camera convention (same as ARCore / OpenGL): +X right, +Y up and the camera looks along -Z.
 * "Right" and "up" are relative to the image readout, so a pixel (u, v) at depth d maps to the
 * camera-space point ((u - cx) / fx * d, -(v - cy) / fy * d, -d).
 *
 * @param depthMm depth per pixel in millimetres along the optical axis, row-major, 0 = invalid.
 * @param confidence optional per-pixel confidence 0..255 (ARCore raw depth confidence), row-major.
 * @param rgb optional colour image as packed 0xRRGGBB ints, row-major, described by [colorIntrinsics].
 * @param cameraToWorld 4x4 column-major rigid transform from camera space to world space.
 */
class DepthFrame(
    val depthMm: ShortArray,
    val depthIntrinsics: Intrinsics,
    val cameraToWorld: FloatArray,
    val confidence: ByteArray? = null,
    val rgb: IntArray? = null,
    val colorIntrinsics: Intrinsics? = null,
) {
    init {
        require(depthMm.size == depthIntrinsics.width * depthIntrinsics.height) { "depth size mismatch" }
        require(cameraToWorld.size == 16) { "cameraToWorld must be a 4x4 matrix" }
        require(confidence == null || confidence.size == depthMm.size) { "confidence size mismatch" }
        require((rgb == null) == (colorIntrinsics == null)) { "rgb and colorIntrinsics go together" }
        if (rgb != null) require(rgb.size == colorIntrinsics!!.width * colorIntrinsics.height) { "rgb size mismatch" }
    }
}

/** Tunables for depth fusion. */
data class IntegrationParams(
    /** Ignore depth closer than this (metres). */
    val minDepth: Float = 0.2f,
    /** Ignore depth further than this (metres); phone depth gets noisy quickly beyond ~4 m. */
    val maxDepth: Float = 4.0f,
    /** Ignore pixels whose confidence (0..255) is below this. Only used when the frame has confidence. */
    val minConfidence: Int = 128,
)
