package com.difft.android.chat.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import com.difft.android.chat.databinding.ChatFragmentInputBinding
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
class ChatInputConfidentialBandTest {
    @Test
    fun `light single-line composer keeps its backgrounds while toggling outlines`() = assertControls(44)

    @Test
    @Config(qualifiers = "night-mdpi")
    fun `dark multiline composer keeps its backgrounds while toggling outlines`() = assertControls(88)

    private fun assertControls(fieldHeight: Int) {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), com.difft.android.base.R.style.Theme_Chative)
        val binding = ChatFragmentInputBinding.inflate(LayoutInflater.from(context))
        binding.root.layout(0, 0, 200, 100)
        ChatInputChrome.apply(binding)
        val rootBackground = binding.root.background
        val panelBackground = binding.llChatActions.background
        val fieldBackground = binding.clInputContent.background
        val plusBackground = binding.clLeftButtons.background
        val sendBackground = binding.buttonSubmit.background
        val before = render(rootBackground, 200, 200, 100)
        try {
            assertNull(binding.clInputContent.foreground)
            assertNull(binding.clLeftButtons.foreground)
            ChatInputChrome.setConfidential(binding, true)
            assertSame(rootBackground, binding.root.background)
            assertSame(panelBackground, binding.llChatActions.background)
            assertSame(fieldBackground, binding.clInputContent.background)
            assertSame(plusBackground, binding.clLeftButtons.background)
            assertSame(sendBackground, binding.buttonSubmit.background)
            
            assertTrue(binding.buttonSubmit.foreground is android.graphics.drawable.RippleDrawable)
            val after = render(binding.root.background, 200, 200, 100)
            try {
                assertTrue("background and navigation extension must be unchanged", before.sameAs(after))
            } finally {
                after.recycle()
            }
            
            val dash = context.getColor(com.difft.android.base.R.color.t_info)
            assertDashedOutline(binding.clInputContent.foreground, 200, fieldHeight, dash)
            assertDashedOutline(binding.clLeftButtons.foreground, 44, 44, dash)
            ChatInputChrome.setConfidential(binding, false)
            assertNull(binding.clInputContent.foreground)
            assertNull(binding.clLeftButtons.foreground)
            assertSame(rootBackground, binding.root.background)
            assertSame(fieldBackground, binding.clInputContent.background)
        } finally {
            before.recycle()
        }
    }

    private fun assertDashedOutline(drawable: Drawable?, width: Int, height: Int, dash: Int) {
        assertNotNull(drawable)
        val bitmap = render(drawable!!, width, height)
        try {
            assertEquals("the overlay must not fill the surface", 0, Color.alpha(bitmap.getPixel(width / 2, height / 2)))
            assertEquals("the rounded corner must stay clear", 0, Color.alpha(bitmap.getPixel(0, 0)))
            assertTrue((0 until width).any { Color.alpha(bitmap.getPixel(it, 0)) > 0 })
            if (width > height) {
                val top = (30 until width - 30).map { bitmap.getPixel(it, 0) }
                assertTrue("the top edge must contain t.info dashes", top.any { it == dash })
                assertTrue("the top edge must contain gaps", top.any { Color.alpha(it) == 0 })
                assertTrue("the stroke is only one pixel at mdpi", top.indices.all { bitmap.getPixel(it + 30, 1) == 0 })
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun render(drawable: Drawable, width: Int, height: Int, boundsHeight: Int = height): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, width, boundsHeight)
            drawable.draw(Canvas(it))
        }
}
