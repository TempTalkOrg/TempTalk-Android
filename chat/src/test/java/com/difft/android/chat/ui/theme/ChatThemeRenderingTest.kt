package com.difft.android.chat.ui.theme

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.RecyclerView
import androidx.compose.ui.graphics.toArgb
import com.difft.android.base.ui.theme.tokens.Palette
import com.difft.android.chat.R
import com.difft.android.chat.messages.TestScopeApplication
import com.difft.android.chat.ui.BubblePalette
import com.difft.android.chat.ui.ChatBackgroundDrawable
import com.difft.android.chat.ui.ChatWallpaperPainter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs


@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [33], qualifiers = "notnight-mdpi")
class ChatThemeRenderingTest {

    private val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), com.difft.android.base.R.style.Theme_Chative)

    @Test
    fun `unknown stored values read as Classic`() {
        assertEquals(ChatTheme.CLASSIC, ChatTheme.fromStored(0))
        assertEquals(ChatTheme.GRADIENT, ChatTheme.fromStored(1))
        assertEquals(ChatTheme.CLASSIC, ChatTheme.fromStored(7))
        assertEquals(ChatTheme.CLASSIC, ChatTheme.fromStored(-1))
    }

    @Test
    fun `Classic light keeps the grey sent bubble with white meta and ice-cyan links`() {
        val sent = BubblePalette.of(context, sent = true, ChatTheme.CLASSIC)
        assertEquals(0xFF474D57.toInt(), sent.fill)
        assertEquals(0xD9FFFFFF.toInt(), sent.meta)
        assertEquals(0xFFA6F4FA.toInt(), sent.link)
        assertFalse(sent.linkMedium)
        val received = BubblePalette.of(context, sent = false, ChatTheme.CLASSIC)
        assertEquals(0xFFF5F5F5.toInt(), received.fill)
        assertEquals(0xFF056FFA.toInt(), received.link)
    }

    @Test
    fun `Gradient light paints a white received bubble, a blue sent fallback and Medium links`() {
        val sent = BubblePalette.of(context, sent = true, ChatTheme.GRADIENT)
        assertEquals(0xFF1E5FCC.toInt(), sent.fill)
        assertEquals(0xD9FFFFFF.toInt(), sent.meta)
        assertEquals(0xFFA6F4FA.toInt(), sent.link)
        assertTrue(sent.linkMedium)
        val received = BubblePalette.of(context, sent = false, ChatTheme.GRADIENT)
        assertEquals(0xFFFFFFFF.toInt(), received.fill)
        assertEquals(0xFF056FFA.toInt(), received.link)
        assertTrue(received.linkMedium)
    }

    @Test
    @Config(qualifiers = "night-mdpi")
    fun `dark received links turn ice cyan and Gradient keeps the dark received fill`() {
        val dark = ContextThemeWrapper(RuntimeEnvironment.getApplication(), com.difft.android.base.R.style.Theme_Chative)
        assertEquals(0xFFA6F4FA.toInt(), BubblePalette.of(dark, sent = false, ChatTheme.CLASSIC).link)
        assertEquals(0xFF181A20.toInt(), BubblePalette.of(dark, sent = false, ChatTheme.GRADIENT).fill)
    }

    @Test
    fun `the XML gradient ends match the Compose palette steps`() {
        assertEquals(Palette.ChatGradientPurple.toArgb(), context.getColor(R.color.chat_bubble_gradient_top))
        assertEquals(Palette.ChatGradientBlue.toArgb(), context.getColor(R.color.chat_bubble_gradient_bottom))
    }

    @Test
    fun `the XML inks, received veil and shadow match the Compose palette steps in light`() {
        assertEquals(Palette.ChatInkPurple.toArgb(), context.getColor(R.color.chat_wallpaper_ink_purple))
        assertEquals(Palette.ChatInkBlue.toArgb(), context.getColor(R.color.chat_wallpaper_ink_blue))
        assertEquals(Palette.ChatInkTeal.toArgb(), context.getColor(R.color.chat_wallpaper_ink_teal))
        assertEquals(Palette.ChatReceivedVeil.toArgb(), context.getColor(R.color.chat_bubble_gradient_recv_veil))
        assertEquals(Palette.ChatReceivedShadow.toArgb(), context.getColor(R.color.chat_bubble_gradient_recv_shadow))
    }

    @Test
    @Config(qualifiers = "night-mdpi")
    fun `the XML inks and received veil match the Compose palette steps in dark`() {
        val dark = RuntimeEnvironment.getApplication()
        assertEquals(Palette.ChatInkPurpleDark.toArgb(), dark.getColor(R.color.chat_wallpaper_ink_purple))
        assertEquals(Palette.ChatInkBlueDark.toArgb(), dark.getColor(R.color.chat_wallpaper_ink_blue))
        assertEquals(Palette.ChatInkTealDark.toArgb(), dark.getColor(R.color.chat_wallpaper_ink_teal))
        assertEquals(Palette.ChatReceivedVeilDark.toArgb(), dark.getColor(R.color.chat_bubble_gradient_recv_veil))
    }

    @Test
    fun `a sent bubble shows the slice of the page gradient behind it`() {
        val page = Page()
        val drawable = AnchoredGradientBubbleDrawable(page.gradient, page.bubble)
        drawable.setBounds(0, 0, BUBBLE_WIDTH, BUBBLE_HEIGHT)
        val bitmap = render { drawable.draw(it) }
        
        assertColorNear(lerp(TOP, BOTTOM, 520f / ANCHOR_HEIGHT), bitmap.getPixel(BUBBLE_WIDTH / 2, BUBBLE_HEIGHT / 2))
        assertFalse("just drawn", drawable.isStale())

        page.bubble.offsetTopAndBottom(-300)
        assertTrue("moved rows need a redraw", drawable.isStale())
        render { drawable.draw(it) }.also { moved ->
            assertColorNear(lerp(TOP, BOTTOM, 220f / ANCHOR_HEIGHT), moved.getPixel(BUBBLE_WIDTH / 2, BUBBLE_HEIGHT / 2))
        }
        assertFalse(drawable.isStale())

        page.anchor.layout(0, 0, WIDTH, ANCHOR_HEIGHT / 2)
        assertTrue("a resized page needs a redraw even where rows stayed put", drawable.isStale())
    }

    @Test
    fun `a received bubble shows the veiled wallpaper ink behind it`() {
        val page = Page()
        val drawable = AnchoredGradientBubbleDrawable(page.gradient, page.bubble, sent = false)
        drawable.setBounds(0, 0, BUBBLE_WIDTH, BUBBLE_HEIGHT)
        val pixel = render { drawable.draw(it) }.getPixel(BUBBLE_WIDTH / 2, BUBBLE_HEIGHT / 2)
        
        assertEquals(255, Color.alpha(pixel))
        assertTrue("tinted, not plain white: ${Integer.toHexString(pixel)}", pixel != Color.WHITE)
        listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)).forEach { channel ->
            assertTrue("veiled: ${Integer.toHexString(pixel)}", channel >= 255 - 26)
        }
        assertFalse("just drawn", drawable.isStale())

        
        page.bubble.offsetLeftAndRight(150)
        assertTrue("a bubble moved sideways needs a redraw", drawable.isStale())
        val sent = AnchoredGradientBubbleDrawable(page.gradient, page.bubble)
        sent.setBounds(0, 0, BUBBLE_WIDTH, BUBBLE_HEIGHT)
        render { sent.draw(it) }
        page.bubble.offsetLeftAndRight(-150)
        assertFalse("the vertical sent gradient ignores sideways moves", sent.isStale())
    }

    @Test
    @Config(qualifiers = "night-mdpi")
    fun `a dark received bubble is the dark veil over the ink`() {
        val page = Page()
        val drawable = AnchoredGradientBubbleDrawable(page.gradient, page.bubble, sent = false)
        drawable.setBounds(0, 0, BUBBLE_WIDTH, BUBBLE_HEIGHT)
        val pixel = render { drawable.draw(it) }.getPixel(BUBBLE_WIDTH / 2, BUBBLE_HEIGHT / 2)
        
        assertEquals(255, Color.alpha(pixel))
        assertTrue("tinted: ${Integer.toHexString(pixel)}", pixel != 0xFF181A20.toInt())
        assertTrue(Color.red(pixel) in 0x18 - 3..0x18 + 26)
        assertTrue(Color.green(pixel) in 0x1A - 3..0x1A + 26)
        assertTrue(Color.blue(pixel) in 0x20 - 3..0x20 + 26)
    }

    @Test
    fun `the wallpaper paints its base before the shared pattern is ready`() {
        val classic = render { ChatWallpaperPainter(context, ChatTheme.CLASSIC).draw(it, 0f, 0f, BUBBLE_WIDTH.toFloat(), BUBBLE_HEIGHT.toFloat()) }
        assertEquals(Color.WHITE, classic.getPixel(BUBBLE_WIDTH / 2, BUBBLE_HEIGHT / 2) or 0xFF000000.toInt())
        val lagoon = render { ChatWallpaperPainter(context, ChatTheme.GRADIENT).draw(it, 0f, 0f, BUBBLE_WIDTH.toFloat(), BUBBLE_HEIGHT.toFloat()) }
        val centre = lagoon.getPixel(BUBBLE_WIDTH / 2, BUBBLE_HEIGHT / 2)
        assertEquals(255, Color.alpha(centre))
        assertTrue("Lagoon is a pale blue, not white: ${Integer.toHexString(centre)}", Color.blue(centre) > Color.red(centre))
    }

    
    private class Page {
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val anchor = FrameLayout(activity)
        val list = RecyclerView(activity)
        val bubble = View(activity)
        val gradient: ChatBubbleGradient

        init {
            anchor.background = ChatBackgroundDrawable(activity, anchor, false)
            anchor.addView(list, FrameLayout.LayoutParams(WIDTH, ANCHOR_HEIGHT))
            anchor.addView(bubble, FrameLayout.LayoutParams(BUBBLE_WIDTH, BUBBLE_HEIGHT).apply { topMargin = 500 })
            activity.setContentView(anchor, FrameLayout.LayoutParams(WIDTH, ANCHOR_HEIGHT))
            anchor.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(ANCHOR_HEIGHT, View.MeasureSpec.EXACTLY),
            )
            anchor.layout(0, 0, WIDTH, ANCHOR_HEIGHT)
            gradient = ChatBubbleGradient(list)
        }
    }

    private fun render(draw: (Canvas) -> Unit): Bitmap =
        Bitmap.createBitmap(BUBBLE_WIDTH, BUBBLE_HEIGHT, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }

    private fun lerp(from: Int, to: Int, fraction: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * fraction).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * fraction).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * fraction).toInt(),
    )

    private fun assertColorNear(expected: Int, actual: Int) {
        val close = abs(Color.red(expected) - Color.red(actual)) <= TOLERANCE &&
            abs(Color.green(expected) - Color.green(actual)) <= TOLERANCE &&
            abs(Color.blue(expected) - Color.blue(actual)) <= TOLERANCE
        assertTrue("expected ${Integer.toHexString(expected)}, was ${Integer.toHexString(actual)}", close)
    }

    private companion object {
        const val WIDTH = 400
        const val ANCHOR_HEIGHT = 1000
        const val BUBBLE_WIDTH = 200
        const val BUBBLE_HEIGHT = 40
        const val TOLERANCE = 4
        val TOP = 0xFF8040D4.toInt()
        val BOTTOM = 0xFF1E5FCC.toInt()
    }
}
