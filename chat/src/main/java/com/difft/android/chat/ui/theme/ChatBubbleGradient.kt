package com.difft.android.chat.ui.theme

import android.content.res.Configuration
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.chat.R
import com.difft.android.chat.ui.ChatMessageViewHolder
import com.difft.android.chat.ui.BUBBLE_RADIUS_DP
import com.difft.android.chat.ui.ChatBackgroundDrawable
import com.difft.android.chat.ui.ChatMessageAdapter
import com.difft.android.chat.ui.ChatWallpaperPainter
import kotlinx.coroutines.launch










class ChatBubbleGradient(private val list: RecyclerView) {

    private val topColor = ContextCompat.getColor(list.context, R.color.chat_bubble_gradient_top)
    private val bottomColor = ContextCompat.getColor(list.context, R.color.chat_bubble_gradient_bottom)
    
    internal val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bottomColor }
    internal val radius = BUBBLE_RADIUS_DP * list.resources.displayMetrics.density
    private val veil = ContextCompat.getColor(list.context, R.color.chat_bubble_gradient_recv_veil)
    
    internal val receivedPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = ContextCompat.getColor(list.context, R.color.chat_bubble_gradient_recv)
    }
    private val receivedShadow = ReceivedBubbleShadow(list, radius)
    private val inkMatrix = Matrix()
    private var ink: BitmapShader? = null
    private var inkSourceWidth = 0
    private var inkSourceHeight = 0
    
    private var inkFrameWidth = 0
    private var inkFrameHeight = 0
    private var inkSpanHeight = 0
    private val location = IntArray(2)
    private var anchor: View? = null

    
    internal var height = 0
        private set

    
    internal var width = 0
        private set

    
    internal var anchorLeft = 0
        private set

    private val tracker = ViewTreeObserver.OnPreDrawListener {
        refreshMovedBubbles()
        true
    }

    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.viewTreeObserver.addOnPreDrawListener(tracker)
        }

        override fun onViewDetachedFromWindow(v: View) {
            v.viewTreeObserver.removeOnPreDrawListener(tracker)
            anchor = null
        }
    }

    
    fun install() {
        list.addOnAttachStateChangeListener(attachment)
        if (list.isAttachedToWindow) list.viewTreeObserver.addOnPreDrawListener(tracker)
        list.addItemDecoration(receivedShadow)
    }

    
    fun release() {
        list.removeOnAttachStateChangeListener(attachment)
        list.viewTreeObserver.removeOnPreDrawListener(tracker)
        list.removeItemDecoration(receivedShadow)
        anchor = null
    }

    
    internal fun anchorTop(): Int {
        val anchor = anchorView()
        val anchorHeight = anchor.height
        if (anchorHeight > 0 && anchorHeight != height) {
            height = anchorHeight
            paint.shader = LinearGradient(0f, 0f, 0f, anchorHeight.toFloat(), topColor, bottomColor, Shader.TileMode.CLAMP)
        }
        val anchorWidth = anchor.width
        if (anchorWidth > 0 && anchorHeight > 0 && (anchorWidth != width || anchorHeight != inkSpanHeight)) {
            width = anchorWidth
            inkSpanHeight = anchorHeight
            spanInk(anchorWidth, anchorHeight)
        }
        anchor.getLocationInWindow(location)
        anchorLeft = location[0]
        return location[1]
    }

    
    private fun spanInk(frameWidth: Int, frameHeight: Int) {
        if (ink == null || ChatWallpaperPainter.needsRender(inkFrameWidth, inkFrameHeight, frameWidth, frameHeight)) {
            val bitmap = ChatWallpaperPainter.veiledInk(list.context, frameWidth, frameHeight, veil)
            inkFrameWidth = frameWidth
            inkFrameHeight = frameHeight
            inkSourceWidth = bitmap.width
            inkSourceHeight = bitmap.height
            ink = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        inkMatrix.setScale(frameWidth / inkSourceWidth.toFloat(), frameHeight / inkSourceHeight.toFloat())
        ink?.setLocalMatrix(inkMatrix)
        receivedPaint.shader = ink
    }

    




    private fun anchorView(): View {
        anchor?.let { return it }
        var view: View? = list
        while (view != null) {
            if (view.background is ChatBackgroundDrawable) return view.also { if (list.isAttachedToWindow) anchor = it }
            view = view.parent as? View
        }
        val root = list.rootView
        return root.also { if (list.isAttachedToWindow) anchor = it }
    }

    
    private fun refreshMovedBubbles() {
        
        if (ChatThemeSetting.current != ChatTheme.GRADIENT || list.childCount == 0 || !list.isShown) return
        val anchorTop = anchorTop()
        for (i in 0 until list.childCount) {
            val holder = list.getChildViewHolder(list.getChildAt(i)) as? ChatMessageViewHolder.Message ?: continue
            val bubble = holder.bubble
            val drawable = bubble.background as? AnchoredGradientBubbleDrawable ?: continue
            if (drawable.isStale(anchorTop)) bubble.invalidate()
        }
    }
}





