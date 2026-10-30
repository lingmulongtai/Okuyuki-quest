package com.okuyuki.quest

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

@Composable
fun QuestOsmoProxy(source: MediaSource, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var failed by remember(source) { mutableStateOf(false) }
    var playing by remember(source) { mutableStateOf(false) }
    var position by remember(source) { mutableLongStateOf(0L) }
    var duration by remember(source) { mutableLongStateOf(0L) }
    var seeking by remember(source) { mutableStateOf(false) }
    val player = remember(source) {
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(source); prepare(); playWhenReady = true
        }
    }
    val view = remember(player) { DualFisheyeView(context, player) { failed = true; player.pause() } }
    DisposableEffect(owner, player, view) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                val ratio = videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height
                if (ratio !in 1.9f..2.1f) { failed = true; player.pause() }
            }
        }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onResume()
                Lifecycle.Event.ON_STOP -> { player.pause(); view.onPause() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onPause()
            view.close()
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            duration = player.duration.coerceAtLeast(0L)
            if (!seeking) position = player.currentPosition.coerceAtLeast(0L)
            delay(250)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("LRF · 実験的な魚眼プレビュー", style = MaterialTheme.typography.titleLarge)
        Text("レンズ・手ぶれ補正なし。映像のずれや継ぎ目が生じます。")
        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().weight(1f))
        if (failed) Text("LRFを再生できません。通信状態と対応形式を確認してください。", color = MaterialTheme.colorScheme.error)
        Slider(value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
            onValueChange = { seeking = true; position = it.toLong() },
            onValueChangeFinished = { player.seekTo(position); seeking = false },
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), enabled = duration > 0 && !failed)
        TextButton(onClick = { if (playing) player.pause() else player.play() }, enabled = !failed) {
            Text(if (playing) "一時停止" else "再生")
        }
    }
}
