package com.difft.android.chat.media

import android.app.Activity
import com.difft.android.selector.basic.PictureSelector
import com.difft.android.selector.config.SelectorProviders
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentPreviewExportPolicyTest {

    @After
    fun tearDown() {
        SelectorProviders.getInstance().reset()
    }

    private fun previewConfigFor(isConfidential: Boolean) = run {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        PictureSelector.create(activity).openPreview().applyExportPolicy(isConfidential)
        SelectorProviders.getInstance().selectorConfig
    }

    @Test
    fun confidentialPreviewHidesSaveAndShare() {
        val config = previewConfigFor(isConfidential = true)
        assertTrue(config.isHidePreviewDownload)
        assertTrue(config.isHidePreviewShare)
    }

    @Test
    fun normalPreviewKeepsSaveAndShare() {
        val config = previewConfigFor(isConfidential = false)
        assertFalse(config.isHidePreviewDownload)
        assertFalse(config.isHidePreviewShare)
    }
}
