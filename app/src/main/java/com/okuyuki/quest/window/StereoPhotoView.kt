package com.okuyuki.quest.window

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.okuyuki.stereo.StereoPair
import metavr.view.SurfaceControlExt
import metavr.view.SurfaceViewExt

/** Whether this OS composes a side-by-side surface in stereo inside a window: Meta VR OS SDK 207 and later. */
val stereoSupported: Boolean by lazy {
    try { metavr.os.Build.Version.getApiVersion() >= 207 } catch (_: LinkageError) { false } catch (_: RuntimeException) { false }
}

/** [eyes] in a window, in stereo where the OS allows it. [onMode] hears whether stereo composition took effect. */
@Composable
fun StereoSurface(eyes: StereoPair, modifier: Modifier, onMode: (Boolean) -> Unit) {
    AndroidView(factory = { context -> StereoPhotoView(context).also { it.onModeChanged = onMode } },
        update = { it.onModeChanged = onMode; it.eyes = eyes }, modifier = modifier, onRelease = { it.eyes = null })
}

class StereoPhotoView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    var eyes: StereoPair? = null
        set(value) {
            if (value === field) return
            field = value
            if (value == null) holder.setSizeFromLayout()
            else holder.setFixedSize(value.left.width * if (configured) 2 else 1, value.left.height)
            drawPhoto()
        }
    var onModeChanged: (Boolean) -> Unit = {}
    private var configured = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init { holder.addCallback(this) }
    private fun configure() {
        configured = if (stereoSupported) try {
            SurfaceViewExt.setStereoComposition(this, SurfaceControlExt.STEREO_COMPOSITION_SIDE_BY_SIDE); true
        } catch (_: LinkageError) { false } catch (_: RuntimeException) { false } else false
        onModeChanged(configured)
        eyes?.let { holder.setFixedSize(it.left.width * if (configured) 2 else 1, it.left.height) }
    }
    override fun surfaceCreated(holder: SurfaceHolder) { configure(); drawPhoto() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { configure(); drawPhoto() }
    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    private fun drawPhoto() {
        if (!holder.surface.isValid) return
        val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val pair = eyes ?: return
            if (configured) {
                drawEye(canvas, pair.left, 0f, canvas.width / 2f)
                drawEye(canvas, pair.right, canvas.width / 2f, canvas.width.toFloat())
            } else drawEye(canvas, pair.left, 0f, canvas.width.toFloat())
        } finally { holder.unlockCanvasAndPost(canvas) }
    }
    private fun drawEye(canvas: Canvas, bitmap: android.graphics.Bitmap, left: Float, right: Float) {
        val scale = minOf((right - left) / bitmap.width, canvas.height.toFloat() / bitmap.height)
        val width = bitmap.width * scale; val height = bitmap.height * scale
        val x = left + (right - left - width) / 2; val y = (canvas.height - height) / 2
        canvas.drawBitmap(bitmap, null, RectF(x, y, x + width, y + height), paint)
    }
}
