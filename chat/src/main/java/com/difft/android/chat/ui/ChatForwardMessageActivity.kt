package com.difft.android.chat.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ChatActivityForwardMessageBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.hi.dhl.binding.viewbind
import com.difft.android.chat.MessageContactsCacheUtil
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
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies

@AndroidEntryPoint
class ChatForwardMessageActivity : BaseActivity() {

    /**
     * Contact cache for author name lookup in title
     */
    private val contactorCache = MessageContactsCacheUtil()

    companion object {
        private const val EXTRA_MESSAGE_ID = "messageId"
        private const val EXTRA_CONFIDENTIAL_MESSAGE_ID = "confidentialMessageId"
        private const val EXTRA_SHOULD_SAVE_TO_PHOTOS = "shouldSaveToPhotos"

        /**
         * Start activity by message ID - will query ForwardContext asynchronously
         * @param shouldSaveToPhotos Whether to auto-save images/videos to photos (from conversation setting)
         */
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

    // Store ForwardContext objects for Fragment access (avoids serialization)
    private val forwardContextStack = mutableListOf<ForwardContext>()

    private val mBinding: ChatActivityForwardMessageBinding by viewbind()

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private lateinit var bottomSheetCallback: BottomSheetBehavior.BottomSheetCallback
    private lateinit var backPressedCallback: OnBackPressedCallback

    // Store confidential message ID for deletion in onDestroy
    private var confidentialMessageId: String? = null

    // Disable BaseActivity auto padding - this Activity handles insets itself
    override fun shouldApplySystemBarsPadding(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupBottomSheet()
        setupBackPressedCallback()
        setupBackStackListener()

        // Set initial title (will be updated after loading)
        mBinding.tvTitle.text = getString(R.string.chat_history)

        // Back button click - same as system back
        mBinding.ibBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        if (savedInstanceState == null) {
            loadInitialFragment()
        }
    }

    private fun setupBottomSheet() {
        val bottomSheet = mBinding.bottomSheet
        val scrim = mBinding.scrim

        // Get status bar height for expanded offset
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.root) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            configureBottomSheetBehavior(bottomSheet, scrim, statusBarHeight)
            // Remove listener after first apply
            ViewCompat.setOnApplyWindowInsetsListener(mBinding.root, null)
            insets
        }
        // Request insets
        mBinding.root.requestApplyInsets()
    }

    private fun configureBottomSheetBehavior(bottomSheet: View, scrim: View, statusBarHeight: Int) {
        val screenHeight = WindowSizeClassUtil.getWindowHeightPx(this)

        // peekHeight: 75% of screen height (collapsed state)
        val peekHeight = (screenHeight * 0.75).toInt()

        // Start scrim transparent, will fade in with bottom sheet slide
        scrim.alpha = 0f

        // Add bottom padding to compensate for expandedOffset
        // When expanded, bottom sheet top is at expandedOffset, but height is match_parent
        // This causes content to be pushed below screen. Bottom padding fixes this.
        bottomSheet.setPadding(0, 0, 0, statusBarHeight)

        // Setup BottomSheetBehavior
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.apply {
            this.peekHeight = peekHeight
            this.expandedOffset = statusBarHeight // Full screen stops below status bar
            isFitToContents = false // Required for half-expanded support
            isHideable = true
            skipCollapsed = false // Allow collapsed (3/4) state
            state = BottomSheetBehavior.STATE_HIDDEN // Start hidden for animation
        }

        // Show bottom sheet after layout is complete
        bottomSheet.post {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED // Start at 3/4 height
        }

        // Scrim click: collapse if expanded, hide if collapsed
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

        // Bottom sheet callback
        bottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
            private var hasOpened = false  // Track if bottom sheet has finished opening
            private var isDraggingDown = false
            private var dragStartOffset = 0f
            private var isClosing = false

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_HALF_EXPANDED -> {
                        // Skip half-expanded state, go directly to collapsed
                        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    }
                    BottomSheetBehavior.STATE_COLLAPSED, BottomSheetBehavior.STATE_EXPANDED -> {
                        // Fade in scrim after bottom sheet is fully visible (first time only)
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
                        // If user was dragging down, close the sheet directly
                        if (hasOpened && isDraggingDown) {
                            isClosing = true
                            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                        }
                    }
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        finish()
                        @Suppress("DEPRECATION")
                        overridePendingTransition(0, 0)
                    }
                    else -> {
                        isDraggingDown = false
                    }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                // Ignore slide events until bottom sheet has opened
                if (!hasOpened) return

                // slideOffset: -1 (hidden) to 1 (expanded), 0 is collapsed (peekHeight)
                // dragStartOffset: small value (~0.02) when expanded, larger (~0.25) when collapsed

                // Detect downward drag from any state:
                // - From expanded (dragStartOffset < 0.1): if drops below 0.85, consider dragging down
                // - From collapsed (dragStartOffset >= 0.1): if drops below -0.1, consider dragging down
                val threshold = if (dragStartOffset < 0.1f) 0.85f else -0.1f
                if (slideOffset < threshold) {
                    isDraggingDown = true
                }

                // Fade scrim when closing (dragging down to hide)
                if (isClosing || isDraggingDown) {
                    val alpha = (slideOffset + 1f) / 2f // -1 -> 0, 0 -> 0.5, 1 -> 1
                    scrim.alpha = alpha.coerceIn(0f, 0.5f)
                }
            }
        }
        bottomSheetBehavior.addBottomSheetCallback(bottomSheetCallback)
    }

    override fun onDestroy() {
        if (::bottomSheetBehavior.isInitialized && ::bottomSheetCallback.isInitialized) {
            bottomSheetBehavior.removeBottomSheetCallback(bottomSheetCallback)
        }
        // Delete confidential message when Activity is destroyed (not when Fragment is replaced)
        confidentialMessageId?.let {
            L.i { "[Confidential] Delete forward message, messageId: $it" }
            ApplicationDependencies.getMessageStore().deleteMessage(listOf(it))
        }
        super.onDestroy()
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
            // Pop ForwardContext from stack when going back
            if (forwardContextStack.size > supportFragmentManager.backStackEntryCount + 1) {
                forwardContextStack.removeLastOrNull()
            }
            // Update title from current ForwardContext
            getCurrentForwardContext()?.let { forwardContext ->
                mBinding.tvTitle.text = getForwardTitle(forwardContext)
            }
        }
    }

    private fun loadInitialFragment() {
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: return

        // Store confidential message ID for deletion when Activity is destroyed
        confidentialMessageId = intent.getStringExtra(EXTRA_CONFIDENTIAL_MESSAGE_ID)

        // Query message asynchronously
        lifecycleScope.launch(Dispatchers.IO) {
            val message = wcdb.message.getFirstObject(DBMessageModel.id.eq(messageId))
            val forwardContext = message?.convertToTextMessage()?.forwardContext ?: return@launch

            // Load contactors for author name lookup in title (recursively collect all author IDs)
            val authorIds = collectAllAuthorIds(forwardContext)
            contactorCache.loadContactors(authorIds)

            withContext(Dispatchers.Main) {
                // Store ForwardContext and update title
                forwardContextStack.add(forwardContext)
                mBinding.tvTitle.text = getForwardTitle(forwardContext)

                // Load fragment
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

    /**
     * Get ForwardContext at specified stack index (for Fragment to access)
     */
    fun getForwardContext(stackIndex: Int): ForwardContext? {
        return forwardContextStack.getOrNull(stackIndex)
    }

    /**
     * Get current (top) ForwardContext
     */
    fun getCurrentForwardContext(): ForwardContext? {
        return forwardContextStack.lastOrNull()
    }

    /**
     * Check if this is a confidential (self-destructing) message
     * Used by Fragment to determine whether to save attachments to media store
     */
    fun isConfidentialMessage(): Boolean {
        return confidentialMessageId != null
    }

    /**
     * Get shouldSaveToPhotos setting from original conversation
     * Used by Fragment for auto-save logic when downloading attachments
     */
    fun getShouldSaveToPhotos(): Boolean {
        return intent.getBooleanExtra(EXTRA_SHOULD_SAVE_TO_PHOTOS, false)
    }

    /**
     * Recursively collect all author IDs from ForwardContext and its nested forwards
     */
    private fun collectAllAuthorIds(forwardContext: ForwardContext): Set<String> {
        val authorIds = mutableSetOf<String>()
        collectAuthorIdsFromForwards(forwardContext.forwards, authorIds)
        return authorIds
    }

    private fun collectAuthorIdsFromForwards(forwards: List<Forward>?, authorIds: MutableSet<String>) {
        forwards?.forEach { forward ->
            authorIds.add(forward.author)
            // Recursively collect from nested forwards
            collectAuthorIdsFromForwards(forward.forwards, authorIds)
        }
    }

    /**
     * Generate title from ForwardContext
     */
    private fun getForwardTitle(forwardContext: ForwardContext): String {
        val forwards = forwardContext.forwards
        return if (forwards?.firstOrNull()?.isFromGroup == true) {
            getString(R.string.group_chat_history)
        } else {
            val authorId = forwards?.firstOrNull()?.author ?: ""
            val author = contactorCache.getContactor(authorId)
            if (author != null) {
                getString(R.string.chat_history_for, author.getDisplayNameWithoutRemarkForUI())
            } else {
                getString(R.string.chat_history_for, authorId.formatBase58Id())
            }
        }
    }

    /**
     * Navigate to nested forward content (called from Fragment)
     * This creates a navigation stack within the same bottom sheet
     */
    fun navigateToNestedForward(title: String, forwardContext: ForwardContext) {
        // Add to stack and update title
        forwardContextStack.add(forwardContext)
        mBinding.tvTitle.text = title

        val fragment = ChatForwardMessageFragment.newInstance(
            title = title,
            stackIndex = forwardContextStack.size - 1
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