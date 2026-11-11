package com.difft.android.chat.ui.textpreview

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import com.difft.android.base.BaseActivity
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.R
import com.difft.android.chat.message.NoticeAggregator
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.databinding.ActivityTextPreviewBinding
import com.difft.android.chat.translate.backend.TranslateBackend
import com.difft.android.chat.ui.SelectChatsUtils
import com.difft.android.chat.ui.messageaction.TextSelectionManager
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Mention
import com.difft.android.chat.util.Util
import javax.inject.Inject
import kotlin.math.abs





@AndroidEntryPoint
class TextPreviewActivity : BaseActivity() {

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils

    @Inject
    lateinit var translateBackend: TranslateBackend

    @Inject
    lateinit var activityNoticeDispatcher: com.difft.android.chat.message.ActivityNoticeDispatcher

    private val binding: ActivityTextPreviewBinding by viewbind()
    private var fullText: String = ""
    private var mentions: List<Mention>? = null
    private var forwardContext: ForwardContext? = null

    
    private var sourceAuthorId: String? = null
    private var sourceConversation: difft.android.messageserialization.For? = null

    
    
    private var sourceCombinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN

    
    
    
    private var sourceCarriesForeign: Boolean = false

    
    private var textSelectionManager: TextSelectionManager? = null
    private var selectionPopup: TextPreviewSelectionPopup? = null
    private var overlayContainer: FrameLayout? = null

    
    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private var isLongPressTriggered = false
    private var isDraggingSelection = false
    
    
    private var touchStartedOnCloseButton = false
    private var selectionAnchor = 0  
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    private val clickTimeout = 200L

    
    private var autoScrollRunnable: Runnable? = null
    private val autoScrollSpeed = 15  
    private val edgeThreshold = 100  
    private var currentDragHandleY = 0f  
    private var currentDragIsStart = false  

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fullText = intent.getStringExtra(EXTRA_TEXT) ?: ""
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        mentions = intent.getSerializableExtra(EXTRA_MENTIONS) as? ArrayList<Mention>
        @Suppress("DEPRECATION")
        forwardContext = intent.getSerializableExtra(EXTRA_FORWARD_CONTEXT) as? ForwardContext

        sourceAuthorId = intent.getStringExtra(EXTRA_SOURCE_AUTHOR_ID)
        val convId = intent.getStringExtra(EXTRA_SOURCE_CONVERSATION_ID)
        sourceConversation = convId?.let {
            when (intent.getIntExtra(EXTRA_SOURCE_CONVERSATION_TYPE, 0)) {
                1 -> difft.android.messageserialization.For.Group(it)
                else -> difft.android.messageserialization.For.Account(it)
            }
        }
        sourceCarriesForeign = intent.getBooleanExtra(EXTRA_SOURCE_CARRIES_FOREIGN, false)
        sourceCombinedForwardMode = intent.getStringExtra(EXTRA_SOURCE_COMBINED_FORWARD_MODE)
            ?.let { runCatching { CombinedForwardMode.valueOf(it) }.getOrNull() }
            ?: CombinedForwardMode.UNKNOWN

        setupTextView()
        setupOverlay()

        
        
