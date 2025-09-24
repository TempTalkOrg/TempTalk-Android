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
        // 假設 colors 陣列的大小至少為 10
        val expectedColor = AvatarUtil.colors[7] // 假設最後一個字符是 '7'
        val resultColor = AvatarUtil.getBgColorResId("+12345678907")
        assertEquals(expectedColor, resultColor)
    }

    @Test
    fun `test getBgColorResId with id ending in non-digit returns correct color`() {
        // 假設 'a' 的 ASCII 碼是 97，並且 colors 陣列的大小超過 10
        val expectedColor = AvatarUtil.colors[97 % AvatarUtil.colors.size]
        val resultColor = AvatarUtil.getBgColorResId("usera")
        assertEquals(expectedColor, resultColor)
    }

    @Test
    fun `test getBgColorResId with id ending in non-digit character not causing index out of bounds`() {
        val resultColor = AvatarUtil.getBgColorResId("userz") // 假設 'z' 的 ASCII 碼可能導致索引超出範圍
        // 只要這個方法不拋出異常，就認為是成功的，因為我們已經在方法中進行了安全的索引處理
        assert(resultColor in AvatarUtil.colors)
    }
}
