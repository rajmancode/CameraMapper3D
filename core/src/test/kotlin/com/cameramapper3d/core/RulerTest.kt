package com.cameramapper3d.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulerTest {
    private val a = floatArrayOf(1f, 0.5f, 2f)
    private val b = floatArrayOf(4f, 1.7f, 6f) // 3 across, 4 deep (5 m horizontally), 1.2 m up

    @Test
    fun freeLevelAndVertical() {
        val free = Ruler.measure(a, b, Ruler.Mode.FREE)
        assertEquals(sqrt(25f + 1.44f), free.length, 1e-5f)
        assertEquals(5f, free.horizontal, 1e-5f)
        assertEquals(1.2f, free.vertical, 1e-5f)

        val level = Ruler.measure(a, b, Ruler.Mode.LEVEL)
        assertEquals(5f, level.length, 1e-5f)
        assertEquals(0f, level.elevationDegrees, 1e-4f)
        assertEquals(a[1], level.end[1])

        val vertical = Ruler.measure(a, b, Ruler.Mode.VERTICAL)
        assertEquals(1.2f, vertical.length, 1e-5f)
        assertEquals(90f, vertical.elevationDegrees, 1e-3f)
        assertEquals(0f, vertical.horizontal, 1e-6f)
    }

    @Test
    fun snapRoundsToFifteenDegreeSteps() {
        // A line 44° around and 13° up snaps to 45° around and 15° up.
        val h = Math.toRadians(44.0); val e = Math.toRadians(13.0); val len = 2.0
        val end = floatArrayOf(
            (a[0] + len * Math.cos(e) * Math.cos(h)).toFloat(), (a[1] + len * Math.sin(e)).toFloat(), (a[2] + len * Math.cos(e) * Math.sin(h)).toFloat(),
        )
        val m = Ruler.measure(a, end, Ruler.Mode.SNAP)
        assertEquals(45f, m.headingDegrees, 1e-3f)
        assertEquals(15f, m.elevationDegrees, 1e-3f)
        assertEquals((len * Math.cos(Math.toRadians(2.0))).toFloat(), m.length, 1e-3f) // projection onto the snapped line

        // Angles are measured from the reference direction (e.g. along the main wall).
        val alongWall = Ruler.measure(a, floatArrayOf(a[0] + 1f, a[1], a[2] + 0.02f), Ruler.Mode.SNAP, referenceHeading = 0.3)
        assertEquals(345f % 180, alongWall.headingDegrees, 1e-3f) // 1.1° - 17.2° from the wall, snaps to -15° = 165°
    }

    @Test
    fun ticksFollowTheRuler() {
        val m = Ruler.measure(a, b, Ruler.Mode.LEVEL) // 5 m → 10 cm ticks
        val t = Ruler.ticks(m)
        assertEquals(51, t.size / 6)
        for (i in 0 until t.size / 6) {
            // Every tick starts on the line and stands upright, square to it.
            val sx = t[i * 6]; val sy = t[i * 6 + 1]; val sz = t[i * 6 + 2]
            val along = ((sx - a[0]) * 3 + (sz - a[2]) * 4) / 5
            assertEquals(i * 0.1f, along, 1e-4f)
            assertEquals(a[1], sy, 1e-5f)
            assertTrue(t[i * 6 + 4] > sy && abs(t[i * 6 + 3] - sx) < 1e-5f)
        }
        // Major ticks every metre are longer.
        assertTrue(t[10 * 6 + 4] - t[10 * 6 + 1] > t[3 * 6 + 4] - t[3 * 6 + 1])
        // A vertical ruler gets horizontal ticks.
        val v = Ruler.ticks(Ruler.measure(a, b, Ruler.Mode.VERTICAL))
        assertTrue(v.isNotEmpty() && abs(v[4] - v[1]) < 1e-6f)
    }

    @Test
    fun mainWallHeadingFollowsTheBiggestWall() {
        // A 4 m wall along a direction 20° from +X, and a smaller 2 m wall at right angles.
        val ang = Math.toRadians(20.0)
        fun wall(len: Float, dir: Double, ox: Float, oz: Float): MeshData {
            val ux = Math.cos(dir).toFloat(); val uz = Math.sin(dir).toFloat()
            val p = floatArrayOf(ox, 0f, oz, ox + ux * len, 0f, oz + uz * len, ox + ux * len, 2.5f, oz + uz * len, ox, 2.5f, oz)
            val n = FloatArray(12) { i -> when (i % 3) { 0 -> -uz; 2 -> ux; else -> 0f } }
            return MeshData(p, n, ByteArray(12), intArrayOf(0, 1, 2, 0, 2, 3))
        }
        val mesh = MeshData.merge(listOf(wall(4f, ang, 0f, 0f), wall(2f, ang + PI / 2, 5f, 0f)), weld = false)
        val heading = WallStraightener.mainWallHeading(mesh)!!
        val diff = ((heading - ang) % PI + PI) % PI
        assertTrue(diff < 1e-3 || PI - diff < 1e-3, "heading $heading should run along the 4 m wall ($ang)")
    }
}
