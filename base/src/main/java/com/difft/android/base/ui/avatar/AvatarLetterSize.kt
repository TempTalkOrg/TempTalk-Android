package com.difft.android.base.ui.avatar

import kotlin.math.roundToInt






object AvatarLetterSize {
    
    const val DEFAULT_AVATAR_SIZE_DP = 48

    
    const val DEFAULT_LETTER_TEXT_SIZE_DP = 22

    
    fun forDiameterDp(avatarSizeDp: Int): Int =
        (avatarSizeDp * DEFAULT_LETTER_TEXT_SIZE_DP / DEFAULT_AVATAR_SIZE_DP.toFloat()).roundToInt()
}
