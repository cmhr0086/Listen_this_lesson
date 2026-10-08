package com.cmhr.listen.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun ListenTheme(
    palette: ThemePalette = ThemePalette.Default,
    darkMode: DarkModePreference = DarkModePreference.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (darkMode) {
        DarkModePreference.SYSTEM -> isSystemInDarkTheme()
        DarkModePreference.LIGHT -> false
        DarkModePreference.DARK -> true
    }
    val colorScheme = if (palette == ThemePalette.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        (if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).withPendingTertiary(darkTheme)
    } else brandColorScheme(palette, darkTheme)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
