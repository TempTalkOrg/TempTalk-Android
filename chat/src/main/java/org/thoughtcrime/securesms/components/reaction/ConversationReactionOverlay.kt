package org.thoughtcrime.securesms.components.reaction

import android.animation.Animator
import android.animation.AnimatorInflater
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.R
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isAttachmentMessage
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.SpeechToTextStatus
import difft.android.messageserialization.model.TranslateStatus
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import com.kongzue.dialogx.dialogs.BottomDialog
import com.kongzue.dialogx.interfaces.OnBindView
import util.DimensionUnit
import util.dp
import org.thoughtcrime.securesms.animation.AnimationCompleteListener
import org.thoughtcrime.securesms.components.menu.ActionItem
import org.thoughtcrime.securesms.util.ThemeUtil
import org.thoughtcrime.securesms.util.Util
import org.thoughtcrime.securesms.util.ViewUtil
import org.thoughtcrime.securesms.util.WindowUtil
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

class ConversationReactionOverlay : FrameLayout {
    //    private final Rect emojiViewGlobalRect = new Rect();
    //    private final Rect emojiStripViewBounds = new Rect();
    //    private float segmentSize;
    private val horizontalEmojiBoundary = Boundary()
    private val verticalScrubBoundary = Boundary()
    private val deadzoneTouchPoint = PointF()
    private var activity: Activity? = null
    private var messageRecord: TextChatMessage? = null
    private var selectedConversationModel: SelectedConversationModel? = null
    private var overlayState = OverlayState.HIDDEN
    private var isNonAdminInAnnouncementGroup = false
    private var downIsOurs = false

    //    private int selected = -1;
    //    private int customEmojiIndex;
    private var originalStatusBarColor = 0
    private var originalNavigationBarColor = 0
    private var dropdownAnchor: View? = null

    //    private View toolbarShade;
    //    private View inputShade;
    private var conversationItem: View? = null
    private var backgroundView: View? = null
    private var foregroundView: ConstraintLayout? = null

    //    private View selectedView;
    //    private EmojiImageView[] emojiViews;
    private var rvEmoji: RecyclerView? = null
    private var contextMenu: ConversationContextMenu? = null
    private var touchDownDeadZoneSize = 0f
    private var distanceFromTouchDownPointToBottomOfScrubberDeadZone = 0f
    private var scrubberWidth = 0
    private var selectedVerticalTranslation = 0
    private var scrubberHorizontalMargin = 0
    private var animationEmojiStartDelayFactor = 0
    private val statusBarHeight = 0
    private val bottomNavigationBarHeight = 0
    private var onReactionSelectedListener: OnReactionSelectedListener? = null
    private var onActionSelectedListener: OnActionSelectedListener? = null
    private var onHideListener: OnHideListener? = null
    private val revealAnimatorSet = AnimatorSet()
    private var hideAnimatorSet = AnimatorSet()

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    override fun onFinishInflate() {
        super.onFinishInflate()
        dropdownAnchor = findViewById(R.id.dropdown_anchor)
        //        toolbarShade = findViewById(R.id.toolbar_shade);
//        inputShade = findViewById(R.id.input_shade);
        conversationItem = findViewById(R.id.conversation_item)
        backgroundView = findViewById(R.id.conversation_reaction_scrubber_background)
        foregroundView = findViewById(R.id.conversation_reaction_scrubber_foreground)
        //        selectedView = findViewById(R.id.conversation_reaction_current_selection_indicator);
        rvEmoji = findViewById(R.id.rv_emoji)
        //        emojiViews = new EmojiImageView[]{findViewById(R.id.reaction_1),
//                findViewById(R.id.reaction_2),
//                findViewById(R.id.reaction_3),
//                findViewById(R.id.reaction_4),
//                findViewById(R.id.reaction_5),
//                findViewById(R.id.reaction_6),
//                findViewById(R.id.reaction_7)};
//
//        customEmojiIndex = emojiViews.length - 1;
        distanceFromTouchDownPointToBottomOfScrubberDeadZone = resources.getDimensionPixelSize(R.dimen.conversation_reaction_scrub_deadzone_distance_from_touch_bottom).toFloat()

        touchDownDeadZoneSize = resources.getDimensionPixelSize(R.dimen.conversation_reaction_touch_deadzone_size).toFloat()
        scrubberWidth = resources.getDimensionPixelOffset(R.dimen.reaction_scrubber_width)
        selectedVerticalTranslation = resources.getDimensionPixelOffset(R.dimen.conversation_reaction_scrub_vertical_translation)
        scrubberHorizontalMargin = resources.getDimensionPixelOffset(R.dimen.conversation_reaction_scrub_horizontal_margin)
        animationEmojiStartDelayFactor = 10
        initAnimators()
    }

