package com.cmhr.listen.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** User-selectable accent palettes. Neutrals and status colors are shared, so every palette reads the same. */
enum class ThemePalette(val label: String, val swatch: Color) {
    TEAL("墨青", Color(0xFF00696B)),
    BLUE("海蓝", Color(0xFF2B5EA7)),
    GREEN("松绿", Color(0xFF2E6A44)),
    PURPLE("雅紫", Color(0xFF6750A4)),
    ORANGE("暖橙", Color(0xFF8F4C00)),
    /** Android 12+ wallpaper colors; falls back to [TEAL] on older systems. */
    DYNAMIC("跟随系统", Color(0xFF7A7F80));

    companion object {
        val Default = TEAL
    }
}

enum class DarkModePreference(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

private data class Accent(
    val light: Tone, val dark: Tone
)

private data class Tone(val primary: Long, val onPrimary: Long, val container: Long, val onContainer: Long)

private fun accent(palette: ThemePalette): Accent = when (palette) {
    ThemePalette.TEAL, ThemePalette.DYNAMIC -> Accent(Tone(0xFF00696B, 0xFFFFFFFF, 0xFFCCE8E7, 0xFF002020), Tone(0xFF80D4D6, 0xFF003738, 0xFF004F51, 0xFF9CF1F2))
    ThemePalette.BLUE -> Accent(Tone(0xFF2B5EA7, 0xFFFFFFFF, 0xFFD6E3FF, 0xFF001B3E), Tone(0xFFA9C7FF, 0xFF003063, 0xFF0F4688, 0xFFD6E3FF))
    ThemePalette.GREEN -> Accent(Tone(0xFF2E6A44, 0xFFFFFFFF, 0xFFB1F1C1, 0xFF00210E), Tone(0xFF96D5A6, 0xFF00391C, 0xFF13512E, 0xFFB1F1C1))
    ThemePalette.PURPLE -> Accent(Tone(0xFF6750A4, 0xFFFFFFFF, 0xFFEADDFF, 0xFF21005D), Tone(0xFFD0BCFF, 0xFF381E72, 0xFF4F378B, 0xFFEADDFF))
    ThemePalette.ORANGE -> Accent(Tone(0xFF8F4C00, 0xFFFFFFFF, 0xFFFFDCBE, 0xFF2E1500), Tone(0xFFFFB871, 0xFF4C2700, 0xFF6D3A00, 0xFFFFDCBE))
}

/**
 * Tertiary is reserved for "needs attention, not an error" (待识别 / 已暂停) and is the same amber
 * in every palette, so status meaning never depends on the accent.
 */
private val PendingLight = Tone(0xFF7A5900, 0xFFFFFFFF, 0xFFFFDF9E, 0xFF261A00)
private val PendingDark = Tone(0xFFF5BF48, 0xFF402D00, 0xFF5C4300, 0xFFFFDF9E)

internal fun brandColorScheme(palette: ThemePalette, dark: Boolean): ColorScheme {
    val a = accent(palette)
    return if (dark) {
        val t = a.dark
        darkColorScheme(
            primary = Color(t.primary), onPrimary = Color(t.onPrimary),
            primaryContainer = Color(t.container), onPrimaryContainer = Color(t.onContainer),
            secondary = Color(t.primary), onSecondary = Color(t.onPrimary),
            secondaryContainer = Color(t.container), onSecondaryContainer = Color(t.onContainer),
            tertiary = Color(PendingDark.primary), onTertiary = Color(PendingDark.onPrimary),
            tertiaryContainer = Color(PendingDark.container), onTertiaryContainer = Color(PendingDark.onContainer),
            background = Color(0xFF111414), onBackground = Color(0xFFE0E3E3),
            surface = Color(0xFF111414), onSurface = Color(0xFFE0E3E3),
            surfaceVariant = Color(0xFF3F4848), onSurfaceVariant = Color(0xFFC4C8C8),
            outline = Color(0xFF8E9292), outlineVariant = Color(0xFF444849),
            surfaceContainerLowest = Color(0xFF0B0F0F), surfaceContainerLow = Color(0xFF191C1C),
            surfaceContainer = Color(0xFF1D2020), surfaceContainerHigh = Color(0xFF272B2B),
            surfaceContainerHighest = Color(0xFF323536), surfaceBright = Color(0xFF373A3A), surfaceDim = Color(0xFF111414),
            inverseSurface = Color(0xFFE0E3E3), inverseOnSurface = Color(0xFF2D3131), inversePrimary = Color(a.light.primary)
        )
    } else {
        val t = a.light
        lightColorScheme(
            primary = Color(t.primary), onPrimary = Color(t.onPrimary),
            primaryContainer = Color(t.container), onPrimaryContainer = Color(t.onContainer),
            secondary = Color(t.primary), onSecondary = Color(t.onPrimary),
            secondaryContainer = Color(t.container), onSecondaryContainer = Color(t.onContainer),
            tertiary = Color(PendingLight.primary), onTertiary = Color(PendingLight.onPrimary),
            tertiaryContainer = Color(PendingLight.container), onTertiaryContainer = Color(PendingLight.onContainer),
            background = Color(0xFFF8FAFA), onBackground = Color(0xFF191C1C),
            surface = Color(0xFFF8FAFA), onSurface = Color(0xFF191C1C),
            surfaceVariant = Color(0xFFDDE4E4), onSurfaceVariant = Color(0xFF414848),
            outline = Color(0xFF717878), outlineVariant = Color(0xFFC1C8C8),
            surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF2F4F4),
            surfaceContainer = Color(0xFFECEEEE), surfaceContainerHigh = Color(0xFFE6E9E9),
            surfaceContainerHighest = Color(0xFFE0E3E3), surfaceBright = Color(0xFFF8FAFA), surfaceDim = Color(0xFFD8DADA),
            inverseSurface = Color(0xFF2D3131), inverseOnSurface = Color(0xFFEFF1F1), inversePrimary = Color(a.dark.primary)
        )
    }
}

/** Dynamic schemes keep the shared amber for pending states so status colors stay consistent. */
internal fun ColorScheme.withPendingTertiary(dark: Boolean): ColorScheme {
    val p = if (dark) PendingDark else PendingLight
    return copy(
        tertiary = Color(p.primary), onTertiary = Color(p.onPrimary),
        tertiaryContainer = Color(p.container), onTertiaryContainer = Color(p.onContainer)
    )
}
