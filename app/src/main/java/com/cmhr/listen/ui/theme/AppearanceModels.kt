package com.cmhr.listen.ui.theme

/** User-selectable accent palettes. Neutrals and status colors are shared, so every palette reads the same. */
enum class ThemePalette(val label: String, val swatchArgb: Long) {
    TEAL("墨青", 0xFF00696B),
    BLUE("海蓝", 0xFF2B5EA7),
    GREEN("松绿", 0xFF2E6A44),
    PURPLE("雅紫", 0xFF6750A4),
    ORANGE("暖橙", 0xFF8F4C00),
    /** Android 12+ wallpaper colors; falls back to [TEAL] on older systems. */
    DYNAMIC("跟随系统", 0xFF7A7F80);

    companion object {
        val Default = TEAL
    }
}

enum class DarkModePreference(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
