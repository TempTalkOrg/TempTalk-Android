package com.difft.android.chat.ui.messageaction

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.chat.message.TextChatMessage
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.time.Duration
import kotlin.test.assertNotNull








internal class ListScrollFixture(
    private val composeRule: ComposeTestRule,
    globalConfigsManager: IGlobalConfigsManager,
) {
    class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var anchorText = (0 until LINE_COUNT).joinToString("\n") { "Line $it" }

        
        var leadHeight = FILLER_HEIGHT

        
        var anchorPaddingTop = 0

        



        var truncateToLines: Int? = null

        override fun getItemCount() = ITEM_COUNT
        override fun getItemViewType(position: Int) = if (position == ANCHOR) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val context = parent.context
            val view = if (viewType == 1) {
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val textWidth = (TEXT_WIDTH_DP * context.resources.displayMetrics.density).toInt()
                    addView(TextView(context).apply { id = TEXT_ID }, LinearLayout.LayoutParams(textWidth, WRAP))
                    layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                }
            } else {
                View(context).apply { layoutParams = RecyclerView.LayoutParams(MATCH, FILLER_HEIGHT) }
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = holder.itemView
            when (position) {
                ANCHOR -> {
                    item.setPadding(0, anchorPaddingTop, 0, 0)
                    val textView = item.findViewById<TextView>(TEXT_ID)
                    textView.text = anchorText
                    truncateToLines?.let { lines ->
                        textView.doOnPreDraw { textView.text = anchorText.lines().take(lines).joinToString("\n") + "\nRead more" }
                    }
                }
                else -> item.layoutParams = item.layoutParams.apply { height = if (position == 0) leadHeight else FILLER_HEIGHT }
            }
        }
    }

    private var controller: ActivityController<FragmentActivity>? = null
    val activity: FragmentActivity =
        Robolectric.buildActivity(FragmentActivity::class.java).setup().also { controller = it }.get()
    val adapter = Adapter()
    val rv = RecyclerView(activity).apply {
        layoutManager = LinearLayoutManager(activity)
        adapter = this@ListScrollFixture.adapter
        itemAnimator = null
    }
    val coordinator = MessageActionCoordinator(activity, globalConfigsManager, translateSupported = true)
    lateinit var bubble: View
    lateinit var textView: TextView
    private var downTime = 0L

    init {
        activity.setContentView(FrameLayout(activity).apply { addView(rv, FrameLayout.LayoutParams(MATCH, MATCH)) })
        idle()
    }

    val popup: MessageActionPopup get() = assertNotNull(coordinator.popupForTest)
    val manager: TextSelectionManager get() = assertNotNull(coordinator.selectionManagerForTest)
    val overlay: MessageActionOverlayLayout get() = popup.getOverlayContainer() as MessageActionOverlayLayout
    val highlight: View get() = (0 until overlay.childCount).map(overlay::getChildAt).single { it is SelectionHighlightView }
    val gestureX: Float get() = overlay.width - 10f

    
    fun showAndIdle(withText: Boolean = true) {
        bubble = assertNotNull(rv.findViewHolderForAdapterPosition(ANCHOR)).itemView
        textView = bubble.findViewById(TEXT_ID)
        val message = TextChatMessage().apply {
            id = "msg-1"
            authorId = "author-1"
            isMine = true
            message = textView.text.toString()
        }
        coordinator.show(
            message = message,
            messageView = bubble,
            textView = textView.takeIf { withText },
            mostUseEmojis = listOf("👍"),
            isForForward = false,
            isSaved = false,
            touchPoint = Point(0, 0),
            containerView = rv,
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        settle()
        
        send(MotionEvent.ACTION_DOWN, gestureX, 10f)
        send(MotionEvent.ACTION_CANCEL, gestureX, 10f)
    }

    
    fun settle() {
        repeat(3) {
            Snapshot.sendApplyNotifications()
            composeRule.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            popup.getOverlayContainer()?.draw(Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
        }
    }

    fun send(action: Int, x: Float, y: Float, timeOffsetMs: Long = 0) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(downTime, downTime + timeOffsetMs, action, x, y, 0)
        try {
            overlay.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    
    fun dragUp(fromY: Float, step: Float, steps: Int): Float {
        send(MotionEvent.ACTION_DOWN, gestureX, fromY)
        var y = fromY
        repeat(steps) {
            y -= step
            send(MotionEvent.ACTION_MOVE, gestureX, y, 20L * (it + 1))
        }
        return y
    }

    
    fun lift(y: Float) {
        send(MotionEvent.ACTION_MOVE, gestureX, y, 5_000)
        send(MotionEvent.ACTION_UP, gestureX, y, 5_000)
    }

    fun release(y: Float) {
        lift(y)
        idle()
    }

    fun highlightOffsetFromText(): Float {
        val o = IntArray(2).also(overlay::getLocationOnScreen)
        val t = IntArray(2).also(textView::getLocationOnScreen)
        return highlight.y - (t[1] - o[1])
    }

    
    fun expectedBubbleBounds(): PopupRect {
        val bounds = Rect().also(bubble::getGlobalVisibleRect)
        val root = Rect().also { activity.findViewById<View>(android.R.id.content).getGlobalVisibleRect(it) }
        return bounds.toRootRelative(root)
    }

    fun destroy() {
        runCatching { controller?.destroy() }
    }

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val ITEM_COUNT = 30
        const val ANCHOR = 1
        const val FILLER_HEIGHT = 300
        const val TEXT_WIDTH_DP = 200
        const val LINE_COUNT = 80
        const val SCROLL_PAST_ANCHOR = 8_000
        val TEXT_ID = View.generateViewId()

        fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    }
}
