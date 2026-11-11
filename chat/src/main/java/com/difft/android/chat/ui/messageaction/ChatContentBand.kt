package com.difft.android.chat.ui.messageaction

import android.graphics.Point
import android.graphics.Rect
import android.view.View
import com.difft.android.chat.R
import kotlin.math.max
import kotlin.math.min


data class ChatChromeInsets(val top: Int, val bottom: Int) {
    companion object {
        val NONE = ChatChromeInsets(0, 0)
    }
}







object ChatContentBand {

    fun setInsets(list: View, insets: ChatChromeInsets) {
        list.setTag(R.id.tag_chat_chrome_insets, insets)
    }

    fun insetsOf(view: View): ChatChromeInsets = view.getTag(R.id.tag_chat_chrome_insets) as? ChatChromeInsets ?: ChatChromeInsets.NONE

    



    fun onScreen(view: View, out: Rect): Boolean {
        val origin = Point()
        if (!view.getGlobalVisibleRect(out, origin)) {
            out.setEmpty()
            return false
        }
        val insets = insetsOf(view)
        out.top = max(out.top, origin.y + insets.top)
        out.bottom = min(out.bottom, origin.y + view.height - insets.bottom)
        if (out.top >= out.bottom) {
            out.setEmpty()
            return false
        }
        return true
    }
}
