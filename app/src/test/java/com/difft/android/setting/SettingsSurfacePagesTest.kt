package com.difft.android.setting

import com.difft.android.base.utils.pane.SettingsIdiomPage
import org.junit.Assert.assertTrue
import org.junit.Test





class SettingsSurfacePagesTest {

    @Test
    fun `text-entry settings pages are settings-idiom pages`() {
        listOf(SetCustomIdFragment::class.java, DeleteAccountFragment::class.java).forEach { page ->
            assertTrue("${page.simpleName} must implement SettingsIdiomPage", SettingsIdiomPage::class.java.isAssignableFrom(page))
        }
    }
}
