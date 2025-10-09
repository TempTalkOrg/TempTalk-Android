package com.difft.android.chat.widget

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText
import com.difft.android.base.utils.FileUtil
import org.thoughtcrime.securesms.util.ServiceUtil

class PasteAwareEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private var onFilePasteListener: ((Uri, String) -> Unit)? = null

    fun setOnFilePasteListener(listener: (Uri, String) -> Unit) {
        onFilePasteListener = listener
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        return when (id) {
            android.R.id.paste -> {
                handlePaste()
            }

            else -> super.onTextContextMenuItem(id)
        }
    }

    private fun handlePaste(): Boolean {
        val clipboard = ServiceUtil.getClipboardManager(context)
        if (!clipboard.hasPrimaryClip()) return super.onTextContextMenuItem(android.R.id.paste)

        val clipData = clipboard.primaryClip
        if (clipData == null || clipData.itemCount == 0) return super.onTextContextMenuItem(android.R.id.paste)

        val item = clipData.getItemAt(0)

        // Check if it's a file URI
        val uri = item.uri
        if (uri != null) {
            // Handle file paste
            val mimeType = FileUtil.getMimeTypeType(uri) ?: "application/octet-stream"
            onFilePasteListener?.invoke(uri, mimeType)
            // 隐藏系统粘贴菜单 - 通过清除焦点来隐藏上下文菜单
            clearFocus()
            return true
        } else {
            // Handle text paste normally - let the system handle it
            return super.onTextContextMenuItem(android.R.id.paste)
        }
    }
}
