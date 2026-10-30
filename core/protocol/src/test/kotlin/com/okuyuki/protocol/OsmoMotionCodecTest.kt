package com.okuyuki.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OsmoMotionCodecTest {
    private fun track() = OsmoMotionTrack(listOf(
        OsmoMotionFrame(0L, listOf(0f, 0f, 0f, 1f)),
        OsmoMotionFrame(20_000L, listOf(0.6f, 0f, 0f, 0.8f)),
    ), 20_000L)

    @Test fun `round trips fixed width camera attitudes`() {
        val bytes = OsmoMotionCodec.encode(track())
        assertEquals(OsmoMotionCodec.HEADER_BYTES + 2 * OsmoMotionCodec.FRAME_BYTES, bytes.size)
        assertEquals(track(), OsmoMotionCodec.decode(bytes))
    }
    @Test fun `rejects malformed or oversized motion payloads`() {
        val bytes = OsmoMotionCodec.encode(track())
        assertThrows(Exception::class.java) { OsmoMotionCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(Exception::class.java) { OsmoMotionCodec.decode(ByteArray(OsmoMotionCodec.MAX_BYTES + 1)) }
        val magic = bytes.clone().also { it[0] = 0 }
        assertThrows(Exception::class.java) { OsmoMotionCodec.decode(magic) }
        val nan = bytes.clone().also { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putFloat(24, Float.NaN) }
        assertThrows(Exception::class.java) { OsmoMotionCodec.decode(nan) }
        val count = bytes.clone().also { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(4, Int.MAX_VALUE) }
        assertThrows(Exception::class.java) { OsmoMotionCodec.decode(count) }
    }
    @Test fun `rejects invalid time and camera orientation`() {
        val valid = track()
        assertFalse(OsmoMotionCodec.isValid(valid.copy(frameIntervalUs = 0)))
        assertFalse(OsmoMotionCodec.isValid(valid.copy(frames = valid.frames.map { it.copy(presentationTimeUs = 0) })))
        assertFalse(OsmoMotionCodec.isValid(valid.copy(frames = valid.frames.map { it.copy(storedAttitude = listOf(0f, 0f, 0f, 0f)) })))
        assertFalse(OsmoMotionCodec.isValid(valid.copy(frames = valid.frames.map { it.copy(storedAttitude = listOf(1f)) })))
        assertFalse(OsmoMotionCodec.isValid(valid.copy(frames = valid.frames.map { it.copy(storedAttitude = listOf(Float.NaN, 0f, 0f, 1f)) })))
    }
}