internal class AnchoredGradientBubbleDrawable(
    val gradient: ChatBubbleGradient,
    private val bubble: View,
    private val sent: Boolean = true,
) : Drawable() {

    private val location = IntArray(2)
    private val rect = RectF()
    private var drawnX = Int.MIN_VALUE
    private var drawnY = Int.MIN_VALUE
    private var drawnWidth = 0
    private var drawnHeight = 0
    private var x = 0
    private var y = 0

    
    private fun locate(anchorTop: Int) {
        bubble.getLocationInWindow(location)
        
        x = if (sent) 0 else location[0] - gradient.anchorLeft
        y = location[1] - anchorTop
    }

    
    internal fun isStale(anchorTop: Int = gradient.anchorTop()): Boolean {
        locate(anchorTop)
        return x != drawnX || y != drawnY || gradient.height != drawnHeight || (!sent && gradient.width != drawnWidth)
    }

    override fun draw(canvas: Canvas) {
        locate(gradient.anchorTop())
        drawnX = x
        drawnY = y
        drawnWidth = gradient.width
        drawnHeight = gradient.height
        val bounds = bounds
        rect.set((bounds.left + x).toFloat(), (bounds.top + y).toFloat(), (bounds.right + x).toFloat(), (bounds.bottom + y).toFloat())
        canvas.save()
        canvas.translate(-x.toFloat(), -y.toFloat())
        canvas.drawRoundRect(rect, gradient.radius, gradient.radius, if (sent) gradient.paint else gradient.receivedPaint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}






private class ReceivedBubbleShadow(list: RecyclerView, private val radius: Float) : RecyclerView.ItemDecoration() {

    private val night = list.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val density = list.resources.displayMetrics.density
        val shadow = ContextCompat.getColor(list.context, R.color.chat_bubble_gradient_recv_shadow)
        color = shadow
        setShadowLayer(BLUR_DP * density, 0f, DY_DP * density, shadow)
    }
    private val bounds = Rect()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (night || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || ChatThemeSetting.current != ChatTheme.GRADIENT) return
        for (i in 0 until parent.childCount) {
            val row = parent.getChildAt(i)
            val bubble = (parent.getChildViewHolder(row) as? ChatMessageViewHolder.Message)?.bubble ?: continue
            if (bubble.sent || !bubble.isShown || bubble.background !is AnchoredGradientBubbleDrawable) continue
            bubble.getDrawingRect(bounds)
            parent.offsetDescendantRectToMyCoords(bubble, bounds)
            rect.set(bounds)
            rect.offset(row.translationX, row.translationY)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private companion object {
        const val BLUR_DP = 2f
        const val DY_DP = 1f
    }
}






fun ChatMessageAdapter.followChatTheme(list: RecyclerView, owner: LifecycleOwner) {
    val gradient = ChatBubbleGradient(list).also { it.install() }
    bubbleGradient = gradient
    
    owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
        if (event != Lifecycle.Event.ON_DESTROY) return@LifecycleEventObserver
        gradient.release()
        if (bubbleGradient === gradient) bubbleGradient = null
    })
    var applied = ChatThemeSetting.current
    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.CREATED) {
            ChatThemeSetting.theme.collect { theme ->
                if (theme == applied) return@collect
                applied = theme
                notifyItemRangeChanged(0, itemCount)
            }
        }
    }
}
