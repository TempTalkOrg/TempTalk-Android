package com.difft.android.chat.common

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.MetricAffectingSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.security.SafeLinkOpener
import com.difft.android.base.utils.AppScheme
import com.difft.android.base.utils.normalizeNewlinesForDisplay
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.contacts.contactsdetail.ContactDetailBottomSheetDialogFragment
import com.difft.android.chat.ui.ChatMessageContainerView
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.Mention
import java.util.regex.Pattern

@SuppressLint("ClickableViewAccessibility")
object LinkTextUtils {

    fun setMarkdownToTextview(
        context: Context,
        text: String,
        textView: TextView?,
        mentions: List<Mention>? = null,
        linkColor: Int = ContextCompat.getColor(context, R.color.t_info),
        linkMedium: Boolean = false,
    ) {
        
        
        val text = text.normalizeNewlinesForDisplay()
        val spannableString = SpannableString(text)

        val prefixes = (listOf("http", "https") + AppScheme.allSchemes).map { "$it://" }


        prefixes.forEach { prefix ->
            var start = 0
            while (start < text.length && text.indexOf(prefix, start) >= 0) {
                val startIndex = text.indexOf(prefix, start)

                val endIndex = findUrlEndIndex(text, startIndex)

                if (endIndex > startIndex) {
                    val fullLink = text.substring(startIndex, endIndex)

                    if (isValidUrl(fullLink)) {
                        val clickableSpan = object : ClickableSpan() {
                            override fun onClick(view: View) {
                                handleUrlClick(view.context, fullLink)
                            }

                            override fun updateDrawState(ds: TextPaint) {
                                styleLink(ds, linkColor)
                            }
                        }
                        spannableString.setSpan(clickableSpan, startIndex, endIndex, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                        if (linkMedium) spannableString.setSpan(MediumWeightSpan(), startIndex, endIndex, Spanned.SPAN_INCLUSIVE_INCLUSIVE)


                    }
                }

                start = endIndex
            }
        }

        val emailPattern = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        val emailMatcher = emailPattern.matcher(text)
        while (emailMatcher.find()) {
            val startIndex = emailMatcher.start()
            val endIndex = emailMatcher.end()
            val email = text.substring(startIndex, endIndex)

            val clickableSpan = object : ClickableSpan() {
                override fun onClick(view: View) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO)
                    intent.data = "mailto:$email".toUri()
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        L.e { "Error sending email: ${e.stackTraceToString()}" }
                    }
                }

                override fun updateDrawState(ds: TextPaint) {
                    styleLink(ds, linkColor)
                }
            }
            spannableString.setSpan(clickableSpan, startIndex, endIndex, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            if (linkMedium) spannableString.setSpan(MediumWeightSpan(), startIndex, endIndex, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }

        mentions?.forEach { mention ->
            val clickableSpan: ClickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val uid = mention.uid
                    if (!uid.isNullOrEmpty() && uid != MENTIONS_ALL_ID) {
                        showContactDetailPopup(context, uid)
                    }
                }

                override fun updateDrawState(ds: TextPaint) {
                    styleLink(ds, linkColor)
                }
            }
            val start = mention.start
            val end = start + mention.length
            if (start <= spannableString.length && end <= spannableString.length && start < end) {
                spannableString.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (linkMedium) spannableString.setSpan(MediumWeightSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }


        textView?.text = spannableString
        textView?.movementMethod = LinkMovementMethod.getInstance()
    }

    
    private fun styleLink(ds: TextPaint, color: Int) {
        ds.color = color
        ds.isUnderlineText = false
    }

    



    private class MediumWeightSpan : MetricAffectingSpan() {
        override fun updateMeasureState(paint: TextPaint) = apply(paint)

        override fun updateDrawState(paint: TextPaint) = apply(paint)

        private fun apply(paint: TextPaint) {
            paint.typeface = medium(paint.typeface)
        }

        private fun medium(base: Typeface?): Typeface =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                
                Typeface.create(base ?: Typeface.DEFAULT, maxOf(MEDIUM_WEIGHT, base?.weight ?: 0), base?.isItalic == true)
            } else {
                if (base?.isBold == true) base else Typeface.create("sans-serif-medium", if (base?.isItalic == true) Typeface.ITALIC else Typeface.NORMAL)
            }
    }

    private const val MEDIUM_WEIGHT = 500

    private fun findUrlEndIndex(text: String, startIndex: Int): Int {
        var endIndex = startIndex

        for (i in startIndex until text.length) {
            val char = text[i]

            if (isUrlEndChar(char)) {
                endIndex = i
                break
            }
        }

        if (endIndex == startIndex) {
            endIndex = text.length
        }

        while (endIndex > startIndex && text[endIndex - 1] in ".,;:!?)]}") {
            endIndex--
        }

        return endIndex
    }

    private fun isUrlEndChar(char: Char): Boolean {
        val urlChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~:/?#[]@!$&'()*+,;=%"

        if (urlChars.contains(char)) {
            return false
        }

        return true
    }

    private fun isValidUrl(url: String): Boolean {
        return try {
            val uri = url.toUri()
            if (uri.scheme == null || uri.host == null) {
                return false
            }

            if (url.startsWith("http")) {
                val host = uri.host ?: return false

                if (host.isEmpty()) {
                    return false
                }

                if (host.startsWith(".") || host.endsWith(".")) {
                    return false
                }

                if (!host.contains(".")) {
                    return false
                }

                val domainPattern = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*$")
                if (!domainPattern.matcher(host).matches()) {
                    return false
                }
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    


    private fun handleUrlClick(context: Context, url: String) {
        val uri = url.toUri()
        val scheme = uri.scheme
        
        when {
            
            scheme != null && AppScheme.allSchemes.contains(scheme) -> {
                val activityProvider = com.difft.android.base.utils.globalServices.activityProvider
                val intent = android.content.Intent(context, activityProvider.getActivityClass(com.difft.android.base.activity.ActivityType.MAIN))
                intent.data = uri
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            
            scheme == "http" || scheme == "https" -> {
                SafeLinkOpener.open(context, url)
            }
        }
    }

    




    private fun showContactDetailPopup(context: Context, contactId: String) {
        val fragmentActivity = getFragmentActivity(context)
        if (fragmentActivity != null) {
            
            val overConversation = (fragmentActivity as? ConversationNavigationCallback)?.currentConversationOnScreen()
            ContactDetailBottomSheetDialogFragment.show(fragmentActivity, contactId, overConversation = overConversation)
        } else {
            
            ContactDetailActivity.startActivity(context, contactId)
        }
    }

    


    private fun getFragmentActivity(context: Context): FragmentActivity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is FragmentActivity) {
                return ctx
            }
            ctx = ctx.baseContext
        }
        return null
    }

    fun findParentChatMessageItemView(view: View?): ChatMessageContainerView? {
        if (view == null) {
            return null
        }

        if (view is ChatMessageContainerView) {
            return view
        }

        if (view.parent is View) {
            return findParentChatMessageItemView(view.parent as View)
        }

        return null
    }

}