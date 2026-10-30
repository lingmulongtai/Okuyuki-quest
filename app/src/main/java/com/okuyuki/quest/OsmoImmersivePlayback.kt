package com.okuyuki.quest

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.okuyuki.protocol.FrameTimestampMap
import com.okuyuki.protocol.PanoramaVideoFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Main-thread owner of the decoder surfaces and their offscreen projection. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OsmoImmersivePlayback(
    private val context: Context,
    private val session: ImmersivePlayback.Connection,
    output: Surface,
    private val scope: CoroutineScope,
    private val onError: () -> Unit,
    private val onSynchronizationFailure: () -> Unit,
    private val onStabilizationUnavailable: () -> Unit,
) {
    private val proxy = session.share.format == PanoramaVideoFormat.OSMO_LRF
    private val count = if (proxy) 1 else 2
    private var focused = false
    private var allEnded = false
    private var closed = false
    private val players = mutableListOf<ExoPlayer>()
    private val selected = BooleanArray(count) { proxy }
    private val expected = arrayOfNulls<TrackGroup>(count)
    private val indices = IntArray(count)
    private var syncJob: Job? = null
    private val timestamps = Array(count) { FrameTimestampMap() }
    private val metadataListeners = List(count) { index -> VideoFrameMetadataListener { pts, releaseNs, _, _ ->
        timestamps[index].record(releaseNs, pts)
    } }
    private val renderer = OsmoSphericalRenderer(output, proxy, session.share.calibration,
        session.stabilization, timestamps, ::prepare, ::fail, ::syncFailure, onStabilizationUnavailable)

    private fun prepare(surfaces: List<Surface>) {
        if (closed) return
        try { preparePlayers(surfaces) } catch (_: Exception) { fail() }
    }
    private fun preparePlayers(surfaces: List<Surface>) {
        if (closed) return
        if (surfaces.size != count) { fail(); return }
        repeat(count) { index ->
            val player = ExoPlayer.Builder(context).build()
            players += player
            val parameters = player.trackSelectionParameters.buildUpon()
            if (!proxy) parameters.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            if (index == 1) parameters.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            player.trackSelectionParameters = parameters.build()
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) = fail()
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (!proxy || size.width <= 0 || size.height <= 0) return
                    if (size.width.toFloat() * size.pixelWidthHeightRatio / size.height !in 1.9f..2.1f) fail()
                }
                override fun onTracksChanged(tracks: Tracks) {
                    if (closed || proxy) return
                    val selectedGroup = expected[index]
                    if (selectedGroup != null) {
                        selected[index] = tracks.groups.any { it.mediaTrackGroup == selectedGroup && it.isTrackSelected(indices[index]) }
                        return
                    }
                    val candidates = tracks.groups.flatMapIndexed { groupIndex, group ->
                        if (group.type != C.TRACK_TYPE_VIDEO) emptyList() else (0 until group.length).map { trackIndex ->
                            val f = group.getTrackFormat(trackIndex)
                            OsmoTrack(groupIndex, trackIndex, f.sampleMimeType, f.width, f.height, group.isTrackSupported(trackIndex))
                        }
                    }
                    if (candidates.isEmpty()) return
                    val pair = osmoTracks(candidates) ?: run { fail(); return }
                    val item = pair[index]
                    val group = tracks.groups[item.group].mediaTrackGroup
                    expected[index] = group; indices[index] = item.index
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group, item.index))
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
                    selected[index] = player.currentTracks.groups.any { it.mediaTrackGroup == group && it.isTrackSelected(item.index) }
                }
            })
            if (!proxy || session.stabilization != null) player.setVideoFrameMetadataListener(metadataListeners[index])
            player.setVideoSurface(surfaces[index])
            player.setMediaSource(session.client.panoramaVideoSource(session.share))
        }
        players.forEach { it.prepare() }
        syncJob = scope.launch {
            var selectionWaitStarted: Long? = null
            while (isActive && !closed) {
                try {
                    if (!proxy && focused && selected.any { !it }) {
                        val now = SystemClock.elapsedRealtime()
                        val started = selectionWaitStarted ?: now.also { selectionWaitStarted = it }
                        if (now - started >= 15_000) { syncFailure(); return@launch }
                    } else selectionWaitStarted = null
                    val ended = players.count { it.playbackState == Player.STATE_ENDED }
                    if (ended == count) { allEnded = true; renderer.setVisible(focused); renderer.finishWhenEnded(timestamps.map { it.latest() }); break }
                    if (ended > 0) {
                        renderer.setVisible(focused)
                        players.filter { it.playbackState != Player.STATE_ENDED }.forEach {
                            if (focused && !it.playWhenReady) it.play() else if (!focused && it.playWhenReady) it.pause()
                        }
                    } else {
                        val prepared = selected.all { it } && players.all { it.playbackState == Player.STATE_READY }
                        if (prepared && count == 2) {
                            val drift = players[1].currentPosition - players[0].currentPosition
                            val speed = when { drift > 40 -> 0.95f; drift < -40 -> 1.05f; else -> 1f }
                            if (players[1].playbackParameters.speed != speed) players[1].setPlaybackSpeed(speed)
                        }
                        val ready = focused && prepared
                        renderer.setVisible(ready)
                        players.forEach { if (ready && !it.playWhenReady) it.play() else if (!ready && it.playWhenReady) it.pause() }
                    }
                } catch (_: Exception) { fail(); return@launch }
                delay(100)
            }
        }
    }
    fun setFocused(value: Boolean) {
        focused = value
        if (allEnded) renderer.setVisible(value)
        if (!value) { renderer.setVisible(false); players.forEach { it.pause() } }
    }
    private fun fail() { if (!closed) { close(); onError() } }
    private fun syncFailure() { if (!closed) { close(); onSynchronizationFailure() } }
    fun close() {
        if (closed) return
        closed = true; syncJob?.cancel()
        players.forEachIndexed { index, player ->
            if (!proxy || session.stabilization != null) player.clearVideoFrameMetadataListener(metadataListeners[index])
            player.clearVideoSurface(); player.release()
        }; players.clear()
        renderer.close()
    }
}
