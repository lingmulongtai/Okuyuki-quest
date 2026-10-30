package com.okuyuki.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayInputStream

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
    @Test fun `decoded attitudes use immutable packed storage`() {
        val decoded = OsmoMotionCodec.decode(OsmoMotionCodec.encode(track()))
        assertTrue(decoded.frames is PackedMotionFrames)
        assertEquals(track().frames, decoded.frames)
        val builder = PackedMotionFrames.Builder(1)
        builder.add(0, 0f, 0f, 0f, 1f)
        val frames = builder.build()
        assertThrows(IllegalStateException::class.java) { builder.add(1, 0f, 0f, 0f, 1f) }
        assertEquals(OsmoMotionFrame(0, listOf(0f, 0f, 0f, 1f)), frames[0])
        assertThrows(IndexOutOfBoundsException::class.java) { frames.componentAt(1 shl 30, 0) }
    }
    @Test fun `streaming decode supports unknown length and rejects trailing bytes`() {
        val bytes = OsmoMotionCodec.encode(track())
        assertEquals(track(), OsmoMotionCodec.decode(ByteArrayInputStream(bytes), -1))
        val fragmented = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(length, 3))
        }
        assertEquals(track(), OsmoMotionCodec.decode(fragmented, bytes.size.toLong()))
        assertThrows(Exception::class.java) { OsmoMotionCodec.decode(ByteArrayInputStream(bytes + 0), -1) }
        assertThrows(Exception::class.java) { OsmoMotionCodec.decode(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)), -1) }
        assertThrows(Exception::class.java) { OsmoMotionCodec.decode(ByteArrayInputStream(bytes), bytes.size.toLong() + 1) }
        val invalidInterval = bytes.clone().also { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putLong(8, 0) }
        assertThrows(Exception::class.java) { OsmoMotionCodec.decode(invalidInterval) }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            OsmoMotionCodec.decode(ByteArrayInputStream(bytes), bytes.size.toLong()) { throw java.util.concurrent.CancellationException() }
        }
    }
    @Test fun `long tracks round trip beyond the previous frame limit`() {
        val count = 180_001
        val builder = PackedMotionFrames.Builder(count)
        repeat(count) { builder.add(it * 20_000L, 0f, 0f, 0f, 1f) }
        val original = OsmoMotionTrack(builder.build(), 20_000)
        val decoded = OsmoMotionCodec.decode(OsmoMotionCodec.encode(original))
        assertEquals(count, decoded.frames.size)
        assertEquals(original.frames.last(), decoded.frames.last())
        val smooth = OsmoStabilization(decoded)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), smooth.correctionAt(50_000L * 20_000), 0.0001f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), smooth.correctionAt((count - 1L) * 20_000), 0.0001f)
        assertNull(smooth.correctionAt(count * 20_000L + 10_001L))
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
