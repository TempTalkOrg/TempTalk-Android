package com.difft.android.base.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The design system's type scale — the Figma text styles `SF/H1–H7` (headings, Medium) and
 * `SF/P0–P5` (paragraphs, Regular) from the "Wea Design system" library, named exactly as the
 * design panel shows them so a spec reads straight into code: `SF/P3` → [p3].
 *
 * Every step of the library's "Font ✅" page (file `16QV9AE1wA37o9D2Xdc4sO`, node `1414:21499`) is
 * here, used or not, so a spec never lands on a size without a token. Each Hn/Pn pair shares size
 * and line height and differs only in weight, which is how the design tells a heading from body
 * text — so Medium is a first-class weight here (the one exception is [h4], see its note). Letter
 * spacing is zero throughout, as in Figma.
 *
 * Access through `DifftTheme.typography.h5` etc. The Material 3 slots on [createDifftTypography]
 * stay for existing callers and for Material components; new code uses this scale.
 */
@Immutable
data class DifftTypeScale(
    val h1: TextStyle = step(32, 40, FontWeight.Medium),
    val h2: TextStyle = step(28, 36, FontWeight.Medium),
    val h3: TextStyle = step(24, 32, FontWeight.Medium),
    /**
     * Weight 500 on the library's spec sheet, which also notes the web font renders it a step
     * heavier ("about a semibold feel"); page instances carry SF Pro Semibold. Bold here on
     * purpose: the system font has no stable 600 (older devices snap it to Bold, newer ones to a
     * true semibold), the XML title bars can only say `textStyle="bold"`, and every 20sp title in
     * the app was Bold before the scale — so Bold keeps Compose and XML pages identical instead of
     * splitting them by one weight step.
     */
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
    /**
     * Apple `Footnote/Regular`, not part of the Wea scale: the design uses it next to the SF steps
     * on the call-rating sheet, so it is kept as the one named exception.
     */
    val footnote: TextStyle = step(13, 18, FontWeight.Normal),
)

private fun step(size: Int, lineHeight: Int, weight: FontWeight): TextStyle = TextStyle(
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = 0.sp,
)

val LocalDifftTypeScale = staticCompositionLocalOf { DifftTypeScale() }

/**
 * Material Design 3 [Typography] for Material components and the existing `titleMedium` /
 * `bodyMedium` call sites. New code uses [DifftTypeScale] via `DifftTheme.typography.p3` etc.
 */
fun createDifftTypography(): Typography = Typography(
    // Display styles
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

    // Headline styles
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

    // Title styles
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

    // Body styles
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

    // Label styles
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
