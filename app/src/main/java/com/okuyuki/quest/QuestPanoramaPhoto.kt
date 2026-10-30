package com.okuyuki.quest

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.okuyuki.quest.window.PanoramaPhotoView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

private sealed interface PhotoLoad {
    data object Loading : PhotoLoad
    data object Failed : PhotoLoad
    data class Ready(val holder: PhotoBitmap) : PhotoLoad
}
private class PhotoBitmap(val bitmap: Bitmap) {
    private var references = 1
    @Synchronized fun retain() { check(references > 0); references++ }
    @Synchronized fun release() { check(references > 0); references--; if (references == 0) bitmap.recycle() }
}

@Composable
fun QuestPanoramaPhoto(bytes: ByteArray, modifier: Modifier = Modifier) {
    val result by produceState<PhotoLoad>(PhotoLoad.Loading, bytes) {
        value = PhotoLoad.Loading
        var holder: PhotoBitmap? = null
        try {
            withContext(Dispatchers.IO) {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val width = info.size.width; val height = info.size.height
                    require(width > 0 && height > 0 && width.toDouble() / height in 1.9..2.1)
                    val scale = minOf(1.0, 4096.0 / width, 4096.0 / height)
                    decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                holder = PhotoBitmap(bitmap)
            }
            value = PhotoLoad.Ready(checkNotNull(holder))
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = PhotoLoad.Failed }
        finally { holder?.release() }
    }
    when (val state = result) {
        PhotoLoad.Loading -> CircularProgressIndicator()
        PhotoLoad.Failed -> Text("この360°写真を開けませんでした。結合済みの横2:縦1の写真を選んでください。", color = MaterialTheme.colorScheme.error)
        is PhotoLoad.Ready -> PhotoSurface(state.holder, modifier)
    }
}

@Composable
private fun PhotoSurface(holder: PhotoBitmap, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var failed by remember(holder) { mutableStateOf(false) }
    val view = remember(holder, owner) { PanoramaPhotoView(context, holder.bitmap) { failed = true } }
    DisposableEffect(owner, view) {
        holder.retain()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onResume()
                Lifecycle.Event.ON_STOP -> view.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onPause()
            view.close(); holder.release()
        }
    }
    if (failed) Text("この端末では360°写真を表示できません。", color = MaterialTheme.colorScheme.error)
    else AndroidView(factory = { view }, modifier = modifier)
}
