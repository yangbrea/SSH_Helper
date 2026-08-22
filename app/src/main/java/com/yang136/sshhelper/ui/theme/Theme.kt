package com.yang136.sshhelper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.settings.ThemeMode
import com.yang136.sshhelper.settings.ThemePreset

val Navy950 = Color(0xFF07131F)
val Navy900 = Color(0xFF0B1D2B)
val Navy800 = Color(0xFF102B3D)
val Cyan400 = Color(0xFF22D3EE)
val Teal400 = Color(0xFF2DD4BF)

@Immutable
data class TerminalPalette(
    val background: String,
    val foreground: String,
    val cursor: String,
    val cursorAccent: String,
    val selectionBackground: String,
    val black: String,
    val red: String,
    val green: String,
    val yellow: String,
    val blue: String,
    val magenta: String,
    val cyan: String,
    val white: String,
    val brightBlack: String,
    val brightRed: String,
    val brightGreen: String,
    val brightYellow: String,
    val brightBlue: String,
    val brightMagenta: String,
    val brightCyan: String,
    val brightWhite: String,
)

val LocalTerminalPalette = staticCompositionLocalOf { oceanTerminalPalette(true) }

private val OceanDark = darkColorScheme(
    primary = Cyan400,
    secondary = Teal400,
    background = Navy950,
    surface = Navy900,
    surfaceVariant = Navy800,
    onPrimary = Navy950,
    onBackground = Color(0xFFE6F7FA),
    onSurface = Color(0xFFE6F7FA),
    error = Color(0xFFFF7B7B),
)

private val OceanLight = lightColorScheme(
    primary = Color(0xFF007C91),
    secondary = Color(0xFF00796B),
    background = Color(0xFFF4FAFB),
    surface = Color.White,
    surfaceVariant = Color(0xFFDCEEF1),
)

private val EmeraldDark = darkColorScheme(
    primary = Color(0xFF35E07F),
    secondary = Color(0xFF73D99D),
    background = Color(0xFF06130E),
    surface = Color(0xFF0B2118),
    surfaceVariant = Color(0xFF123426),
    onPrimary = Color(0xFF00210D),
    onBackground = Color(0xFFD6FFE4),
    onSurface = Color(0xFFD6FFE4),
)

private val EmeraldLight = lightColorScheme(
    primary = Color(0xFF08783B),
    secondary = Color(0xFF286B45),
    background = Color(0xFFF3FBF5),
    surface = Color.White,
    surfaceVariant = Color(0xFFDCEFE2),
)

private val AmberDark = darkColorScheme(
    primary = Color(0xFFFFB84D),
    secondary = Color(0xFFE8C07A),
    background = Color(0xFF171007),
    surface = Color(0xFF261A0A),
    surfaceVariant = Color(0xFF3A2912),
    onPrimary = Color(0xFF3D2700),
    onBackground = Color(0xFFFFF1D6),
    onSurface = Color(0xFFFFF1D6),
)

private val AmberLight = lightColorScheme(
    primary = Color(0xFF8A5700),
    secondary = Color(0xFF735C2E),
    background = Color(0xFFFFF9EF),
    surface = Color.White,
    surfaceVariant = Color(0xFFF3E5C9),
)

private val VioletDark = darkColorScheme(
    primary = Color(0xFFB388FF),
    secondary = Color(0xFFD0A6FF),
    background = Color(0xFF100A1D),
    surface = Color(0xFF1B1230),
    surfaceVariant = Color(0xFF2D2048),
    onPrimary = Color(0xFF2B0B5A),
    onBackground = Color(0xFFEFE5FF),
    onSurface = Color(0xFFEFE5FF),
)

private val VioletLight = lightColorScheme(
    primary = Color(0xFF7040B8),
    secondary = Color(0xFF73558F),
    background = Color(0xFFFAF7FF),
    surface = Color.White,
    surfaceVariant = Color(0xFFECE2FA),
)

@Composable
fun SshHelperTheme(settings: AppSettings = AppSettings(), content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = colorScheme(settings.themePreset, dark)
    val terminal = terminalPalette(settings.themePreset, dark)
    androidx.compose.runtime.CompositionLocalProvider(LocalTerminalPalette provides terminal) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

private fun colorScheme(preset: ThemePreset, dark: Boolean): ColorScheme = when (preset) {
    ThemePreset.OCEAN -> if (dark) OceanDark else OceanLight
    ThemePreset.EMERALD -> if (dark) EmeraldDark else EmeraldLight
    ThemePreset.AMBER -> if (dark) AmberDark else AmberLight
    ThemePreset.VIOLET -> if (dark) VioletDark else VioletLight
}

private fun terminalPalette(preset: ThemePreset, dark: Boolean): TerminalPalette = when (preset) {
    ThemePreset.OCEAN -> oceanTerminalPalette(dark)
    ThemePreset.EMERALD -> terminalPaletteBase(dark, "#06130e", "#e2ffe9", "#35e07f", "#143d2a")
    ThemePreset.AMBER -> terminalPaletteBase(dark, "#171007", "#fff1d6", "#ffb84d", "#4a3213")
    ThemePreset.VIOLET -> terminalPaletteBase(dark, "#100a1d", "#efe5ff", "#b388ff", "#352354")
}

private fun oceanTerminalPalette(dark: Boolean): TerminalPalette =
    terminalPaletteBase(dark, "#07131f", "#dff8fb", "#22d3ee", "#155e75")

private fun terminalPaletteBase(
    dark: Boolean,
    darkBackground: String,
    darkForeground: String,
    accent: String,
    selection: String,
): TerminalPalette {
    val background = if (dark) darkBackground else "#f8fbfc"
    val foreground = if (dark) darkForeground else "#15242b"
    return TerminalPalette(
        background = background,
        foreground = foreground,
        cursor = accent,
        cursorAccent = background,
        selectionBackground = "${selection}99",
        black = if (dark) darkBackground else "#263238",
        red = "#ff6b6b",
        green = "#2dcf86",
        yellow = "#d7a51e",
        blue = "#4d9df8",
        magenta = "#b878e8",
        cyan = accent,
        white = if (dark) darkForeground else "#e8eef0",
        brightBlack = "#607d8b",
        brightRed = "#ff9292",
        brightGreen = "#61e6a9",
        brightYellow = "#f6cf65",
        brightBlue = "#87bcff",
        brightMagenta = "#d8a9fa",
        brightCyan = "#76e7ef",
        brightWhite = "#ffffff",
    )
}
