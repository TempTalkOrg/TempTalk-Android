package com.difft.android.chat.ui

import com.difft.android.base.utils.safeTopInset
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import difft.android.messageserialization.For
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.chat.R
import com.difft.android.chat.attachment.ConfidentialViewerHost
import com.difft.android.chat.databinding.ChatActivityForwardMessageBinding
import com.difft.android.base.widget.floatAsTopBarButton
import com.difft.android.chat.widget.AudioMessageManager
import com.difft.android.base.widget.applySharedMaxWidth
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.hi.dhl.binding.viewbind
import com.difft.android.chat.ui.mvi.ChatForwardMessageContract
import com.difft.android.chat.ui.mvi.ChatForwardMessageViewModel
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.convertToTextMessage
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.wcdb
import com.difft.android.chat.dependencies.ApplicationDependencies

@AndroidEntryPoint
class ChatForwardMessageActivity : BaseActivity(), ConfidentialViewerHost {

    companion object {
        private const val EXTRA_MESSAGE_ID = "messageId"
        private const val EXTRA_CONFIDENTIAL_MESSAGE_ID = "confidentialMessageId"
        private const val EXTRA_SHOULD_SAVE_TO_PHOTOS = "shouldSaveToPhotos"

        



        fun startActivity(
            activity: Activity,
            messageId: String,
            confidentialMessageId: String? = null,
            shouldSaveToPhotos: Boolean = false
        ) {
            val intent = Intent(activity, ChatForwardMessageActivity::class.java)
            intent.putExtra(EXTRA_MESSAGE_ID, messageId)
            intent.putExtra(EXTRA_CONFIDENTIAL_MESSAGE_ID, confidentialMessageId)
            intent.putExtra(EXTRA_SHOULD_SAVE_TO_PHOTOS, shouldSaveToPhotos)
            activity.startActivity(intent)
        }
    }

    
    
    private val viewModel: ChatForwardMessageViewModel by viewModels()

    
    
    fun getOuterSourceConversation(): For? = viewModel.state.value.outerSourceConversation
    fun getOuterSourceAuthorId(): String? = viewModel.state.value.outerSourceAuthorId

    private val mBinding: ChatActivityForwardMessageBinding by viewbind()

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private lateinit var bottomSheetCallback: BottomSheetBehavior.BottomSheetCallback
    private lateinit var backPressedCallback: OnBackPressedCallback

    
    private var confidentialMessageId: String? = null

    
    
    
    private var entryPlayingMessageId: String? = null

    
    override fun shouldApplySystemBarsPadding(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        
        
        confidentialMessageId = intent.getStringExtra(EXTRA_CONFIDENTIAL_MESSAGE_ID)

        entryPlayingMessageId = AudioMessageManager.currentPlayingMessage?.id

        setupBottomSheet()
        setupBackPressedCallback()
        setupBackStackListener()

        
        mBinding.tvTitle.text = getString(R.string.chat_history)

        mBinding.ibBack.floatAsTopBarButton(leading = true)
        
        mBinding.ibBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val stack = viewModel.state.value.stack
        when {
            savedInstanceState == null -> loadInitialFragment()
            
            stack.isNotEmpty() -> mBinding.tvTitle.text = getForwardTitle(stack.last())
            
            
            else -> {
                val backStackCount = supportFragmentManager.backStackEntryCount
                L.i { "[ForwardViewer] restore without context backStack=$backStackCount" }
                supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                loadInitialFragment(finishIfMissing = true)
            }
        }
    }

    private fun setupBottomSheet() {
        val bottomSheet = mBinding.bottomSheet
        val scrim = mBinding.scrim

        
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.root) { root, insets ->
            val statusBarHeight = root.safeTopInset(insets)
            configureBottomSheetBehavior(bottomSheet, scrim, statusBarHeight)
            
            ViewCompat.setOnApplyWindowInsetsListener(mBinding.root, null)
            insets
        }
        
