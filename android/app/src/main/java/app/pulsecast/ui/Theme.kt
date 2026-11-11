package app.pulsecast.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colours for one theme. The feed, cards and sponsored posts all read from this. */
data class Palette(
    val isDark: Boolean,
    val bg: Color,
    val card: Color,
    val raised: Color,
    val text1: Color,
    val body: Color,
    val text2: Color,
    val accent: Color,
    val onAccent: Color,
    val line: Color,
)

val DarkPalette = Palette(
    isDark = true,
    bg = Color(0xFF0F1113),
    card = Color(0xFF181B1E),
    raised = Color(0xFF1F2327),
    text1 = Color(0xFFF2F2EE),
    body = Color(0xFFC9CBC6),
    text2 = Color(0xFF9A9C98),
    accent = Color(0xFFB7F36B),
    onAccent = Color(0xFF0F1113),
    line = Color(0xFF2A2E33),
)

val LightPalette = Palette(
    isDark = false,
    bg = Color(0xFFF4F4F0),
    card = Color(0xFFFFFFFF),
    raised = Color(0xFFEDEEE8),
    text1 = Color(0xFF15171A),
    body = Color(0xFF3A3D41),
    text2 = Color(0xFF6B6E72),
    accent = Color(0xFF3F7300), // darker green so links stay readable on white
    onAccent = Color(0xFFFFFFFF),
    line = Color(0xFFDADBD5),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

val Ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bg
val CardBg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.card
val Raised: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.raised
val Text1: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text1
val Body: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.body
val Text2: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text2
val Accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onAccent
val Line: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.line

enum class ThemeMode(val label: String) { SYSTEM("Match phone"), LIGHT("Light"), DARK("Dark") }
