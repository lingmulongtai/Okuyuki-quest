package com.okuyuki.quest.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A card on the page; the building block of the connect and settings screens. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = Extras.panel, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(vertical = 12.dp), content = content)
    }
}

/** A heading inside a panel. */
@Composable
fun PanelTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        trailing?.invoke()
    }
}

/** A glyph in a soft round backdrop, for list rows and empty states. */
@Composable
fun GlyphBubble(glyph: ImageVector, modifier: Modifier = Modifier, size: Dp = 44.dp,
    container: Color = MaterialTheme.colorScheme.secondaryContainer, tint: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Box(modifier.size(size).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Icon(glyph, contentDescription = null, modifier = Modifier.size(size * 0.5f), tint = tint)
    }
}

enum class Tone { INFO, ERROR }

/**
 * A short message for states that need attention but not a dialog, with its buttons at the end. [busy] shows a
 * spinner in place of the glyph.
 */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.INFO, busy: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val (container, content, accent) = when (tone) {
        Tone.INFO -> Triple(colors.secondaryContainer, colors.onSecondaryContainer, colors.primary)
        Tone.ERROR -> Triple(colors.errorContainer, colors.onErrorContainer, colors.error)
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = container, contentColor = content) {
        Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = accent, strokeWidth = 2.5.dp)
            else Icon(Glyphs.Info, contentDescription = null, modifier = Modifier.size(22.dp), tint = accent)
            Spacer(Modifier.width(14.dp))
            Text(text, Modifier.weight(1f).padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
            if (actions != null) {
                Spacer(Modifier.width(8.dp))
                actions()
            }
        }
    }
}

/** A text button in a notice, in the notice's own color. */
@Composable
fun NoticeButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) { Text(text) }
}

/** A centered explanation for an empty screen, with the one thing to do about it. */
@Composable
fun EmptyState(glyph: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Column(modifier.widthIn(max = 560.dp).padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GlyphBubble(glyph, size = 84.dp, container = MaterialTheme.colorScheme.primaryContainer, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(28.dp))
            Button(onClick = onAction, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)) { Text(action) }
        }
    }
}

/**
 * A row in a panel that lights up under the pointer: a glyph, a title with an optional line under it, and something at
 * the end such as a button.
 */
@Composable
fun ListRow(title: String, modifier: Modifier = Modifier, glyph: ImageVector? = null, subtitle: String? = null,
    onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(MaterialTheme.shapes.medium)
        .background(if (hovered && onClick != null) Extras.panelHover else Color.Transparent)
        .then(if (onClick != null) Modifier.clickable(interaction, LocalIndication.current, onClick = onClick) else Modifier)
        .heightIn(min = 68.dp).padding(start = 16.dp, end = if (trailing != null) 10.dp else 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) { GlyphBubble(glyph); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}

/** A label and its value on one line, for facts such as the version. */
@Composable
fun FactRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
}

/** The four digits both devices show while pairing, each in its own box so they read at a glance. */
@Composable
fun PairingDigits(code: String, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "接続番号 ${code.toList().joinToString(" ")}" },
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        code.forEach { digit ->
            Box(Modifier.size(width = 76.dp, height = 96.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center) {
                Text("$digit", fontSize = 50.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** A small rounded label, such as 3D on the viewer. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, glyph: ImageVector? = null, container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Row(modifier.background(container, CircleShape).padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) { Icon(glyph, contentDescription = null, modifier = Modifier.size(18.dp), tint = content); Spacer(Modifier.width(7.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}
