package com.difft.android.base.user

import com.difft.android.test.fakes.FakeUserManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pure JVM unit tests for the 3 ImageEditor preference fields introduced in PR 1
 * (replacements for the deleted SignalStore.imageEditorValues entries).
 *
 * Exercises:
 *   - defaults
 *   - round-trip via FakeUserManager.update {} (the write path used by ImageEditorHudV2)
 *   - copy / equals semantics (data class contract preservation)
 */
class UserDataImageEditorFieldsTest {

    @Test
    fun `default values are zero`() {
        val data = UserData()
        assertEquals(0, data.imageEditorMarkerPercentage)
        assertEquals(0, data.imageEditorHighlighterPercentage)
        assertEquals(0, data.imageEditorBlurPercentage)
    }

    @Test
    fun `update marker percentage persists through UserManager update`() {
        val userManager = FakeUserManager(userData = UserData())
        userManager.update { imageEditorMarkerPercentage = 55 }

        assertEquals(55, userManager.getUserData()?.imageEditorMarkerPercentage)
        assertEquals(0, userManager.getUserData()?.imageEditorHighlighterPercentage)
        assertEquals(0, userManager.getUserData()?.imageEditorBlurPercentage)
    }

    @Test
    fun `update highlighter percentage persists through UserManager update`() {
        val userManager = FakeUserManager(userData = UserData())
        userManager.update { imageEditorHighlighterPercentage = 77 }

        assertEquals(77, userManager.getUserData()?.imageEditorHighlighterPercentage)
        assertEquals(0, userManager.getUserData()?.imageEditorMarkerPercentage)
        assertEquals(0, userManager.getUserData()?.imageEditorBlurPercentage)
    }

    @Test
    fun `update blur percentage persists through UserManager update`() {
        val userManager = FakeUserManager(userData = UserData())
        userManager.update { imageEditorBlurPercentage = 33 }

        assertEquals(33, userManager.getUserData()?.imageEditorBlurPercentage)
        assertEquals(0, userManager.getUserData()?.imageEditorMarkerPercentage)
        assertEquals(0, userManager.getUserData()?.imageEditorHighlighterPercentage)
    }

    @Test
    fun `all three percentages can be set independently`() {
        val userManager = FakeUserManager(userData = UserData())
        userManager.update {
            imageEditorMarkerPercentage = 10
            imageEditorHighlighterPercentage = 20
            imageEditorBlurPercentage = 30
        }

        val data = userManager.getUserData()!!
        assertEquals(10, data.imageEditorMarkerPercentage)
        assertEquals(20, data.imageEditorHighlighterPercentage)
        assertEquals(30, data.imageEditorBlurPercentage)
    }

    @Test
    fun `data class copy preserves new fields`() {
        val original = UserData(
            imageEditorMarkerPercentage = 1,
            imageEditorHighlighterPercentage = 2,
            imageEditorBlurPercentage = 3,
        )
        val copied = original.copy()

        assertEquals(original, copied)
        assertEquals(1, copied.imageEditorMarkerPercentage)
        assertEquals(2, copied.imageEditorHighlighterPercentage)
        assertEquals(3, copied.imageEditorBlurPercentage)
    }

    @Test
    fun `equals distinguishes instances that differ only by new fields`() {
        val a = UserData(imageEditorMarkerPercentage = 0)
        val b = UserData(imageEditorMarkerPercentage = 1)
        assertFalse(a == b)
    }
}