    private var moreEmojiReactionDialog: BottomDialog? = null
    private fun showMoreEmojiReactionDialog() {
        moreEmojiReactionDialog = BottomDialog.build()
            .setBackgroundColor(ContextCompat.getColor(context, com.difft.android.base.R.color.bg2))
            .setCustomView(object : OnBindView<BottomDialog?>(R.layout.layout_more_emoji_dialog) {
                override fun onBind(dialog: BottomDialog?, v: View) {
                    val rvMostUse = v.findViewById<RecyclerView>(R.id.rv_most_use)
                    val tvOthers = v.findViewById<TextView>(R.id.tv_others)
                    val rvOthers = v.findViewById<RecyclerView>(R.id.rv_other)

                    val emojis = selectedConversationModel?.mostUseEmojis

                    if (!emojis.isNullOrEmpty()) {
                        var mostUseEmojis: List<String>? = null
                        var otherEmojis: List<String>? = null
                        if (emojis.size > 7) {
                            mostUseEmojis = emojis.subList(0, 7)
                            otherEmojis = emojis.subList(7, emojis.size)
                        } else {
                            mostUseEmojis = emojis
                        }

                        if (mostUseEmojis.isNotEmpty()) {
                            val rvMostUseAdapter = object : ReactionEmojisAdapter(messageRecord!!) {
                                override fun onEmojiSelected(emoji: String, position: Int, remove: Boolean) {
                                    moreEmojiReactionDialog?.hide()
                                    onReactionSelectedListener?.onReactionSelected(messageRecord!!, emoji, remove)
                                }
                            }

                            rvMostUse.layoutManager = GridLayoutManager(context, 7)
                            rvMostUse.adapter = rvMostUseAdapter
                            rvMostUseAdapter.submitList(mostUseEmojis)
                        }

                        if (!otherEmojis.isNullOrEmpty()) {
                            tvOthers.visibility = View.VISIBLE
                            rvOthers.visibility = View.VISIBLE

                            val rvOthersAdapter = object : ReactionEmojisAdapter(messageRecord!!) {
                                override fun onEmojiSelected(emoji: String, position: Int, remove: Boolean) {
                                    moreEmojiReactionDialog?.hide()
                                    onReactionSelectedListener?.onReactionSelected(messageRecord!!, emoji, remove)
                                }
                            }
                            rvOthers.layoutManager = GridLayoutManager(context, 7)
                            rvOthers.adapter = rvOthersAdapter
                            rvOthersAdapter.submitList(otherEmojis)
                        } else {
                            tvOthers.visibility = View.GONE
                            rvOthers.visibility = View.GONE
                        }
                    }
                }
            })
            .show()
    }

    fun show(
        activity: Activity,
        rootView: View,
        conversationMessage: TextChatMessage,
        lastSeenDownPoint: PointF,
        isNonAdminInAnnouncementGroup: Boolean,
        selectedConversationModel: SelectedConversationModel
    ) {
        if (overlayState != OverlayState.HIDDEN) {
            return
        }
        messageRecord = conversationMessage
        this.selectedConversationModel = selectedConversationModel
        this.isNonAdminInAnnouncementGroup = isNonAdminInAnnouncementGroup
        overlayState = OverlayState.UNINITAILIZED

        val mostUseEmojis = selectedConversationModel.mostUseEmojis
        L.i { "[emoji] reaction overlay mostUseEmojis:${mostUseEmojis?.size ?: 0}" }
        if (!mostUseEmojis.isNullOrEmpty()
            && conversationMessage.sharedContacts.isNullOrEmpty()
            && conversationMessage.attachment?.isAudioMessage() != true
            && conversationMessage.attachment?.isAudioFile() != true
        ) {
            backgroundView?.visibility = View.VISIBLE
            foregroundView?.visibility = View.VISIBLE

            val reactionEmojisAdapter = object : ReactionEmojisAdapter(messageRecord!!) {
                override fun onEmojiSelected(emoji: String, position: Int, remove: Boolean) {
                    if (emoji == "...") {
                        showMoreEmojiReactionDialog()
                    } else {
                        onReactionSelectedListener?.onReactionSelected(messageRecord!!, emoji, remove)
                    }
                }
            }
            rvEmoji?.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            rvEmoji?.adapter = reactionEmojisAdapter
            val emojis: MutableList<String> = ArrayList()
            if (mostUseEmojis.size > 5) {
                emojis.addAll(mostUseEmojis.subList(0, 5))
            } else {
                emojis.addAll(mostUseEmojis)
            }
            emojis.add("...")
            reactionEmojisAdapter.submitList(emojis)
        } else {
            backgroundView?.visibility = View.GONE
            foregroundView?.visibility = View.GONE
        }
        //        selected = -1;

//        setupSelectedEmoji();

//        View statusBarBackground = activity.findViewById(android.R.id.statusBarBackground);
//        statusBarHeight = statusBarBackground == null ? 0 : statusBarBackground.getHeight();

//        View navigationBarBackground = activity.findViewById(android.R.id.navigationBarBackground);
//        bottomNavigationBarHeight = navigationBarBackground == null ? 0 : navigationBarBackground.getHeight();

//        if (zeroNavigationBarHeightForConfiguration()) {
//            bottomNavigationBarHeight = 0;
//        }
        val conversationItemSnapshot = selectedConversationModel.bitmap
        conversationItem!!.layoutParams = LayoutParams(conversationItemSnapshot.width, conversationItemSnapshot.height)
        conversationItem!!.background = BitmapDrawable(resources, conversationItemSnapshot)
        val isMessageOnLeft = selectedConversationModel.isOutgoing xor ViewUtil.isLtr(this)
        conversationItem!!.scaleX = 0.95f
        conversationItem!!.scaleY = 0.95f
        visibility = INVISIBLE
        this.activity = activity
        updateSystemUiOnShow(activity)
        this.doOnLayout { v: View? ->
            showAfterLayout(activity, rootView, conversationMessage, lastSeenDownPoint, isMessageOnLeft)
            Unit
        }
    }

