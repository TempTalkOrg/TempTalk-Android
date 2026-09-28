package com.difft.android.call

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the uid-only avatar normalisation onto the app-wide first-letter rule
 * (`ContactorUtil.getFirstLetter`): ASCII letters and CJK survive, everything else becomes `#`.
 * The colour-seed id keeps the device-suffix and `+` strip.
 *
 * Robolectric is required: `ContactorUtil.getFirstLetter` calls `TextUtils.isEmpty`, which returns
 * a stub value under plain JUnit and silently takes the wrong branch for null/blank names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CallAvatarFieldsTest {

    @Test
    fun `name is uppercased and the device suffix is stripped`() {
        val fields = letterAvatarFieldsFor(name = "alice", uid = "+123.1")

        assertEquals("A", fields.firstLetter)
        assertEquals("123", fields.id)
    }

    @Test
    fun `non-ASCII letters fall back to the hash placeholder`() {
        assertEquals("#", letterAvatarFieldsFor(name = "éric", uid = "+123.1").firstLetter)
    }

    @Test
    fun `digits fall back to the hash placeholder`() {
        assertEquals("#", letterAvatarFieldsFor(name = "123", uid = "+123.1").firstLetter)
    }

    @Test
    fun `null name falls back to the hash placeholder`() {
        assertEquals("#", letterAvatarFieldsFor(name = null, uid = "+123.1").firstLetter)
    }

    @Test
    fun `empty name falls back to the hash placeholder`() {
        assertEquals("#", letterAvatarFieldsFor(name = "", uid = "+123.1").firstLetter)
    }

    @Test
    fun `blank name falls back to the hash placeholder`() {
        assertEquals("#", letterAvatarFieldsFor(name = "   ", uid = "+123.1").firstLetter)
    }

    @Test
    fun `a participant without a contact record uses its generated name`() {
        assertEquals("T", letterAvatarFieldsFor(name = "TT-abc", uid = "+123.1").firstLetter)
    }

    @Test
    fun `CJK names keep their first character`() {
        assertEquals("张", letterAvatarFieldsFor(name = "张三", uid = "+123.1").firstLetter)
    }

    @Test
    fun `uid without a device suffix keeps its digits and drops the plus`() {
        assertEquals("19998887777", letterAvatarFieldsFor(name = "Bob", uid = "+19998887777").id)
    }
}
