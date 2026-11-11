package com.difft.android.chat.translate.ui

import android.widget.ProgressBar
import androidx.appcompat.widget.AppCompatTextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.difft.android.chat.R
import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.TranslateStatus












internal object TranslateBlockBinder {

    data class Views(
        val container: ConstraintLayout,
        val progress: ProgressBar,
        val content: AppCompatTextView,
        val attribution: AppCompatTextView,
        val error: AppCompatTextView,
    )

    



    fun bind(
        views: Views,
        message: TextChatMessage,
        onRetry: (TextChatMessage) -> Unit,
        showContent: (AppCompatTextView, String, TextChatMessage) -> Unit,
    ) {
        val data = message.translateData
        
        views.container.setOnClickListener(null)
        views.container.isClickable = false

        if (data == null) {
            views.container.isVisible = false
            return
        }

        when (data.translateStatus) {
            TranslateStatus.Translating -> {
                showBlock(views, progress = true, content = true, attribution = false, error = false)
                views.content.setOnLongClickListener(null)
                views.content.text = views.content.context.getString(R.string.chat_translating)
            }

            TranslateStatus.ShowCN, TranslateStatus.ShowEN -> {
                val text = if (data.translateStatus == TranslateStatus.ShowCN) {
                    data.translatedContentCN
                } else {
                    data.translatedContentEN
                }
                if (text.isNullOrEmpty()) {
                    views.container.isVisible = false
                    return
                }
                showBlock(views, progress = false, content = true, attribution = true, error = false)
                showContent(views.content, text, message)
            }

            TranslateStatus.Failed -> {
                showBlock(views, progress = false, content = false, attribution = false, error = true)
                views.error.setText(R.string.chat_translate_failed_retry)
                views.container.setOnClickListener { onRetry(message) }
            }

            TranslateStatus.FailedModel -> {
                showBlock(views, progress = false, content = false, attribution = false, error = true)
                views.error.setText(R.string.chat_translate_model_failed)
                views.container.setOnClickListener { onRetry(message) }
            }

            TranslateStatus.FailedTooLong -> {
                showBlock(views, progress = false, content = false, attribution = false, error = true)
                views.error.setText(R.string.chat_translate_too_long)
            }

            TranslateStatus.Invisible, TranslateStatus.SameLanguage -> {
                views.container.isVisible = false
            }
        }
    }

    private fun showBlock(
        views: Views,
        progress: Boolean,
        content: Boolean,
        attribution: Boolean,
        error: Boolean,
    ) {
        views.container.isVisible = true
        views.progress.isVisible = progress
        views.content.isVisible = content
        views.attribution.isVisible = attribution
        views.error.isVisible = error
    }
}
