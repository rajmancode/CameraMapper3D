package com.cameramapper3d.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measuring with constraints, like holding a real ruler level, plumb or at a set angle.
 * World +Y is up (ARCore aligns it with gravity).
 */
object Ruler {

    enum class Mode {
        /** Straight line between the two points. */
        FREE,
        /** Horizontal distance only: the end is moved to the start's height. */
        LEVEL,
        /** Height difference only: the end is moved straight above/below the start. */
        VERTICAL,
        /** The direction is rounded to the nearest [Measurement.SNAP_DEGREES] up/down and around the room. */
        SNAP,
    }

    class Measurement(
        val start: FloatArray,
        /** The end point after applying the mode's constraint. */
        val end: FloatArray,
        val length: Float,
        /** Angle above (+) or below (−) level, degrees. */
        val elevationDegrees: Float,
        /** Angle around the room from the reference direction (the main wall), 0..180 degrees. */
        val headingDegrees: Float,
    ) {
        val horizontal: Float get() = sqrt((end[0] - start[0]).sq() + (end[2] - start[2]).sq())
        val vertical: Float get() = abs(end[1] - start[1])

        companion object {
            const val SNAP_DEGREES = 15.0
        }
    }

    /**
     * @param a first tapped point, [b] second tapped point (xyz).
     * @param referenceHeading direction (radians, in the horizontal plane, from +X towards +Z) that
     *   counts as 0° around the room, e.g. along the main wall.
     */
    fun measure(a: FloatArray, b: FloatArray, mode: Mode, referenceHeading: Double = 0.0): Measurement {
        val end = when (mode) {
            Mode.FREE -> b.copyOf()
            Mode.LEVEL -> floatArrayOf(b[0], a[1], b[2])
            Mode.VERTICAL -> floatArrayOf(a[0], b[1], a[2])
            Mode.SNAP -> snap(a, b, referenceHeading)
        }
        val dx = end[0] - a[0]; val dy = end[1] - a[1]; val dz = end[2] - a[2]
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        val elevation = if (len < 1e-9f) 0.0 else Math.toDegrees(asin((dy / len).toDouble().coerceIn(-1.0, 1.0)))
        var heading = Math.toDegrees(atan2(dz.toDouble(), dx.toDouble()) - referenceHeading)
        heading = ((heading % 180) + 180) % 180 // a line has no front or back
        if (abs(dx) + abs(dz) < 1e-6f) heading = 0.0
        if (heading > 179.9999) heading = 0.0
        return Measurement(a.copyOf(), end, len, elevation.toFloat(), heading.toFloat())
    }

    private fun snap(a: FloatArray, b: FloatArray, ref: Double): FloatArray {
        val dx = (b[0] - a[0]).toDouble(); val dy = (b[1] - a[1]).toDouble(); val dz = (b[2] - a[2]).toDouble()
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-9) return b.copyOf()
        val step = Math.toRadians(Measurement.SNAP_DEGREES)
        val elevation = round(asin((dy / len).coerceIn(-1.0, 1.0)) / step) * step
        val heading = round((atan2(dz, dx) - ref) / step) * step + ref
        val ux = cos(elevation) * cos(heading); val uy = sin(elevation); val uz = cos(elevation) * sin(heading)
        // The reading is the tapped end projected onto the snapped direction.
        val t = dx * ux + dy * uy + dz * uz
        return floatArrayOf((a[0] + t * ux).toFloat(), (a[1] + t * uy).toFloat(), (a[2] + t * uz).toFloat())
    }

    /**
     * Tick marks for drawing the measurement as a ruler: a list of line segments (6 floats each:
     * x1 y1 z1 x2 y2 z2), short ones at the minor spacing and longer ones every 10 minor ticks.
     * The spacing grows with the length so there are never more than about 60 ticks.
     */
    fun ticks(m: Measurement): FloatArray {
        if (m.length < 1e-4f) return FloatArray(0)
        val spacing = listOf(0.01f, 0.05f, 0.1f, 0.5f, 1f, 5f).first { m.length / it <= 60f }
        val d = FloatArray(3) { (m.end[it] - m.start[it]) / m.length }
        // Ticks stand out sideways: horizontally for steep lines, upwards (square to the line) for flatter ones.
        var px: Float; var py: Float; var pz: Float
        if (abs(d[1]) > 0.7f) {
            px = -d[2]; py = 0f; pz = d[0]
            if (abs(px) + abs(pz) < 1e-6f) px = 1f // exactly vertical
        } else {
            px = -d[1] * d[0]; py = 1f - d[1] * d[1]; pz = -d[1] * d[2] // "up" with the along-line part removed
        }
        val pl = sqrt(px * px + py * py + pz * pz)
        px /= pl; py /= pl; pz /= pl
        val minor = (m.length * 0.012f).coerceAtLeast(0.004f)
        val out = ArrayList<Float>()
        val count = floor(m.length / spacing + 1e-4f).toInt()
        for (i in 0..count) {
            val s = i * spacing
            val h = if (i % 10 == 0) minor * 2.5f else if (i % 5 == 0) minor * 1.6f else minor
            val cx = m.start[0] + d[0] * s; val cy = m.start[1] + d[1] * s; val cz = m.start[2] + d[2] * s
            out += listOf(cx, cy, cz, cx + px * h, cy + py * h, cz + pz * h)
        }
        return out.toFloatArray()
    }

    private fun Float.sq() = this * this
}
