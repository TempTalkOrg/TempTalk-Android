package com.difft.android.chat.ui.messageaction

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.difft.android.chat.R

/**
 * Represents a message action item
 */
data class MessageAction(
    val type: Type,
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    @ColorRes val tintRes: Int = com.difft.android.base.R.color.icon,
    val isDestructive: Boolean = false
) {
    enum class Type {
        // Quick actions
        QUOTE,
        COPY,
        TRANSLATE,
        TRANSLATE_OFF,
        FORWARD,
        
        // More actions
        SPEECH_TO_TEXT,
        SPEECH_TO_TEXT_OFF,
        SAVE,
        MULTISELECT,
        SAVE_TO_NOTE,
        DELETE_SAVED,
        RECALL,
        MORE_INFO,
        
        // Special
        MORE,  // Opens more actions sheet
        SELECT_ALL,  // Text selection: select all text
        
        // Failed message actions
        RESEND,
        DELETE
    }
    
    companion object {
        fun quote() = MessageAction(
            type = Type.QUOTE,
            iconRes = R.drawable.chat_message_action_quote,
            labelRes = R.string.chat_message_action_quote
        )
        
        fun copy() = MessageAction(
            type = Type.COPY,
            iconRes = R.drawable.chat_message_action_copy,
            labelRes = R.string.chat_message_action_copy
        )
        
        fun translate() = MessageAction(
            type = Type.TRANSLATE,
            iconRes = R.drawable.chat_message_action_translate,
            labelRes = R.string.chat_message_action_translate
        )
        
        fun translateOff() = MessageAction(
            type = Type.TRANSLATE_OFF,
            iconRes = R.drawable.chat_message_action_translate,
            labelRes = R.string.chat_message_action_translate_off
        )
        
        fun forward() = MessageAction(
            type = Type.FORWARD,
            iconRes = R.drawable.chat_message_action_forward,
            labelRes = R.string.chat_message_action_forward
        )
        
        fun speechToText() = MessageAction(
            type = Type.SPEECH_TO_TEXT,
            iconRes = R.drawable.chat_message_action_voice2text,
            labelRes = R.string.chat_message_action_voice2text
        )
        
        fun speechToTextOff() = MessageAction(
            type = Type.SPEECH_TO_TEXT_OFF,
            iconRes = R.drawable.chat_message_action_voice2text_off,
            labelRes = R.string.chat_message_action_voice2text_off
        )
        
        fun save(isMediaFile: Boolean) = MessageAction(
            type = Type.SAVE,
            iconRes = R.drawable.chat_message_action_download,
            labelRes = if (isMediaFile) R.string.chat_message_action_save_to_photos else R.string.chat_message_action_download
        )
        
        fun multiSelect() = MessageAction(
            type = Type.MULTISELECT,
            iconRes = R.drawable.chat_message_action_select_multiple,
            labelRes = R.string.chat_message_action_select_multiple
        )
        
        fun saveToNote() = MessageAction(
            type = Type.SAVE_TO_NOTE,
            iconRes = R.drawable.chat_message_action_favorites,
            labelRes = R.string.chat_message_action_save_to_note
        )
        
        fun deleteSaved() = MessageAction(
            type = Type.DELETE_SAVED,
            iconRes = R.drawable.chat_message_action_delete,
            labelRes = R.string.chat_message_action_delete
        )
        
        fun recall() = MessageAction(
            type = Type.RECALL,
            iconRes = R.drawable.chat_message_action_recall,
            labelRes = R.string.chat_message_action_recall,
            tintRes = com.difft.android.base.R.color.error,
            isDestructive = true
        )
        
        fun moreInfo() = MessageAction(
            type = Type.MORE_INFO,
            iconRes = R.drawable.chat_message_action_more,
            labelRes = R.string.chat_message_action_more_info
        )
        
        fun more() = MessageAction(
            type = Type.MORE,
            iconRes = R.drawable.ic_message_action_more_dots,
            labelRes = R.string.chat_message_action_more
        )
        
        fun selectAll() = MessageAction(
            type = Type.SELECT_ALL,
            iconRes = R.drawable.chat_message_action_select_all,
            labelRes = R.string.chat_message_action_select_all
        )
        
        /** For text preview selection menu */
        fun translateAction() = MessageAction(
            type = Type.TRANSLATE,
            iconRes = R.drawable.chat_message_action_translate,
            labelRes = R.string.chat_message_action_translate
        )
        
        fun resend() = MessageAction(
            type = Type.RESEND,
            iconRes = R.drawable.chat_message_action_resend,
            labelRes = R.string.chat_message_action_resend
        )
        
        fun delete() = MessageAction(
            type = Type.DELETE,
            iconRes = R.drawable.chat_message_action_delete,
            labelRes = R.string.chat_message_action_delete,
            tintRes = com.difft.android.base.R.color.error,
            isDestructive = true
        )
    }
}
