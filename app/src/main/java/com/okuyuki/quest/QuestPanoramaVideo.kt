package com.okuyuki.quest

import android.view.LayoutInflater
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun QuestPanoramaVideo(source: MediaSource, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var failed by remember(source) { mutableStateOf(false) }
    val player = remember(source) { ExoPlayer.Builder(context).build().apply { setMediaSource(source); prepare(); playWhenReady = true } }
    val view = remember(player) { (LayoutInflater.from(context).inflate(R.layout.panorama_player, null) as PlayerView).apply { this.player = player } }
    DisposableEffect(owner, player, view) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true }
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
            view.player = null; player.removeListener(listener); player.release()
        }
    }
    if (failed) Text("360°動画を再生できませんでした。結合済み2:1のMP4と通信状態を確認してください。", color = MaterialTheme.colorScheme.error)
    else AndroidView(factory = { view }, modifier = modifier)
}
