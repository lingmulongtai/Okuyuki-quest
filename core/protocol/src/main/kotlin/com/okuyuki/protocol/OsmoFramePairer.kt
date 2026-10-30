package com.okuyuki.protocol

/** A latched texture and the presentation time verified for that same decoder output. */
data class OsmoDecodedFrame(val textureTimestampNs: Long, val presentationTimeUs: Long)

sealed interface OsmoPairAction {
    data class Latch(val side: Int, val generation: Long) : OsmoPairAction
    data class Present(val presentationTimeUs: Long, val generation: Long) : OsmoPairAction
    data object Wait : OsmoPairAction
    data object Ended : OsmoPairAction
    data object Invalid : OsmoPairAction
}

/**
 * Coordinates two independently delivered decoder surfaces. Only the lagging side may advance;
 * a pair becomes presentable exclusively when both latched presentation times are identical.
 * Callbacks and GL operations carry a generation so a reset can ignore obsolete work.
 */
class OsmoFramePairer {
    private val frames = arrayOfNulls<OsmoDecodedFrame>(2)
    private val pending = BooleanArray(2)
    private val ended = BooleanArray(2)
    private var generation = 0L
    private var requested: Int? = null
    private var presenting: Long? = null
    private var presented = false
    private var invalid = false
    private var minimumPresentationUs = 0L
    private var preferredSide = 0

    @Synchronized fun reset(minimumPresentationUs: Long = 0L): Long {
        require(minimumPresentationUs >= 0)
        generation = Math.addExact(generation, 1L)
        frames.fill(null); pending.fill(false); ended.fill(false)
        requested = null; presenting = null; presented = false; invalid = false
        this.minimumPresentationUs = minimumPresentationUs; preferredSide = 0
        return generation
    }
    @Synchronized fun generation(): Long = generation
    @Synchronized fun frameAvailable(side: Int, callbackGeneration: Long) {
        require(side in 0..1)
        if (callbackGeneration == generation && !ended[side] && !invalid) pending[side] = true
    }
    /** Call only after EOS and the final released frame token has been latched or discarded. */
    @Synchronized fun drainComplete(side: Int, callbackGeneration: Long) {
        require(side in 0..1)
        if (callbackGeneration == generation) ended[side] = true
    }
    @Synchronized fun next(): OsmoPairAction {
        if (invalid) return OsmoPairAction.Invalid
        if (requested != null || presenting != null) return OsmoPairAction.Wait
        val left = frames[0]; val right = frames[1]
        if (left != null && right != null && left.presentationTimeUs == right.presentationTimeUs && !presented) {
            presenting = left.presentationTimeUs
            return OsmoPairAction.Present(left.presentationTimeUs, generation)
        }
        val desired = when {
            left == null && right == null -> when {
                pending[preferredSide] -> preferredSide
                pending[1 - preferredSide] -> 1 - preferredSide
                ended[0] || ended[1] -> return OsmoPairAction.Ended
                else -> return OsmoPairAction.Wait
            }
            left == null -> 0
            right == null -> 1
            left.presentationTimeUs < right.presentationTimeUs -> 0
            right.presentationTimeUs < left.presentationTimeUs -> 1
            pending[preferredSide] -> preferredSide
            pending[1 - preferredSide] -> 1 - preferredSide
            else -> if (ended[0] || ended[1]) return OsmoPairAction.Ended else return OsmoPairAction.Wait
        }
        if (!pending[desired]) return if (ended[desired]) OsmoPairAction.Ended else OsmoPairAction.Wait
        pending[desired] = false; requested = desired; preferredSide = 1 - desired
        return OsmoPairAction.Latch(desired, generation)
    }
    @Synchronized fun latched(side: Int, callbackGeneration: Long, frame: OsmoDecodedFrame?) {
        require(side in 0..1)
        if (callbackGeneration != generation) return
        if (requested != side) { invalid = true; return }
        requested = null
        if (frame == null || frame.textureTimestampNs < 0 || frame.presentationTimeUs < 0) { invalid = true; return }
        val previous = frames[side]
        if (previous?.textureTimestampNs == frame.textureTimestampNs) {
            if (previous.presentationTimeUs != frame.presentationTimeUs) invalid = true
            return // A delayed notification for the already-latched texture.
        }
        if (previous != null && frame.presentationTimeUs <= previous.presentationTimeUs) { invalid = true; return }
        if (frame.presentationTimeUs < minimumPresentationUs) return
        frames[side] = frame; presented = false
    }
    /** Discard an unmatchable latched texture; callers must bound repeated misses. */
    @Synchronized fun discarded(side: Int, callbackGeneration: Long) {
        require(side in 0..1)
        if (callbackGeneration != generation) return
        if (requested != side) { invalid = true; return }
        requested = null
    }
    @Synchronized fun presented(presentationTimeUs: Long, callbackGeneration: Long) {
        if (callbackGeneration != generation) return
        val left = frames[0]; val right = frames[1]
        if (left == null || right == null || presenting != presentationTimeUs || left.presentationTimeUs != presentationTimeUs || right.presentationTimeUs != presentationTimeUs || presented) {
            invalid = true; return
        }
        presenting = null; presented = true
    }
}
