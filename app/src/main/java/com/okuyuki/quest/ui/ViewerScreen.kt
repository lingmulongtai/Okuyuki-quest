package com.okuyuki.quest.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.okuyuki.quest.Link
import com.okuyuki.quest.Opening
import com.okuyuki.quest.QuestActions
import com.okuyuki.quest.QuestState
import com.okuyuki.quest.Thumbnails
import com.okuyuki.quest.window.stereoSupported
import kotlinx.coroutines.delay

private val Scrim = Color.Black.copy(alpha = 0.72f)
private val Glass = Color.White.copy(alpha = 0.14f)

/**
 * One photo filling the window, in stereo where the OS allows it. The bars fade away while the pointer rests, so
 * nothing sits in front of the photo; moving the pointer brings them back. Left and right, or a swipe, step through.
 */
@Composable
fun ViewerScreen(state: QuestState, actions: QuestActions, thumbnails: Thumbnails, stereo: StereoContent) {
    val photo = state.current ?: return
    val online = state.link == Link.CONNECTED
    val index = state.photos.indexOfFirst { it.id == photo.id }
    val hasPrevious = index > 0
    val hasNext = index in 0 until state.photos.lastIndex || state.next != null
    var stereoShown by remember(state.eyes) { mutableStateOf<Boolean?>(null) }
    var chrome by remember { mutableStateOf(true) }
    var nudge by remember { mutableIntStateOf(0) }
    val topBar = remember { MutableInteractionSource() }
    val bottomBar = remember { MutableInteractionSource() }
    val overBars = topBar.collectIsHoveredAsState().value || bottomBar.collectIsHoveredAsState().value
    val settled = state.eyes != null && state.opening == null && state.openError == null
    LaunchedEffect(chrome, nudge, overBars, settled) { if (chrome && settled && !overBars) { delay(4_000); chrome = false } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color.Black)
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (event.key) {
                Key.DirectionLeft, Key.PageUp -> { actions.step(-1); true }
                Key.DirectionRight, Key.PageDown -> { actions.step(1); true }
                else -> false
            }
        }
        .focusRequester(focus).focusable()
        .pointerInput(Unit) {
            // A controller ray is never perfectly still: only a real move brings the bars back.
            val reach = 40.dp.toPx()
            var anchor: Offset? = null
            awaitPointerEventScope {
                while (true) {
                    val position = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull()?.position ?: continue
                    val from = anchor
                    if (from == null || (position - from).getDistance() > reach) { anchor = position; chrome = true; nudge++ }
                }
            }
        }
        .pointerInput(Unit) { detectTapGestures { chrome = true; nudge++ } }
        .pointerInput(Unit) {
            var travel = 0f
            detectHorizontalDragGestures(onDragStart = { travel = 0f }, onDragEnd = {
                val far = 96.dp.toPx()
                if (travel <= -far) actions.step(1) else if (travel >= far) actions.step(-1)
            }) { change, amount -> travel += amount; change.consume() }
        }) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val eyes = state.eyes
            val ratio = eyes?.left?.let { it.width.toFloat() / it.height } ?: (photo.width.toFloat() / photo.height.coerceAtLeast(1)).coerceAtLeast(0.1f)
            val width = if (maxWidth / maxHeight > ratio) maxHeight * ratio else maxWidth
            val fitted = Modifier.size(width, width / ratio)
            if (eyes != null) stereo(eyes, fitted) { stereoShown = it }
            else Thumbnail(photo, thumbnails, online, fitted.alpha(if (state.openError != null) 0.3f else 0.55f), ContentScale.Fit, placeholder = 56.dp)
        }
        state.opening?.let { OpeningCard(it, Modifier.align(Alignment.Center)) }
        state.openError?.let { message ->
            ErrorCard(message, online, retry = { actions.open(photo) }, back = actions::close, modifier = Modifier.align(Alignment.Center))
        }
        AnimatedVisibility(chrome || !settled, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Scrim, Color.Transparent))).hoverable(topBar)
                .padding(start = 20.dp, end = 28.dp, top = 18.dp, bottom = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = actions::close, modifier = Modifier.height(52.dp), contentPadding = PaddingValues(start = 14.dp, end = 22.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Glass, contentColor = Color.White)) {
                    Icon(Glyphs.ChevronLeft, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("一覧")
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(dateTimeLabel(photo.takenAt), style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(if (stereoShown == false) flatReason() else photo.name, style = MaterialTheme.typography.bodySmall,
                        color = if (stereoShown == false) Extras.warning else Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when (stereoShown) {
                    true -> Badge("3D", glyph = Glyphs.Cube)
                    false -> Badge("平面", container = Glass, content = Color.White)
                    null -> Unit
                }
            }
        }
        if (!online) LinkPill(state.link, Modifier.align(Alignment.TopCenter).padding(top = 124.dp))
        AnimatedVisibility(chrome || !settled, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Scrim))).hoverable(bottomBar)
                .padding(top = 44.dp, bottom = 22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Glyphs.ChevronLeft, "前の写真", enabled = hasPrevious && online) { actions.step(-1) }
                Text("${index + 1} / ${maxOf(state.total, state.photos.size)}", Modifier.widthIn(min = 140.dp).padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
                RoundButton(Glyphs.ChevronRight, "次の写真", enabled = hasNext && online) { actions.step(1) }
            }
        }
    }
}

