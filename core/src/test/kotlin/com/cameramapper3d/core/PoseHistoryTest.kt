package com.cameramapper3d.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PoseHistoryTest {
    private fun pose(tag: Float) = FloatArray(16).also { it[12] = tag }

    @Test
    fun findsExactAndNearestPoses() {
        val h = PoseHistory(capacity = 4)
        h.add(1_000, pose(1f)); h.add(2_000, pose(2f)); h.add(3_000, pose(3f))
        assertEquals(2f, h.nearest(2_000, 0)!![12])
        assertEquals(2f, h.nearest(2_300, 500)!![12]) // depth timestamp between frames
        assertEquals(3f, h.nearest(2_700, 500)!![12])
        assertNull(h.nearest(10_000, 500)) // too far from anything recorded
    }

    @Test
    fun keepsOnlyTheMostRecentPoses() {
        val h = PoseHistory(capacity = 2)
        h.add(1_000, pose(1f)); h.add(2_000, pose(2f)); h.add(3_000, pose(3f))
        assertEquals(2, h.size)
        assertEquals(2f, h.nearest(1_000, 1_000)!![12]) // 1_000 was evicted; 2_000 is nearest
        h.clear()
        assertNull(h.nearest(2_000, 1_000))
    }

    @Test
    fun returnedPoseIsACopy() {
        val h = PoseHistory()
        h.add(5, pose(7f))
        h.nearest(5, 0)!![12] = 99f
        assertEquals(7f, h.nearest(5, 0)!![12])
    }
}
