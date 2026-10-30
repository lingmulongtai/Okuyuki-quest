package com.okuyuki.protocol

import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

data class FrameTimestampToken(val releaseNs: Long, val presentationUs: Long?)

/** Bounded exact mapping between MediaCodec release timestamps and media PTS. */
class FrameTimestampMap(private val capacity: Int = 128) {
    private val timestamps = LinkedHashMap<Long, Long?>()
    private var latestToken: FrameTimestampToken? = null
    init { require(capacity > 0) }
    @Synchronized fun record(releaseNs: Long, presentationUs: Long) {
        if (releaseNs < 0 || presentationUs < 0) {
            latestToken = FrameTimestampToken(releaseNs, null); return
        }
        timestamps[releaseNs] = if (timestamps.containsKey(releaseNs)) null else presentationUs
        latestToken = FrameTimestampToken(releaseNs, timestamps[releaseNs])
        while (timestamps.size > capacity) timestamps.remove(timestamps.keys.first())
    }
    @Synchronized fun lookup(releaseNs: Long): Long? = timestamps[releaseNs]
    @Synchronized fun contains(releaseNs: Long): Boolean = timestamps.containsKey(releaseNs)
    @Synchronized fun latest(): FrameTimestampToken? = latestToken
    @Synchronized fun clear() { timestamps.clear(); latestToken = null }
}

data class MatchedCorrection(val correction: FloatArray?, val waiting: Boolean)

/** GL-thread state: redraws reuse a verified mapping, while seeks wait for a new frame. */
class FrameCorrectionMatcher(private val timestamps: FrameTimestampMap, private val stabilization: OsmoStabilization,
    private val nowNs: () -> Long = System::nanoTime) {
    private var lastTimestamp = Long.MIN_VALUE
    private var lastPresentation: Long? = null
    private var lastCorrection: FloatArray? = null
    private var waiting = true
    private var beforeSeek = Long.MIN_VALUE
    private var unmatchedFrames = 0
    private var lastUnmatched = Long.MIN_VALUE
    private var waitingSince: Long? = null
    fun resetSurface() {
        lastTimestamp = Long.MIN_VALUE; lastPresentation = null; lastCorrection = null
        waiting = true; beforeSeek = Long.MIN_VALUE; unmatchedFrames = 0; lastUnmatched = Long.MIN_VALUE; waitingSince = null
    }
    fun beginSeek() {
        waiting = true; beforeSeek = lastTimestamp; unmatchedFrames = 0; lastUnmatched = Long.MIN_VALUE; waitingSince = null
    }
    fun frame(timestamp: Long): MatchedCorrection {
        if (waiting) {
            if (!timestamps.contains(timestamp)) {
                if (timestamp != 0L && timestamp != beforeSeek && timestamp != lastUnmatched) {
                    unmatchedFrames++; lastUnmatched = timestamp
                }
                val now = nowNs()
                val started = waitingSince ?: now.also { waitingSince = it }
                return MatchedCorrection(null, unmatchedFrames < 5 && now - started < 2_000_000_000L)
            }
            if (timestamp == beforeSeek || timestamps.lookup(timestamp) == null) return MatchedCorrection(null, false)
            waiting = false
        }
        val presentation = timestamps.lookup(timestamp)
        if (timestamp != lastTimestamp || presentation != lastPresentation) {
            lastCorrection = presentation?.let(stabilization::correctionAt)
            lastTimestamp = timestamp; lastPresentation = presentation
        }
        return MatchedCorrection(lastCorrection, false)
    }
}

/** Causal rotational smoothing in the Osmo body frame; corrections are w,x,y,z. */
class OsmoStabilization(track: OsmoMotionTrack) {
    private val frames = track.frames
    private val tolerance = track.frameIntervalUs / 2
    private val corrections: FloatArray
    init {
        require(OsmoMotionCodec.isValid(track))
        var previousTime = frames.first().presentationTimeUs
        var target = fromStored(frames.first().storedAttitude)
        corrections = FloatArray(frames.size * 4)
        frames.forEachIndexed { index, frame ->
            val current = fromStored(frame.storedAttitude)
            val dt = (frame.presentationTimeUs - previousTime).coerceAtLeast(0L).toDouble() / 1000000.0
            target = slerp(target, current, (1.0 - exp(-dt / 0.7)).toFloat())
            previousTime = frame.presentationTimeUs
            val correction = multiply(floatArrayOf(current[0], -current[1], -current[2], -current[3]), target)
            correction.copyInto(corrections, index * 4)
        }
    }
    fun correctionAt(presentationUs: Long): FloatArray? {
        if (presentationUs < 0 || presentationUs > frames.last().presentationTimeUs + tolerance) return null
        var low = 0; var high = frames.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (frames[mid].presentationTimeUs < presentationUs) low = mid + 1 else high = mid
        }
        val index = if (low > 0 && presentationUs - frames[low - 1].presentationTimeUs < frames[low].presentationTimeUs - presentationUs) low - 1 else low
        if (kotlin.math.abs(frames[index].presentationTimeUs - presentationUs) > tolerance) return null
        return corrections.copyOfRange(index * 4, index * 4 + 4)
    }
    companion object {
        internal fun fromStored(q: List<Float>): FloatArray {
            require(q.size == 4 && q.all { it.isFinite() })
            return normalize(floatArrayOf(q[3], -q[1], q[2], q[0]))
        }
        private fun normalize(q: FloatArray): FloatArray {
            val norm = sqrt(q.sumOf { (it * it).toDouble() }).toFloat()
            require(norm > 0f && norm.isFinite())
            return FloatArray(4) { q[it] / norm }
        }
        private fun multiply(a: FloatArray, b: FloatArray) = normalize(floatArrayOf(
            a[0]*b[0]-a[1]*b[1]-a[2]*b[2]-a[3]*b[3],
            a[0]*b[1]+a[1]*b[0]+a[2]*b[3]-a[3]*b[2],
            a[0]*b[2]-a[1]*b[3]+a[2]*b[0]+a[3]*b[1],
            a[0]*b[3]+a[1]*b[2]-a[2]*b[1]+a[3]*b[0]))
        private fun slerp(a: FloatArray, b: FloatArray, amount: Float): FloatArray {
            var dot = a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()
            val direction = if (dot < 0f) -1f else 1f
            dot = kotlin.math.abs(dot).coerceIn(0f, 1f)
            if (dot > 0.9995f) return normalize(FloatArray(4) { a[it] * (1f - amount) + direction * b[it] * amount })
            val angle = acos(dot); val denominator = sin(angle)
            val left = sin((1f - amount) * angle) / denominator
            val right = sin(amount * angle) / denominator * direction
            return normalize(FloatArray(4) { a[it] * left + b[it] * right })
        }
    }
}
