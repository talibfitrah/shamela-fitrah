package org.murabbie.shamela.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

private val Brown = Color(0xFF5C3D1E)
private val BrownDark = Color(0xFF3E2A14)
private val Gold = Color(0xFFC9A227)
private val Cream = Color(0xFFF8F3E6)
private val Paper = Color(0xFFFFFCF5)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Brown, onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDCC6), onPrimaryContainer = BrownDark,
    secondary = Gold, onSecondary = Color.Black,
    secondaryContainer = Color(0xFFF4E7BF), onSecondaryContainer = Color(0xFF4A3B05),
    tertiary = Color(0xFF1F6F5C), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFE9DF), onTertiaryContainer = Color(0xFF0E3A2F),
    background = Cream, onBackground = Color(0xFF1D1B16),
    surface = Paper, onSurface = Color(0xFF1D1B16),
    surfaceVariant = Color(0xFFEFE6D3), onSurfaceVariant = Color(0xFF4A473E),
    outline = Color(0xFF9B9587), error = Color(0xFFB3261E),
)

private val SepiaColors: ColorScheme = LightColors.copy(
    background = Color(0xFFF3E7CF), surface = Color(0xFFF7EDD9), surfaceVariant = Color(0xFFE9DCC0), onBackground = Color(0xFF3A2E1E), onSurface = Color(0xFF3A2E1E),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFE0BE8A), onPrimary = BrownDark,
    primaryContainer = Color(0xFF5A4630), onPrimaryContainer = Color(0xFFEBDCC6),
    secondary = Color(0xFFE8CB6B), onSecondary = Color(0xFF3E3000),
    secondaryContainer = Color(0xFF5A4A0F), onSecondaryContainer = Color(0xFFF4E7BF),
    tertiary = Color(0xFF8ED1BB), onTertiary = Color(0xFF0E3A2F),
    tertiaryContainer = Color(0xFF1F5A4A), onTertiaryContainer = Color(0xFFCFE9DF),
    background = Color(0xFF15161A), onBackground = Color(0xFFE6E2DA),
    surface = Color(0xFF1C1E23), onSurface = Color(0xFFE6E2DA),
    surfaceVariant = Color(0xFF2B2E35), onSurfaceVariant = Color(0xFFC9C5BB),
    outline = Color(0xFF8B877D),
)

val AppTypography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 18.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 26.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 22.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 22.sp, lineHeight = 32.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 18.sp, lineHeight = 28.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 13.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 24.sp, lineHeight = 34.sp),
)

@Composable
fun AppTheme(themeMode: Int, fontScale: Float, content: @Composable () -> Unit) {
    val colors = when (themeMode) {
        1 -> LightColors
        2 -> DarkColors
        3 -> SepiaColors
        else -> if (isSystemInDarkTheme()) DarkColors else LightColors
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalDensity provides Density(density.density, density.fontScale * fontScale),
    ) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
    }
}