        mBinding.root.requestApplyInsets()
    }

    private fun configureBottomSheetBehavior(bottomSheet: View, scrim: View, statusBarHeight: Int) {
        val screenHeight = WindowSizeClassUtil.getWindowHeightPx(this)

        
        val peekHeight = (screenHeight * 0.75).toInt()

        
        scrim.alpha = 0f

        
        
        
        bottomSheet.setPadding(0, 0, 0, statusBarHeight)

        
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.apply {
            applySharedMaxWidth(resources)
            this.peekHeight = peekHeight
            this.expandedOffset = statusBarHeight 
            isFitToContents = false 
            isHideable = true
            skipCollapsed = false 
            state = BottomSheetBehavior.STATE_HIDDEN 
        }

        
        bottomSheet.post {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED 
        }

        
        scrim.setOnClickListener {
            when (bottomSheetBehavior.state) {
                BottomSheetBehavior.STATE_EXPANDED -> {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                }
                else -> {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                }
            }
        }

        
        bottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
            private var hasOpened = false  
            private var isDraggingDown = false
            private var dragStartOffset = 0f
            private var isClosing = false

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_HALF_EXPANDED -> {
                        
                        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    }
                    BottomSheetBehavior.STATE_COLLAPSED, BottomSheetBehavior.STATE_EXPANDED -> {
                        
                        if (!hasOpened) {
                            hasOpened = true
                            scrim.animate()
                                .alpha(0.5f)
                                .setDuration(150)
                                .start()
                        }
                        isDraggingDown = false
                        isClosing = false
                    }
                    BottomSheetBehavior.STATE_DRAGGING -> {
                        isDraggingDown = false
                        isClosing = false
                        val parentHeight = (bottomSheet.parent as? View)?.height?.takeIf { it > 0 } ?: 1
                        dragStartOffset = bottomSheet.top.toFloat() / parentHeight
                    }
                    BottomSheetBehavior.STATE_SETTLING -> {
                        
                        if (hasOpened && isDraggingDown) {
                            isClosing = true
                            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                        }
                    }
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        dismissNow()
                    }
                    else -> {
                        isDraggingDown = false
                    }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                
                if (!hasOpened) return

                
                

                
                
                
                val threshold = if (dragStartOffset < 0.1f) 0.85f else -0.1f
                if (slideOffset < threshold) {
                    isDraggingDown = true
                }

                
                if (isClosing || isDraggingDown) {
                    val alpha = (slideOffset + 1f) / 2f 
                    scrim.alpha = alpha.coerceIn(0f, 0.5f)
                }

                
                
                
                
                if (slideOffset <= -0.99f) {
                    dismissNow()
                }
            }
        }
        bottomSheetBehavior.addBottomSheetCallback(bottomSheetCallback)
    }

    override fun onDestroy() {
        if (::bottomSheetBehavior.isInitialized && ::bottomSheetCallback.isInitialized) {
            bottomSheetBehavior.removeBottomSheetCallback(bottomSheetCallback)
        }
        
        stopPreviewOwnedAudio()
        
        
        if (!isChangingConfigurations) {
            confidentialMessageId?.let {
                L.i { "[Confidential] Delete forward message, messageId: $it" }
                ApplicationDependencies.getMessageStore().deleteMessage(listOf(it))
            }
        }
        super.onDestroy()
    }

    private fun stopPreviewOwnedAudio() {
        val currentId = AudioMessageManager.currentPlayingMessage?.id
        if (currentId != entryPlayingMessageId) {
            AudioMessageManager.releasePlayer()
        }
    }

    



    private fun dismissNow() {
        if (isFinishing) return
        stopPreviewOwnedAudio()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun setupBackPressedCallback() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val fragmentCount = supportFragmentManager.backStackEntryCount
                if (fragmentCount > 0) {
                    supportFragmentManager.popBackStack()
                } else if (::bottomSheetBehavior.isInitialized) {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                } else {
                    finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    private fun setupBackStackListener() {
        supportFragmentManager.addOnBackStackChangedListener {
            
            val expectedSize = supportFragmentManager.backStackEntryCount + 1
            if (viewModel.state.value.stack.size > expectedSize) {
                viewModel.dispatch(ChatForwardMessageContract.Intent.TrimTo(expectedSize))
            }
            
            getCurrentForwardContext()?.let { forwardContext ->
                mBinding.tvTitle.text = getForwardTitle(forwardContext)
            }
        }
    }

    



    private fun loadInitialFragment(finishIfMissing: Boolean = false) {
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: return

        
        lifecycleScope.launch(Dispatchers.IO) {
            val message = wcdb.message.getFirstObject(DBMessageModel.id.eq(messageId))
            val forwardContext = message?.convertToTextMessage()?.forwardContext
            if (message == null || forwardContext == null) {
                if (finishIfMissing) {
                    withContext(Dispatchers.Main) { finish() }
                }
                return@launch
            }

            
            val outerAuthorId = message.fromWho
            val outerConversation = if (message.roomType == 1) For.Group(message.roomId ?: "") else For.Account(message.roomId ?: "")

            
            val authorIds = collectAllAuthorIds(forwardContext)
            viewModel.contactorCache.loadContactors(authorIds)

            withContext(Dispatchers.Main) {
                
                viewModel.dispatch(
                    ChatForwardMessageContract.Intent.SetRoot(forwardContext, outerConversation, outerAuthorId)
                )
                mBinding.tvTitle.text = getForwardTitle(forwardContext)

                
                val fragment = ChatForwardMessageFragment.newInstance(
                    title = getForwardTitle(forwardContext),
                    stackIndex = 0
                )
                supportFragmentManager.commit {
                    replace(R.id.fragment_container, fragment)
                }
            }
        }
    }

    


    fun getForwardContext(stackIndex: Int): ForwardContext? {
        return viewModel.state.value.stack.getOrNull(stackIndex)
    }

    
    fun replaceForwardContext(stackIndex: Int, forwardContext: ForwardContext) {
        viewModel.dispatch(ChatForwardMessageContract.Intent.ReplaceAt(stackIndex, forwardContext))
    }

    


    fun getCurrentForwardContext(): ForwardContext? {
        return viewModel.state.value.stack.lastOrNull()
    }

    



    fun isConfidentialMessage(): Boolean {
        return confidentialMessageId != null
    }

    override fun isConfidentialViewer(): Boolean = isConfidentialMessage()

    



    fun getShouldSaveToPhotos(): Boolean {
        return intent.getBooleanExtra(EXTRA_SHOULD_SAVE_TO_PHOTOS, false)
    }

    


    private fun collectAllAuthorIds(forwardContext: ForwardContext): Set<String> {
        val authorIds = mutableSetOf<String>()
        collectAuthorIdsFromForwards(forwardContext.forwards, authorIds)
        return authorIds
    }

    private fun collectAuthorIdsFromForwards(forwards: List<Forward>?, authorIds: MutableSet<String>) {
        forwards?.forEach { forward ->
            authorIds.add(forward.author)
            
            collectAuthorIdsFromForwards(forward.forwards, authorIds)
        }
    }

    


    private fun getForwardTitle(forwardContext: ForwardContext): String {
        val forwards = forwardContext.forwards
        return if (forwards?.firstOrNull()?.isFromGroup == true) {
            getString(R.string.group_chat_history)
        } else {
            val authorId = forwards?.firstOrNull()?.author ?: ""
            val author = viewModel.contactorCache.getContactor(authorId)
            if (author != null) {
                getString(R.string.chat_history_for, author.getDisplayNameWithoutRemarkForUI())
            } else {
                getString(R.string.chat_history_for, authorId.formatBase58Id())
            }
        }
    }

    



    fun navigateToNestedForward(title: String, forwardContext: ForwardContext) {
        
        viewModel.dispatch(ChatForwardMessageContract.Intent.PushNested(forwardContext))
        mBinding.tvTitle.text = title

        val fragment = ChatForwardMessageFragment.newInstance(
            title = title,
            stackIndex = viewModel.state.value.stack.size - 1
        )

        supportFragmentManager.commit {
            setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            replace(R.id.fragment_container, fragment)
            addToBackStack(null)
        }
    }
}