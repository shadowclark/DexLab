package com.dexlab.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * A service-manual palette: warm graphite, paper ink, brass and sage.
 * Deliberately no blue-purple gradient, no neon status soup, no floating
 * rounded cards. The object is a field instrument, not a landing page.
 */
val Amber = Color(0xFFD39A5A)
val AmberDeep = Color(0xFF925128)
val Teal = Color(0xFF9EAE99)
val Charcoal = Color(0xFF16130F)
val Panel = Color(0xFF1D1914)
val Panel2 = Color(0xFF29231A)
val TextMain = Color(0xFFEDE6D8)
val TextDim = Color(0xFFAA9E8B)
val OutlineDark = Color(0xFF453C2F)
val Red = Color(0xFFD47763)
val Green = Color(0xFF9CAD78)
val Blue = Color(0xFF8D9EAC)
val Purple = Color(0xFFB58A70)
val CodeFont = FontFamily.Monospace

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF24170A),
    primaryContainer = Color(0xFF4A3217),
    onPrimaryContainer = Color(0xFFFFE0B5),
    secondary = Teal,
    onSecondary = Color(0xFF171D14),
    secondaryContainer = Color(0xFF30392A),
    onSecondaryContainer = Color(0xFFE2EBD5),
    tertiary = Purple,
    onTertiary = Color(0xFF26160E),
    background = Charcoal,
    onBackground = TextMain,
    surface = Panel,
    onSurface = TextMain,
    surfaceVariant = Panel2,
    onSurfaceVariant = TextDim,
    outline = OutlineDark,
    outlineVariant = Color(0xFF332C23),
    error = Red,
    onError = Color(0xFF2A0D08),
    errorContainer = Color(0xFF4A1D16),
    onErrorContainer = Color(0xFFFFDAD3)
)

private val LightColors = lightColorScheme(
    primary = AmberDeep,
    onPrimary = Color(0xFFFFF6E8),
    primaryContainer = Color(0xFFF0D8B8),
    onPrimaryContainer = Color(0xFF2A1608),
    secondary = Color(0xFF5E6B4F),
    onSecondary = Color(0xFFFFF8E9),
    secondaryContainer = Color(0xFFDDE5CF),
    onSecondaryContainer = Color(0xFF1A2112),
    tertiary = Color(0xFF9A6246),
    onTertiary = Color(0xFFFFF5EC),
    background = Color(0xFFF1EBDD),
    onBackground = Color(0xFF211C15),
    surface = Color(0xFFFAF5EA),
    onSurface = Color(0xFF211C15),
    surfaceVariant = Color(0xFFE8E0D1),
    onSurfaceVariant = Color(0xFF6D6253),
    outline = Color(0xFFB6AA96),
    outlineVariant = Color(0xFFD6CCBC),
    error = Color(0xFF9D3F31)
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 29.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontFamily = CodeFont,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = CodeFont,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        letterSpacing = 0.7.sp
    )
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

@Composable
fun DexLabTheme(mode: String = "dark", content: @Composable () -> Unit) {
    val dark = when (mode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