        binding.btnClose.setOnClickListener { finish() }
    }

    private fun setupTextView() {
        
        binding.textContent.autoLinkMask = 0

        
        binding.textContent.setTextIsSelectable(false)

        
        LinkTextUtils.setMarkdownToTextview(this, fullText, binding.textContent, mentions)

        
        val isShortText = fullText.length <= SHORT_TEXT_THRESHOLD
        binding.textContent.gravity = if (isShortText) {
            android.view.Gravity.CENTER_HORIZONTAL
        } else {
            android.view.Gravity.START
        }
    }

    private fun setupOverlay() {
        
        overlayContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        
        
        
        
        
        val root = binding.root as ViewGroup
        root.addView(overlayContainer, root.indexOfChild(binding.btnClose))
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        
        
        
        
        
        val maskedAction = ev.actionMasked
        if (maskedAction == MotionEvent.ACTION_DOWN) {
            touchStartedOnCloseButton = isTouchOnCloseButton(ev)
            
            
            if (touchStartedOnCloseButton) cancelLongPress()
        }
        if (touchStartedOnCloseButton) {
            if (maskedAction == MotionEvent.ACTION_UP || maskedAction == MotionEvent.ACTION_CANCEL) {
                touchStartedOnCloseButton = false
            }
            return super.dispatchTouchEvent(ev)
        }

        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.rawX
                touchDownY = ev.rawY
                touchDownTime = System.currentTimeMillis()
                isLongPressTriggered = false
                isDraggingSelection = false

                
                if (isTouchOnTextView(ev)) {
                    
                    longPressRunnable = Runnable {
                        if (!isLongPressTriggered) {
                            isLongPressTriggered = true
                            isDraggingSelection = true
                            onLongPress(ev.rawX, ev.rawY)
                        }
                    }
                    handler.postDelayed(longPressRunnable!!, longPressTimeout)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDraggingSelection) {
                    
                    onDragSelection(ev.rawX, ev.rawY)
                    return true
                }

                val xDiff = abs(ev.rawX - touchDownX)
                val yDiff = abs(ev.rawY - touchDownY)

                
                if (xDiff > touchSlop || yDiff > touchSlop) {
                    cancelLongPress()

                    
                    
                    if (hasSelection() && !isHandleDragging()) {
                        dismissSelection()
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                cancelLongPress()

                if (isDraggingSelection) {
                    
                    isDraggingSelection = false
                    onDragSelectionEnd()
                    return true
                }

                
                if (isLongPressTriggered) {
                    isLongPressTriggered = false
                    return true
                }

                val timeDiff = System.currentTimeMillis() - touchDownTime
                val xDiff = abs(ev.rawX - touchDownX)
                val yDiff = abs(ev.rawY - touchDownY)

                
                val isSingleClick = timeDiff < clickTimeout && xDiff < touchSlop && yDiff < touchSlop

                
                if (isSingleClick) {
                    
                    if (isTouchOnPopup(ev)) {
                        return super.dispatchTouchEvent(ev)
                    }

                    
                    if (isTouchOnClickableSpan(ev)) {
                        return super.dispatchTouchEvent(ev)
                    }

                    
                    if (hasSelection()) {
                        
                        dismissSelection()
                        return true
                    } else {
                        
                        finish()
                        return true
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                isDraggingSelection = false
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    private fun isTouchOnTextView(ev: MotionEvent): Boolean {
        val location = IntArray(2)
        binding.textContent.getLocationOnScreen(location)
        val rect = Rect(
            location[0],
            location[1],
            location[0] + binding.textContent.width,
            location[1] + binding.textContent.height
        )
        return rect.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    private fun isTouchOnCloseButton(ev: MotionEvent): Boolean {
        val button = binding.btnClose
        if (button.visibility != View.VISIBLE) return false
        val location = IntArray(2)
        button.getLocationOnScreen(location)
        val rect = Rect(
            location[0],
            location[1],
            location[0] + button.width,
            location[1] + button.height
        )
        return rect.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    private fun isTouchOnPopup(ev: MotionEvent): Boolean {
        val bounds = selectionPopup?.getPopupBounds() ?: return false
        return bounds.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    private fun isTouchOnClickableSpan(ev: MotionEvent): Boolean {
        val textView = binding.textContent
        val text = textView.text as? android.text.Spannable ?: return false

        
        val location = IntArray(2)
        textView.getLocationOnScreen(location)
        val localX = ev.rawX - location[0]
        val localY = ev.rawY - location[1]

        
        val x = localX - textView.totalPaddingLeft + textView.scrollX
        val y = localY - textView.totalPaddingTop + textView.scrollY

        val layout = textView.layout ?: return false

        
        if (y < 0 || y > layout.height) return false

        val line = layout.getLineForVertical(y.toInt())
        val offset = layout.getOffsetForHorizontal(line, x)

        
        val clickableSpans = text.getSpans(offset, offset, android.text.style.ClickableSpan::class.java)
        return clickableSpans.isNotEmpty()
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun onLongPress(x: Float, y: Float) {
        
        binding.textContent.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        
        dismissSelection()

        val container = overlayContainer ?: return

        
        textSelectionManager = TextSelectionManager(binding.textContent).apply {
            onSelectionChanged = object : TextSelectionManager.SelectionCallback {
                override fun onSelectionChanged(start: Int, end: Int, isFullSelect: Boolean) {
                    
                    if (!isDraggingSelection && start < end) {
                        showSelectionPopup()
                    } else if (start >= end) {
                        dismissSelectionPopup()
                    }
                }
            }
            handleDragCallback = object : TextSelectionManager.HandleDragCallback {
                override fun onHandleDrag(screenY: Float, isStart: Boolean) {
                    handleAutoScroll(screenY, isStart)
                }

                override fun onHandleDragEnd() {
                    stopAutoScroll()
                }
            }
            attachToOverlay(container)

            
            val offset = getCharacterOffsetAt(x, y)
            if (offset >= 0) {
                selectWordAt(offset)
                
                selectionAnchor = getSelectionStart()
            } else {
                selectAll()
                selectionAnchor = 0
            }
        }
    }

    private var lastDragX = 0f
    private var lastDragY = 0f

    private fun onDragSelection(x: Float, y: Float) {
        lastDragX = x
        lastDragY = y

        val manager = textSelectionManager ?: return
        manager.extendSelectionToScreenPosition(x, y, selectionAnchor)

        
        handleAutoScrollDuringDrag(y)
    }

    private fun handleAutoScrollDuringDrag(screenY: Float) {
        
        currentDragHandleY = screenY
        currentDragIsStart = false  

        val scrollView = binding.rootLayout
        val scrollViewLocation = IntArray(2)
        scrollView.getLocationOnScreen(scrollViewLocation)

        val scrollViewTop = scrollViewLocation[1]
        val scrollViewBottom = scrollViewTop + scrollView.height

        val distanceFromTop = screenY - scrollViewTop
        val distanceFromBottom = scrollViewBottom - screenY

        val contentHeight = scrollView.getChildAt(0)?.height ?: 0
        val maxScroll = contentHeight - scrollView.height

        when {
            distanceFromTop < edgeThreshold && scrollView.scrollY > 0 -> {
                startAutoScrollDuringDrag(-autoScrollSpeed)
            }
            distanceFromBottom < edgeThreshold && scrollView.scrollY < maxScroll -> {
                startAutoScrollDuringDrag(autoScrollSpeed)
            }
            else -> {
                stopAutoScroll()
            }
        }
    }

    private fun startAutoScrollDuringDrag(scrollAmount: Int) {
        if (autoScrollRunnable != null) return

        autoScrollRunnable = object : Runnable {
            override fun run() {
                if (!isDraggingSelection) {
                    stopAutoScroll()
                    return
                }

                val scrollView = binding.rootLayout
                val currentScroll = scrollView.scrollY
                val maxScroll = scrollView.getChildAt(0).height - scrollView.height
                val newScroll = (currentScroll + scrollAmount).coerceIn(0, maxScroll)

                if (newScroll != currentScroll) {
                    scrollView.scrollTo(0, newScroll)

                    
                    textSelectionManager?.updateUIAfterScroll()

                    
                    textSelectionManager?.extendSelectionToScreenPosition(
                        lastDragX,
                        lastDragY,
                        selectionAnchor
                    )

                    handler.postDelayed(this, 16)
                } else {
                    stopAutoScroll()
                }
            }
        }
        handler.post(autoScrollRunnable!!)
    }

    private fun onDragSelectionEnd() {
        
        stopAutoScroll()

        
        if (hasSelection()) {
            showSelectionPopup()
        }
    }

    private fun getCharacterOffsetAt(screenX: Float, screenY: Float): Int {
        val layout = binding.textContent.layout ?: return -1

        
        val location = IntArray(2)
        binding.textContent.getLocationOnScreen(location)

        
        val localX = screenX - location[0] - binding.textContent.paddingLeft
        val localY = screenY - location[1] - binding.textContent.paddingTop

        
        val layoutHeight = layout.height
        val contentHeight = binding.textContent.height - binding.textContent.paddingTop - binding.textContent.paddingBottom
        val gravityOffset = if (contentHeight > layoutHeight) (contentHeight - layoutHeight) / 2 else 0
        val adjustedY = localY - gravityOffset

        
        val line = when {
            adjustedY < 0 -> 0
            adjustedY >= layout.height -> layout.lineCount - 1
            else -> layout.getLineForVertical(adjustedY.toInt())
        }

        
        return layout.getOffsetForHorizontal(line, localX)
    }

    private fun showSelectionPopup() {
        val manager = textSelectionManager ?: return
        val selectionBounds = manager.getSelectionBoundsOnScreen() ?: return
        val isFullSelection = manager.isFullSelection()

        
        dismissSelectionPopup()

        selectionPopup = TextPreviewSelectionPopup(this, translateBackend.isSupported).apply {
            show(
                selectionBounds = selectionBounds,
                isFullSelection = isFullSelection,
                callbacks = object : TextPreviewSelectionPopup.Callbacks {
                    override fun onCopy() {
                        copySelectedText()
                    }

                    override fun onForward() {
                        forwardSelectedText()
                    }

                    override fun onTranslate() {
                        translateSelectedText()
                    }

                    override fun onSelectAll() {
                        textSelectionManager?.selectAll()
                    }

                    override fun onDismiss() {
                        
                    }
                }
            )
        }
    }

    private fun dismissSelectionPopup() {
        selectionPopup?.dismiss()
        selectionPopup = null
    }

    private fun dismissSelection() {
        dismissSelectionPopup()
        textSelectionManager?.detach()
        textSelectionManager = null
    }

    private fun hasSelection(): Boolean {
        return textSelectionManager?.hasSelection() == true
    }

    private fun isHandleDragging(): Boolean {
        return textSelectionManager?.isDragging() == true
    }

    private fun handleAutoScroll(handleScreenY: Float, isStart: Boolean) {
        
        currentDragHandleY = handleScreenY
        currentDragIsStart = isStart

        val scrollView = binding.rootLayout
        val scrollViewLocation = IntArray(2)
        scrollView.getLocationOnScreen(scrollViewLocation)

        val scrollViewTop = scrollViewLocation[1]
        val scrollViewBottom = scrollViewTop + scrollView.height

        
        val distanceFromTop = handleScreenY - scrollViewTop
        val distanceFromBottom = scrollViewBottom - handleScreenY

        val contentHeight = scrollView.getChildAt(0)?.height ?: 0
        val maxScroll = contentHeight - scrollView.height

        when {
            distanceFromTop < edgeThreshold && scrollView.scrollY > 0 -> {
                
                startAutoScroll(-autoScrollSpeed)
            }
            distanceFromBottom < edgeThreshold && scrollView.scrollY < maxScroll -> {
                
                startAutoScroll(autoScrollSpeed)
            }
            else -> {
                
                stopAutoScroll()
            }
        }
    }

    private fun startAutoScroll(scrollAmount: Int) {
        
        if (autoScrollRunnable != null) {
            return
        }

        autoScrollRunnable = object : Runnable {
            override fun run() {
                val scrollView = binding.rootLayout
                val currentScroll = scrollView.scrollY
                val maxScroll = scrollView.getChildAt(0).height - scrollView.height
                val newScroll = (currentScroll + scrollAmount).coerceIn(0, maxScroll)

                if (newScroll != currentScroll) {
                    scrollView.scrollTo(0, newScroll)

                    
                    textSelectionManager?.updateUIAfterScroll()

                    
                    
                    
                    updateSelectionDuringScroll()

                    
                    handler.postDelayed(this, 16) 
                } else {
                    stopAutoScroll()
                }
            }
        }
        handler.post(autoScrollRunnable!!)
    }

    private fun updateSelectionDuringScroll() {
        val manager = textSelectionManager ?: return

        
        val textView = binding.textContent
        val textViewLocation = IntArray(2)
        textView.getLocationOnScreen(textViewLocation)

        
        val screenX = textViewLocation[0] + textView.width / 2f
        val screenY = currentDragHandleY

        
        val offset = manager.getOffsetForScreenPosition(screenX, screenY)
        if (offset < 0) return

        
        if (currentDragIsStart) {
            val currentEnd = manager.getSelectionEnd()
            if (offset < currentEnd) {
                manager.setSelection(offset, currentEnd)
            }
        } else {
            val currentStart = manager.getSelectionStart()
            if (offset > currentStart) {
                manager.setSelection(currentStart, offset)
            }
        }
    }

    private fun stopAutoScroll() {
        autoScrollRunnable?.let { handler.removeCallbacks(it) }
        autoScrollRunnable = null
    }

    private fun copySelectedText() {
        val text = textSelectionManager?.getSelectedText() ?: return
        if (text.isEmpty()) {
            dismissSelection()
            return
        }
        Util.copyToClipboard(this, text)
        
        
        val author = sourceAuthorId
        val conv = sourceConversation
        
        
        if (!author.isNullOrEmpty() && conv != null && sourceCarriesForeign) {
            activityNoticeDispatcher.dispatchCopyNotice(
                sourceConversation = conv,
                sourceAuthorIds = listOf(author),
                myId = globalServices.myId,
                messageCount = 1,
                combinedForwardMode = sourceCombinedForwardMode,
            )
        }
        dismissSelection()
    }

    private fun forwardSelectedText() {
        val text = textSelectionManager?.getSelectedText() ?: return
        val isFullSelect = textSelectionManager?.isFullSelection() == true
        
        
        val authorIds = sourceAuthorId?.let { listOf(it) }
        
        val carriesForeignContent = sourceCarriesForeign

        if (isFullSelect && forwardContext != null) {
            selectChatsUtils.showChatSelectAndSendDialog(
                this,
                text,
                null,
                null,
                listOf(forwardContext!!),
                sourceConversation = sourceConversation,
                sourceAuthorIds = authorIds,
                combinedForwardMode = sourceCombinedForwardMode,
                carriesForeignContent = carriesForeignContent,
            )
        } else {
            selectChatsUtils.showChatSelectAndSendDialog(
                this,
                text,
                sourceConversation = sourceConversation,
                sourceAuthorIds = authorIds,
                combinedForwardMode = sourceCombinedForwardMode,
                carriesForeignContent = carriesForeignContent,
            )
        }

        dismissSelection()
    }

    private fun translateSelectedText() {
        val text = textSelectionManager?.getSelectedText() ?: return
        TranslateBottomSheetFragment.show(this, text)
        dismissSelection()
    }

    override fun onDestroy() {
        cancelLongPress()
        dismissSelection()
        overlayContainer?.let { container ->
            (container.parent as? ViewGroup)?.removeView(container)
        }
        overlayContainer = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_MENTIONS = "extra_mentions"
        private const val EXTRA_FORWARD_CONTEXT = "extra_forward_context"
        private const val EXTRA_SOURCE_AUTHOR_ID = "extra_source_author_id"
        private const val EXTRA_SOURCE_CONVERSATION_ID = "extra_source_conversation_id"
        private const val EXTRA_SOURCE_CONVERSATION_TYPE = "extra_source_conversation_type"
        
        private const val EXTRA_SOURCE_COMBINED_FORWARD_MODE = "extra_source_combined_forward_mode"
        
        private const val EXTRA_SOURCE_CARRIES_FOREIGN = "extra_source_carries_foreign"
        private const val SHORT_TEXT_THRESHOLD = 200

        
        fun start(
            context: Context,
            text: String,
            mentions: List<Mention>? = null,
            forwardContext: ForwardContext? = null,
            sourceMessage: com.difft.android.chat.message.TextChatMessage? = null,
        ) {
            val intent = Intent(context, TextPreviewActivity::class.java).apply {
                putExtra(EXTRA_TEXT, text)
                mentions?.let { putExtra(EXTRA_MENTIONS, ArrayList(it)) }
                forwardContext?.let { putExtra(EXTRA_FORWARD_CONTEXT, it) }
                sourceMessage?.let { msg ->
                    val conv = msg.forWhat
                    if (conv != null) {
                        
                        
                        putExtra(EXTRA_SOURCE_AUTHOR_ID, msg.sourceAuthorOverride ?: msg.authorId)
                        putExtra(EXTRA_SOURCE_CONVERSATION_ID, conv.id)
                        putExtra(EXTRA_SOURCE_CONVERSATION_TYPE, conv.typeValue)
                        
                        putExtra(
                            EXTRA_SOURCE_COMBINED_FORWARD_MODE,
                            (msg.sourceMode ?: CombinedForwardMode.UNKNOWN).name,
                        )
                        
                        
                        
                        
                        
                        
                        
                        
                        
                        putExtra(
                            EXTRA_SOURCE_CARRIES_FOREIGN,
                            NoticeAggregator.forwardCarriesForeignContent(listOf(msg), globalServices.myId),
                        )
                    }
                }
            }
            context.startActivity(intent)
        }
    }
}
