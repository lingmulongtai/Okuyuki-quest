package com.okuyuki.quest.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.okuyuki.protocol.Hello
import com.okuyuki.quest.BuildConfig
import com.okuyuki.quest.Link
import com.okuyuki.quest.QuestActions
import com.okuyuki.quest.QuestState
import com.okuyuki.quest.link.TemporaryPhotoCache
import com.okuyuki.quest.window.stereoSupported
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The phone in use, the photos held for this session, and what this app is. */
@Composable
fun SettingsScreen(state: QuestState, actions: QuestActions, back: () -> Unit) {
    var licenses by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(84.dp).padding(start = 16.dp, end = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back, modifier = Modifier.size(56.dp)) { Icon(Glyphs.ChevronLeft, contentDescription = "戻る", modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(8.dp))
            Text("設定", style = MaterialTheme.typography.headlineSmall)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val gap = Arrangement.spacedBy(20.dp)
            if (maxWidth >= 840.dp) Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, bottom = 32.dp),
                horizontalArrangement = gap) {
                Column(Modifier.weight(1f), verticalArrangement = gap) { PhonePanel(state, actions, back); StoragePanel(state, actions) }
                Column(Modifier.weight(1f), verticalArrangement = gap) { AboutPanel { licenses = true }; AlphaNotice() }
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = gap) {
                PhonePanel(state, actions, back); StoragePanel(state, actions); AboutPanel { licenses = true }; AlphaNotice()
            }
        }
    }
    if (licenses) LicensesDialog { licenses = false }
}

@Composable
private fun PhonePanel(state: QuestState, actions: QuestActions, back: () -> Unit) {
    Panel {
        PanelTitle("つないでいるスマホ")
        val hello = state.hello
        if (hello == null || state.link == Link.IDLE) Text("いまはつないでいません。", Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Connected(state, hello, actions, back)
    }
}

/** The phone in use, with leaving it for now and forgetting it. */
@Composable
private fun Connected(state: QuestState, hello: Hello, actions: QuestActions, back: () -> Unit) {
    ListRow(hello.deviceName, glyph = Glyphs.Phone, subtitle = listOfNotNull(state.endpoint?.label, "Okuyuki ${hello.appVersion}").joinToString(" · "))
    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { actions.disconnect(); back() }, modifier = Modifier.height(52.dp)) { Text("切断") }
        TextButton(onClick = { actions.forget(); back() }, modifier = Modifier.height(52.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("登録を解除") }
    }
    Text("切断しても、次に Okuyuki を開いたときはこのスマホに自動でつながります。登録を解除すると、次はスマホでもう一度許可します。",
        Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 10.dp), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StoragePanel(state: QuestState, actions: QuestActions) {
    Panel {
        PanelTitle("一時保存")
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 10.dp)) {
            val limit = TemporaryPhotoCache.LIMIT_BYTES
            LinearProgressIndicator(progress = { (state.cacheBytes.toFloat() / limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(10.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
            Spacer(Modifier.height(12.dp))
            Text("${sizeLabel(state.cacheBytes)} / ${sizeLabel(limit)}", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text("開いた写真は Quest に一時的に置かれ、Okuyuki を閉じると消えます。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = actions::clearCache, enabled = state.cacheBytes > 0, modifier = Modifier.height(52.dp)) {
                Icon(Glyphs.Trash, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("今すぐ消す")
            }
        }
    }
}

@Composable
private fun AboutPanel(openLicenses: () -> Unit) {
    Panel {
        PanelTitle("このアプリについて")
        FactRow("バージョン", BuildConfig.VERSION_NAME)
        FactRow("立体表示", if (stereoSupported) "使えます" else "使えません（平面で表示）")
        ListRow("オープンソースライセンス", onClick = openLicenses, trailing = {
            Icon(Glyphs.ChevronRight, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        })
    }
}

@Composable
private fun AlphaNotice() {
    Notice("アルファ版です。実機での確認はこれからです。左右の見え方に違和感があれば、使うのをやめてください。")
}

@Composable
private fun LicensesDialog(close: () -> Unit) {
    val context = LocalContext.current
    val paragraphs by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            listOf("libheif" to "1.23.6", "libde265" to "1.1.3").flatMap { (name, version) ->
                listOf("$name $version") + context.assets.open("licenses/$name-COPYING.txt").bufferedReader().use { it.readText() }
                    .split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(28.dp), shape = MaterialTheme.shapes.large, color = Extras.panel, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("オープンソースライセンス", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = close, modifier = Modifier.size(56.dp)) { Icon(Glyphs.Close, contentDescription = "閉じる", modifier = Modifier.size(26.dp)) }
                }
                LazyColumn(contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("HEIC 写真を読むために libheif と libde265 を改変せずに使っています（LGPL）。対応するソースは GitHub のリリースに添えています。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(paragraphs) { paragraph ->
                        if (paragraph.startsWith("libheif ") || paragraph.startsWith("libde265 ")) {
                            Text(paragraph, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                        } else Text(paragraph, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
