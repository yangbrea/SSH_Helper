package com.yang136.sshhelper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yang136.sshhelper.settings.AppSettings
import com.yang136.sshhelper.settings.ThemeMode
import com.yang136.sshhelper.settings.ThemePreset
import com.yang136.sshhelper.settings.ThemeSource
import com.yang136.sshhelper.theme.ImageThemePalette
import com.yang136.sshhelper.theme.imageThemeTokens
import com.yang136.sshhelper.ui.design.LocalSshMotion
import com.yang136.sshhelper.ui.design.LocalSshSpacing
import com.yang136.sshhelper.ui.design.LocalSshStatusColors
import com.yang136.sshhelper.ui.design.SshMotion
import com.yang136.sshhelper.ui.design.SshSpacing
import com.yang136.sshhelper.ui.design.SshStatusColors

val Navy950 = Color(0xFF07131F)
val Navy900 = Color(0xFF0B1D2B)
val Navy800 = Color(0xFF102B3D)
val Cyan400 = Color(0xFF22D3EE)
val Teal400 = Color(0xFF2DD4BF)

/** 终端画布背景恒为纯黑，不随应用浅色/深色模式或预设变化。 */
internal const val TERMINAL_BLACK = "#000000"

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
    surfaceContainer = Color(0xFF102533),
    surfaceContainerHigh = Color(0xFF173241),
    outline = Color(0xFF73909A),
)

private val OceanLight = lightColorScheme(
    primary = Color(0xFF007C91),
    secondary = Color(0xFF00796B),
    background = Color(0xFFF4FAFB),
    surface = Color.White,
    surfaceVariant = Color(0xFFDCEEF1),
    surfaceContainer = Color(0xFFEAF3F5),
    surfaceContainerHigh = Color(0xFFDDEBED),
    outline = Color(0xFF657A80),
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
    primary = Color(0xFFD9B45F),
    secondary = Color(0xFFA98D50),
    background = Color(0xFF0B0B0D),
    surface = Color(0xFF15151A),
    surfaceVariant = Color(0xFF22222B),
    onPrimary = Color(0xFF241A00),
    onBackground = Color(0xFFF2ECDD),
    onSurface = Color(0xFFF2ECDD),
    onSurfaceVariant = Color(0xFFB3AC9C),
    surfaceContainer = Color(0xFF15151A),
    surfaceContainerHigh = Color(0xFF202027),
    outline = Color(0xFF7C7565),
)

private val AmberLight = lightColorScheme(
    primary = Color(0xFF765B12),
    secondary = Color(0xFF6E5F3C),
    background = Color(0xFFFAF8F2),
    surface = Color(0xFFFFFFFD),
    surfaceVariant = Color(0xFFECE7DA),
    onPrimary = Color.White,
    onBackground = Color(0xFF29251D),
    onSurface = Color(0xFF29251D),
    onSurfaceVariant = Color(0xFF625D51),
    surfaceContainer = Color(0xFFF3F0E7),
    surfaceContainerHigh = Color(0xFFECE7DA),
    outline = Color(0xFF817A6B),
)

private val VioletDark = darkColorScheme(
    primary = Color(0xFFB8C4D6),
    secondary = Color(0xFF8FA7B8),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161C24),
    surfaceVariant = Color(0xFF232C37),
    onPrimary = Color(0xFF202832),
    onBackground = Color(0xFFE9EEF5),
    onSurface = Color(0xFFE9EEF5),
    onSurfaceVariant = Color(0xFFB7C1CE),
    surfaceContainer = Color(0xFF161C24),
    surfaceContainerHigh = Color(0xFF202833),
    outline = Color(0xFF718090),
)

private val VioletLight = lightColorScheme(
    primary = Color(0xFF465A70),
    secondary = Color(0xFF526B79),
    background = Color(0xFFF5F7FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE1E7ED),
    onPrimary = Color.White,
    onBackground = Color(0xFF1A2028),
    onSurface = Color(0xFF1A2028),
    onSurfaceVariant = Color(0xFF515C68),
    surfaceContainer = Color(0xFFEDF1F5),
    surfaceContainerHigh = Color(0xFFE1E7ED),
    outline = Color(0xFF6E7B89),
)

private val SshTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

private val SshShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun SshHelperTheme(
    settings: AppSettings = AppSettings(),
    imageThemePalette: ImageThemePalette? = null,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val imageActive = settings.themeSource == ThemeSource.IMAGE && imageThemePalette != null
    val imageTokens = if (imageActive) imageThemeTokens(imageThemePalette, settings.imageThemeVariant) else null
    val dark = imageTokens?.dark ?: resolveDarkMode(settings.themeMode, systemDark)
    val colors = imageTokens?.let(::imageColorScheme) ?: colorScheme(settings.themePreset, dark)
    // 终端始终使用所选预设的深色调色板且背景恒为纯黑：图片主题与浅色模式只接管 Compose
    // 界面，绝不改变终端画布的背景。
    val terminal = terminalPaletteForTerminal(settings)
    val status = SshStatusColors(
        connected = if (dark) Color(0xFF62D69A) else Color(0xFF16734B),
        connecting = if (dark) Color(0xFF70C7FF) else Color(0xFF00658A),
        waiting = if (dark) Color(0xFFE8C96B) else Color(0xFF756000),
        warning = if (dark) Color(0xFFFFB86A) else Color(0xFF9B5200),
        error = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A),
        offline = if (dark) Color(0xFFAAB3BC) else Color(0xFF5F6871),
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalTerminalPalette provides terminal,
        LocalSshSpacing provides SshSpacing(),
        LocalSshMotion provides SshMotion(),
        LocalSshStatusColors provides status,
    ) {
        MaterialTheme(colorScheme = colors, typography = SshTypography, shapes = SshShapes, content = content)
    }
}

