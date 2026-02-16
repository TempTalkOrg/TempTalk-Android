package com.difft.android.chat.common

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.AppScheme
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.contacts.contactsdetail.ContactDetailBottomSheetDialogFragment
import com.difft.android.chat.ui.ChatMessageContainerView
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.Mention
import java.util.regex.Pattern

@SuppressLint("ClickableViewAccessibility")
object LinkTextUtils {

    fun setMarkdownToTextview(context: Context, text: String, textView: TextView?, mentions: List<Mention>? = null) {
        val spannableString = SpannableString(text)

        // 定义需要匹配的前缀数组
        val prefixes = (listOf("http", "https") + AppScheme.allSchemes).map { "$it://" }

//        // 记录手动识别的URL位置，避免与Linkify冲突
//        val manualUrlRanges = mutableListOf<Pair<Int, Int>>()

        // 处理URL链接
        prefixes.forEach { prefix ->
            var start = 0
            while (start < text.length && text.indexOf(prefix, start) >= 0) {
                val startIndex = text.indexOf(prefix, start)

                // 使用更精确的URL边界检测
                val endIndex = findUrlEndIndex(text, startIndex)

                if (endIndex > startIndex) {
                    val fullLink = text.substring(startIndex, endIndex)

                    // 验证提取的链接是否有效
                    if (isValidUrl(fullLink)) {
                        val clickableSpan = object : ClickableSpan() {
                            override fun onClick(view: View) {
                                handleUrlClick(view.context, fullLink)
                            }

                            override fun updateDrawState(ds: TextPaint) {
                                ds.color = ContextCompat.getColor(context, R.color.t_info)
                                ds.isUnderlineText = false
                            }
                        }
                        spannableString.setSpan(clickableSpan, startIndex, endIndex, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

//                        // 记录手动识别的URL范围
//                        manualUrlRanges.add(Pair(startIndex, endIndex))
                    }
                }

                start = endIndex
            }
        }

        // 处理邮箱链接
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
                    ds.color = ContextCompat.getColor(context, R.color.t_info)
                    ds.isUnderlineText = false
                }
            }
            spannableString.setSpan(clickableSpan, startIndex, endIndex, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
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
                    ds.color = ContextCompat.getColor(context, R.color.t_info)
                    ds.isUnderlineText = false
                }
            }
            val start = mention.start
            val end = start + mention.length
            if (start <= spannableString.length && end <= spannableString.length && start < end) {
                spannableString.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

//        // 使用Linkify识别其他URL（如www.baidu.com）
//        Linkify.addLinks(spannableString, Linkify.WEB_URLS)
//
//        // 处理Linkify添加的URLSpan
//        val urlSpans = spannableString.getSpans(0, text.length, URLSpan::class.java)
//        urlSpans.forEach { urlSpan ->
//            val start = spannableString.getSpanStart(urlSpan)
//            val end = spannableString.getSpanEnd(urlSpan)
//
//            // 检查是否与手动识别的URL重叠
//            val isOverlapping = manualUrlRanges.any { (manualStart, manualEnd) ->
//                start < manualEnd && end > manualStart
//            }
//
//            // 如果不重叠，才处理这个URLSpan
//            if (!isOverlapping) {
//                spannableString.removeSpan(urlSpan)
//                spannableString.setSpan(object : URLSpan(urlSpan.url) {
//                    override fun onClick(widget: View) {
//                        handleUrlClick(context, url)
//                    }
//
//                    override fun updateDrawState(ds: TextPaint) {
//                        ds.color = ContextCompat.getColor(context, R.color.t_info)
//                        ds.isUnderlineText = false
//                    }
//                }, start, end, 0)
//            } else {
//                // 如果重叠，直接移除Linkify添加的URLSpan
//                spannableString.removeSpan(urlSpan)
//            }
//        }

        textView?.text = spannableString
        textView?.movementMethod = LinkMovementMethod.getInstance()
    }

    /**
     * 查找URL的结束位置
     */
    private fun findUrlEndIndex(text: String, startIndex: Int): Int {
        var endIndex = startIndex

        // 从startIndex开始，逐个字符检查
        for (i in startIndex until text.length) {
            val char = text[i]

            // 检查是否是URL结束的字符
            if (isUrlEndChar(char)) {
                endIndex = i
                break
            }
        }

        // 如果没有找到结束位置，使用文本长度
        if (endIndex == startIndex) {
            endIndex = text.length
        }

        // 去除末尾的常见标点符号（这些通常是句子的标点，而不是URL的一部分）
        while (endIndex > startIndex && text[endIndex - 1] in ".,;:!?)]}") {
            endIndex--
        }

        return endIndex
    }

    /**
     * 判断字符是否是URL的结束字符
     */
    private fun isUrlEndChar(char: Char): Boolean {
        // 定义URL中允许的字符
        val urlChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~:/?#[]@!$&'()*+,;=%"

        // 如果字符在URL允许字符中，不是结束字符
        if (urlChars.contains(char)) {
            return false
        }

        // 其他不允许的字符都是URL结束字符
        return true
    }

    /**
     * 验证URL是否有效
     */
    private fun isValidUrl(url: String): Boolean {
        return try {
            val uri = url.toUri()
            // 检查scheme和host都存在
            if (uri.scheme == null || uri.host == null) {
                return false
            }

            // 对于http/https链接，检查host格式是否有效
            if (url.startsWith("http")) {
                val host = uri.host ?: return false

                // 检查host格式是否符合域名规范
                // 1. 不能为空
                if (host.isEmpty()) {
                    return false
                }

                // 2. 不能以点开头或结尾
                if (host.startsWith(".") || host.endsWith(".")) {
                    return false
                }

                // 3. 必须包含至少一个点（顶级域名）
                if (!host.contains(".")) {
                    return false
                }

                // 4. 检查域名格式：字母数字连字符，以字母数字结尾
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

    /**
     * Handle URL click event.
     */
    private fun handleUrlClick(context: Context, url: String) {
        val uri = url.toUri()
        val scheme = uri.scheme
        
        when {
            // Internal scheme (chative://) - route to MainActivity
            scheme != null && AppScheme.allSchemes.contains(scheme) -> {
                val activityProvider = com.difft.android.base.utils.globalServices.activityProvider
                val intent = android.content.Intent(context, activityProvider.getActivityClass(com.difft.android.base.activity.ActivityType.MAIN))
                intent.data = uri
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            // External http/https links
            scheme == "http" || scheme == "https" -> {
                context.openExternalBrowser(url)
            }
        }
    }

    /**
     * Show contact detail in popup mode.
     * Try to get FragmentActivity from context and show BottomSheet dialog.
     * Falls back to starting ContactDetailActivity if FragmentActivity is not available.
     */
    private fun showContactDetailPopup(context: Context, contactId: String) {
        val fragmentActivity = getFragmentActivity(context)
        if (fragmentActivity != null) {
            ContactDetailBottomSheetDialogFragment.show(fragmentActivity, contactId)
        } else {
            // Fallback to Activity mode if FragmentActivity is not available
            ContactDetailActivity.startActivity(context, contactId)
        }
    }

    /**
     * Try to get FragmentActivity from Context.
     */
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

        // 递归查找父 View
        if (view.parent is View) {
            return findParentChatMessageItemView(view.parent as View)
        }

        return null
    }

}