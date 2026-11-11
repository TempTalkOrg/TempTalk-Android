package com.difft.android.base.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.difft.android.base.R
import com.difft.android.base.databinding.BaseLayoutChativePopupViewBinding
import com.difft.android.base.databinding.BaseLayoutChativePopupViewItemBinding
import com.difft.android.base.utils.application
import com.difft.android.base.utils.dp

class ChativePopupView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    class Item(
        val drawable: Drawable? = null,
        val label: CharSequence?,
        val iconColor: Int? = null,
        val textColor: Int? = null,
        val drawableRight: Drawable? = null,
        val fitImageSize: Boolean? = true,
        val drawableUrl: String? = null,
        val onClickListener: OnClickListener?,
    )

    private val binding: BaseLayoutChativePopupViewBinding

    var items: List<Item>? = null
        set(value) {
            binding.linearlayoutConatiner.removeAllViews()

            value
                ?.asSequence()
                ?.map { item ->
                    val layoutInflater = LayoutInflater.from(context)
                    val itemBinding = BaseLayoutChativePopupViewItemBinding.inflate(
                        layoutInflater, this, false
                    )

                    if (item.drawable != null) {
                        itemBinding.imageviewIcon.visibility = View.VISIBLE
                        itemBinding.imageviewIcon.setImageDrawable(item.drawable)
                    } else if (item.drawableUrl != null) {
                        itemBinding.imageviewIcon.visibility = View.VISIBLE
                        Glide.with(context)
                            .load(item.drawableUrl)
                            .listener(object : RequestListener<Drawable> {
                                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                                    return false
                                }

                                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                                    item.iconColor?.let {
                                        val tintedDrawable = tintDrawable(resource, it)
                                        itemBinding.imageviewIcon.setImageDrawable(tintedDrawable)
                                    } ?: run {
                                        itemBinding.imageviewIcon.setImageDrawable(resource)
                                    }
                                    return true
                                }
                            })
                            .into(itemBinding.imageviewIcon)
                    } else {
                        itemBinding.imageviewIcon.visibility = View.GONE
                    }

                    item.drawableRight?.let {
                        itemBinding.imageviewRight.visibility = View.VISIBLE
                        itemBinding.imageviewRight.setImageDrawable(item.drawableRight)
                        val layoutParams = itemBinding.root.layoutParams
                        layoutParams.width = 216.dp
                        itemBinding.root.layoutParams = layoutParams
                    } ?: run {
                        itemBinding.imageviewRight.visibility = View.GONE
                    }


                    itemBinding.textviewLabel.text = item.label

                    val iconColor = item.iconColor ?: ContextCompat.getColor(context, R.color.icon)
                    ImageViewCompat.setImageTintList(itemBinding.imageviewIcon, ColorStateList.valueOf(iconColor))

                    
                    
                    val labelColor = item.textColor
                        ?: item.iconColor
                        ?: ContextCompat.getColor(context, R.color.t_primary)
                    itemBinding.textviewLabel.setTextColor(labelColor)

                    itemBinding.root.setOnClickListener(item.onClickListener)
                    itemBinding.root
                }
                ?.forEach { view ->
                    binding.linearlayoutConatiner.addView(view)
                }

            field = value
        }

    private fun tintDrawable(drawable: Drawable, color: Int): Drawable {
        val bitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        val tintFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        val tintedBitmap = bitmap.toDrawable(application.resources)
        tintedBitmap.paint.colorFilter = tintFilter

        return tintedBitmap
    }

    fun setCardBackgroundColor(cardBackgroundColor: Int) {
        binding.root.applyFloatingSurface(menuCornerPx(), fillColor = cardBackgroundColor, clipsContent = true)
    }

    private fun menuCornerPx(): Float = resources.getDimension(R.dimen.radius_floating)

    
    internal val rows: View get() = binding.linearlayoutConatiner

    fun setTitle(title: String, titleTextColor: Int?) {
        binding.menuTitle.text = title
        binding.menuTitle.isVisible = true
        titleTextColor?.let {
            binding.menuTitle.setTextColor(it)
        }
    }

    init {
        val layoutInflater = LayoutInflater.from(context)
        this.binding = BaseLayoutChativePopupViewBinding.inflate(
            layoutInflater, this, true
        )

        
        binding.root.applyFloatingSurface(menuCornerPx(), clipsContent = true)
        ViewCompat.setPaddingRelative(this, 16.dp, 8.dp, 16.dp, 8.dp)
        clipToPadding = false
        clipChildren = false
    }

}

