package com.difft.android.base.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
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

//                    item.fitImageSize?.let {
//                        if (it) {
//                            itemBinding.imageviewRight.scaleType = ImageView.ScaleType.FIT_XY
//                            itemBinding.imageviewIcon.scaleType = ImageView.ScaleType.FIT_XY
//                        } else {
//                            itemBinding.imageviewRight.scaleType = ImageView.ScaleType.CENTER
//                            itemBinding.imageviewIcon.scaleType = ImageView.ScaleType.CENTER
//                        }
//                    }

                    itemBinding.textviewLabel.text = item.label

                    val iconColor = item.iconColor ?: ContextCompat.getColor(context, R.color.icon)
                    ImageViewCompat.setImageTintList(itemBinding.imageviewIcon, ColorStateList.valueOf(iconColor))

                    item.textColor?.let {
                        itemBinding.textviewLabel.setTextColor(it)
                    } ?: {
                        itemBinding.textviewLabel.setTextColor(iconColor)
                    }

                    itemBinding.root.setOnClickListener(item.onClickListener)
                    itemBinding.root
                }
                ?.forEach { view ->
                    binding.linearlayoutConatiner.addView(view)
                }

            field = value
        }

    private fun tintDrawable(drawable: Drawable, color: Int): Drawable {
        val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        val tintFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        val tintedBitmap = BitmapDrawable(application.resources, bitmap)
        tintedBitmap.paint.colorFilter = tintFilter

        return tintedBitmap
    }

    fun setCardBackgroundColor(cardBackgroundColor: Int) {
        binding.root.setCardBackgroundColor(cardBackgroundColor)
    }

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

        ViewCompat.setPaddingRelative(this, 16.dp, 5.dp, 16.dp, 0)
        clipToPadding = false
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

        val popupWindow = PopupWindow(view, view.measuredWidth, view.measuredHeight)
        popupWindow.isOutsideTouchable = true
        popupWindow.showAsDropDown(anchorView)
        return popupWindow
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showAsDropDown2(
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

        // Get screen dimensions for measuring view size
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

        // Create and configure the PopupWindow
        val popupWindow = PopupWindow(view, view.measuredWidth, view.measuredHeight)
        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true // Make the popup focusable to intercept outside clicks
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) // Transparent background

        // Set a touch interceptor to handle outside clicks without propagating
        popupWindow.setTouchInterceptor { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                popupWindow.dismiss() // Dismiss the popup
                true // Indicate that we've handled the touch event
            } else {
                false
            }
        }

        // Show the popup
        popupWindow.showAsDropDown(anchorView)
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

        // 测量弹窗内容的宽高
        popupView.measure(
            MeasureSpec.UNSPECIFIED,
            MeasureSpec.UNSPECIFIED
        )
        val popupWidth = popupView.measuredWidth
        val popupHeight = popupView.measuredHeight

        // 确定 X 和 Y 坐标
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        // 水平居中计算
        val adjustedX = when {
            touchX - popupWidth / 2 < 0 -> 0 // 左侧超出屏幕
            touchX + popupWidth / 2 > screenWidth -> screenWidth - popupWidth // 右侧超出屏幕
            else -> touchX - popupWidth / 2
        }

        // 垂直位置
        val adjustedY = when {
            touchY + popupHeight > screenHeight -> screenHeight - popupHeight // 靠下对齐
            else -> touchY
        }

        // 创建并显示 PopupWindow
        val popupWindow = PopupWindow(popupView, popupWidth, popupHeight, true)
        popupWindow.isOutsideTouchable = true
        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, adjustedX, adjustedY)
        return popupWindow
    }

}