    private fun showAfterLayout(
        activity: Activity,
        rootView: View,
        conversationMessage: ChatMessage,
        lastSeenDownPoint: PointF,
        isMessageOnLeft: Boolean
    ) {
//        updateToolbarShade();
//        updateInputShade();
        contextMenu = ConversationContextMenu(dropdownAnchor!!, getMenuActionItems(rootView, conversationMessage))
        conversationItem!!.x = selectedConversationModel!!.snapshotMetrics.snapshotOffset
        conversationItem!!.y = selectedConversationModel!!.itemY + selectedConversationModel!!.bubbleY - statusBarHeight
        val conversationItemSnapshot = selectedConversationModel!!.bitmap
        val isWideLayout = contextMenu!!.getMaxWidth() + scrubberWidth < width
        val overlayHeight = height - bottomNavigationBarHeight
        val bubbleWidth = selectedConversationModel!!.bubbleWidth
        var endX = selectedConversationModel!!.snapshotMetrics.snapshotOffset
        var endY = conversationItem!!.y
        var endApparentTop = endY
        var endScale = 1f
        val menuPadding = DimensionUnit.DP.toPixels(12f)
        val reactionBarTopPadding = DimensionUnit.DP.toPixels(32f)
        val reactionBarHeight = backgroundView!!.height
        var reactionBarBackgroundY: Float
        if (isWideLayout) {
            val everythingFitsVertically = reactionBarHeight + menuPadding + reactionBarTopPadding + conversationItemSnapshot.height < overlayHeight
            if (everythingFitsVertically) {
                val reactionBarFitsAboveItem = conversationItem!!.y > reactionBarHeight + menuPadding + reactionBarTopPadding
                if (reactionBarFitsAboveItem) {
                    reactionBarBackgroundY = conversationItem!!.y - menuPadding - reactionBarHeight
                } else {
                    endY = reactionBarHeight + menuPadding + reactionBarTopPadding
                    reactionBarBackgroundY = reactionBarTopPadding
                }
            } else {
                val spaceAvailableForItem = overlayHeight - reactionBarHeight - menuPadding - reactionBarTopPadding
                endScale = spaceAvailableForItem / conversationItem!!.height
                endX += Util.halfOffsetFromScale(conversationItemSnapshot.width, endScale) * if (isMessageOnLeft) -1 else 1
                endY = reactionBarHeight + menuPadding + reactionBarTopPadding - Util.halfOffsetFromScale(conversationItemSnapshot.height, endScale)
                reactionBarBackgroundY = reactionBarTopPadding
            }
        } else {
            val reactionBarOffset = DimensionUnit.DP.toPixels(48f)
            val spaceForReactionBar = Math.max(reactionBarHeight + reactionBarOffset - conversationItemSnapshot.height, 0f)
            val everythingFitsVertically = contextMenu!!.getMaxHeight() + conversationItemSnapshot.height + menuPadding + spaceForReactionBar < overlayHeight
            if (everythingFitsVertically) {
                val bubbleBottom = selectedConversationModel!!.itemY + selectedConversationModel!!.bubbleY + conversationItemSnapshot.height
                val menuFitsBelowItem = bubbleBottom + menuPadding + contextMenu!!.getMaxHeight() <= overlayHeight + statusBarHeight
                if (menuFitsBelowItem) {
                    if (conversationItem!!.y < 0) {
                        endY = 0f
                    }
                    val contextMenuTop = endY + conversationItemSnapshot.height
                    reactionBarBackgroundY = getReactionBarOffsetForTouch(lastSeenDownPoint, contextMenuTop, menuPadding, reactionBarOffset, reactionBarHeight, reactionBarTopPadding, endY)
                    if (reactionBarBackgroundY <= reactionBarTopPadding) {
                        endY = backgroundView!!.height + menuPadding + reactionBarTopPadding
                    }
                } else {
                    endY = overlayHeight - contextMenu!!.getMaxHeight() - menuPadding - conversationItemSnapshot.height
                    val contextMenuTop = endY + conversationItemSnapshot.height
                    reactionBarBackgroundY = getReactionBarOffsetForTouch(lastSeenDownPoint, contextMenuTop, menuPadding, reactionBarOffset, reactionBarHeight, reactionBarTopPadding, endY)
                }
                endApparentTop = endY
            } else if (reactionBarOffset + reactionBarHeight + contextMenu!!.getMaxHeight() + menuPadding < overlayHeight) {
                val spaceAvailableForItem = overlayHeight.toFloat() - contextMenu!!.getMaxHeight() - menuPadding - spaceForReactionBar
                endScale = spaceAvailableForItem / conversationItemSnapshot.height
                endX += Util.halfOffsetFromScale(conversationItemSnapshot.width, endScale) * if (isMessageOnLeft) -1 else 1
                endY = spaceForReactionBar - Util.halfOffsetFromScale(conversationItemSnapshot.height, endScale)
                val contextMenuTop = endY + conversationItemSnapshot.height * endScale
                reactionBarBackgroundY = getReactionBarOffsetForTouch(lastSeenDownPoint, contextMenuTop + Util.halfOffsetFromScale(conversationItemSnapshot.height, endScale), menuPadding, reactionBarOffset, reactionBarHeight, reactionBarTopPadding, endY)
                endApparentTop = endY + Util.halfOffsetFromScale(conversationItemSnapshot.height, endScale)
            } else {
                contextMenu!!.height = contextMenu!!.getMaxHeight() / 2
                val menuHeight = contextMenu!!.height
                val fitsVertically = menuHeight + conversationItem!!.height + menuPadding * 2 + reactionBarHeight + reactionBarTopPadding < overlayHeight
                if (fitsVertically) {
                    val bubbleBottom = selectedConversationModel!!.itemY + selectedConversationModel!!.bubbleY + conversationItemSnapshot.height
                    val menuFitsBelowItem = bubbleBottom + menuPadding + menuHeight <= overlayHeight + statusBarHeight
                    if (menuFitsBelowItem) {
                        reactionBarBackgroundY = conversationItem!!.y - menuPadding - reactionBarHeight
                        if (reactionBarBackgroundY < reactionBarTopPadding) {
                            endY = reactionBarTopPadding + reactionBarHeight + menuPadding
                            reactionBarBackgroundY = reactionBarTopPadding
                        }
                    } else {
                        endY = overlayHeight - menuHeight - menuPadding - conversationItemSnapshot.height
                        reactionBarBackgroundY = endY - reactionBarHeight - menuPadding
                    }
                    endApparentTop = endY
                } else {
                    val spaceAvailableForItem = overlayHeight.toFloat() - menuHeight - menuPadding * 2 - reactionBarHeight - reactionBarTopPadding
                    endScale = spaceAvailableForItem / conversationItemSnapshot.height
                    endX += Util.halfOffsetFromScale(conversationItemSnapshot.width, endScale) * if (isMessageOnLeft) -1 else 1
                    endY = reactionBarHeight - Util.halfOffsetFromScale(conversationItemSnapshot.height, endScale) + menuPadding + reactionBarTopPadding
                    reactionBarBackgroundY = reactionBarTopPadding
                    endApparentTop = reactionBarHeight + menuPadding + reactionBarTopPadding
                }
            }
        }
        reactionBarBackgroundY = Math.max(reactionBarBackgroundY, -statusBarHeight.toFloat())
        hideAnimatorSet.end()
        visibility = VISIBLE
        val scrubberX: Float = if (isMessageOnLeft) {
            scrubberHorizontalMargin.toFloat() + 14.dp
        } else {
            (width - scrubberWidth - scrubberHorizontalMargin).toFloat()
        }
        foregroundView!!.x = scrubberX
        foregroundView!!.y = reactionBarBackgroundY + reactionBarHeight / 2f - foregroundView!!.height / 2f
        backgroundView!!.x = scrubberX
        backgroundView!!.y = reactionBarBackgroundY
        verticalScrubBoundary.update(
            reactionBarBackgroundY,
            lastSeenDownPoint.y + distanceFromTouchDownPointToBottomOfScrubberDeadZone
        )

//        updateBoundsOnLayoutChanged();
        revealAnimatorSet.start()
        if (isWideLayout) {
            val scrubberRight = scrubberX + scrubberWidth
            val offsetX = if (isMessageOnLeft) scrubberRight + menuPadding else scrubberX - contextMenu!!.getMaxWidth() - menuPadding
            contextMenu!!.show(offsetX.toInt(), Math.min(backgroundView!!.y, (overlayHeight - contextMenu!!.getMaxHeight()).toFloat()).toInt())
        } else {
            val contentX = selectedConversationModel!!.snapshotMetrics.contextMenuPadding
            val offsetX = if (isMessageOnLeft) contentX else -contextMenu!!.getMaxWidth() + contentX + bubbleWidth
            val menuTop = endApparentTop + conversationItemSnapshot.height * endScale
            contextMenu!!.show(offsetX.toInt(), (menuTop + menuPadding).toInt())
        }
        val revealDuration = 200
        conversationItem!!.animate()
            .x(endX)
            .y(endY)
            .scaleX(endScale)
            .scaleY(endScale)
            .setDuration(revealDuration.toLong())
    }

