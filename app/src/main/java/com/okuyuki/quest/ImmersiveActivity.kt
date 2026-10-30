package com.okuyuki.quest

import android.os.Bundle
import android.view.Surface
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.meta.spatial.core.Entity
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.runtime.SessionState
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.*
import com.meta.spatial.vr.VRFeature
import kotlinx.coroutines.*

/** Experimental head-tracked, mono equirectangular video. */
@androidx.annotation.OptIn(UnstableApi::class)
class ImmersiveActivity : AppSystemActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connection: ImmersivePlayback.Connection? = null
    private var player: ExoPlayer? = null
    private var videoSurface: Surface? = null
    private var focused = false

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this))
    override fun registerPanels(): List<PanelRegistration> = listOf(
        VideoSurfacePanelRegistration(R.id.immersive_video,
            surfaceConsumer = { _, surface -> videoSurface = surface; player?.setVideoSurface(surface) },
            settingsCreator = { MediaPanelSettings(shape = Equirect360ShapeOptions(radius = 300f),
                display = PixelDisplayOptions(width = 100, height = 100),
                rendering = MediaPanelRenderOptions(stereoMode = StereoMode.None, zIndex = -1)) })
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = ImmersivePlayback.take() ?: run { finish(); return }
        connection = session
        player = ExoPlayer.Builder(this).build().apply {
            videoSurface?.let { setVideoSurface(it) }
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { finish() }
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0 && size.width.toFloat() * size.pixelWidthHeightRatio / size.height !in 1.9f..2.1f) finish()
                }
            })
            setMediaSource(session.client.panoramaVideoSource(session.share))
            prepare(); playWhenReady = focused
        }
        session.client.events(scope, changed = { scope.launch { verifyShare(session) } }, failed = { scope.launch { finish() } })
        scope.launch { while (isActive) { verifyShare(session); delay(5000) } }
    }
    private suspend fun verifyShare(session: ImmersivePlayback.Connection) {
        try {
            if (session.client.panoramaVideo() != session.share) finish()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { finish() }
    }
    override fun onSceneReady() {
        super.onSceneReady()
        Entity.create(Panel(R.id.immersive_video), Transform(), Visible(true))
    }
    override fun onSessionStateChanged(state: SessionState) {
        super.onSessionStateChanged(state)
        when (state) {
            SessionState.FOCUSED -> { focused = true; player?.play() }
            else -> { focused = false; player?.pause() }
        }
    }
    override fun onPause() {
        focused = false; player?.pause()
        super.onPause()
    }
    override fun onStop() {
        player?.pause()
        if (!isFinishing) finish()
        super.onStop()
    }
    override fun onDestroy() {
        scope.cancel(); player?.release(); player = null
        connection?.client?.close(); connection = null; videoSurface = null
        super.onDestroy()
    }
}
