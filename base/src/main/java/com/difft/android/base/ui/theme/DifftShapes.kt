package com.difft.android.base.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material Design 3 Shapes configuration, read by Material3 components.
 *
 * `extraSmall` (4) and `small` (8) mirror the design-library [DifftRadius.medium] and
 * [DifftRadius.large] tiers; the larger MD3 slots have no library equivalent.
 */
fun createDifftShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Corner-radius tiers of the design library, exposed as `DifftTheme.radius`.
 *
 * - [small] (2) — tags
 * - [medium] (4) — small components: small/medium buttons, dropdown panels
 * - [large] (8) — cards, message bubbles, dropdown panels (bottom sheets keep their own 16)
 * - [pill] — "Pill" and "Circle" in the library are both a full radius, so they collapse
 *   to a single [CircleShape]
 *
 * Off-scale radii (6, 12, 16, 20, 24, 28) stay as literals at their call sites.
 */
object DifftRadius {
    val small: Dp = 2.dp
    val medium: Dp = 4.dp
    val large: Dp = 8.dp

    val smallShape = RoundedCornerShape(small)
    val mediumShape = RoundedCornerShape(medium)
    val largeShape = RoundedCornerShape(large)
    val pill = CircleShape
}