    private fun getReactionBarOffsetForTouch(
        touchPoint: PointF,
        contextMenuTop: Float,
        contextMenuPadding: Float,
        reactionBarOffset: Float,
        reactionBarHeight: Int,
        spaceNeededBetweenTopOfScreenAndTopOfReactionBar: Float,
        messageTop: Float
    ): Float {
        val adjustedTouchY = touchPoint.y - statusBarHeight
        var reactionStartingPoint = Math.min(adjustedTouchY, contextMenuTop)
        val spaceBetweenTopOfMessageAndTopOfContextMenu = Math.abs(messageTop - contextMenuTop)
        if (spaceBetweenTopOfMessageAndTopOfContextMenu < DimensionUnit.DP.toPixels(150f)) {
            val offsetToMakeReactionBarOffsetMatchMenuPadding = reactionBarOffset - contextMenuPadding
            reactionStartingPoint = messageTop + offsetToMakeReactionBarOffsetMatchMenuPadding
        }
        return Math.max(reactionStartingPoint - reactionBarOffset - reactionBarHeight, spaceNeededBetweenTopOfScreenAndTopOfReactionBar)
    }
    //    private void updateToolbarShade() {
    //        LayoutParams layoutParams = (LayoutParams) toolbarShade.getLayoutParams();
    //        layoutParams.height = 0;
    //        toolbarShade.setLayoutParams(layoutParams);
    //    }
    //    private void updateInputShade() {
    //        LayoutParams layoutParams = (LayoutParams) inputShade.getLayoutParams();
    //        layoutParams.height = 0;
    //        inputShade.setLayoutParams(layoutParams);
    //    }
    /**
     * Returns true when the device is in a configuration where the navigation bar doesn't take up
     * space at the bottom of the screen.
     */
    private fun zeroNavigationBarHeightForConfiguration(): Boolean {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (Build.VERSION.SDK_INT >= 29) {
            rootWindowInsets.systemGestureInsets.bottom == 0 && isLandscape
        } else {
            isLandscape
        }
    }

    private fun updateSystemUiOnShow(activity: Activity) {
        val window = activity.window
        val barColor = ContextCompat.getColor(context, R.color.conversation_item_selected_system_ui)
        originalStatusBarColor = window.statusBarColor
        WindowUtil.setStatusBarColor(window, barColor)
        originalNavigationBarColor = window.navigationBarColor
        WindowUtil.setNavigationBarColor(activity, barColor)
        if (!ThemeUtil.isDarkTheme(context)) {
            WindowUtil.clearLightStatusBar(window)
            WindowUtil.clearLightNavigationBar(window)
        }
    }

    fun hide() {
        hideInternal(onHideListener)
    }

    fun hideForReactWithAny() {
        hideInternal(onHideListener)
    }

