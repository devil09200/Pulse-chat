package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class PulseChatColors(
    val headerBackground: Color,
    val headerContent: Color,
    val chatBackground: Color,
    val chatPatternTint: Color,
    val incomingBubble: Color,
    val outgoingBubble: Color,
    val bubbleText: Color,
    val bubbleSubtext: Color,
    val divider: Color,
    val accentGreen: Color,
    val readTick: Color,
    val unreadBadgeBg: Color,
    val unreadBadgeText: Color,
    val navContainer: Color,
    val navIndicator: Color,
    val isDark: Boolean
)

private val LightPulseColors = PulseChatColors(
    headerBackground = HeaderGreen,
    headerContent = PureWhite,
    chatBackground = ChatBackgroundLight,
    chatPatternTint = Color(0x14075E54),
    incomingBubble = IncomingBubbleLight,
    outgoingBubble = OutgoingBubbleLight,
    bubbleText = MainTextLight,
    bubbleSubtext = SecondaryTextLight,
    divider = DividerLight,
    accentGreen = PrimaryGreen,
    readTick = ReadReceiptBlue,
    unreadBadgeBg = PrimaryGreen,
    unreadBadgeText = PureWhite,
    navContainer = PureWhite,
    navIndicator = LightGreen,
    isDark = false
)

private val DarkPulseColors = PulseChatColors(
    headerBackground = DarkHeader,
    headerContent = DarkMainText,
    chatBackground = DarkBackground,
    chatPatternTint = Color(0x148696A0),
    incomingBubble = DarkIncomingBubble,
    outgoingBubble = DarkOutgoingBubble,
    bubbleText = DarkMainText,
    bubbleSubtext = DarkSecondaryText,
    divider = DarkDivider,
    accentGreen = PrimaryGreen,
    readTick = ReadReceiptBlue,
    unreadBadgeBg = PrimaryGreen,
    unreadBadgeText = DarkBackground,
    navContainer = DarkSurface,
    navIndicator = DarkOutgoingBubble,
    isDark = true
)

val LocalPulseChatColors = staticCompositionLocalOf { LightPulseColors }

private val LightColorScheme = lightColorScheme(
    primary = HeaderGreen,
    onPrimary = PureWhite,
    primaryContainer = LightGreen,
    onPrimaryContainer = DarkGreen,
    secondary = PrimaryGreen,
    onSecondary = PureWhite,
    secondaryContainer = Color(0xFFE7FCE8),
    onSecondaryContainer = DarkGreen,
    tertiary = DarkGreen,
    onTertiary = PureWhite,
    background = PureWhite,
    onBackground = MainTextLight,
    surface = PureWhite,
    onSurface = MainTextLight,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = SecondaryTextLight,
    outline = DividerLight,
    outlineVariant = DividerLight,
    error = MissedCallRed,
    onError = PureWhite
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryGreen,
    onPrimary = DarkBackground,
    primaryContainer = DarkOutgoingBubble,
    onPrimaryContainer = DarkMainText,
    secondary = StatusRingTeal,
    onSecondary = DarkBackground,
    secondaryContainer = DarkHeader,
    onSecondaryContainer = DarkMainText,
    tertiary = PrimaryGreen,
    onTertiary = DarkBackground,
    background = DarkBackground,
    onBackground = DarkMainText,
    surface = DarkSurface,
    onSurface = DarkMainText,
    surfaceVariant = DarkHeader,
    onSurfaceVariant = DarkSecondaryText,
    outline = DarkDivider,
    outlineVariant = DarkDivider,
    error = MissedCallRed,
    onError = PureWhite
)

@Composable
fun PulseChatTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDarkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    val colorScheme = if (useDarkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (useDarkTheme) DarkPulseColors else LightPulseColors

    CompositionLocalProvider(LocalPulseChatColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
