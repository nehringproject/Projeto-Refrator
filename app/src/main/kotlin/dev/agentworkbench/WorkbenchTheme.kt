package dev.agentworkbench

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Semantic colors used throughout the Refrator interface. */
internal object WorkbenchTokens {
    val Canvas = Color(0xFF0A0C11)
    val Navigation = Color(0xFF0D1117)
    val Surface = Color(0xFF11161D)
    val SurfaceRaised = Color(0xFF151B23)
    val SurfaceHigh = Color(0xFF1B232D)
    val Border = Color(0xFF2A3542)
    val BorderSoft = Color(0xFF1F2833)

    // Primary action and selection.
    val Gold = Color(0xFFE8B85C)
    val GoldSoft = Color(0xFF4A3B22)

    // Identity and brand accent.
    val Coral = Color(0xFFE8899A)
    val CoralSoft = Color(0xFF432730)

    // Error and destructive action.
    val Red = Color(0xFFD5495F)
    val RedSoft = Color(0xFF44212A)

    // Success and completed work.
    val Green = Color(0xFF7FD1A8)
    val GreenSoft = Color(0xFF1D3A2C)

    // Profiles and skills.
    val Purple = Color(0xFFB39EE0)
    val PurpleSoft = Color(0xFF31294A)

    // Neutral information and reasoning state.
    val Sky = Color(0xFF7FB8E0)
    val SkySoft = Color(0xFF1E3346)

    val Text = Color(0xFFF1F3F5)
    val TextMuted = Color(0xFF8B96A3)
    val TextFaint = Color(0xFF5F6975)
}

private val WorkbenchScheme = darkColorScheme(
    primary = WorkbenchTokens.Gold,
    onPrimary = Color(0xFF171005),
    primaryContainer = WorkbenchTokens.GoldSoft,
    onPrimaryContainer = Color(0xFFFFE4A3),
    secondary = WorkbenchTokens.Green,
    onSecondary = Color(0xFF0A2118),
    secondaryContainer = WorkbenchTokens.GreenSoft,
    onSecondaryContainer = Color(0xFFC4EFD9),
    tertiary = WorkbenchTokens.Purple,
    onTertiary = Color(0xFF201636),
    tertiaryContainer = WorkbenchTokens.PurpleSoft,
    onTertiaryContainer = Color(0xFFE6DBFA),
    error = WorkbenchTokens.Red,
    onError = Color(0xFF2C0C13),
    errorContainer = WorkbenchTokens.RedSoft,
    onErrorContainer = Color(0xFFF7D2D9),
    background = WorkbenchTokens.Canvas,
    onBackground = WorkbenchTokens.Text,
    surface = WorkbenchTokens.Surface,
    onSurface = WorkbenchTokens.Text,
    surfaceVariant = WorkbenchTokens.SurfaceRaised,
    onSurfaceVariant = WorkbenchTokens.TextMuted,
    surfaceContainerLowest = WorkbenchTokens.Canvas,
    surfaceContainerLow = WorkbenchTokens.Navigation,
    surfaceContainer = WorkbenchTokens.Surface,
    surfaceContainerHigh = WorkbenchTokens.SurfaceRaised,
    surfaceContainerHighest = WorkbenchTokens.SurfaceHigh,
    outline = WorkbenchTokens.Border,
    outlineVariant = WorkbenchTokens.BorderSoft,
    inverseSurface = WorkbenchTokens.Text,
    inverseOnSurface = WorkbenchTokens.Canvas,
    inversePrimary = Color(0xFF765600),
    scrim = Color.Black,
)

private val WorkbenchTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp,
    ),
)

private val WorkbenchShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(9.dp),
    medium = RoundedCornerShape(13.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
internal fun WorkbenchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WorkbenchScheme,
        typography = WorkbenchTypography,
        shapes = WorkbenchShapes,
        content = content,
    )
}
