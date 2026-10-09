package com.cmhr.listen.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tuned for Chinese text: Material's Latin letter spacing (0.1–0.5sp) loosens CJK lines, and its
 * large titles waste a phone's first screen, so titles are smaller and every style has 0 spacing.
 */
private val Base = Typography()

val Typography = Typography(
    headlineSmall = Base.headlineSmall.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    titleLarge = Base.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    titleMedium = Base.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    titleSmall = Base.titleSmall.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 27.sp, letterSpacing = 0.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.sp),
    bodySmall = Base.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.sp),
    labelLarge = Base.labelLarge.copy(fontSize = 15.sp, letterSpacing = 0.sp),
    labelMedium = Base.labelMedium.copy(fontSize = 13.sp, letterSpacing = 0.sp),
    labelSmall = Base.labelSmall.copy(fontSize = 12.sp, letterSpacing = 0.sp)
)
