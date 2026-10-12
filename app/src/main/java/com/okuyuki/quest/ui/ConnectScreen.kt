package com.okuyuki.quest.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.okuyuki.quest.Nearby
import com.okuyuki.quest.QuestActions
import com.okuyuki.quest.QuestState
import com.okuyuki.quest.link.Endpoint

/** The brand and the settings button, above the connect and library screens. */
@Composable
fun BrandBar(openSettings: () -> Unit, modifier: Modifier = Modifier, title: @Composable RowScope.() -> Unit = {
    Text("Okuyuki", style = MaterialTheme.typography.titleLarge)
}, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().height(84.dp).padding(start = 32.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        BrandMark(32.dp)
        Spacer(Modifier.width(14.dp))
        title()
        Spacer(Modifier.weight(1f))
        actions()
        IconButton(onClick = openSettings, modifier = Modifier.size(56.dp)) { Icon(Glyphs.Tune, contentDescription = "設定", modifier = Modifier.size(26.dp)) }
    }
}

/** How to get the phone ready, the phones on the network, and typing an address when none shows up. */
@Composable
fun ConnectScreen(state: QuestState, nearby: List<Nearby>, discoveryError: String?, actions: QuestActions, openSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BrandBar(openSettings)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 760.dp) Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 32.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Intro(Modifier.weight(0.44f).fillMaxHeight().verticalScroll(rememberScrollState()), side = "右")
                Column(Modifier.weight(0.56f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Phones(state, nearby, discoveryError, actions)
                    AddressEntry(discoveryError != null, actions)
                }
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Intro(Modifier, side = "下")
                Phones(state, nearby, discoveryError, actions)
                AddressEntry(discoveryError != null, actions)
            }
        }
    }
}

@Composable
private fun Intro(modifier: Modifier, side: String) {
    Column(modifier) {
        Text("スマホとつなぐ", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("スマホの 3D 写真を、Quest で立体的に見られます。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Step(1, "スマホで Okuyuki を開く")
        Step(2, "「つなぐ」タブで「接続を待つ」をオンにする")
        Step(3, "${side}に出てくるスマホを選ぶ")
        Spacer(Modifier.height(24.dp))
        Footnote(Glyphs.Wifi, "Quest とスマホを、同じ Wi-Fi かスマホのテザリングにつないでください。")
        Footnote(Glyphs.Lock, "写真はクラウドを通らず、Quest にも残りません。")
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Footnote(glyph: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Icon(glyph, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Phones(state: QuestState, nearby: List<Nearby>, discoveryError: String?, actions: QuestActions) {
    Panel {
        PanelTitle("近くのスマホ") {
            if (discoveryError == null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text("探しています", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val inset = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        if (state.resuming) Notice(named(state.phone ?: "前回のスマホ", "につないでいます…"), inset, busy = true) { NoticeButton("やめる", actions::cancel) }
        state.notice?.let { Notice(it, inset, tone = Tone.ERROR) }
        discoveryError?.let { Notice(it, inset, tone = Tone.ERROR) }
        if (nearby.isEmpty() && discoveryError == null) Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp)) {
            Text("まだ見つかりません", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text("スマホで「接続を待つ」をオンにすると、ここに出てきます。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else nearby.forEach { phone ->
            val connect = { actions.connect(phone.endpoint, phone.name) }
            ListRow(phone.name, glyph = Glyphs.Phone, onClick = connect, subtitle = when {
                phone.last -> "前回つないだスマホ"
                phone.paired -> "許可済み"
                else -> phone.endpoint.label
            }, trailing = { FilledTonalButton(onClick = connect, modifier = Modifier.height(48.dp)) { Text("つなぐ") } })
        }
    }
}

@Composable
private fun AddressEntry(open: Boolean, actions: QuestActions) {
    var expanded by rememberSaveable { mutableStateOf(open) }
    LaunchedEffect(open) { if (open) expanded = true }
    var address by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    fun submit() {
        val endpoint = Endpoint.parse(address)
        if (endpoint == null) invalid = true else actions.connect(endpoint, null)
    }
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Panel {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(MaterialTheme.shapes.medium)
            .background(if (hovered) Extras.panelHover else Color.Transparent)
            .clickable(interaction, LocalIndication.current) { expanded = !expanded }
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("アドレスを入力してつなぐ", style = MaterialTheme.typography.titleMedium)
                Text("自動で見つからないときに", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Glyphs.ChevronDown, contentDescription = if (expanded) "閉じる" else "開く", modifier = Modifier.size(26.dp).rotate(turn))
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp)) {
                Text("スマホの「つなぐ」タブの下にある「自動で見つからないとき」のアドレスを入力してください。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Top) {
                    OutlinedTextField(value = address, onValueChange = { address = it; invalid = false }, modifier = Modifier.weight(1f),
                        singleLine = true, textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        placeholder = { Text("192.168.1.23:38765", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline) },
                        isError = invalid,
                        supportingText = if (invalid) { { Text("スマホに出ているアドレスを、そのまま入力してください。") } } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submit() }))
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { submit() }, enabled = address.isNotBlank(), modifier = Modifier.padding(top = 4.dp).height(56.dp)) { Text("つなぐ") }
                }
            }
        }
    }
}

/** Content centered in the window that still scrolls when the window is made small. */
@Composable
private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content)
    }
}

/** Waiting for the phone to answer, before it asks for permission. */
@Composable
fun ConnectingScreen(state: QuestState, actions: QuestActions) {
    Centered {
        CircularProgressIndicator(Modifier.size(56.dp), strokeWidth = 4.dp)
        Spacer(Modifier.height(28.dp))
        Text("スマホとつないでいます…", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(state.phone ?: state.endpoint?.label.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = actions::cancel, modifier = Modifier.height(52.dp).widthIn(min = 160.dp)) { Text("やめる") }
    }
}

/** The four digits the phone shows too, while the person allows this Quest on the phone. */
@Composable
fun PairingScreen(state: QuestState, actions: QuestActions) {
    Centered {
        Panel(Modifier.widthIn(max = 620.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GlyphBubble(Glyphs.Phone, size = 72.dp, container = MaterialTheme.colorScheme.primaryContainer, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(20.dp))
                Text("スマホで許可してください", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(named(state.phone ?: "スマホ", "に同じ番号が出ていれば、スマホで「許可する」を押してください。"), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                PairingDigits(state.pairingCode.orEmpty())
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text("スマホの操作を待っています", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = actions::cancel, modifier = Modifier.height(52.dp).widthIn(min = 160.dp)) { Text("やめる") }
            }
        }
    }
}
