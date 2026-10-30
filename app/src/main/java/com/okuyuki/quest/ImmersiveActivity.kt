package com.okuyuki.quest

import android.os.Bundle
import android.view.Surface
import android.widget.Toast
import com.okuyuki.protocol.PanoramaVideoFormat
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

/** Experimental head-tracked, mono panoramic video. */
@androidx.annotation.OptIn(UnstableApi::class)
class ImmersiveActivity : AppSystemActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connection: ImmersivePlayback.Connection? = null
    private var player: ExoPlayer? = null
    private var videoSurface: Surface? = null
    private var focused = false
    private var rawPlayback: OsmoImmersivePlayback? = null
    private var rawSurface: Surface? = null

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this))
    override fun registerPanels(): List<PanelRegistration> = listOf(
        VideoSurfacePanelRegistration(R.id.immersive_video,
            surfaceConsumer = { _, surface -> runOnUiThread {
                videoSurface = surface
                player?.setVideoSurface(surface)
                startRawIfReady()
            } },
            settingsCreator = { MediaPanelSettings(shape = Equirect360ShapeOptions(radius = 300f),
                display = PixelDisplayOptions(width = 100, height = 100),
                rendering = MediaPanelRenderOptions(stereoMode = StereoMode.None, zIndex = -1)) })
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = ImmersivePlayback.take() ?: run { finish(); return }
        connection = session
        if (session.share.format != PanoramaVideoFormat.EQUIRECTANGULAR_MP4) {
            startRawIfReady()
        } else player = ExoPlayer.Builder(this).build().apply {
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
    private fun startRawIfReady() {
        if (isFinishing || isDestroyed) return
        val session = connection ?: return
        if (session.share.format == PanoramaVideoFormat.EQUIRECTANGULAR_MP4) return
        val surface = videoSurface ?: return
        if (rawPlayback != null) {
            if (rawSurface !== surface) finish()
            return
        }
        rawSurface = surface
        rawPlayback = OsmoImmersivePlayback(this, session, surface, scope, onError = {
            Toast.makeText(this, "没入再生を開始できませんでした。通常プレビューをお試しください。", Toast.LENGTH_LONG).show()
            finish()
        }, onSynchronizationFailure = {
            Toast.makeText(this, "左右映像のフレームを同期できませんでした。通常プレビューをお試しください。", Toast.LENGTH_LONG).show()
            finish()
        }, onStabilizationUnavailable = {
            Toast.makeText(this, "映像と姿勢の時刻を照合できないため、手ぶれ補正をオフにしました。", Toast.LENGTH_LONG).show()
        }, onQualityFallback = {
            Toast.makeText(this, "高画質で開始できないため、標準画質に切り替えました。", Toast.LENGTH_LONG).show()
        }).also { it.setFocused(focused) }
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
            SessionState.FOCUSED -> { focused = true; player?.play(); rawPlayback?.setFocused(true) }
            else -> { focused = false; player?.pause(); rawPlayback?.setFocused(false) }
        }
    }
    override fun onPause() {
        focused = false; player?.pause(); rawPlayback?.setFocused(false)
        super.onPause()
    }
    override fun onStop() {
        player?.pause(); rawPlayback?.setFocused(false)
        if (!isFinishing) finish()
        super.onStop()
    }
    override fun onDestroy() {
        rawPlayback?.close(); rawPlayback = null; rawSurface = null
        scope.cancel(); player?.release(); player = null
        connection?.client?.close(); connection = null; videoSurface = null
        super.onDestroy()
    }
}
