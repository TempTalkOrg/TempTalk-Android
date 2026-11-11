package com.difft.android.chat.ui.messageaction

import android.graphics.Point
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.chat.common.TextTruncationUtil
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isLongTextAttachment
import difft.android.messageserialization.model.Mention





















class MessageActionCoordinator(
    private val activity: FragmentActivity,
    private val globalConfigsManager: IGlobalConfigsManager,
    
    private val translateSupported: Boolean,
) {
    
    


    interface ActionListener {
        



        fun onReactionSelected(message: TextChatMessage, emoji: String, isRemove: Boolean)
        fun onMoreEmojiClick(message: TextChatMessage)
        fun onQuote(message: TextChatMessage)
        fun onCopy(message: TextChatMessage, selectedText: String? = null)
        fun onTranslate(message: TextChatMessage, selectedText: String? = null)
        fun onTranslateOff(message: TextChatMessage)
        fun onForward(message: TextChatMessage, selectedText: String? = null)
        fun onSpeechToText(message: TextChatMessage)
        fun onSpeechToTextOff(message: TextChatMessage)
        fun onSave(message: TextChatMessage)
        fun onMultiSelect(message: TextChatMessage)
        fun onSaveToNote(message: TextChatMessage)
        fun onDeleteSaved(message: TextChatMessage)
        fun onRecall(message: TextChatMessage)
        fun onMoreInfo(message: TextChatMessage)
        fun onFavoriteGif(message: TextChatMessage)
        fun onDismiss()
    }
    
    private val configBuilder = MessageActionConfigBuilder(globalConfigsManager, translateSupported)
    private var messageActionPopup: MessageActionPopup? = null
    private var textSelectionManager: TextSelectionManager? = null
    private val selectionSync = SelectionMenuSync({ messageActionPopup }, { textSelectionManager })
    private val listSync = ListScrollSync({ messageActionPopup }, { textSelectionManager }, selectionSync, ::dismiss)
    
    private var currentMessage: TextChatMessage? = null
    private var currentTextView: TextView? = null
    private var currentAnchorView: View? = null
    private var currentContainerView: View? = null
    private var currentConfig: MessageActionConfigBuilder.Config? = null
    private var actionListener: ActionListener? = null
    private var lastSelStart = -1
    private var lastSelEnd = -1
    private var isInitializing = true  
    private var textSelectionEnabled = true  
    
    private var savedRawText: String = ""
    private var savedMentions: List<Mention>? = null
    
    


    fun setActionListener(listener: ActionListener) {
        this.actionListener = listener
    }
    
    













    fun show(
        message: TextChatMessage,
        messageView: View,
        textView: TextView?,
        mostUseEmojis: List<String>?,
        isForForward: Boolean,
        isSaved: Boolean,
        touchPoint: Point,
        containerView: View? = null,
        enableTextSelection: Boolean = true
    ) {
        
        if (isShowing && currentMessage?.id == message.id) {
            return
        }
        
        
        dismiss()
        
        currentMessage = message
        currentTextView = textView
        currentAnchorView = messageView
        currentContainerView = containerView
        textSelectionEnabled = enableTextSelection
        
        
        val forwardsSize = message.forwardContext?.forwards?.size ?: 0
        if (forwardsSize == 1) {
            val forward = message.forwardContext?.forwards?.firstOrNull()
            savedRawText = forward?.text ?: ""
            savedMentions = forward?.mentions
        } else {
            savedRawText = message.message?.toString() ?: ""
            savedMentions = message.mentions
        }
        
        
        val bubbleBounds = Rect()
        messageView.getGlobalVisibleRect(bubbleBounds)
        
        
        val config = configBuilder.build(
            message = message,
            mostUseEmojis = mostUseEmojis,
            isForForward = isForForward,
            isSaved = isSaved,
            anchorBounds = bubbleBounds
        )
        currentConfig = config
        
        
        messageActionPopup = MessageActionPopup(activity).also { popup ->
            
            popup.setSelectionMenuCallbacks(object : MessageActionPopup.SelectionMenuCallbacks {
                override fun onCopy() {
                    
                    
                    
                    
                    
                    val selectedText = resolveSelectionForFullMessage(currentMessage)
                    currentMessage?.let { msg ->
                        actionListener?.onCopy(msg, selectedText)
                    }
                    dismiss()
                }

                override fun onForward() {
                    
                    val selectedText = resolveSelectionForFullMessage(currentMessage)
                    currentMessage?.let { msg ->
                        actionListener?.onForward(msg, selectedText)
                    }
                    dismiss()
                }
                
                override fun onSelectAll() {
                    
                    textSelectionManager?.selectAll()
                    
                }
            })
            
            popup.show(
                anchorView = messageView,
                config = config,
                containerView = containerView,
                callbacks = createPopupCallbacks()
            )
            popup.onGestureEnd = listSync::onGestureEnd
        }
        
        
        isInitializing = true
        if (enableTextSelection && textView != null) {
            setupCustomTextSelection(textView)
        }
        
        
        textView?.postDelayed({
            isInitializing = false
        }, 100) ?: run {
            isInitializing = false
        }
        
        
        (findRecyclerView(messageView) ?: findRecyclerView(containerView))?.let {
            listSync.attach(it, messageView, containerView, textView)
        }
    }

    


    private fun findRecyclerView(view: View?): RecyclerView? {
        var current: View? = view
        while (current != null) {
            if (current is RecyclerView) {
                return current
            }
            val parent = current.parent
            current = if (parent is View) parent else null
        }
        return null
    }

    


    fun dismiss() {
        
        listSync.detach()
        
        messageActionPopup?.dismiss()
        messageActionPopup = null
        
        
        textSelectionManager?.detach()
        textSelectionManager = null
        selectionSync.reset()
        
        
        val textView = currentTextView
        val message = currentMessage
        if (textView != null && message != null && savedRawText.isNotEmpty()) {
            TextTruncationUtil.setupDoubleClickPreview(textView, savedRawText, savedMentions, message)
        }
        
        currentTextView = null
        currentAnchorView = null
        currentMessage = null
        currentConfig = null
        lastSelStart = -1
        lastSelEnd = -1
        isInitializing = true
        savedRawText = ""
        savedMentions = null
    }
    
    


    val isShowing: Boolean
        get() = messageActionPopup?.isShowing == true
    
    


    val message: TextChatMessage?
        get() = currentMessage

    @VisibleForTesting internal val popupForTest: MessageActionPopup? get() = messageActionPopup
    @VisibleForTesting internal val selectionManagerForTest: TextSelectionManager? get() = textSelectionManager

    










    private fun resolveSelectionForFullMessage(message: TextChatMessage?): String? {
        val selectedText = textSelectionManager?.getSelectedText()?.takeIf { it.isNotEmpty() }
        if (selectedText == null) return null
        if (message?.isLongTextAttachment() == true && selectionReachesTextEnd()) {
            return null
        }
        return selectedText
    }

    




    private fun selectionReachesTextEnd(): Boolean {
        val manager = textSelectionManager ?: return false
        val length = currentTextView?.text?.length ?: return false
        return manager.hasSelection() && manager.getSelectionEnd() >= length
    }

    private fun createPopupCallbacks(): MessageActionPopup.Callbacks {
        return object : MessageActionPopup.Callbacks {
            override fun onReactionSelected(emoji: String, isRemove: Boolean) {
                currentMessage?.let { msg ->
                    actionListener?.onReactionSelected(msg, emoji, isRemove)
                }
            }
            
            override fun onMoreEmojiClick() {
                currentMessage?.let { msg ->
                    actionListener?.onMoreEmojiClick(msg)
                }
            }
            
            override fun onActionSelected(action: MessageAction.Type) {
                currentMessage?.let { msg -> actionListener?.let { action.dispatchTo(it, msg) } }
            }
            
            override fun onDismiss() {
                cleanupAfterDismiss()
            }
        }
    }
    
    



    private fun setupCustomTextSelection(textView: TextView) {
        
        textView.post {
            val overlayContainer = messageActionPopup?.getOverlayContainer()
            if (overlayContainer == null) {
                return@post
            }
            
            
            TextSelectionManager(textView).also { textSelectionManager = it }.apply {
                setEnabled(textSelectionEnabled)
                onSelectionChanged = object : TextSelectionManager.SelectionCallback {
                    override fun onSelectionChanged(start: Int, end: Int, isFullSelect: Boolean) {
                        
                        selectionSync.reanchor()
                        handleCustomSelectionChanged(start, end, isFullSelect)
                    }
                }
                handleDragCallback = selectionSync.dragCallback
                
                attachToOverlay(overlayContainer, currentContainerView)
                
                
                selectAll()
            }
        }
    }
    
    





    private fun handleCustomSelectionChanged(selStart: Int, selEnd: Int, isFullSelect: Boolean) {
        
        if (isInitializing) {
            return
        }
        
        val hasSelection = selStart < selEnd
        
        lastSelStart = selStart
        lastSelEnd = selEnd
        
        if (!hasSelection || isFullSelect) {
            
            messageActionPopup?.showFullMenu()
            return
        }
        
        
        messageActionPopup?.showSelectionMenu()
    }
    
    
    private fun cleanupAfterDismiss() {
        listSync.detach()
        
        textSelectionManager?.detach()
        textSelectionManager = null
        selectionSync.reset()
        
        
        
        val textView = currentTextView
        val message = currentMessage
        if (textView != null && message != null && savedRawText.isNotEmpty()) {
            TextTruncationUtil.setupDoubleClickPreview(textView, savedRawText, savedMentions, message)
        }
        
        currentTextView = null
        currentMessage = null
        savedRawText = ""
        savedMentions = null
        actionListener?.onDismiss()
    }
}
