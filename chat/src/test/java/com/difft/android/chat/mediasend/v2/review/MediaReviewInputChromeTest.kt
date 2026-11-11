package com.difft.android.chat.mediasend.v2.review

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import androidx.appcompat.view.ContextThemeWrapper
import com.difft.android.base.widget.FloatingSurfaceDrawable
import com.difft.android.chat.R
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [33], qualifiers = "notnight-mdpi")
class MediaReviewInputChromeTest {
    @Test
    fun `dark review outlines the composer only, without repainting the area, the tools or the send circle`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), com.difft.android.base.R.style.Theme_Chative)
        val root = MediaReviewInputChrome.inflate(LayoutInflater.from(context), null)
        assertEquals(Configuration.UI_MODE_NIGHT_YES, root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        MediaReviewInputChrome.apply(root)
        val input = root.findViewById<View>(R.id.input_container)
        val send = root.findViewById<ImageView>(R.id.send)
        val tools = listOf(R.id.draw_tool, R.id.crop_and_rotate_tool, R.id.quality_selector, R.id.save_to_media)
            .map { root.findViewById<View>(it) }
        val controls = listOf(input) + tools
        val backgrounds = controls.map { it.background }
        val areaBackground = root.findViewById<View>(R.id.controls).background
        assertTrue(input.background is FloatingSurfaceDrawable)
        assertEquals(44, send.layoutParams.width)
        assertEquals(44, send.layoutParams.height)
        assertEquals(10, send.paddingLeft)
        controls.forEach { assertNull(it.foreground) }
        
        val sendForeground = send.foreground
        for (enabled in listOf(true, false, true, false)) {
            MediaReviewInputChrome.setConfidential(root, enabled)
            controls.forEachIndexed { index, control ->
                assertSame(backgrounds[index], control.background)
            }
            if (enabled) assertNotNull(input.foreground) else assertNull(input.foreground)
            
            tools.forEach { assertNull(it.foreground) }
            assertSame(areaBackground, root.findViewById<View>(R.id.controls).background)
            assertSame(sendForeground, send.foreground)
        }

        val bitmap = Bitmap.createBitmap(44, 44, Bitmap.Config.ARGB_8888)
        try {
            send.background.setBounds(0, 0, 44, 44)
            send.background.draw(Canvas(bitmap))
            assertEquals(0xFFEAECEF.toInt(), bitmap.getPixel(22, 22))
        } finally {
            bitmap.recycle()
        }
    }
}
