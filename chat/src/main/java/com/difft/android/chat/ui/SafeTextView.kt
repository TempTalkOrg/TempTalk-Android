package com.difft.android.chat.ui

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.util.AttributeSet
import android.view.textclassifier.TextClassifier
import androidx.appcompat.widget.AppCompatTextView






open class SafeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setTextClassifier(TextClassifier.NO_OP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        try {
            super.onDraw(canvas)
        } catch (e: NullPointerException) {
            
            
            
        } catch (e: IllegalArgumentException) {
            
            
            
        }
    }
}