internal fun imageColorScheme(tokens: com.yang136.sshhelper.theme.ImageThemeTokens): ColorScheme {
    val primary = Color(tokens.primary)
    val secondary = Color(tokens.secondary)
    val background = Color(tokens.background)
    val surface = Color(tokens.surface)
    val surfaceVariant = Color(tokens.surfaceVariant)
    val onBackground = Color(tokens.onBackground)
    val containerAmount = if (tokens.dark) .28f else .14f
    return (if (tokens.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = primary,
        onPrimary = Color(tokens.onPrimary),
        primaryContainer = lerp(surface, primary, containerAmount),
        onPrimaryContainer = onBackground,
        secondary = secondary,
        onSecondary = Color(tokens.onSecondary),
        secondaryContainer = lerp(surface, secondary, containerAmount),
        onSecondaryContainer = onBackground,
        tertiary = lerp(primary, secondary, .5f),
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onBackground,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = Color(tokens.onSurfaceVariant),
        outline = Color(tokens.outline),
        outlineVariant = lerp(surfaceVariant, Color(tokens.outline), .52f),
        surfaceContainerLowest = if (tokens.dark) lerp(background, Color.Black, .16f) else Color.White,
        surfaceContainerLow = lerp(background, surface, .55f),
        surfaceContainer = surface,
        surfaceContainerHigh = lerp(surface, surfaceVariant, .55f),
        surfaceContainerHighest = surfaceVariant,
    )
}

internal fun resolveDarkMode(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * 终端调色板：无论应用处于浅色还是深色模式，始终取所选预设的深色调色板，
 * 并把背景与光标底色强制为纯黑（终端背景保持黑色，文字保持浅色可读）。
 */
internal fun terminalPaletteForTerminal(settings: AppSettings): TerminalPalette =
    terminalPalette(settings.themePreset, dark = true)
        .let { it.copy(background = TERMINAL_BLACK, cursorAccent = TERMINAL_BLACK) }

internal fun colorScheme(preset: ThemePreset, dark: Boolean): ColorScheme = when (preset) {
    ThemePreset.OCEAN -> if (dark) OceanDark else OceanLight
    ThemePreset.EMERALD -> if (dark) EmeraldDark else EmeraldLight
    ThemePreset.AMBER -> if (dark) AmberDark else AmberLight
    ThemePreset.VIOLET -> if (dark) VioletDark else VioletLight
}

internal fun terminalPalette(preset: ThemePreset, dark: Boolean): TerminalPalette = when (preset) {
    ThemePreset.OCEAN -> oceanTerminalPalette(dark)
    ThemePreset.EMERALD -> terminalPaletteBase(dark, "#06130e", "#e2ffe9", "#35e07f", "#143d2a")
    ThemePreset.AMBER -> terminalPaletteBase(
        dark, "#0b0b0d", "#f2ecdd", "#d9b45f", "#4a4025",
        "#faf8f2", "#29251d", "#765b12", "#ddd2b5",
    )
    ThemePreset.VIOLET -> terminalPaletteBase(
        dark, "#0d1117", "#e9eef5", "#b8c4d6", "#344354",
        "#f5f7fa", "#1a2028", "#465a70", "#ced8e3",
    )
}

private fun oceanTerminalPalette(dark: Boolean): TerminalPalette =
    terminalPaletteBase(dark, "#07131f", "#dff8fb", "#22d3ee", "#155e75")

private fun terminalPaletteBase(
    dark: Boolean,
    darkBackground: String,
    darkForeground: String,
    accent: String,
    selection: String,
    lightBackground: String = "#f8fbfc",
    lightForeground: String = "#15242b",
    lightAccent: String = accent,
    lightSelection: String = selection,
): TerminalPalette {
    val background = if (dark) darkBackground else lightBackground
    val foreground = if (dark) darkForeground else lightForeground
    val activeAccent = if (dark) accent else lightAccent
    val activeSelection = if (dark) selection else lightSelection
    return TerminalPalette(
        background = background,
        foreground = foreground,
        cursor = activeAccent,
        cursorAccent = background,
        selectionBackground = "${activeSelection}99",
        black = if (dark) darkBackground else "#263238",
        red = "#ff6b6b",
        green = "#2dcf86",
        yellow = "#d7a51e",
        blue = "#4d9df8",
        magenta = "#b878e8",
        cyan = activeAccent,
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
