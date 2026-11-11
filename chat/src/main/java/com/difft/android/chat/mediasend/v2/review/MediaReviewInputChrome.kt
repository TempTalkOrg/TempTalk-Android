package com.difft.android.chat.mediasend.v2.review

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.difft.android.base.widget.applyFloatingSurface
import com.difft.android.chat.R
import com.difft.android.chat.ui.ChatInputChrome
import com.difft.android.chat.ui.setConfidentialInputBorder


internal object MediaReviewInputChrome {
    fun darkInflater(inflater: LayoutInflater): LayoutInflater {
        val configuration = Configuration(inflater.context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        val context = ContextThemeWrapper(inflater.context, inflater.context.theme).apply {
            applyOverrideConfiguration(configuration)
        }
        return inflater.cloneInContext(context)
    }

    fun inflate(inflater: LayoutInflater, container: ViewGroup?): View =
        darkInflater(inflater).inflate(R.layout.v2_media_review_fragment, container, false)

    fun apply(root: View) {
        val field = root.findViewById<View>(R.id.input_container)
        field.applyFloatingSurface(cornerRadiusPx = field.resources.getDimension(R.dimen.chat_input_field_radius))
        ChatInputChrome.applySendButton(root.findViewById(R.id.send))
    }

    
    fun setConfidential(root: View, enabled: Boolean) {
        val field = root.findViewById<View>(R.id.input_container)
        field.setConfidentialInputBorder(enabled, field.resources.getDimension(R.dimen.chat_input_field_radius))
    }
}
