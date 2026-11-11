package com.difft.android.base.ui.avatar

import org.junit.Assert.assertEquals
import org.junit.Test





class AvatarLetterSizeTest {

    @Test
    fun `48dp keeps the designed 22dp letter`() {
        assertEquals(22, AvatarLetterSize.forDiameterDp(48))
        assertEquals(AvatarLetterSize.DEFAULT_LETTER_TEXT_SIZE_DP, AvatarLetterSize.forDiameterDp(48))
    }

    @Test
    fun `32dp participants-sheet avatar scales to 15dp`() {
        assertEquals(15, AvatarLetterSize.forDiameterDp(32))
    }

    @Test
    fun `96dp tile scales to 44dp`() {
        assertEquals(44, AvatarLetterSize.forDiameterDp(96))
    }

    @Test
    fun `16dp banner avatar scales to 7dp`() {
        
        assertEquals(7, AvatarLetterSize.forDiameterDp(16))
    }

    @Test
    fun `default diameter constant is 48`() {
        assertEquals(48, AvatarLetterSize.DEFAULT_AVATAR_SIZE_DP)
    }
}
