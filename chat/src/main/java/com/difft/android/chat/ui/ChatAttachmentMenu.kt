package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.widget.FloatingMenuMotion
import com.difft.android.base.widget.applyFloatingSurface
import com.difft.android.base.widget.getSafeActivity
import kotlin.math.roundToInt
import com.difft.android.base.R as BaseR










internal class ChatAttachmentMenu(
    private val source: View,
    private val area: View,
    private val owner: LifecycleOwner,
    items: List<Item>,
    private val onDismissed: () -> Unit,
) {
    class Item(@param:DrawableRes val icon: Int, val label: String, val action: () -> Unit)

    private val context: Context = source.context
    private val density = context.resources.displayMetrics.density
    private val host: ViewGroup? = context.getSafeActivity()?.findViewById(android.R.id.content)
    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(PADDING_VERTICAL_DP)
        setPadding(0, pad, 0, pad)
        items.forEach { addView(row(it)) }
    }
    private val card = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        applyFloatingSurface(cornerRadiusPx = context.resources.getDimension(BaseR.dimen.radius_floating), clipsContent = true)
        clipToOutline = true
        addView(rows, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private val overlay = Overlay(context).apply {
        clipChildren = false
        
        addView(card, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.LEFT))
    }
    private var placement: AttachmentMenuPlacement? = null
    private var dismissing = false

    var isShowing = false
        private set

    private val follow = ViewTreeObserver.OnPreDrawListener {
        if (isShowing && !dismissing) place()
        true
    }
    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = dismiss()
    }
    
    
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) remove()
    }

    fun show() {
        val root = host ?: return
        if (isShowing) return
        isShowing = true
        root.addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        place()
        overlay.viewTreeObserver.addOnPreDrawListener(follow)
        owner.lifecycle.addObserver(lifecycleObserver)
        (context.getSafeActivity() as? ComponentActivity)?.onBackPressedDispatcher?.addCallback(owner, back)

        FloatingMenuMotion.grow(card, rows)
    }

    
    fun dismiss(then: (() -> Unit)? = null) {
        if (!isShowing || dismissing) return
        dismissing = true
        back.remove()
        rows.animate().cancel()
        FloatingMenuMotion.shrink(card) {
            remove()
            
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) then?.invoke()
        }
    }

    private fun remove() {
        if (!isShowing) return
        isShowing = false
        card.animate().cancel()
        back.remove()
        owner.lifecycle.removeObserver(lifecycleObserver)
        overlay.viewTreeObserver.removeOnPreDrawListener(follow)
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        onDismissed()
    }

    private fun place() {
        val root = host ?: return
        val origin = IntArray(2).also(root::getLocationInWindow)
        val sourceBox = source.boxIn(origin)
        val areaBox = area.boxIn(origin).let { it.copy(top = it.top + area.paddingTop, bottom = it.bottom - area.paddingBottom) }
        val width = AttachmentMenuPlacement.width(areaBox, density)
        rows.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val next = AttachmentMenuPlacement.place(sourceBox, areaBox, rows.measuredHeight, density)
        if (next == placement) return
        if (next.width != placement?.width || next.height != placement?.height) {
            card.updateLayoutParams<FrameLayout.LayoutParams> {
                this.width = next.width
                this.height = next.height
            }
        }
        placement = next
        card.translationX = next.x.toFloat()
        card.translationY = next.y.toFloat()
        card.pivotX = next.pivotX
        card.pivotY = next.pivotY
    }

    private fun View.boxIn(origin: IntArray): PxBox {
        val at = IntArray(2).also(::getLocationInWindow)
        val left = at[0] - origin[0]
        val top = at[1] - origin[1]
        return PxBox(left, top, left + width, top + height)
    }

    private fun row(item: Item): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ROW_MIN_HEIGHT_DP)
        val horizontal = dp(ROW_PADDING_HORIZONTAL_DP)
        val vertical = dp(ROW_PADDING_VERTICAL_DP)
        setPaddingRelative(horizontal, vertical, horizontal, vertical)
        background = TypedValue().let { value ->
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            ContextCompat.getDrawable(context, value.resourceId)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { dismiss(item.action) }
        addView(ImageView(context).apply {
            setImageResource(item.icon)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, BaseR.color.icon))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
        addView(TextView(context).apply {
            text = item.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP)
            setTextColor(ContextCompat.getColor(context, BaseR.color.t_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(ICON_GAP_DP) })
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()

    
    private inner class Overlay(context: Context) : FrameLayout(context) {
        private val hitRect = Rect()

        @SuppressLint("ClickableViewAccessibility")
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return super.dispatchTouchEvent(event)
            val x = event.x.roundToInt()
            val y = event.y.roundToInt()
            
            val inCard = placement != null && hitRect.also(card::getHitRect).contains(x, y)
            if (inCard && !dismissing) return super.dispatchTouchEvent(event)
            val origin = IntArray(2).also(::getLocationInWindow)
            val onSource = source.boxIn(origin).let { x in it.left until it.right && y in it.top until it.bottom }
            dismiss()
            return onSource
        }
    }

    private companion object {
        const val PADDING_VERTICAL_DP = 12f
        const val ROW_MIN_HEIGHT_DP = 48f
        const val ROW_PADDING_HORIZONTAL_DP = 20f
        const val ROW_PADDING_VERTICAL_DP = 12f
        const val ICON_DP = 24f
        const val ICON_GAP_DP = 16f
        const val LABEL_SP = 16f
    }
}
