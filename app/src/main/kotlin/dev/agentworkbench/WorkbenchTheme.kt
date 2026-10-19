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
    // Near-black surfaces stepped by lightness only, with one red accent (Refrator identity).
    val Canvas = Color(0xFF000000)
    val Navigation = Color(0xFF050507)
    val Surface = Color(0xFF0C0D12)
    val SurfaceRaised = Color(0xFF111219)
    val SurfaceHigh = Color(0xFF191A23)
    val Border = Color(0xFF22232E)
    val BorderSoft = Color(0xFF15161E)

    // Main accent (violet); mascots and tabs bring the other colours.
    val Gold = Color(0xFF8B7CFF)
    val GoldSoft = Color(0xFF1E1A3A)

    // The user's own messages: neutral, the accent is reserved for a few small details.
    val UserBubble = Color(0xFF2A0C14)
    val UserBubbleBorder = Color(0xFF4A1624)

    // Identity accent, kept muted so it never competes with the primary action.
    val Coral = Color(0xFFC9A39A)
    val CoralSoft = Color(0xFF2E2523)

    // Error and destructive action (same family as the accent, slightly warmer).
    val Red = Color(0xFFFF5A5F)
    val RedSoft = Color(0xFF3A1416)

    // Success and completed work.
    val Green = Color(0xFF7FC8A0)
    val GreenSoft = Color(0xFF1B2E24)

    // Profiles and skills.
    val Purple = Color(0xFFA99BD8)
    val PurpleSoft = Color(0xFF26223A)

    // Neutral information and reasoning state.
    val Sky = Color(0xFF8AB4D8)
    val SkySoft = Color(0xFF1B2733)

    // Section colours for the bottom bar and the chat filters (Conversas, Arquivadas, Lixeira...).
    val Blue = Color(0xFF2F7BFF)
    val Amber = Color(0xFFF2A33A)
    val Pink = Color(0xFFFF4F86)
    val Teal = Color(0xFF2DD4BF)

    val Text = Color(0xFFF2F2F2)
    val TextMuted = Color(0xFF9A9A9A)
    val TextFaint = Color(0xFF5E5E5E)
}

private val WorkbenchScheme = darkColorScheme(
    primary = WorkbenchTokens.Gold,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = WorkbenchTokens.GoldSoft,
    onPrimaryContainer = Color(0xFFFFD6D7),
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
    inversePrimary = Color(0xFF8A3B22),
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
        fontSize = 16.sp,
        lineHeight = 25.sp,
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
