package com.cmhr.listen.ui.theme

import com.cmhr.listen.data.settings.AppearanceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The palette picker is retired; its stored enum names still belong to existing user data. */
class PalettesTest {
    @Test fun legacyPaletteNamesKeepTheirMeaningAndOrder() {
        val storedNames = listOf("TEAL", "BLUE", "GREEN", "PURPLE", "ORANGE", "DYNAMIC")
        assertEquals(storedNames, ThemePalette.entries.map { it.name })
        storedNames.zip(ThemePalette.entries).forEach { (stored, expected) ->
            assertSame(expected, ThemePalette.valueOf(stored))
            assertEquals(stored, expected.name)
        }
        assertSame(ThemePalette.TEAL, ThemePalette.Default)
        assertSame(ThemePalette.TEAL, AppearanceSettings().palette)
    }

    @Test fun retainedPaletteMetadataUsesTheOriginalArgbValues() {
        assertEquals(listOf("墨青", "海蓝", "松绿", "雅紫", "暖橙", "跟随系统"), ThemePalette.entries.map { it.label })
        assertEquals(listOf(0xFF00696BL, 0xFF2B5EA7L, 0xFF2E6A44L, 0xFF6750A4L, 0xFF8F4C00L, 0xFF7A7F80L),
            ThemePalette.entries.map { it.swatchArgb })
    }

    @Test fun persistedDarkModeNamesAndDefaultRemainCompatible() {
        val storedNames = listOf("SYSTEM", "LIGHT", "DARK")
        assertEquals(storedNames, DarkModePreference.entries.map { it.name })
        storedNames.zip(DarkModePreference.entries).forEach { (stored, expected) ->
            assertSame(expected, DarkModePreference.valueOf(stored))
            assertEquals(stored, expected.name)
        }
        assertSame(DarkModePreference.SYSTEM, AppearanceSettings().darkMode)
    }
}
