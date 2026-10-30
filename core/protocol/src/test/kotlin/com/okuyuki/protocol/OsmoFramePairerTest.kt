package com.okuyuki.protocol

import org.junit.Assert.*
import org.junit.Test

class OsmoFramePairerTest {
    private fun latch(pairer: OsmoFramePairer, side: Int, time: Long, texture: Long = time + 100): Unit {
        val gen = pairer.generation(); pairer.frameAvailable(side, gen)
        assertEquals(OsmoPairAction.Latch(side, gen), pairer.next())
        pairer.latched(side, gen, OsmoDecodedFrame(texture, time))
    }
    @Test fun `either side can deliver the first frame`() {
        val pairer = OsmoFramePairer(); pairer.reset()
        latch(pairer, 1, 0); latch(pairer, 0, 0)
        assertEquals(OsmoPairAction.Present(0, pairer.generation()), pairer.next())
    }
    @Test fun `only identical presentation times can be shown`() {
        val pairer = OsmoFramePairer(); val gen = pairer.reset()
        assertEquals(OsmoPairAction.Wait, pairer.next())
        latch(pairer, 0, 0); latch(pairer, 1, 0)
        assertEquals(OsmoPairAction.Present(0, pairer.generation()), pairer.next())
        assertEquals(OsmoPairAction.Wait, pairer.next())
        pairer.presented(0, pairer.generation())
        assertEquals(OsmoPairAction.Wait, pairer.next())
        latch(pairer, 0, 20)
        pairer.frameAvailable(0, gen) // the leading side must not overtake again
        assertEquals(OsmoPairAction.Wait, pairer.next())
        latch(pairer, 1, 20)
        assertEquals(OsmoPairAction.Present(20, pairer.generation()), pairer.next()); pairer.presented(20, pairer.generation())
        assertEquals(OsmoPairAction.Latch(0, gen), pairer.next())
    }
    @Test fun `catch up drops mismatched frames without presenting them`() {
        val pairer = OsmoFramePairer(); pairer.reset()
        latch(pairer, 0, 20); latch(pairer, 1, 0)
        latch(pairer, 1, 40) // right overtakes; left becomes lagging
        latch(pairer, 0, 40)
        assertEquals(OsmoPairAction.Present(40, pairer.generation()), pairer.next())
    }
    @Test fun `reset discards obsolete callbacks and rejects unknown frame clocks`() {
        val pairer = OsmoFramePairer(); val old = pairer.reset()
        pairer.frameAvailable(0, old); assertEquals(OsmoPairAction.Latch(0, old), pairer.next())
        val current = pairer.reset(40)
        pairer.latched(0, old, OsmoDecodedFrame(100, 0)); pairer.frameAvailable(1, old)
        assertEquals(OsmoPairAction.Wait, pairer.next())
        pairer.frameAvailable(0, current); assertEquals(OsmoPairAction.Latch(0, current), pairer.next())
        pairer.latched(0, current, null)
        assertEquals(OsmoPairAction.Invalid, pairer.next())
    }
    @Test fun `a stale presentation cannot consume a new generation`() {
        val pairer = OsmoFramePairer(); val old = pairer.reset()
        latch(pairer, 0, 0); latch(pairer, 1, 0)
        assertEquals(OsmoPairAction.Present(0, old), pairer.next())
        val current = pairer.reset()
        latch(pairer, 0, 0); latch(pairer, 1, 0)
        pairer.presented(0, old)
        assertEquals(OsmoPairAction.Present(0, current), pairer.next())
    }
    @Test fun `preseek frames are discarded and fully drained streams end`() {
        val pairer = OsmoFramePairer(); val gen = pairer.reset(40)
        latch(pairer, 0, 20); assertEquals(OsmoPairAction.Wait, pairer.next())
        latch(pairer, 0, 40); latch(pairer, 1, 40)
        assertEquals(OsmoPairAction.Present(40, pairer.generation()), pairer.next()); pairer.presented(40, pairer.generation())
        pairer.drainComplete(1, gen)
        assertEquals(OsmoPairAction.Ended, pairer.next())
    }
    @Test fun `reordered frames are rejected but duplicate notifications are harmless`() {
        val pairer = OsmoFramePairer(); pairer.reset()
        latch(pairer, 0, 20, 100); latch(pairer, 1, 20, 200)
        assertEquals(OsmoPairAction.Present(20, pairer.generation()), pairer.next())
        pairer.presented(20, pairer.generation())
        latch(pairer, 0, 20, 100) // same texture was reported twice
        assertEquals(OsmoPairAction.Wait, pairer.next())
        latch(pairer, 0, 10, 101)
        assertEquals(OsmoPairAction.Invalid, pairer.next())
    }
}