private fun flatReason() = if (stereoSupported) "立体で表示できませんでした。平面で表示しています。" else "この Quest では平面で表示します。立体には Quest の更新が必要です。"

@Composable
private fun RoundButton(glyph: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(60.dp), shape = CircleShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Glass, contentColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.06f), disabledContentColor = Color.White.copy(alpha = 0.3f))) {
        Icon(glyph, contentDescription = label, modifier = Modifier.size(28.dp))
    }
}

/** How far a photo has come from the phone, then that its two eyes are being read. */
@Composable
private fun OpeningCard(opening: Opening, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = Extras.panel.copy(alpha = 0.94f), contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.widthIn(min = 320.dp).padding(horizontal = 36.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (opening.decoding || opening.total == 0L) CircularProgressIndicator(Modifier.size(52.dp), strokeWidth = 4.dp)
            else CircularProgressIndicator(progress = { opening.fraction }, modifier = Modifier.size(52.dp), strokeWidth = 4.dp,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            Spacer(Modifier.height(18.dp))
            Text(if (opening.decoding) "左右の画像を読み込んでいます…" else "スマホから受け取っています", style = MaterialTheme.typography.titleMedium)
            if (!opening.decoding && opening.total > 0) {
                Spacer(Modifier.height(4.dp))
                Text("${(opening.fraction * 100).toInt()}% · ${sizeLabel(opening.received)} / ${sizeLabel(opening.total)}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, online: Boolean, retry: () -> Unit, back: () -> Unit, modifier: Modifier) {
    Surface(modifier.padding(24.dp), shape = MaterialTheme.shapes.large, color = Extras.panel, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.widthIn(max = 520.dp).padding(horizontal = 36.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GlyphBubble(Glyphs.Info, size = 60.dp, container = MaterialTheme.colorScheme.errorContainer, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(18.dp))
            Text("この写真を開けませんでした", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = back, modifier = Modifier.height(52.dp)) { Text("一覧に戻る") }
                Button(onClick = retry, enabled = online, modifier = Modifier.height(52.dp)) { Text("もう一度") }
            }
        }
    }
}

/** Over the photo while the phone is away: the app is trying again, or has stopped trying. */
@Composable
private fun LinkPill(link: Link, modifier: Modifier) {
    Surface(modifier, shape = CircleShape, color = Extras.panel.copy(alpha = 0.94f), contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (link == Link.RECONNECTING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
            Spacer(Modifier.width(12.dp))
            Text(if (link == Link.RECONNECTING) "スマホとの接続が切れました。つなぎ直しています…" else "スマホにつながりません。一覧から、もう一度つなげます。",
                style = MaterialTheme.typography.labelLarge)
        }
    }
}
