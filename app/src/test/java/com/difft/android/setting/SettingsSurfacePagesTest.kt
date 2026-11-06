package com.difft.android.setting

import com.difft.android.base.utils.pane.SettingsIdiomPage
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every Me page that paints the settings surface (`bg`) must declare it, so the pane container
 * behind it uses the same surface during transitions and the theme guard enumerates it.
 */
class SettingsSurfacePagesTest {

    @Test
    fun `text-entry settings pages are settings-idiom pages`() {
        listOf(SetCustomIdFragment::class.java, DeleteAccountFragment::class.java).forEach { page ->
            assertTrue("${page.simpleName} must implement SettingsIdiomPage", SettingsIdiomPage::class.java.isAssignableFrom(page))
        }
    }
}
