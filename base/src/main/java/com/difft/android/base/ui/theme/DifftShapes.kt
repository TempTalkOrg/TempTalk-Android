package com.difft.android.base.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material Design 3 Shapes configuration for Difft design system
 *
 * Defines corner radius system following MD3 guidelines
 */
fun createDifftShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Additional shape definitions for specific components
 */
object DifftShapeTokens {
    val Circle = RoundedCornerShape(50)
    val Button = RoundedCornerShape(6.dp)
    val Dialog = RoundedCornerShape(8.dp)
    val Input = RoundedCornerShape(8.dp)
    val Card = RoundedCornerShape(12.dp)
    val IconContainer = RoundedCornerShape(6.67.dp)
}
