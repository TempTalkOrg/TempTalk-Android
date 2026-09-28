package com.difft.android.base.ui.avatar

import kotlin.math.roundToInt

/**
 * Single owner of the letter/diameter ratio the avatar letter fallback was designed with.
 * Lives in :base so the chat avatar view, its Compose wrappers and the call module can all
 * derive the same letter size without duplicating the constants.
 */
object AvatarLetterSize {
    /** Avatar diameter the default letter size was designed for. */
    const val DEFAULT_AVATAR_SIZE_DP = 48

    /** Letter size the [DEFAULT_AVATAR_SIZE_DP] list avatar was designed with. */
    const val DEFAULT_LETTER_TEXT_SIZE_DP = 22

    /** Letter size that keeps the designed ratio for an avatar rendered at [avatarSizeDp]. */
    fun forDiameterDp(avatarSizeDp: Int): Int =
        (avatarSizeDp * DEFAULT_LETTER_TEXT_SIZE_DP / DEFAULT_AVATAR_SIZE_DP.toFloat()).roundToInt()
}
