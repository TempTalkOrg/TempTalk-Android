package com.difft.android.chat.widget

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.util.ServiceUtil

class RichContentEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private var onFilePasteListener: ((Uri, String) -> Unit)? = null
    private var onStickerCommitListener: ((InputContentInfoCompat, String) -> Unit)? = null

    fun setOnFilePasteListener(listener: (Uri, String) -> Unit) {
        onFilePasteListener = listener
    }

    /**
     * Stickers/GIFs/images committed by the IME (Gboard, Sogou, etc.) via the Commit Content API.
     * The listener receives the [InputContentInfoCompat] and is responsible for calling
     * [InputContentInfoCompat.releasePermission] once it finishes reading the content URI.
     */
    fun setOnStickerCommitListener(listener: (InputContentInfoCompat, String) -> Unit) {
        onStickerCommitListener = listener
    }

    // Accept rich content from any keyboard so sticker/GIF/image taps deliver a content URI.
    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(editorInfo) ?: return null
        EditorInfoCompat.setContentMimeTypes(
            editorInfo,
            arrayOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/*")
        )
        val callback = InputConnectionCompat.OnCommitContentListener { info, flags, _ ->
            val listener = onStickerCommitListener ?: return@OnCommitContentListener false
            if (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
                try {
                    info.requestPermission()
                } catch (e: Exception) {
                    L.w { "[RichContentEditText] commit content requestPermission failed: ${e.stackTraceToString()}" }
                    return@OnCommitContentListener false
                }
            }
            val mimeType = info.description.getMimeType(0) ?: "image/*"
            listener.invoke(info, mimeType)
            true
        }
        return InputConnectionCompat.createWrapper(ic, editorInfo, callback)
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
