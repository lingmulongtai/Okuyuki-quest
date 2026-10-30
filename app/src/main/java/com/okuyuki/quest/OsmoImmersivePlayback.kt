package com.okuyuki.quest

import android.content.Context
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.okuyuki.protocol.PanoramaVideoFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Main-thread owner of the decoder surfaces and their offscreen projection. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OsmoImmersivePlayback(
    private val context: Context,
    private val session: ImmersivePlayback.Connection,
    output: Surface,
    private val scope: CoroutineScope,
    private val onError: () -> Unit,
) {
    private val proxy = session.share.format == PanoramaVideoFormat.OSMO_LRF
    private val count = if (proxy) 1 else 2
    private var focused = false
    private var closed = false
    private val players = mutableListOf<ExoPlayer>()
    private val selected = BooleanArray(count) { proxy }
    private val expected = arrayOfNulls<TrackGroup>(count)
    private val indices = IntArray(count)
    private var syncJob: Job? = null
    private val renderer = OsmoSphericalRenderer(output, proxy, session.share.calibration, ::prepare, ::fail)

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
            if (index == 1) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
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
                        .setOverrideForType(TrackSelectionOverride(group, item.index)).build()
                    selected[index] = player.currentTracks.groups.any { it.mediaTrackGroup == group && it.isTrackSelected(item.index) }
                }
            })
            player.setVideoSurface(surfaces[index])
            player.setMediaSource(session.client.panoramaVideoSource(session.share))
        }
        players.forEach { it.prepare() }
        syncJob = scope.launch {
            while (isActive && !closed) {
                try {
                val prepared = selected.all { it } && players.all { it.playbackState == Player.STATE_READY }
                val drift = if (count == 2) abs(players[0].currentPosition - players[1].currentPosition) else 0L
                if (prepared && count == 2 && drift > 150) players[1].seekTo(players[0].currentPosition)
                val ready = focused && prepared && drift <= 150
                renderer.setVisible(ready)
                players.forEach { if (ready && !it.playWhenReady) it.play() else if (!ready && it.playWhenReady) it.pause() }
                } catch (_: Exception) { fail(); return@launch }
                delay(100)
            }
        }
    }
    fun setFocused(value: Boolean) {
        focused = value
        if (!value) { renderer.setVisible(false); players.forEach { it.pause() } }
    }
    private fun fail() { if (!closed) { close(); onError() } }
    fun close() {
        if (closed) return
        closed = true; syncJob?.cancel()
        players.forEach { it.clearVideoSurface(); it.release() }; players.clear()
        renderer.close()
    }
}
