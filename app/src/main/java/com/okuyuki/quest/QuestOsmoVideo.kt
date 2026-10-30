package com.okuyuki.quest

import android.os.SystemClock
import com.okuyuki.protocol.PanoramaStereoCalibration
import com.okuyuki.protocol.OsmoStabilization
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun QuestOsmoVideo(sources: List<MediaSource>, calibration: PanoramaStereoCalibration?, stabilization: OsmoStabilization?,
    stabilizationEnabled: Boolean, onStabilizationUnavailable: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var failed by remember(sources) { mutableStateOf(false) }
    var wantPlaying by remember(sources) { mutableStateOf(true) }
    var syncReady by remember(sources) { mutableStateOf(false) }
    var position by remember(sources) { mutableLongStateOf(0L) }
    var duration by remember(sources) { mutableLongStateOf(0L) }
    var seeking by remember(sources) { mutableStateOf(false) }
    var resettingSeek by remember(sources) { mutableStateOf(false) }
    var pausePending by remember(sources) { mutableStateOf(false) }
    var active by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var ended by remember(sources) { mutableStateOf(false) }
    val selected = remember(sources) { BooleanArray(2) }
    val expectedGroups = remember(sources) { arrayOfNulls<androidx.media3.common.TrackGroup>(2) }
    val expectedIndices = remember(sources) { IntArray(2) }
    val players = remember(sources) {
        List(2) { index ->
            ExoPlayer.Builder(context).build().apply {
                val parameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                if (index == 1) parameters.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                trackSelectionParameters = parameters.build()
                setMediaSource(sources[index])
            }
        }
    }
    val view = remember(players, calibration, stabilization) { DualTrackFisheyeView(context, players, calibration, stabilization,
        onStabilizationUnavailable = onStabilizationUnavailable) { failed = true } }
    LaunchedEffect(view, stabilizationEnabled) { view.setStabilizationEnabled(stabilizationEnabled) }
    LaunchedEffect(view, windowFocused) {
        if (!windowFocused) { pausePending = false; view.resumeFrames(); wantPlaying = false; players.forEach { it.pause() } }
    }
    DisposableEffect(owner, players, view) {
        val listeners = players.mapIndexed { playerIndex, player ->
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { failed = true; players.forEach { it.pause() } }
                override fun onTracksChanged(tracks: Tracks) {
                    val expected = expectedGroups[playerIndex]
                    if (expected != null) {
                        selected[playerIndex] = tracks.groups.any { it.mediaTrackGroup == expected && it.isTrackSelected(expectedIndices[playerIndex]) }
                        return
                    }
                    val candidates = tracks.groups.flatMapIndexed { groupIndex, group ->
                        if (group.type != C.TRACK_TYPE_VIDEO) emptyList() else (0 until group.length).map { index ->
                            val f = group.getTrackFormat(index)
                            OsmoTrack(groupIndex, index, f.sampleMimeType, f.width, f.height, group.isTrackSupported(index))
                        }
                    }
                    if (candidates.isEmpty()) return
                    val pair = osmoTracks(candidates)
                    if (pair == null) { failed = true; players.forEach { it.pause() }; return }
                    val item = pair[playerIndex]
                    val group = tracks.groups[item.group].mediaTrackGroup
                    expectedGroups[playerIndex] = group
                    expectedIndices[playerIndex] = item.index
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group, item.index))
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
                    selected[playerIndex] = player.currentTracks.groups.any { it.mediaTrackGroup == group && it.isTrackSelected(item.index) }
                }
            }.also { player.addListener(it) }
        }
        players.forEach { it.prepare() }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { active = true; view.onResume() }
                Lifecycle.Event.ON_STOP -> { active = false; pausePending = false; view.resumeFrames(); wantPlaying = false; players.forEach { it.pause() }; view.onPause() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onPause()
            view.close()
            players.forEachIndexed { index, player -> player.removeListener(listeners[index]); player.release() }
        }
    }
    LaunchedEffect(players, wantPlaying, failed, windowFocused) {
        var selectionSince: Long? = null
        var finalSince: Long? = null
        var pauseSince: Long? = null
        var lastCount = view.frameCount()
        var progressAt = SystemClock.elapsedRealtime()
        while (true) {
            val first = players[0]; val second = players[1]
            val now = SystemClock.elapsedRealtime()
            if (!active || !windowFocused) {
                selectionSince = null; finalSince = null; pauseSince = null; progressAt = now
                delay(100); continue
            }
            if (pausePending) {
                val started = pauseSince ?: now.also { pauseSince = it }
                if (view.isFrozen() || now - started >= 3_000) {
                    if (!view.isFrozen()) view.resumeFrames()
                    pausePending = false; wantPlaying = false
                }
            } else pauseSince = null
            duration = first.duration.coerceAtLeast(0L)
            if (!seeking) position = first.currentPosition.coerceAtLeast(0L)
            val selectedBoth = selected.all { it }
            if (wantPlaying && !failed && !seeking && !selectedBoth) {
                val started = selectionSince ?: now.also { selectionSince = it }
                if (now - started >= 15_000) failed = true
            } else selectionSince = null
            val endedCount = players.count { it.playbackState == Player.STATE_ENDED }
            if (endedCount == 2 && view.isFrozen()) { view.resumeFrames(); pausePending = false }
            val prepared = selectedBoth && players.all { it.playbackState == Player.STATE_READY || it.playbackState == Player.STATE_ENDED }
            val drift = second.currentPosition - first.currentPosition
            if (prepared && endedCount == 0) {
                val speed = when { drift > 40 -> 0.95f; drift < -40 -> 1.05f; else -> 1f }
                if (second.playbackParameters.speed != speed) second.setPlaybackSpeed(speed)
            }
            syncReady = selectedBoth && (prepared || endedCount > 0) && !seeking && !failed
            view.setFrameVisibility(syncReady)
            if (endedCount == 2 && !seeking && !failed) {
                if (view.finalFramesPresented()) {
                    ended = true; wantPlaying = false; finalSince = null
                } else {
                    val started = finalSince ?: now.also { finalSince = it }
                    if (now - started >= 8_000) failed = true
                }
            } else finalSince = null
            if (!wantPlaying || !selectedBoth || seeking || failed || (!prepared && endedCount == 0)) {
                players.forEach { if (it.playWhenReady) it.pause() }
            } else players.filter { it.playbackState != Player.STATE_ENDED }.forEach { if (!it.playWhenReady) it.play() }
            val count = view.frameCount()
            if (count != lastCount || !wantPlaying || (!prepared && endedCount == 0) || seeking || endedCount == 2) progressAt = now
            else if (now - progressAt >= 8_000) failed = true
            lastCount = count
            delay(100)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().weight(1f))
        if (failed) Text("OSVを再生できません。2つの4K HEVC映像を同時に扱えない場合があります。", color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                onValueChange = { seeking = true; position = it.toLong() },
                onValueChangeFinished = {
                    val target = position.coerceAtMost((duration - 100L).coerceAtLeast(0L))
                    ended = false; resettingSeek = true; pausePending = false
                    players.forEach { it.pause() }
                    view.setFrameVisibility(false)
                    view.resetForSeek { players.forEach { it.seekTo(target) }; seeking = false; resettingSeek = false }
                },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), enabled = duration > 0 && !failed && !resettingSeek && !pausePending, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (ended) {
                    ended = false; seeking = true; resettingSeek = true; position = 0L
                    view.setFrameVisibility(false)
                    view.resetForSeek { players.forEach { it.seekTo(0L) }; seeking = false; resettingSeek = false; wantPlaying = true }
                } else if (wantPlaying) {
                    pausePending = true; view.freezeAtPair()
                } else {
                    view.resumeFrames(); wantPlaying = true
                }
            }, enabled = !failed && !resettingSeek && !pausePending) {
                Text(if (wantPlaying) "一時停止" else "再生")
            }
        }
    }
}
