package com.okuyuki.quest.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand colors, shared with the phone app: ink, a blue square and an ember square overlapping. */
object Brand {
    val Ink = Color(0xFF141821)
    val Blue = Color(0xFF2747E6)
    val Ember = Color(0xFFE8603F)
}

/** Colors Material has no slot for: [panel] is a card on the page and [success] marks a live connection. */
object Extras {
    val panel = Color(0xFF161922)
    val panelHover = Color(0xFF1E2230)
    val success = Color(0xFF6FD6A6)
    val warning = Color(0xFFF2C46B)
}

// The phone's dark scheme. A headset always gets the dark one: large bright panels glare in a dim room.
private val Colors = darkColorScheme(
    primary = Color(0xFFA9B8FF), onPrimary = Color(0xFF0D1C73),
    primaryContainer = Color(0xFF2A3DB3), onPrimaryContainer = Color(0xFFE0E5FF),
    secondary = Color(0xFFBEC5DB), onSecondary = Color(0xFF283044),
    secondaryContainer = Color(0xFF2C3346), onSecondaryContainer = Color(0xFFDCE1F4),
    tertiary = Color(0xFFFFA68C), onTertiary = Color(0xFF561800),
    tertiaryContainer = Color(0xFF77301A), onTertiaryContainer = Color(0xFFFFDBD1),
    background = Color(0xFF0B0D12), onBackground = Color(0xFFE8EAF1),
    surface = Color(0xFF0B0D12), onSurface = Color(0xFFE8EAF1),
    surfaceVariant = Color(0xFF2A2F3D), onSurfaceVariant = Color(0xFFA9AEBF),
    surfaceContainerLowest = Color(0xFF07080C), surfaceContainerLow = Color(0xFF12151C),
    surfaceContainer = Color(0xFF161A22), surfaceContainerHigh = Color(0xFF1D212B),
    surfaceContainerHighest = Color(0xFF252A36),
    outline = Color(0xFF737A8C), outlineVariant = Color(0xFF2D3240),
    // A muted red: a problem should stand out from the page without glaring in the headset.
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF3A1F24), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE8EAF1), inverseOnSurface = Color(0xFF1A1D26), inversePrimary = Brand.Blue,
)

// A panel is read from about a metre away, so every style is a step larger than on the phone.
// The text is Japanese whatever the headset's language: Japanese glyph forms, and lines that break between phrases
// rather than inside a word (Android 13 and later).
private val base = Typography()
private val japanese = LocaleList("ja-JP")
private val paragraph = LineBreak(LineBreak.Strategy.HighQuality, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase)
private val heading = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase)
private fun TextStyle.quest(weight: FontWeight?, size: Int, height: Int, lines: LineBreak = paragraph) =
    copy(fontWeight = weight ?: fontWeight, fontSize = size.sp, lineHeight = height.sp, localeList = japanese, lineBreak = lines)
private val QuestType = Typography(
    headlineMedium = base.headlineMedium.quest(FontWeight.Bold, 32, 40, heading),
    headlineSmall = base.headlineSmall.quest(FontWeight.Bold, 28, 36, heading),
    titleLarge = base.titleLarge.quest(FontWeight.Bold, 22, 30, heading),
    titleMedium = base.titleMedium.quest(FontWeight.SemiBold, 18, 26),
    titleSmall = base.titleSmall.quest(FontWeight.SemiBold, 16, 22),
    bodyLarge = base.bodyLarge.quest(null, 18, 28),
    bodyMedium = base.bodyMedium.quest(null, 16, 25),
    bodySmall = base.bodySmall.quest(null, 14, 21),
    labelLarge = base.labelLarge.quest(FontWeight.SemiBold, 16, 22),
    labelMedium = base.labelMedium.quest(FontWeight.SemiBold, 14, 20),
    labelSmall = base.labelSmall.quest(FontWeight.SemiBold, 13, 18),
)

private val QuestShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp), extraLarge = RoundedCornerShape(32.dp),
)

/** Material with the app's colors, type and shapes. Text outside any surface, as in dialogs, gets the page's text color. */
@Composable
fun OkuyukiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = QuestType, shapes = QuestShapes) {
        CompositionLocalProvider(LocalContentColor provides Colors.onBackground, content = content)
    }
}

/** The app's mark: two squares overlapping in depth, the shared part in ink. The launcher icon draws the same shapes. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    val ink = Colors.background
    Canvas(modifier.size(size)) {
        val full = this.size.minDimension
        val side = full * 0.64f
        val r = side * 0.16f
        val start = full - side
        drawRoundRect(Brand.Blue, Offset.Zero, Size(side, side), CornerRadius(r))
        drawRoundRect(Brand.Ember, Offset(start, start), Size(side, side), CornerRadius(r))
        // The overlap keeps the front square's corner at its top left and the back square's at its bottom right.
        drawPath(Path().apply {
            addRoundRect(RoundRect(start, start, side, side, topLeftCornerRadius = CornerRadius(r), bottomRightCornerRadius = CornerRadius(r)))
        }, ink)
    }
}
