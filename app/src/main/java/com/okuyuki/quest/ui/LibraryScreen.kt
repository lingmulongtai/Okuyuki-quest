package com.okuyuki.quest.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.okuyuki.protocol.Photo
import com.okuyuki.quest.Link
import com.okuyuki.quest.QuestActions
import com.okuyuki.quest.QuestState
import com.okuyuki.quest.Thumbnails
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** The phone's 3D photos by day, newest first. More pages load as the end comes into view. */
@Composable
fun LibraryScreen(state: QuestState, actions: QuestActions, thumbnails: Thumbnails, grid: LazyGridState, openSettings: () -> Unit) {
    val online = state.link == Link.CONNECTED
    Column(Modifier.fillMaxSize()) {
        BrandBar(openSettings, title = {
            Text("3D写真", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Text("${maxOf(state.total, state.photos.size)} 枚", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }) {
            LinkChip(state, onClick = openSettings)
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = actions::refresh, enabled = online && !state.loadingPage, modifier = Modifier.size(56.dp)) {
                if (state.refreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Glyphs.Refresh, contentDescription = "一覧を更新", modifier = Modifier.size(26.dp))
            }
        }
        val inset = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 12.dp)
        when (state.link) {
            Link.RECONNECTING -> Notice("スマホとの接続が切れました。つなぎ直しています…", inset, busy = true)
            Link.LOST -> Notice("スマホにつながりません。スマホの「接続を待つ」がオンか確かめてください。", inset, tone = Tone.ERROR) {
                NoticeButton("もう一度", actions::retry)
                NoticeButton("ほかのスマホ", actions::disconnect)
            }
            else -> Unit
        }
        when {
            state.photos.isEmpty() && state.loadingPage -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.photos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Glyphs.Cube, "3D写真はまだありません",
                    "Galaxy で撮った 3D 写真が、ここに並びます。スマホに増えると、この一覧にも自動で出てきます。")
            }
            else -> PhotoGrid(state, actions, thumbnails, grid, online)
        }
    }
}

@Composable
private fun LinkChip(state: QuestState, onClick: () -> Unit) {
    val (dot, label) = when (state.link) {
        Link.CONNECTED -> Extras.success to (state.phone ?: "スマホ")
        Link.RECONNECTING -> Extras.warning to "つなぎ直しています"
        else -> MaterialTheme.colorScheme.error to "接続が切れました"
    }
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(label, Modifier.widthIn(max = 220.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PhotoGrid(state: QuestState, actions: QuestActions, thumbnails: Thumbnails, grid: LazyGridState, online: Boolean) {
    val today = remember { LocalDate.now() }
    val days = remember(state.photos) { state.photos.groupBy { localDay(it.takenAt) } }
    // Back from the viewer, keep the photo just seen in view.
    LaunchedEffect(Unit) {
        val id = state.lastViewed ?: return@LaunchedEffect
        var index = 0
        for ((_, group) in days) {
            val at = group.indexOfFirst { it.id == id }
            if (at >= 0) { index += 1 + at; break }
            index += 1 + group.size
        }
        val visible = snapshotFlow { grid.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
        if (visible.none { it.index == index }) grid.scrollToItem((index - 1).coerceAtLeast(0))
    }
    LazyVerticalGrid(GridCells.Adaptive(176.dp), Modifier.fillMaxSize(), grid, contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        days.forEach { (day, group) ->
            stickyHeader(key = "day:$day", contentType = "day") {
                Text(dayLabel(day, today), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(start = 2.dp, top = 14.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.titleMedium)
            }
            items(group, key = { it.id }, contentType = { "photo" }) { photo ->
                Tile(photo, thumbnails, online, seen = photo.id == state.lastViewed) { actions.open(photo) }
            }
        }
        if (state.next != null) item(key = "more", span = { GridItemSpan(maxLineSpan) }, contentType = "more") {
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                val error = state.pageError
                if (error != null) Notice(error, tone = Tone.ERROR) { NoticeButton("もう一度", actions::more) }
                else {
                    // Coming into view asks for the next page; each page that arrives while it is still in view asks again.
                    LaunchedEffect(state.photos.size, online) { if (online) actions.more() }
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
        }
    }
}

@Composable
private fun Tile(photo: Photo, thumbnails: Thumbnails, online: Boolean, seen: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lifted = hovered && online
    val scale by animateFloatAsState(if (lifted) 1.04f else 1f, label = "lift")
    val shape = RoundedCornerShape(14.dp)
    val ring = when {
        lifted -> MaterialTheme.colorScheme.primary
        seen -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        else -> null
    }
    Box(Modifier.aspectRatio(1f).zIndex(if (lifted) 1f else 0f).graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
        .clickable(interaction, LocalIndication.current, enabled = online, onClick = onClick)
        .semantics { contentDescription = "3D写真 ${dateTimeLabel(photo.takenAt)}" }) {
        Thumbnail(photo, thumbnails, online, Modifier.fillMaxSize())
        if (lifted) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.36f)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))) {
            Text(timeLabel(photo.takenAt), Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
        if (!online) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)))
        if (ring != null) Box(Modifier.matchParentSize().border(if (lifted) 3.dp else 2.dp, ring, shape))
    }
}

/** The phone's thumbnail of [photo], from memory when it is there; while it loads, a quiet cube. */
@Composable
fun Thumbnail(photo: Photo, thumbnails: Thumbnails, online: Boolean, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop,
    placeholder: Dp = 28.dp) {
    val bitmap by produceState(thumbnails.cached(photo.id), photo.id, online) {
        if (value == null) value = thumbnails.cached(photo.id) ?: if (online) thumbnails.load(photo.id) else null
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val image = remember(bitmap) { bitmap?.asImageBitmap() }
        if (image != null) Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        else Icon(Glyphs.Cube, contentDescription = null, modifier = Modifier.size(placeholder), tint = MaterialTheme.colorScheme.outline)
    }
}
