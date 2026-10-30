package com.okuyuki.protocol

import org.junit.Assert.*
import org.junit.Test

class OsmoStabilizationTest {
    @Test fun `timestamps require exact release match and remain bounded`() {
        val map = FrameTimestampMap(2)
        map.record(100, 10); map.record(200, 20); map.record(300, 30)
        assertNull(map.lookup(100)); assertNull(map.lookup(201))
        assertEquals(20L, map.lookup(200)); assertEquals(20L, map.lookup(200))
        map.clear(); assertNull(map.lookup(300))
        map.record(-1, 4); map.record(1, -1); assertNull(map.lookup(1))
        map.record(400, 40); map.record(400, 50); assertNull(map.lookup(400))
        assertEquals(FrameTimestampToken(400, null), map.latest())
        map.record(500, 60); assertEquals(FrameTimestampToken(500, 60), map.latest())
        map.record(-1, 80); assertEquals(FrameTimestampToken(-1, null), map.latest())
        map.record(600, -1); assertEquals(FrameTimestampToken(600, null), map.latest())
        map.clear(); assertNull(map.latest())
    }
    @Test fun `redraws preserve matches and seeks wait for a new frame`() {
        val map = FrameTimestampMap()
        val frames = listOf(OsmoMotionFrame(0, listOf(0f, 0f, 0f, 1f)), OsmoMotionFrame(40000, listOf(0f, 0f, 0f, 1f)))
        val matcher = FrameCorrectionMatcher(map, OsmoStabilization(OsmoMotionTrack(frames, 40000)))
        map.record(100, 0)
        assertNotNull(matcher.frame(100).correction)
        assertNotNull(matcher.frame(100).correction) // touch redraw or delayed callback
        map.clear(); matcher.beginSeek()
        assertTrue(matcher.frame(100).waiting)
        assertNull(matcher.frame(100).correction)
        assertTrue(matcher.frame(101).waiting) // an old unmatched buffer may arrive after seek
        map.record(200, 40000)
        assertFalse(matcher.frame(200).waiting)
        assertNotNull(matcher.frame(200).correction)
        map.record(200, 0) // ambiguous release time must not reuse the previous correction
        assertNull(matcher.frame(200).correction)
    }
    @Test fun `first frames wait for metadata but repeated misses fail closed`() {
        val map = FrameTimestampMap()
        val frame = OsmoMotionFrame(0, listOf(0f, 0f, 0f, 1f))
        val matcher = FrameCorrectionMatcher(map, OsmoStabilization(OsmoMotionTrack(listOf(frame), 40000)))
        assertTrue(matcher.frame(0).waiting)
        for (timestamp in 1L..4L) assertTrue(matcher.frame(timestamp).waiting)
        assertFalse(matcher.frame(5).waiting)
        matcher.resetSurface(); map.record(100, 0)
        assertNotNull(matcher.frame(100).correction)
    }
    @Test fun `a repeated unsupported texture timestamp times out`() {
        var now = 0L
        val map = FrameTimestampMap()
        val frame = OsmoMotionFrame(0, listOf(0f, 0f, 0f, 1f))
        val matcher = FrameCorrectionMatcher(map, OsmoStabilization(OsmoMotionTrack(listOf(frame), 40000))) { now }
        assertTrue(matcher.frame(0).waiting)
        now = 2_000_000_000L
        assertFalse(matcher.frame(0).waiting)
    }
    @Test fun `stored camera axes and rotations are converted consistently`() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), OsmoStabilization.fromStored(listOf(0f, 0f, 0f, 1f)), 0.0001f)
        assertArrayEquals(floatArrayOf(0f, -1f, 0f, 0f), OsmoStabilization.fromStored(listOf(0f, 1f, 0f, 0f)), 0.0001f)
    }
    @Test fun `smoothing is identity when stationary and handles quaternion sign changes`() {
        val frames = listOf(OsmoMotionFrame(0, listOf(0f, 0f, 0f, 1f)), OsmoMotionFrame(40000, listOf(0f, 0f, 0f, -1f)))
        val smooth = OsmoStabilization(OsmoMotionTrack(frames, 40000))
        for (time in listOf(0L, 40000L)) {
            val q = smooth.correctionAt(time)!!
            assertEquals(1f, kotlin.math.abs(q[0]), 0.0001f)
            assertEquals(0f, q[1], 0.0001f); assertEquals(0f, q[2], 0.0001f); assertEquals(0f, q[3], 0.0001f)
        }
        assertNull(smooth.correctionAt(-1)); assertNull(smooth.correctionAt(61000))
    }
    @Test fun `smoothing compensates a sudden rotation and maps seek positions`() {
        val frames = listOf(OsmoMotionFrame(0, listOf(0f, 0f, 0f, 1f)), OsmoMotionFrame(40000, listOf(0f, 0f, 0.70710677f, 0.70710677f)))
        val smooth = OsmoStabilization(OsmoMotionTrack(frames, 40000))
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), smooth.correctionAt(0), 0.0001f)
        assertTrue(smooth.correctionAt(40000)!![2] < -0.5f)
        assertArrayEquals(smooth.correctionAt(40000), smooth.correctionAt(39000), 0.0001f)
    }
    @Test fun `callers cannot alter stored corrections`() {
        val frame = OsmoMotionFrame(0, listOf(0f, 0f, 0f, 1f))
        val smooth = OsmoStabilization(OsmoMotionTrack(listOf(frame), 40000))
        smooth.correctionAt(0)!![0] = 0f
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), smooth.correctionAt(0), 0.0001f)
    }
}
