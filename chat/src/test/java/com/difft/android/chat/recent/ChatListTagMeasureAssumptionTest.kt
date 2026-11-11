package com.difft.android.chat.recent

import androidx.appcompat.widget.AppCompatTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
















@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ChatListTagMeasureAssumptionTest {

    private lateinit var textView: AppCompatTextView

    private val threeTags = "[Send failed] · [@You] · [Draft]"
    private val twoTags = "[Send failed] · [@You]"

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        textView = AppCompatTextView(context)
    }

    private fun measure(text: String): Float = textView.paint.measureText(text)

    @Test
    fun `T4-18 measureText tracks text size and string length`() {
        textView.textSize = 14f
        val w14 = measure(threeTags)
        assertTrue("regular-size measurement should be positive, was $w14", w14 > 0f)

        textView.textSize = 21f
        val w21 = measure(threeTags)
        assertTrue("larger size must measure wider: w14=$w14 w21=$w21", w21 > w14)

        assertEquals(0f, measure(""), 0f)

        
        assertTrue(measure(threeTags) > measure(twoTags))
    }
}
