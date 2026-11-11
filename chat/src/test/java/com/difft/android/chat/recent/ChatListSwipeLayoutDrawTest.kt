package com.difft.android.chat.recent

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.difft.android.chat.R
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatListSwipeLayoutDrawTest {

    @Test
    fun `a closed row draws no action behind its foreground`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(activity, com.difft.android.base.R.style.Theme_Chative)
        val row = LayoutInflater.from(context).inflate(R.layout.chat_fragment_recent_chat_list_item, null) as ChatListSwipeLayout
        
        row.findViewById<View>(R.id.swipe_foreground).background = null
        row.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        row.layout(0, 0, 400, 80)
        val image = Bitmap.createBitmap(400, 80, Bitmap.Config.ARGB_8888)
        try {
            row.draw(Canvas(image))
            val delete = ContextCompat.getColor(context, com.difft.android.base.R.color.error)
            
            assertNotEquals("the delete action stays undrawn while closed", delete, image.getPixel(380, 40))
        } finally {
            image.recycle()
        }
    }
}
