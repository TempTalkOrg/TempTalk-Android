package com.difft.android.chat.common

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AvatarUtilTest {

    @Test
    fun `test getBgColorResId with empty id returns first color`() {
        val expectedColor = AvatarUtil.colors[0]
        val resultColor = AvatarUtil.getBgColorResId("")
        assertEquals(expectedColor, resultColor)
    }

    @Test
    fun `test getBgColorResId with id ending in digit returns correct color`() {
        val expectedColor = AvatarUtil.colors[7]
        val resultColor = AvatarUtil.getBgColorResId("+12345678907")
        assertEquals(expectedColor, resultColor)
    }

    @Test
    fun `test getBgColorResId with id ending in non-digit returns correct color`() {
        val expectedColor = AvatarUtil.colors[97 % AvatarUtil.colors.size]
        val resultColor = AvatarUtil.getBgColorResId("usera")
        assertEquals(expectedColor, resultColor)
    }

    @Test
    fun `test getBgColorResId with id ending in non-digit character not causing index out of bounds`() {
        val resultColor = AvatarUtil.getBgColorResId("userz")
        assert(resultColor in AvatarUtil.colors)
    }
}
