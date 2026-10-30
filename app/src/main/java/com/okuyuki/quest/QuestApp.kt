package com.okuyuki.quest

import android.graphics.Bitmap
import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okuyuki.protocol.Photo
import com.okuyuki.protocol.PanoramaVideoFormat
import com.okuyuki.quest.link.Endpoint
import com.okuyuki.quest.window.StereoPhotoView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun QuestApp(model: QuestViewModel) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val nearby by model.discovery.phones.collectAsStateWithLifecycle()
    val discoveryError by model.discovery.error.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf("") }
    var addressError by remember { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var highResolution by rememberSaveable { mutableStateOf(false) }
    val theme = darkColorScheme(primary = Color.White, onPrimary = Color(0xFF10131C), background = Color(0xFF090A0F), surface = Color(0xFF232630), onSurface = Color.White, onSurfaceVariant = Color(0xFFB5B6BD))
    BackHandler(state.current != null || state.viewingPanorama || state.viewingVideo) {
        if (state.viewingVideo) model.closeVideo() else if (state.viewingPanorama) model.closePanorama() else model.backToLibrary()
    }
    MaterialTheme(colorScheme = theme) {
        Surface(Modifier.fillMaxSize(), color = theme.background) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Okuyuki", style = MaterialTheme.typography.headlineMedium)
                    Text(if (state.connected) "● ${state.hello?.deviceName}" else "スマホとつなぐ", color = if (state.connected) Color(0xFF6FE0AE) else theme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (state.hello != null) TextButton(onClick = { model.disconnect(); model.start() }) { Text("切断") }
                    TextButton(onClick = { settings = !settings }) { Text(if (settings) "戻る" else "設定") }
                }
                state.error?.let { message ->
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(message, Modifier.weight(1f), color = theme.error); TextButton(onClick = model::reconnect) { Text("再接続") } }
                }
                when {
                    settings -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("一時保存", style = MaterialTheme.typography.titleLarge)
                        Text("使用量 ${state.cacheBytes / 1024 / 1024} MB · 上限 1 GB")
                        Text("写真はQuestに残しません。終了時と次の起動時に一時保存を消します。")
                        Text("Okuyuki ${BuildConfig.VERSION_NAME}")
                        Text("立体表示にはMeta VR OS SDK 207以降が必要です。通常Androidでは平面プレビューになります。")
                        Text("実機検証は未完了です。左右の見え方を確認し、違和感があれば使用を中止してください。")
                        Text("HEICの読み取りにlibheifとlibde265を使用しています（LGPL）。ライセンスと対応ソースはGitHubのリリースに同梱しています。")
                    }
                    state.pairingCode != null -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text(state.pairingCode!!, fontSize = 64.sp)
                        Text(state.message)
                        OutlinedButton(onClick = { model.disconnect(); model.start() }) { Text("キャンセル") }
                    }
                    state.viewingVideo -> {
                        TextButton(onClick = model::closeVideo) { Text("一覧に戻る") }
                        Text("360°動画 · ウィンドウ内プレビュー")
                        val share = state.sharedVideo
                        val videoGeneration = state.videoGeneration
                        if (state.videoMotionLoading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("スマホから姿勢情報を確認しています…")
                        }
                        if (share != null && share.format in listOf(PanoramaVideoFormat.OSMO_LRF, PanoramaVideoFormat.OSMO_OSV) && !state.videoMotionLoading && state.videoMotion != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = state.videoStabilizationEnabled, onCheckedChange = { model.setVideoStabilization(it, share.id, videoGeneration) })
                                Text("手ぶれ補正（実験的）")
                            }
                            if (state.videoStabilizationUnavailable) Text("映像フレームと姿勢を照合できないため、補正をオフにしました。")
                        }
                        if (share?.format == PanoramaVideoFormat.OSMO_OSV) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = highResolution, onCheckedChange = { highResolution = it })
                                Text("没入表示を高画質にする（実験的・動作が重くなる場合があります）")
                            }
                        }
                        if (share != null && !state.videoMotionLoading) {
                            OutlinedButton(onClick = {
                                if (model.prepareImmersiveVideo(highResolution)) {
                                    try {
                                        context.startActivity(Intent(context, ImmersiveActivity::class.java).apply {
                                            action = Intent.ACTION_MAIN
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        })
                                        (context as? Activity)?.finishAndRemoveTask()
                                    } catch (_: Exception) {
                                        ImmersivePlayback.cancel()
                                        Toast.makeText(context, "没入画面を開けませんでした", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }) { Text("没入表示を試す（実験的）") }
                        }
                        val sources = remember(share) { listOfNotNull(model.videoSource(), model.videoSource()) }
                        if (!state.videoMotionLoading && sources.size == 2) when (share?.format) {
                            PanoramaVideoFormat.OSMO_LRF -> QuestOsmoProxy(sources[0], share.calibration, state.videoMotion, state.videoStabilizationEnabled,
                                { model.videoStabilizationUnavailable(share.id, videoGeneration) }, Modifier.weight(1f).fillMaxWidth())
                            PanoramaVideoFormat.OSMO_OSV -> QuestOsmoVideo(sources, share.calibration, state.videoMotion, state.videoStabilizationEnabled,
                                { model.videoStabilizationUnavailable(share.id, videoGeneration) }, Modifier.weight(1f).fillMaxWidth())
                            else -> QuestPanoramaVideo(sources[0], Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                    state.viewingPanorama -> {
                        TextButton(onClick = model::closePanorama) { Text("一覧に戻る") }
                        Text("360°写真 · ウィンドウ内をドラッグして見回します")
                        if (state.openingPanorama) CircularProgressIndicator()
                        state.panoramaBytes?.let { QuestPanoramaPhoto(it, Modifier.weight(1f).fillMaxWidth()) }
                    }
                    state.current != null -> Viewer(model, state)
                    state.connected -> {
                        if (state.sharedVideo != null) {
                            OutlinedButton(onClick = model::openVideo, modifier = Modifier.fillMaxWidth()) { Text("スマホで共有中の360°動画を開く") }
                        }
                        if (state.sharedPanorama != null) {
                            OutlinedButton(onClick = model::openPanorama, modifier = Modifier.fillMaxWidth()) { Text("スマホで共有中の360°写真を開く") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("スマホの3D写真 · ${state.hello?.photoCount ?: state.photos.size}枚", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = model::refresh) { Text("更新") }
                        }
                        if (state.photos.isEmpty()) Text("3D写真が見つかりません。スマホ側で写真のアクセス許可とギャラリーを確認してください。")
                        LazyVerticalGrid(columns = GridCells.Adaptive(180.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.photos.groupBy { date(it.takenAt) }.forEach { (day, photos) ->
                                item(span = { GridItemSpan(maxLineSpan) }) { Text(day, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium) }
                                items(photos, key = { it.id }) { photo ->
                                    Box(Modifier.aspectRatio(1f).clickable { model.open(photo) }) { QuestThumbnail(model, photo, Modifier.fillMaxSize()) }
                                }
                            }
                            if (state.next != null) item(span = { GridItemSpan(maxLineSpan) }) {
                                OutlinedButton(onClick = model::more, enabled = !state.loadingPage, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text(if (state.loadingPage) "一覧を確認中…" else "続きを見る") }
                            }
                        }
                    }
                    else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(state.message, style = MaterialTheme.typography.titleLarge)
                        Text("同じWi-Fiまたはスマホのテザリングに接続してください。")
                        nearby.forEach { phone ->
                            OutlinedCard(onClick = { model.connect(phone.endpoint) }, modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(phone.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); Text("つなぐ")
                                }
                            }
                        }
                        if (nearby.isEmpty()) Text("近くのスマホを探しています…", color = theme.onSurfaceVariant)
                        discoveryError?.let { Text(it) }
                        HorizontalDivider()
                        Text("アドレスを入力してつなぐ", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(value = address, onValueChange = { address = it; addressError = false }, singleLine = true,
                            label = { Text("スマホに表示されたアドレス") }, placeholder = { Text("192.168.1.2:12345") }, isError = addressError,
                            supportingText = { if (addressError) Text("スマホに表示されたIPアドレスとポートを入力してください") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { val endpoint = Endpoint.parse(address); if (endpoint == null) addressError = true else model.connect(endpoint) }) { Text("つなぐ") }
                    }
                }
            }
        }
    }
}

@Composable private fun ColumnScope.Viewer(model: QuestViewModel, state: QuestState) {
    val photo = state.current ?: return
    var stereoAvailable by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = model::backToLibrary) { Text("一覧に戻る") }
        Text(date(photo.takenAt), Modifier.weight(1f))
        Text(if (state.eyes != null && stereoAvailable) "3D" else "平面プレビュー")
    }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
        val ratio = state.eyes?.left?.let { it.width.toFloat() / it.height } ?: (photo.width.toFloat() / photo.height.coerceAtLeast(1)).coerceAtLeast(.1f)
        val displayWidth = if (maxWidth / maxHeight > ratio) maxHeight * ratio else maxWidth
        val displayHeight = displayWidth / ratio
        if (state.eyes == null) QuestThumbnail(model, photo, Modifier.fillMaxSize(), ContentScale.Fit)
        else AndroidView(factory = { context -> StereoPhotoView(context).also { view -> view.onModeChanged = { stereoAvailable = it } } },
            update = { it.eyes = state.eyes }, modifier = Modifier.size(displayWidth, displayHeight), onRelease = { it.eyes = null })
        if (state.opening) Surface(color = Color(0xCC232630), modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) { Text("写真を開いています…", Modifier.padding(12.dp)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val index = state.photos.indexOfFirst { it.id == photo.id }
        OutlinedButton(onClick = { model.step(-1) }, enabled = index > 0) { Text("前へ") }
        Text("${index + 1} / ${state.photos.size}", Modifier.padding(horizontal = 24.dp))
        OutlinedButton(onClick = { model.step(1) }, enabled = index in 0 until state.photos.lastIndex) { Text("次へ") }
    }
    if (state.eyes != null && !stereoAvailable) Text("このOSではウィンドウ内の立体表示を使用できません。Questを更新してください。", style = MaterialTheme.typography.bodySmall)
}

@Composable private fun QuestThumbnail(model: QuestViewModel, photo: Photo, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val bitmap by produceState<Bitmap?>(null, photo.id) { value = model.thumbnail(photo.id) }
    Box(modifier.background(Color(0xFF232630)), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), photo.name, Modifier.fillMaxSize(), contentScale = scale) } ?: Text("3D写真", color = Color.White.copy(alpha = .66f))
    }
}
private fun date(time: Long) = SimpleDateFormat("M月d日（E）", Locale.JAPANESE).format(Date(time))
