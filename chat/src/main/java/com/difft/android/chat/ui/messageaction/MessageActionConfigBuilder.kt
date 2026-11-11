package com.difft.android.chat.ui.messageaction

import android.graphics.Rect
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.canDownloadFile
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.chat.message.isLongTextAttachment
import com.difft.android.chat.message.singleForwardableAttachment
import difft.android.messageserialization.model.SpeechToTextStatus
import difft.android.messageserialization.model.TranslateStatus
import difft.android.messageserialization.model.isAnimatedImage
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isPlayableVideo
import org.whispersystems.signalservice.internal.push.SignalServiceProtos




class MessageActionConfigBuilder(
    private val globalConfigsManager: IGlobalConfigsManager,
    
    private val translateSupported: Boolean,
) {

    


    data class Config(
        val message: TextChatMessage,
        val reactions: List<String>,
        val selectedEmojis: Set<String>,  
        val showReactionBar: Boolean,
        val quickActions: List<MessageAction>,
        val moreActions: List<MessageAction>,
        val allActions: List<MessageAction>,  
        val anchorBounds: Rect,
        val isOutgoing: Boolean
    )

    








    fun build(
        message: TextChatMessage,
        mostUseEmojis: List<String>?,
        isForForward: Boolean,
        isSaved: Boolean,
        anchorBounds: Rect
    ): Config {
        val allActions = buildAllActions(message, isForForward, isSaved)

        val showReactionBar = shouldShowReactionBar(message, mostUseEmojis, isSaved)
        val reactions = if (showReactionBar) {
            mostUseEmojis?.take(5) ?: emptyList()
        } else {
            emptyList()
        }

        
        val myId = globalServices.myId
        val selectedEmojis = message.reactions
            ?.filter { it.uid == myId }
            ?.mapNotNull { it.emoji }
            ?.toSet()
            ?: emptySet()

        return Config(
            message = message,
            reactions = reactions,
            selectedEmojis = selectedEmojis,
            showReactionBar = showReactionBar,
            quickActions = allActions,   
            moreActions = emptyList(),   
            allActions = allActions,
            anchorBounds = anchorBounds,
            isOutgoing = message.isMine
        )
    }

    


    private fun buildAllActions(
        message: TextChatMessage,
        isForForward: Boolean,
        isSaved: Boolean
    ): List<MessageAction> {
        val actions = mutableListOf<MessageAction>()

        if (isForForward) {
            
            buildForwardModeActions(message, actions)
        } else {
            
            buildNormalModeActions(message, isSaved, actions)
        }

        return actions
    }

    


    private fun buildForwardModeActions(
        message: TextChatMessage,
        actions: MutableList<MessageAction>
    ) {
        
        if (hasTextContent(message) || message.canDownloadFile() || message.isLongTextAttachment()) {
            actions.add(MessageAction.copy())
        }

        
        if (message.attachment?.isAudioMessage() != true) {
            actions.add(MessageAction.forward())
        }

        
        if (message.canDownloadFile()) {
            val isMediaFile = isMediaFile(message)
            actions.add(MessageAction.save(isMediaFile))
        }

        
        if (isAnimatedImageMessage(message)) {
            actions.add(MessageAction.favoriteGif())
        }
    }

    


    private fun buildNormalModeActions(
        message: TextChatMessage,
        isSaved: Boolean,
        actions: MutableList<MessageAction>
    ) {
        val isConfidential = message.mode == SignalServiceProtos.Mode.CONFIDENTIAL_VALUE

        if (!isConfidential) {
            
            actions.add(MessageAction.quote())

            
            if (hasTextContent(message) || message.canDownloadFile() || message.isLongTextAttachment()) {
                actions.add(MessageAction.copy())
            }

            
            if (message.attachment?.isAudioMessage() != true) {
                actions.add(MessageAction.forward())
            }

            
            
            if (message.attachment?.isAudioMessage() != true) {
                actions.add(MessageAction.multiSelect())
            }

            
            if (translateSupported && hasTextContent(message)) {
                
                
                
                val translateStatus = message.translateData?.translateStatus
                val blockOnScreen = translateStatus != null &&
                    translateStatus != TranslateStatus.Invisible &&
                    translateStatus != TranslateStatus.SameLanguage
                if (blockOnScreen) {
                    actions.add(MessageAction.translateOff())
                } else {
                    actions.add(MessageAction.translate())
                }
            }

            
            if (message.attachment?.isAudioMessage() == true) {
                if (message.speechToTextData == null || message.speechToTextData?.convertStatus != SpeechToTextStatus.Show) {
                    actions.add(MessageAction.speechToText())
                } else {
                    actions.add(MessageAction.speechToTextOff())
                }
            }

            
            if (message.canDownloadFile()) {
                val isMediaFile = isMediaFile(message)
                actions.add(MessageAction.save(isMediaFile))
            }

            
            if (isAnimatedImageMessage(message)) {
                actions.add(MessageAction.favoriteGif())
            }

            
            if (!isSaved) {
                actions.add(MessageAction.saveToNote())
            }
        }

        
        if (message.isMine && isWithinRecallTimeout(message)) {
            actions.add(MessageAction.recall())
        }

        
        if (isSaved) {
            actions.add(MessageAction.deleteSaved())
        }

        
        actions.add(MessageAction.moreInfo())
    }

    


    private fun shouldShowReactionBar(
        message: TextChatMessage,
        mostUseEmojis: List<String>?,
        isSaved: Boolean
    ): Boolean {
        return !mostUseEmojis.isNullOrEmpty()
            && !isSaved
            && message.sharedContacts.isNullOrEmpty()
            && message.attachment?.isAudioMessage() != true
            && message.attachment?.isAudioFile() != true
            && message.mode != SignalServiceProtos.Mode.CONFIDENTIAL_VALUE
    }

    


    private fun hasTextContent(message: TextChatMessage): Boolean {
        if (message.isAttachmentMessage()) {
            return false
        }

        message.forwardContext?.forwards?.let { forwards ->
            if (forwards.size == 1) {
                val forward = forwards.firstOrNull()
                if (!forward?.text.isNullOrEmpty()) {
                    return true
                }
            }
        } ?: run {
            if (!message.message.isNullOrEmpty()) {
                return true
            }
        }

        return false
    }

    


    private fun isWithinRecallTimeout(message: TextChatMessage): Boolean {
        val recallTimeoutInterval = (globalConfigsManager.getNewGlobalConfigs()?.data?.recall?.timeoutInterval ?: (24 * 60 * 60)) * 1000L
        return ServerTimeProvider.nowMillis() - message.systemShowTimestamp <= recallTimeoutInterval
    }

    




    private fun isAnimatedImageMessage(message: TextChatMessage): Boolean =
        message.singleForwardableAttachment()?.isAnimatedImage() == true

    


    private fun isMediaFile(message: TextChatMessage): Boolean {
        val attachment = message.attachment
            ?: message.forwardContext?.forwards?.firstOrNull()?.attachments?.firstOrNull()
        return attachment != null && (attachment.isImage() || attachment.isPlayableVideo())
    }
}