object ChativePopupWindow {
    fun showAsDropDown(
        anchorView: View,
        items: List<ChativePopupView.Item>,
        cardBackgroundColor: Int? = null,
        title: String? = null,
        titleTextColor: Int? = null
    ): PopupWindow? {
        val context = anchorView.context
        val view = ChativePopupView(context)
        view.items = items
        cardBackgroundColor?.let {
            view.setCardBackgroundColor(cardBackgroundColor)
        }

        title?.let {
            view.setTitle(title, titleTextColor)
        }

        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager =
                ContextCompat.getSystemService(context, WindowManager::class.java) ?: return null
            windowManager.currentWindowMetrics.bounds.run { width() to height() }
        } else {
            context.resources.displayMetrics.run { widthPixels to heightPixels }
        }
        view.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST)
        )

        val popupWindow = FloatingMenuPopupWindow(view, view.measuredWidth, view.measuredHeight)
        popupWindow.isOutsideTouchable = true
        popupWindow.growFrom(anchorView.centerOnScreen())
        popupWindow.showAsDropDown(anchorView)
        return popupWindow
    }

    fun showAsDropDown2(
        anchorView: View,
        items: List<ChativePopupView.Item>,
        cardBackgroundColor: Int? = null,
        title: String? = null,
        titleTextColor: Int? = null
    ): PopupWindow? {
        val popupWindow = buildPopup(anchorView, items, cardBackgroundColor, title, titleTextColor) ?: return null
        popupWindow.growFrom(anchorView.centerOnScreen())
        popupWindow.showAsDropDown(anchorView)
        return popupWindow
    }

    



    fun showBesideBarButton(anchorView: View, buttonBoundsInWindow: Rect, items: List<ChativePopupView.Item>): PopupWindow? {
        val popupWindow = buildPopup(anchorView, items, null, null, null) ?: return null
        val gap = BAR_MENU_GAP_DP.dp
        val x = (buttonBoundsInWindow.left - gap - popupWindow.width).coerceAtLeast(gap)
        val window = IntArray(2).also(anchorView.rootView::getLocationOnScreen)
        popupWindow.growFrom(window[0] + buttonBoundsInWindow.centerX(), window[1] + buttonBoundsInWindow.centerY())
        popupWindow.showAtLocation(anchorView, Gravity.TOP or Gravity.LEFT, x, buttonBoundsInWindow.top)
        return popupWindow
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPopup(
        anchorView: View,
        items: List<ChativePopupView.Item>,
        cardBackgroundColor: Int?,
        title: String?,
        titleTextColor: Int?
    ): FloatingMenuPopupWindow? {
        val context = anchorView.context
        val view = ChativePopupView(context)
        view.items = items
        cardBackgroundColor?.let {
            view.setCardBackgroundColor(cardBackgroundColor)
        }

        title?.let {
            view.setTitle(title, titleTextColor)
        }

        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager =
                ContextCompat.getSystemService(context, WindowManager::class.java) ?: return null
            windowManager.currentWindowMetrics.bounds.run { width() to height() }
        } else {
            context.resources.displayMetrics.run { widthPixels to heightPixels }
        }

        view.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST)
        )

        val popupWindow = FloatingMenuPopupWindow(view, view.measuredWidth, view.measuredHeight)
        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true 
        popupWindow.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())

        
        popupWindow.setTouchInterceptor { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                popupWindow.dismiss()
                true
            } else {
                false
            }
        }

        return popupWindow
    }


    fun showAtTouchPosition(
        anchorView: View,
        items: List<ChativePopupView.Item>,
        touchX: Int,
        touchY: Int,
        cardBackgroundColor: Int? = null,
        title: String? = null,
        titleTextColor: Int? = null
    ): PopupWindow {
        val context = anchorView.context
        val popupView = ChativePopupView(context).apply {
            this.items = items
            cardBackgroundColor?.let { setCardBackgroundColor(it) }
            title?.let { setTitle(it, titleTextColor) }
        }

        popupView.measure(
            MeasureSpec.UNSPECIFIED,
            MeasureSpec.UNSPECIFIED
        )
        val popupWidth = popupView.measuredWidth
        val popupHeight = popupView.measuredHeight

        
        val rootView = anchorView.rootView
        val screenWidth = rootView.width
        val screenHeight = rootView.height

        val adjustedX = when {
            touchX - popupWidth / 2 < 0 -> 0
            touchX + popupWidth / 2 > screenWidth -> screenWidth - popupWidth
            else -> touchX - popupWidth / 2
        }

        val adjustedY = when {
            touchY + popupHeight > screenHeight -> screenHeight - popupHeight
            else -> touchY
        }

        val popupWindow = FloatingMenuPopupWindow(popupView, popupWidth, popupHeight, focusable = true)
        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        popupWindow.growFrom(touchX, touchY)
        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, adjustedX, adjustedY)
        return popupWindow
    }

}

private const val BAR_MENU_GAP_DP = 8

private fun View.centerOnScreen(): Pair<Int, Int> {
    val at = IntArray(2).also(::getLocationOnScreen)
    return at[0] + width / 2 to at[1] + height / 2
}






class FloatingMenuPopupWindow(
    private val menu: ChativePopupView,
    width: Int,
    height: Int,
    focusable: Boolean = false,
) : PopupWindow(menu, width, height, focusable) {
    private var leaving = false

    init {
        animationStyle = 0
    }

    fun growFrom(point: Pair<Int, Int>) = growFrom(point.first, point.second)

    
    fun growFrom(x: Int, y: Int) {
        menu.alpha = 0f
        menu.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                menu.viewTreeObserver.removeOnPreDrawListener(this)
                val at = IntArray(2).also(menu::getLocationOnScreen)
                menu.pivotX = (x - at[0]).toFloat()
                menu.pivotY = (y - at[1]).toFloat()
                FloatingMenuMotion.grow(menu, menu.rows)
                return true
            }
        })
    }

    override fun dismiss() {
        if (leaving) return
        
        if (!isShowing || !menu.isAttachedToWindow || menu.context.getSafeActivity()?.isFinishing == true) {
            dismissNow()
            return
        }
        leaving = true
        FloatingMenuMotion.shrink(menu, ::dismissNow)
    }

    private fun dismissNow() = super.dismiss()
}