    private fun hideInternal(onHideListener: OnHideListener?) {
        overlayState = OverlayState.HIDDEN
        val animatorSet = newHideAnimatorSet()
        hideAnimatorSet = animatorSet
        revealAnimatorSet.end()
        animatorSet.start()
        onHideListener?.startHide(selectedConversationModel!!.focusedView)
        animatorSet.addListener(object : AnimationCompleteListener() {
            override fun onAnimationEnd(animation: Animator) {
                animatorSet.removeListener(this)

//                toolbarShade.setVisibility(INVISIBLE);
//                inputShade.setVisibility(INVISIBLE);
                onHideListener?.onHide()
            }
        })
        if (contextMenu != null) {
            contextMenu!!.dismiss()
        }
    }

    val isShowing: Boolean
        get() = overlayState != OverlayState.HIDDEN

    fun getMessageRecord(): ChatMessage {
        return messageRecord!!
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)

//        updateBoundsOnLayoutChanged();
    }

    //    private void updateBoundsOnLayoutChanged() {
    //        backgroundView.getGlobalVisibleRect(emojiStripViewBounds);
    //        emojiViews[0].getGlobalVisibleRect(emojiViewGlobalRect);
    //        emojiStripViewBounds.left = getStart(emojiViewGlobalRect);
    //        emojiViews[emojiViews.length - 1].getGlobalVisibleRect(emojiViewGlobalRect);
    //        emojiStripViewBounds.right = getEnd(emojiViewGlobalRect);
    //
    //        segmentSize = emojiStripViewBounds.width() / (float) emojiViews.length;
    //    }
    private fun getStart(rect: Rect): Int {
        return if (ViewUtil.isLtr(this)) {
            rect.left
        } else {
            rect.right
        }
    }

    private fun getEnd(rect: Rect): Int {
        return if (ViewUtil.isLtr(this)) {
            rect.right
        } else {
            rect.left
        }
    }

    fun applyTouchEvent(motionEvent: MotionEvent): Boolean {
        check(isShowing) { "Touch events should only be propagated to this method if we are displaying the scrubber." }
        if (motionEvent.action and MotionEvent.ACTION_POINTER_INDEX_MASK != 0) {
            return true
        }
        if (overlayState == OverlayState.UNINITAILIZED) {
            downIsOurs = false
            deadzoneTouchPoint[motionEvent.x] = motionEvent.y
            overlayState = OverlayState.DEADZONE
        }
        if (overlayState == OverlayState.DEADZONE) {
            val deltaX = Math.abs(deadzoneTouchPoint.x - motionEvent.x)
            val deltaY = Math.abs(deadzoneTouchPoint.y - motionEvent.y)
            if (deltaX > touchDownDeadZoneSize || deltaY > touchDownDeadZoneSize) {
                overlayState = OverlayState.SCRUB
            } else {
                if (motionEvent.action == MotionEvent.ACTION_UP) {
                    overlayState = OverlayState.TAP
                    if (downIsOurs) {
                        handleUpEvent()
                        return false
                    }
                }
                return MotionEvent.ACTION_MOVE == motionEvent.action
            }
        }
        return when (motionEvent.action) {
            MotionEvent.ACTION_DOWN -> {
                //                selected = getSelectedIndexViaDownEvent(motionEvent);
                deadzoneTouchPoint[motionEvent.x] = motionEvent.y
                overlayState = OverlayState.DEADZONE
                downIsOurs = true
                false
            }

            MotionEvent.ACTION_MOVE -> //                selected = getSelectedIndexViaMoveEvent(motionEvent);
                false

            MotionEvent.ACTION_UP -> //                handleUpEvent();
                downIsOurs

            MotionEvent.ACTION_CANCEL -> //                hide();
                downIsOurs

            else -> false
        }
    }

    //    private void setupSelectedEmoji() {
    //        final List<String> emojis = SignalStore.emojiValues().getReactions();
    //        final String oldEmoji = getOldEmoji(messageRecord);
    //
    //        if (oldEmoji == null) {
    //            selectedView.setVisibility(View.GONE);
    //        }
    //
    //        boolean foundSelected = false;
    //
    //        for (int i = 0; i < emojiViews.length; i++) {
    //            final EmojiImageView view = emojiViews[i];
    //
    //            view.setScaleX(1.0f);
    //            view.setScaleY(1.0f);
    //            view.setTranslationY(0);
    //
    //            boolean isAtCustomIndex = i == customEmojiIndex;
    //            boolean isNotAtCustomIndexAndOldEmojiMatches = !isAtCustomIndex && oldEmoji != null;
    ////                    && EmojiUtil.isCanonicallyEqual(emojis.get(i), oldEmoji);
    //            boolean isAtCustomIndexAndOldEmojiExists = isAtCustomIndex && oldEmoji != null;
    //
    //            if (!foundSelected &&
    //                    (isNotAtCustomIndexAndOldEmojiMatches || isAtCustomIndexAndOldEmojiExists)) {
    //                foundSelected = true;
    //                selectedView.setVisibility(View.VISIBLE);
    //
    //                ConstraintSet constraintSet = new ConstraintSet();
    //                constraintSet.clone(foregroundView);
    //                constraintSet.clear(selectedView.getId(), ConstraintSet.LEFT);
    //                constraintSet.clear(selectedView.getId(), ConstraintSet.RIGHT);
    //                constraintSet.connect(selectedView.getId(), ConstraintSet.LEFT, view.getId(), ConstraintSet.LEFT);
    //                constraintSet.connect(selectedView.getId(), ConstraintSet.RIGHT, view.getId(), ConstraintSet.RIGHT);
    //                constraintSet.applyTo(foregroundView);
    //
    //                if (isAtCustomIndex) {
    //                    view.setImageEmoji(oldEmoji);
    //                    view.setTag(oldEmoji);
    //                } else {
    //                    view.setImageEmoji(SignalStore.emojiValues().getPreferredVariation(emojis.get(i)));
    //                }
    //            } else if (isAtCustomIndex) {
    //                view.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.ic_any_emoji_32));
    //                view.setTag(null);
    //            } else {
    //                view.setImageEmoji(SignalStore.emojiValues().getPreferredVariation(emojis.get(i)));
    //            }
    //        }
    //    }
    //    private int getSelectedIndexViaDownEvent(@NonNull MotionEvent motionEvent) {
    //        return getSelectedIndexViaMotionEvent(motionEvent, new Boundary(emojiStripViewBounds.top, emojiStripViewBounds.bottom));
    //    }
    //    private int getSelectedIndexViaMoveEvent(@NonNull MotionEvent motionEvent) {
    //        return getSelectedIndexViaMotionEvent(motionEvent, verticalScrubBoundary);
    //    }
    //    private int getSelectedIndexViaMotionEvent(@NonNull MotionEvent motionEvent, @NonNull Boundary boundary) {
    //        int selected = -1;
    //
    //        if (backgroundView.getVisibility() != View.VISIBLE) {
    //            return selected;
    //        }
    //        for (int i = 0; i < emojiViews.length; i++) {
    //            final float emojiLeft = (segmentSize * i) + emojiStripViewBounds.left;
    //            horizontalEmojiBoundary.update(emojiLeft, emojiLeft + segmentSize);
    //
    //            if (horizontalEmojiBoundary.contains(motionEvent.getX()) && boundary.contains(motionEvent.getY())) {
    //                selected = i;
    //            }
    //        }
    //
    //        if (this.selected != -1 && this.selected != selected) {
    //            shrinkView(emojiViews[this.selected]);
    //        }
    //
    //        if (this.selected != selected && selected != -1) {
    //            growView(emojiViews[selected]);
    //        }
    //        return selected;
    //    }
    //    private void growView(@NonNull View view) {
    //        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    //        view.animate()
    //                .scaleY(1.5f)
    //                .scaleX(1.5f)
    //                .translationY(-selectedVerticalTranslation)
    //                .setDuration(200)
    //                .setInterpolator(INTERPOLATOR)
    //                .start();
    //    }
    //    private void shrinkView(@NonNull View view) {
    //        view.animate()
    //                .scaleX(1.0f)
    //                .scaleY(1.0f)
    //                .translationY(0)
    //                .setDuration(200)
    //                .setInterpolator(INTERPOLATOR)
    //                .start();
    //    }
    private fun handleUpEvent() {
//        if (selected != -1 && onReactionSelectedListener != null && backgroundView.getVisibility() == View.VISIBLE) {
//            if (selected == customEmojiIndex) {
//                onReactionSelectedListener.onCustomReactionSelected(messageRecord, emojiViews[selected].getTag() != null);
//            } else {
//                onReactionSelectedListener.onReactionSelected(messageRecord, SignalStore.emojiValues().getPreferredVariation(SignalStore.emojiValues().getReactions().get(selected)));
//            }
//        } else {
        hide()
        //        }
    }

    fun setOnReactionSelectedListener(onReactionSelectedListener: OnReactionSelectedListener?) {
        this.onReactionSelectedListener = onReactionSelectedListener
    }

    fun setOnActionSelectedListener(onActionSelectedListener: OnActionSelectedListener?) {
        this.onActionSelectedListener = onActionSelectedListener
    }

    fun setOnHideListener(onHideListener: OnHideListener?) {
        this.onHideListener = onHideListener
    }

    //    private static @Nullable String getOldEmoji(@NonNull ChatMessage messageRecord) {
    //        return Stream.of(messageRecord.getReactions())
    //                .filter(record -> record.getAuthor()
    //                        .serialize()
    //                        .equals(Recipient.self()
    //                                .getId()
    //                                .serialize()))
    //                .findFirst()
    //                .map(ReactionRecord::getEmoji)
    //                .orElse(null);
    //        return null;
    //    }
    private fun getMenuActionItems(rootView: View, data: ChatMessage): List<ActionItem> {
        val items: MutableList<ActionItem> = ArrayList()
        if (selectedConversationModel?.isForForward == true) {
            if (canDownload(data)) {
                items.add(ActionItem(R.drawable.symbol_save_android_24, resources.getString(R.string.chat_message_action_download), action = { handleActionItemClicked(Action.SAVE, rootView) }))
            }
        } else {
            if (data.mode != SignalServiceProtos.Mode.CONFIDENTIAL_VALUE) {
                items.add(ActionItem(R.drawable.chat_message_action_quote, resources.getString(R.string.chat_message_action_quote), action = { handleActionItemClicked(Action.QUOTE, rootView) }))
                if (data is TextChatMessage && hasTextContent(data)) {
                    items.add(ActionItem(R.drawable.chat_message_action_copy, resources.getString(R.string.chat_message_action_copy), action = { handleActionItemClicked(Action.COPY, rootView) }))
                }
                if (data is TextChatMessage && hasTextContent(data)) {
                    if (data.translateData == null || data.translateData?.translateStatus == TranslateStatus.Invisible) {
                        items.add(ActionItem(R.drawable.chat_message_action_translate, resources.getString(R.string.chat_message_action_translate), action = { handleActionItemClicked(Action.TRANSLATE, rootView) }))
                    } else {
                        items.add(ActionItem(R.drawable.chat_message_action_translate, resources.getString(R.string.chat_message_action_translate_off), action = { handleActionItemClicked(Action.TRANSLATE_OFF, rootView) }))
                    }
                }
                if (data is TextChatMessage && data.attachment?.isAudioMessage() != true && data.attachment?.isAudioMessage() != true) {
                    items.add(
                        ActionItem(
                            R.drawable.chat_message_action_forward,
                            resources.getString(R.string.chat_message_action_forward),
                            action = { handleActionItemClicked(Action.FORWARD, rootView) })
                    )
                }
                if (data is TextChatMessage && data.attachment?.isAudioMessage() == true) { // flags == 1 表示音频消息，且是语音转文字消息时显示语音转文字按钮
                    if (data.speechToTextData == null || data.speechToTextData?.convertStatus != SpeechToTextStatus.Show) {
                        items.add(
                            ActionItem(
                                R.drawable.chat_message_action_voice2text,
                                resources.getString(R.string.chat_message_action_voice2text),
                                action = { handleActionItemClicked(Action.SPEECH_TO_TEXT, rootView) })
                        )
                    } else {
                        items.add(ActionItem(R.drawable.chat_message_action_voice2text_off, resources.getString(R.string.chat_message_action_voice2text_off), action = { handleActionItemClicked(Action.SPEECH_TO_TEXT_OFF, rootView) }))
                    }
                }
                if (canDownload(data)) {
                    items.add(ActionItem(R.drawable.symbol_save_android_24, resources.getString(R.string.chat_message_action_download), action = { handleActionItemClicked(Action.SAVE, rootView) }))
                }
                if (data is TextChatMessage && data.attachment?.isAudioMessage() != true && data.attachment?.isAudioFile() != true) {
                    items.add(
                        ActionItem(
                            R.drawable.chat_message_action_select_multiple,
                            resources.getString(R.string.chat_message_action_select_multiple),
                            action = { handleActionItemClicked(Action.MULTISELECT, rootView) })
                    )
                }
                items.add(ActionItem(R.drawable.chat_icon_favorites, resources.getString(R.string.chat_message_action_save_to_note), action = { handleActionItemClicked(Action.SAVE_TO_NOTE, rootView) }))
            }
            if (selectedConversationModel?.isSaved == true) {
                items.add(ActionItem(R.drawable.chat_message_action_delete, resources.getString(R.string.chat_message_action_delete), action = { handleActionItemClicked(Action.DELETE_SAVED, rootView) }))
            }
            val recallTimeoutInterval = (globalServices.globalConfigsManager.getNewGlobalConfigs()?.data?.recall?.timeoutInterval ?: (24 * 60 * 60)) * 1000
            L.i { "[ConversationReactionOverlay] recallTimeoutInterval:$recallTimeoutInterval" }
            if (data.isMine && (System.currentTimeMillis() - data.systemShowTimestamp <= recallTimeoutInterval)) {
                items.add(ActionItem(R.drawable.chat_message_action_recall, resources.getString(R.string.chat_message_action_recall), com.difft.android.base.R.color.error, action = { handleActionItemClicked(Action.RECALL, rootView) }))
            }
            items.add(ActionItem(R.drawable.chat_contact_detail_ic_more, resources.getString(R.string.chat_message_action_more_info), action = { handleActionItemClicked(Action.MORE_INFO, rootView) }))


//        backgroundView.setVisibility(menuState.shouldShowReactions() ? View.VISIBLE : View.INVISIBLE);
//        foregroundView.setVisibility(menuState.shouldShowReactions() ? View.VISIBLE : View.INVISIBLE);
        }
        return items
    }

    //是否可以下载
    private fun canDownload(data: ChatMessage): Boolean {
        if (data is TextChatMessage) {
            if (data.isAttachmentMessage()
                && (data.attachment?.isAudioMessage() != true)
                && (data.attachment?.status == AttachmentStatus.SUCCESS.code || FileUtil.progressMap[data.id] == 100)
            ) {
                return true
            }

            val forwards = data.forwardContext?.forwards
            if (forwards?.size == 1) {
                val forward = forwards.firstOrNull()
                if (forward?.attachments?.isNotEmpty() == true
                    && forward.attachments?.firstOrNull()?.isAudioMessage() != true
                    && (forward.attachments?.firstOrNull()?.status == AttachmentStatus.SUCCESS.code || FileUtil.progressMap[forward.attachments?.firstOrNull()?.authorityId.toString()] == 100)
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun hasTextContent(data: TextChatMessage): Boolean {
        if (data.isAttachmentMessage()) {
            return false
        }

        data.forwardContext?.forwards?.let { forwards ->
            if (forwards.size == 1) {
                val forward = forwards.firstOrNull()
                if (!forward?.card?.content.isNullOrEmpty() || !forward?.text.isNullOrEmpty()) {
                    return true
                }
            }
        } ?: run {
            if (!data.card?.content.isNullOrEmpty() || !data.message.isNullOrEmpty()) {
                return true
            }
        }

        return false
    }

    private fun handleActionItemClicked(action: Action, rootView: View) {
        hideInternal(object : OnHideListener {
            override fun startHide(focusedView: View?) {
                if (onHideListener != null) {
                    onHideListener!!.startHide(focusedView)
                }
            }

            override fun onHide() {
                if (onHideListener != null) {
                    onHideListener!!.onHide()
                }
                if (onActionSelectedListener != null) {
                    onActionSelectedListener!!.onActionSelected(action, rootView)
                }
            }
        })
    }

    private fun initAnimators() {
        val revealDuration = 200
        val revealOffset = 100
        val reveals: MutableList<Animator> = ArrayList()
        //                Stream.of(emojiViews)
//                .mapIndexed((idx, v) -> {
//                    Animator anim = AnimatorInflater.loadAnimator(getContext(), R.animator.reactions_scrubber_reveal);
//                    anim.setTarget(v);
//                    anim.setStartDelay((long) idx * animationEmojiStartDelayFactor);
//                    return anim;
//                })
//                .toList();
        val backgroundRevealAnim = AnimatorInflater.loadAnimator(context, android.R.animator.fade_in)
        backgroundRevealAnim.setTarget(backgroundView)
        backgroundRevealAnim.setDuration(revealDuration.toLong())
        backgroundRevealAnim.startDelay = revealOffset.toLong()
        reveals.add(backgroundRevealAnim)

//        Animator selectedRevealAnim = AnimatorInflater.loadAnimator(getContext(), android.R.animator.fade_in);
//        selectedRevealAnim.setTarget(selectedView);
//        backgroundRevealAnim.setDuration(revealDuration);
//        backgroundRevealAnim.setStartDelay(revealOffset);
//        reveals.add(selectedRevealAnim);
        revealAnimatorSet.interpolator = INTERPOLATOR
        revealAnimatorSet.playTogether(reveals)
    }

    private fun newHideAnimatorSet(): AnimatorSet {
        val set = AnimatorSet()
        set.addListener(object : AnimationCompleteListener() {
            override fun onAnimationEnd(animation: Animator) {
                visibility = GONE
            }
        })
        set.interpolator = INTERPOLATOR
        set.playTogether(newHideAnimators())
        return set
    }

    private fun newHideAnimators(): List<Animator> {
        val duration = 150
        val animators: MutableList<Animator> = ArrayList()
        //                new ArrayList<>(Stream.of(emojiViews)
//                .mapIndexed((idx, v) -> {
//                    Animator anim = AnimatorInflater.loadAnimator(getContext(), R.animator.reactions_scrubber_hide);
//                    anim.setTarget(v);
//                    return anim;
//                })
//                .toList());
        val backgroundHideAnim = AnimatorInflater.loadAnimator(context, android.R.animator.fade_out)
        backgroundHideAnim.setTarget(backgroundView)
        backgroundHideAnim.setDuration(duration.toLong())
        animators.add(backgroundHideAnim)

//        Animator selectedHideAnim = AnimatorInflater.loadAnimator(getContext(), android.R.animator.fade_out);
//        selectedHideAnim.setTarget(selectedView);
//        selectedHideAnim.setDuration(duration);
//        animators.add(selectedHideAnim);
        val itemScaleXAnim = ObjectAnimator()
        itemScaleXAnim.setProperty(SCALE_X)
        itemScaleXAnim.setFloatValues(1f)
        itemScaleXAnim.target = conversationItem
        itemScaleXAnim.setDuration(duration.toLong())
        animators.add(itemScaleXAnim)
        val itemScaleYAnim = ObjectAnimator()
        itemScaleYAnim.setProperty(SCALE_Y)
        itemScaleYAnim.setFloatValues(1f)
        itemScaleYAnim.target = conversationItem
        itemScaleYAnim.setDuration(duration.toLong())
        animators.add(itemScaleYAnim)
        val itemXAnim = ObjectAnimator()
        itemXAnim.setProperty(X)
        itemXAnim.setFloatValues(selectedConversationModel!!.snapshotMetrics.snapshotOffset)
        itemXAnim.target = conversationItem
        itemXAnim.setDuration(duration.toLong())
        animators.add(itemXAnim)
        val itemYAnim = ObjectAnimator()
        itemYAnim.setProperty(Y)
        itemYAnim.setFloatValues(selectedConversationModel!!.itemY + selectedConversationModel!!.bubbleY - statusBarHeight)
        itemYAnim.target = conversationItem
        itemYAnim.setDuration(duration.toLong())
        animators.add(itemYAnim)
        if (activity != null) {
            val statusBarAnim = ValueAnimator.ofArgb(activity!!.window.statusBarColor, originalStatusBarColor)
            statusBarAnim.setDuration(duration.toLong())
            statusBarAnim.addUpdateListener { animation: ValueAnimator -> WindowUtil.setStatusBarColor(activity!!.window, animation.animatedValue as Int) }
            animators.add(statusBarAnim)
            val navigationBarAnim = ValueAnimator.ofArgb(activity!!.window.statusBarColor, originalNavigationBarColor)
            navigationBarAnim.setDuration(duration.toLong())
            navigationBarAnim.addUpdateListener { animation: ValueAnimator -> WindowUtil.setNavigationBarColor(activity!!, animation.animatedValue as Int) }
            animators.add(navigationBarAnim)
        }
        return animators
    }

    interface OnHideListener {
        fun startHide(focusedView: View?)
        fun onHide()
    }

    interface OnReactionSelectedListener {
        fun onReactionSelected(messageRecord: ChatMessage, emoji: String, remove: Boolean) //        void onCustomReactionSelected(@NonNull ChatMessage messageRecord, boolean hasAddedCustomEmoji);
    }

    interface OnActionSelectedListener {
        fun onActionSelected(action: Action, rootView: View)
    }

    private class Boundary {
        private var min = 0f
        private var max = 0f

        internal constructor()
        internal constructor(min: Float, max: Float) {
            update(min, max)
        }

        fun update(min: Float, max: Float) {
            this.min = min
            this.max = max
        }

        operator fun contains(value: Float): Boolean {
            return if (min < max) {
                min < value && max > value
            } else {
                min > value && max < value
            }
        }
    }

    private enum class OverlayState {
        HIDDEN,
        UNINITAILIZED,
        DEADZONE,
        SCRUB,
        TAP
    }

    enum class Action {
        EDIT,
        FORWARD,
        RESEND,
        DOWNLOAD,
        COPY,
        MULTISELECT,
        PAYMENT_DETAILS,
        VIEW_INFO,
        DELETE,
        QUOTE,
        RECALL,
        PIN,
        UNPIN,
        TRANSLATE,
        TRANSLATE_OFF,
        MORE_INFO,
        SAVE,
        SAVE_TO_NOTE,
        SPEECH_TO_TEXT,
        SPEECH_TO_TEXT_OFF,
        DELETE_SAVED,
    }

    companion object {
        private val INTERPOLATOR: Interpolator = DecelerateInterpolator()
    }
}