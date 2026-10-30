package com.okuyuki.quest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val darkPalette = darkColorScheme(
    primary = Color(0xFFC3DDE8), onPrimary = Color(0xFF10232B),
    primaryContainer = Color(0xFF29404B), onPrimaryContainer = Color(0xFFE1F2FA),
    background = Color(0xFF08090B), onBackground = Color(0xFFF2F3F5),
    surface = Color(0xFF111315), onSurface = Color(0xFFF2F3F5),
    surfaceVariant = Color(0xFF222629), onSurfaceVariant = Color(0xFFB9C2C7),
    outline = Color(0xFF69777D), outlineVariant = Color(0xFF383E41),
)

private val lightPalette = lightColorScheme(
    primary = Color(0xFF315966), onPrimary = Color.White,
    primaryContainer = Color(0xFFD4EAF1), onPrimaryContainer = Color(0xFF142F38),
    background = Color(0xFFF4F5F5), onBackground = Color(0xFF161C1E),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF161C1E),
    surfaceVariant = Color(0xFFE3EAEC), onSurfaceVariant = Color(0xFF46565B),
    outline = Color(0xFF738287), outlineVariant = Color(0xFFCED8DA),
)

private val appShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
internal fun OkuyukiTheme(dark: Boolean, content: @Composable () -> Unit) {
    val defaults = Typography()
    val type = defaults.copy(
        headlineMedium = defaults.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = defaults.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = defaults.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = defaults.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = defaults.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
    MaterialTheme(colorScheme = if (dark) darkPalette else lightPalette, shapes = appShapes, typography = type, content = content)
}

@Composable
internal fun GlassPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)),
    ) {
        Column(
            Modifier.background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .33f), Color.Transparent)))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}
