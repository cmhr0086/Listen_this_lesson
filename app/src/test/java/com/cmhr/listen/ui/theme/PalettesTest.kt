package com.cmhr.listen.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PalettesTest {
    private val fixed = ThemePalette.entries.filter { it != ThemePalette.DYNAMIC }

    @Test fun pendingStatusColorIsTheSameInEveryPalette() {
        for (dark in listOf(false, true)) {
            val tertiary = fixed.map { brandColorScheme(it, dark).tertiaryContainer }.toSet()
            assertEquals("tertiary must not depend on the accent (dark=$dark)", 1, tertiary.size)
        }
    }

    @Test fun palettesDifferInTheirAccent() {
        val primaries = fixed.map { brandColorScheme(it, dark = false).primary }.toSet()
        assertEquals(fixed.size, primaries.size)
    }

    @Test fun darkAndLightSchemesUseDifferentSurfaces() {
        assertNotEquals(brandColorScheme(ThemePalette.TEAL, false).surface, brandColorScheme(ThemePalette.TEAL, true).surface)
    }
}
