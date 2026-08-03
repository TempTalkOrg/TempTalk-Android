package com.difft.android.base.security

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.difft.android.base.R
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.base.widget.ComposeDialogManager

/**
 * Single choke point for opening user-content-originated external links.
 *
 * Safe links open directly; suspicious ones (see [LinkSafetyInspector]) surface a
 * confirmation dialog showing the real destination (host normalised to ASCII/punycode,
 * deceptive userinfo stripped) so the user can verify it before proceeding. The
 * original URL is still what actually gets opened on confirm.
 */
object SafeLinkOpener {

    /**
     * Opens [url] in the external browser, performing a homograph/phishing check first.
     *
     * - Safe links are opened immediately; [onOpen] is invoked right after.
     * - Suspicious links show a confirmation dialog. [onOpen] is invoked only if the user
     *   confirms, immediately after the browser is launched. If the user cancels, [onOpen]
     *   is NOT invoked.
     *
     * [onOpen] is intended for callers that need to perform cleanup after the browser
     * launches (e.g. finishing a scan Activity). Pass `null` (default) if not needed.
     */
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
