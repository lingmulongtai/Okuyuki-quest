package com.okuyuki.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** A camera attitude in the four-component order stored in Osmo metadata. */
data class OsmoMotionFrame(val presentationTimeUs: Long, val storedAttitude: List<Float>)
data class OsmoMotionTrack(val frames: List<OsmoMotionFrame>, val frameIntervalUs: Long)

/** Bounded, versioned transport for a single explicitly shared camera motion track. */
object OsmoMotionCodec {
    const val MAX_FRAMES = 50_000
    const val HEADER_BYTES = 16
    const val FRAME_BYTES = 24
    const val MAX_BYTES = HEADER_BYTES + MAX_FRAMES * FRAME_BYTES
    private const val MAGIC = 0x4f4b4d31 // OKM1

    fun isValid(track: OsmoMotionTrack): Boolean {
        if (track.frames.size !in 1..MAX_FRAMES || track.frameIntervalUs !in 1L..1_000_000L) return false
        var previous = -1L
        for ((index, frame) in track.frames.withIndex()) {
            if (frame.storedAttitude.size != 4 || frame.storedAttitude.any { !it.isFinite() }) return false
            val norm = frame.storedAttitude.sumOf { (it * it).toDouble() }
            if (norm !in 0.99..1.01) return false
            val time = frame.presentationTimeUs
            if (index == 0) { if (time != 0L) return false }
            else {
                if (time <= previous || time > MAX_FRAMES.toLong() * 1_000_001L) return false
                if (abs((time - previous) - track.frameIntervalUs) > 1L) return false
            }
            previous = time
        }
        return true
    }

    fun encode(track: OsmoMotionTrack): ByteArray {
        require(isValid(track)) { "Invalid camera motion track" }
        val buffer = ByteBuffer.allocate(HEADER_BYTES + track.frames.size * FRAME_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC).putInt(track.frames.size).putLong(track.frameIntervalUs)
        for (frame in track.frames) {
            buffer.putLong(frame.presentationTimeUs)
            frame.storedAttitude.forEach(buffer::putFloat)
        }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): OsmoMotionTrack {
        require(bytes.size in (HEADER_BYTES + FRAME_BYTES)..MAX_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.int == MAGIC) { "Unsupported camera motion format" }
        val count = buffer.int
        val interval = buffer.long
        require(count in 1..MAX_FRAMES && bytes.size == HEADER_BYTES + count * FRAME_BYTES)
        val frames = ArrayList<OsmoMotionFrame>(count)
        repeat(count) {
            val time = buffer.long
            val attitude = List(4) { buffer.float }
            frames += OsmoMotionFrame(time, attitude)
        }
        return OsmoMotionTrack(frames, interval).also { require(isValid(it)) { "Invalid camera motion track" } }
    }
}
