package com.okuyuki.protocol

/** Immutable primitive storage; the List view preserves the motion protocol API. */
class PackedMotionFrames private constructor(
    private val times: LongArray,
    private val attitudes: FloatArray,
) : AbstractList<OsmoMotionFrame>() {
    override val size: Int get() = times.size
    fun timeAt(index: Int): Long = times[index]
    fun componentAt(index: Int, component: Int): Float {
        if (index !in indices) throw IndexOutOfBoundsException("Motion frame index: $index")
        require(component in 0..3)
        return attitudes[index * 4 + component]
    }
    override fun get(index: Int): OsmoMotionFrame = OsmoMotionFrame(timeAt(index), List(4) { componentAt(index, it) })

    class Builder(count: Int) {
        private val times: LongArray
        private val attitudes: FloatArray
        private var next = 0
        private var finished = false

        init {
            require(count in 1..OsmoMotionCodec.MAX_FRAMES)
            times = LongArray(count)
            attitudes = FloatArray(count * 4)
        }

        fun add(time: Long, a: Float, b: Float, c: Float, d: Float) {
            check(!finished && next < times.size)
            times[next] = time
            val offset = next * 4
            attitudes[offset] = a; attitudes[offset + 1] = b
            attitudes[offset + 2] = c; attitudes[offset + 3] = d
            next++
        }

        fun build(): PackedMotionFrames {
            check(!finished && next == times.size)
            finished = true
            return PackedMotionFrames(times, attitudes)
        }
    }
}
