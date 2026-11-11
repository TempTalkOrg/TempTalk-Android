package com.difft.android.base.security

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.difft.android.base.R
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.base.widget.ComposeDialogManager









object SafeLinkOpener {

    










    fun open(context: Context, url: String, onOpen: (() -> Unit)? = null) {
        val verdict = LinkSafetyInspector.inspect(url)
        if (verdict.isSafe) {
            context.openExternalBrowser(url)
            onOpen?.invoke()
            return
        }
        showConfirmDialog(context, url, verdict, onOpen)
    }

    private fun showConfirmDialog(
        context: Context,
        url: String,
        verdict: LinkVerdict,
        onOpen: (() -> Unit)? = null,
    ) {
        ComposeDialogManager.showMessageDialog(
            context = context,
            title = context.getString(R.string.link_safety_warning_title),
            message = context.getString(R.string.link_safety_confirm_message, verdict.safeDisplayUrl),
            confirmText = context.getString(R.string.link_safety_open_anyway),
            cancelText = context.getString(R.string.link_safety_cancel),
            confirmButtonColor = Color(ContextCompat.getColor(context, R.color.t_error)),
            onConfirm = {
                context.openExternalBrowser(url)
                onOpen?.invoke()
            },
        )
    }
}
