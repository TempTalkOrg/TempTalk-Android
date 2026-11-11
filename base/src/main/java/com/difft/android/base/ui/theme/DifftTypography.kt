package com.difft.android.base.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp







@Immutable
data class DifftTypeScale(
    val h1: TextStyle = step(32, 40, FontWeight.Medium),
    val h2: TextStyle = step(28, 36, FontWeight.Medium),
    val h3: TextStyle = step(24, 32, FontWeight.Medium),
    



    val h4: TextStyle = step(20, 28, FontWeight.Bold),
    val h5: TextStyle = step(16, 24, FontWeight.Medium),
    val h6: TextStyle = step(14, 20, FontWeight.Medium),
    val h7: TextStyle = step(12, 16, FontWeight.Medium),
    val p0: TextStyle = step(24, 32, FontWeight.Normal),
    val p1: TextStyle = step(20, 28, FontWeight.Normal),
    val p2: TextStyle = step(16, 24, FontWeight.Normal),
    val p3: TextStyle = step(14, 20, FontWeight.Normal),
    val p4: TextStyle = step(12, 16, FontWeight.Normal),
    val p5: TextStyle = step(10, 14, FontWeight.Normal),
    
    val footnote: TextStyle = step(13, 18, FontWeight.Normal),
)

private fun step(size: Int, lineHeight: Int, weight: FontWeight): TextStyle = TextStyle(
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = 0.sp,
)

val LocalDifftTypeScale = staticCompositionLocalOf { DifftTypeScale() }






fun DifftTypeScale.scaled(factor: Float): DifftTypeScale {
    fun TextStyle.times() = copy(fontSize = fontSize * factor, lineHeight = lineHeight * factor)
    return DifftTypeScale(
        h1 = h1.times(), h2 = h2.times(), h3 = h3.times(), h4 = h4.times(), h5 = h5.times(), h6 = h6.times(), h7 = h7.times(),
        p0 = p0.times(), p1 = p1.times(), p2 = p2.times(), p3 = p3.times(), p4 = p4.times(), p5 = p5.times(),
        footnote = footnote.times(),
    )
}





fun createDifftTypography(): Typography = Typography(
    
    displayLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),

    
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    
    titleLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
