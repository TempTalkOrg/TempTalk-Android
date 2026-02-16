package com.difft.android.chat.ui.messageaction

import android.graphics.Rect
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.canDownloadFile
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.chat.message.isLongTextAttachment
import difft.android.messageserialization.model.SpeechToTextStatus
import difft.android.messageserialization.model.TranslateStatus
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isVideo
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

/**
 * Builds MessageActionPopup configuration based on message conditions
 */
class MessageActionConfigBuilder(
    private val globalConfigsManager: IGlobalConfigsManager
) {
    
    /**
     * Configuration for MessageActionPopup
     */
    data class Config(
        val message: TextChatMessage,
        val reactions: List<String>,
        val selectedEmojis: Set<String>,  // Current user's selected emojis (supports multiple)
        val showReactionBar: Boolean,
        val quickActions: List<MessageAction>,
        val moreActions: List<MessageAction>,
        val allActions: List<MessageAction>,  // All actions for "More" sheet (without MORE button)
        val anchorBounds: Rect,
        val isOutgoing: Boolean
    )
    
    /**
     * Build configuration for the message action popup
     * 
     * @param message The message to show actions for
     * @param mostUseEmojis List of most used emojis for reactions
     * @param isForForward Whether in forward selection mode
     * @param isSaved Whether the message is saved/favorited
     * @param anchorBounds The bounds of the message bubble on screen
     */
    fun build(
        message: TextChatMessage,
        mostUseEmojis: List<String>?,
        isForForward: Boolean,
        isSaved: Boolean,
        anchorBounds: Rect
    ): Config {
        val allActions = buildAllActions(message, isForForward, isSaved)
        
        // Split into quick actions (first 4) and more actions (rest)
        val quickActions: List<MessageAction>
        val moreActions: List<MessageAction>
        
        if (isForForward) {
            // In forward mode, show all actions as quick actions (max 3)
            quickActions = allActions.take(3)
            moreActions = emptyList()
        } else {
            // Normal mode: first 4 as quick, rest in More
            quickActions = if (allActions.size > 4) {
                allActions.take(4) + MessageAction.more()
            } else {
                allActions
            }
            moreActions = if (allActions.size > 4) {
                allActions.drop(4)
            } else {
                emptyList()
            }
        }
        
        val showReactionBar = shouldShowReactionBar(message, mostUseEmojis)
        val reactions = if (showReactionBar) {
            mostUseEmojis?.take(5) ?: emptyList()
        } else {
            emptyList()
        }
        
        // Find all emojis current user has reacted with (supports multiple reactions)
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
            quickActions = quickActions,
            moreActions = moreActions,
            allActions = allActions,  // All actions for "More" sheet
            anchorBounds = anchorBounds,
            isOutgoing = message.isMine
        )
    }
    
    /**
     * Build all available actions based on message and mode
     */
    private fun buildAllActions(
        message: TextChatMessage,
        isForForward: Boolean,
        isSaved: Boolean
    ): List<MessageAction> {
        val actions = mutableListOf<MessageAction>()
        
        if (isForForward) {
            // Forward selection mode: limited actions
            buildForwardModeActions(message, actions)
        } else {
            // Normal mode: full action set
            buildNormalModeActions(message, isSaved, actions)
        }
        
        return actions
    }
    
    /**
     * Build actions for forward selection mode
     */
    private fun buildForwardModeActions(
        message: TextChatMessage,
        actions: MutableList<MessageAction>
    ) {
        // Copy - for text, long text attachment, or file
        if (hasTextContent(message) || message.canDownloadFile() || message.isLongTextAttachment()) {
            actions.add(MessageAction.copy())
        }
        
        // Forward - for non-audio messages
        if (message.attachment?.isAudioMessage() != true) {
            actions.add(MessageAction.forward())
        }
        
        // Save - for downloadable files
        if (message.canDownloadFile()) {
            val isMediaFile = isMediaFile(message)
            actions.add(MessageAction.save(isMediaFile))
        }
    }
    
    /**
     * Build actions for normal mode
     */
    private fun buildNormalModeActions(
        message: TextChatMessage,
        isSaved: Boolean,
        actions: MutableList<MessageAction>
    ) {
        val isConfidential = message.mode == SignalServiceProtos.Mode.CONFIDENTIAL_VALUE
        
        if (!isConfidential) {
            // Quote
            actions.add(MessageAction.quote())
            
            // Copy
            if (hasTextContent(message) || message.canDownloadFile() || message.isLongTextAttachment()) {
                actions.add(MessageAction.copy())
            }
            
            // Translate
            if (hasTextContent(message)) {
                if (message.translateData == null || message.translateData?.translateStatus == TranslateStatus.Invisible) {
                    actions.add(MessageAction.translate())
                } else {
                    actions.add(MessageAction.translateOff())
                }
            }
            
            // Forward - for non-audio messages
            if (message.attachment?.isAudioMessage() != true) {
                actions.add(MessageAction.forward())
            }
            
            // Speech to Text - for audio messages
            if (message.attachment?.isAudioMessage() == true) {
                if (message.speechToTextData == null || message.speechToTextData?.convertStatus != SpeechToTextStatus.Show) {
                    actions.add(MessageAction.speechToText())
                } else {
                    actions.add(MessageAction.speechToTextOff())
                }
            }
            
            // Save/Download - for downloadable files
            if (message.canDownloadFile()) {
                val isMediaFile = isMediaFile(message)
                actions.add(MessageAction.save(isMediaFile))
            }
            
            // Multi-select - for non-audio messages
            if (message.attachment?.isAudioMessage() != true && message.attachment?.isAudioFile() != true) {
                actions.add(MessageAction.multiSelect())
            }
            
            // Save to Note
            actions.add(MessageAction.saveToNote())
        }
        
        // Delete Saved - for saved messages
        if (isSaved) {
            actions.add(MessageAction.deleteSaved())
        }
        
        // Recall - for own messages within timeout
        if (message.isMine && isWithinRecallTimeout(message)) {
            actions.add(MessageAction.recall())
        }
        
        // More Info - always show
        actions.add(MessageAction.moreInfo())
    }
    
    /**
     * Check if reaction bar should be shown
     */
    private fun shouldShowReactionBar(
        message: TextChatMessage,
        mostUseEmojis: List<String>?
    ): Boolean {
        return !mostUseEmojis.isNullOrEmpty()
            && message.sharedContacts.isNullOrEmpty()
            && message.attachment?.isAudioMessage() != true
            && message.attachment?.isAudioFile() != true
            && message.mode != SignalServiceProtos.Mode.CONFIDENTIAL_VALUE
    }
    
    /**
     * Check if message has text content (not attachment-only)
     */
    private fun hasTextContent(message: TextChatMessage): Boolean {
        if (message.isAttachmentMessage()) {
            return false
        }
        
        message.forwardContext?.forwards?.let { forwards ->
            if (forwards.size == 1) {
                val forward = forwards.firstOrNull()
                if (!forward?.card?.content.isNullOrEmpty() || !forward?.text.isNullOrEmpty()) {
                    return true
                }
            }
        } ?: run {
            if (!message.card?.content.isNullOrEmpty() || !message.message.isNullOrEmpty()) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * Check if message is within recall timeout
     */
    private fun isWithinRecallTimeout(message: TextChatMessage): Boolean {
        val recallTimeoutInterval = (globalConfigsManager.getNewGlobalConfigs()?.data?.recall?.timeoutInterval ?: (24 * 60 * 60)) * 1000L
        return System.currentTimeMillis() - message.systemShowTimestamp <= recallTimeoutInterval
    }
    
    /**
     * Check if the downloadable file is a media file (image/video)
     */
    private fun isMediaFile(message: TextChatMessage): Boolean {
        val attachment = message.attachment 
            ?: message.forwardContext?.forwards?.firstOrNull()?.attachments?.firstOrNull()
        return attachment != null && (attachment.isImage() || attachment.isVideo())
    }
}
