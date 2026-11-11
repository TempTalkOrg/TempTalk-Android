package com.difft.android.chat.ui

import com.difft.android.base.utils.avoidCornerCutout
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.difft.android.base.ui.glass.SystemBarEdge
import com.difft.android.base.ui.glass.SystemBarGlassDrawable
import com.difft.android.base.ui.sidechrome.onSideChromeActive
import com.difft.android.base.widget.FloatingSurfaceDrawable
import com.difft.android.base.widget.applyFloatingSurface
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ChatFragmentInputBinding
import com.difft.android.chat.ui.theme.ChatTheme
import com.difft.android.chat.ui.theme.ChatThemeSetting
import kotlin.math.roundToInt







class ChatBottomFadeDrawable(context: Context, private val extentBelow: () -> Int) : Drawable() {
    private val color = ChatTopFade.color(context)
    private val fade = FADE_DP * context.resources.displayMetrics.density
    private val fadePaint = Paint()
    private val solidPaint = Paint().apply { color = this@ChatBottomFadeDrawable.color }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val stops = RAMP.map { ColorUtils.setAlphaComponent(color, (Color.alpha(color) * it).roundToInt()) }.toIntArray()
        fadePaint.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.top + fade, stops, RAMP_AT, Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + fade, fadePaint)
        canvas.drawRect(b.left.toFloat(), b.top + fade, b.right.toFloat(), (b.bottom + extentBelow()).toFloat(), solidPaint)
    }

    private companion object {
        const val FADE_DP = 48f
        
        val RAMP = floatArrayOf(0f, 0.42f, 0.77f, 1f)
        val RAMP_AT = floatArrayOf(0f, 1f / 3, 2f / 3, 1f)
    }

    override fun setAlpha(alpha: Int) {
        fadePaint.alpha = alpha
        solidPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fadePaint.colorFilter = colorFilter
        solidPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}









internal object ChatInputChrome {
    private const val CONTROL_DP = 44f

    fun apply(binding: ChatFragmentInputBinding, glass: Boolean = false) {
        binding.root.setTag(R.id.chat_glass_input, glass)
        
        binding.clInput.avoidCornerCutout()
        binding.clLeftButtons.applyFloatingSurface(maxCircleDiameterDp = CONTROL_DP, glass = glass)
        applySendButton(binding.buttonSubmit)
        val cardRadius = binding.root.resources.getDimension(com.difft.android.base.R.dimen.radius_card)
        listOf(binding.llAt, binding.quoteZone).forEach { card ->
            card.applyFloatingSurface(cornerRadiusPx = cardRadius, glass = glass, clipsContent = true)
            card.clipToOutline = true
        }
        applyPlain(binding)
    }

    
    fun applySendButton(button: View, theme: ChatTheme = ChatThemeSetting.current) {
        val context = button.context
        val gradient = theme == ChatTheme.GRADIENT
        button.applyFloatingSurface(
            maxCircleDiameterDp = CONTROL_DP,
            fillColor = ContextCompat.getColor(context, com.difft.android.base.R.color.primary),
            pressable = true,
            solidFill = true,
            fillGradient = if (gradient) {
                intArrayOf(
                    ContextCompat.getColor(context, R.color.chat_bubble_gradient_top),
                    ContextCompat.getColor(context, R.color.chat_bubble_gradient_bottom),
                )
            } else {
                null
            },
        )
        
        (button as? ImageView)?.imageTintList = if (gradient) {
            ColorStateList.valueOf(ContextCompat.getColor(context, com.difft.android.base.R.color.bubble_sent_text))
        } else {
            null
        }
    }

    
    fun setConfidential(binding: ChatFragmentInputBinding, enabled: Boolean) {
        applyPlain(binding)
        binding.clLeftButtons.setConfidentialInputBorder(enabled)
        val field = binding.clInputContent
        field.setConfidentialInputBorder(enabled, field.resources.getDimension(R.dimen.chat_input_field_radius))
    }

    
    fun applyPlain(binding: ChatFragmentInputBinding) {
        val root = binding.root
        val glass = root.getTag(R.id.chat_glass_input) == true
        if (glass) {
            if (root.background !is SystemBarGlassDrawable) {
                root.background = SystemBarGlassDrawable(
                    root, SystemBarEdge.BOTTOM,
                    tintColor = ContextCompat.getColor(root.context, com.difft.android.base.R.color.bg1),
                )
            }
        } else if (root.background !is ChatBottomFadeDrawable) {
            
            (root.background as? SystemBarGlassDrawable)?.close()
            root.background = ChatBottomFadeDrawable(root.context) { root.rootView.height }
        }
        val field = binding.clInputContent
        if (field.background !is FloatingSurfaceDrawable) {
            field.applyFloatingSurface(cornerRadiusPx = field.resources.getDimension(R.dimen.chat_input_field_radius), glass = glass)
        }
    }
}






internal object ChatInputGap {
    private const val PHONE_DP = 8f
    private const val SIDE_BAR_RESTING_DP = 26f
    private const val SIDE_BAR_KEYBOARD_DP = 10f

    
    fun bottomPx(sideBar: Boolean, keyboardUp: Boolean, navBottomPx: Int, density: Float): Int {
        val dp = when {
            !sideBar -> PHONE_DP
            keyboardUp -> SIDE_BAR_KEYBOARD_DP
            else -> maxOf(PHONE_DP, SIDE_BAR_RESTING_DP - navBottomPx / density)
        }
        return (dp * density).toInt()
    }

    



    fun follow(fragment: Fragment, row: View, panelOpen: () -> Boolean): () -> Unit {
        var sideBar = false
        val update = {
            val insets = ViewCompat.getRootWindowInsets(row)
            val keyboardUp = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true || panelOpen()
            val nav = insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            val px = bottomPx(sideBar, keyboardUp, nav, row.resources.displayMetrics.density)
            if (row.paddingBottom != px) row.updatePadding(bottom = px)
        }
        ViewCompat.setOnApplyWindowInsetsListener(row) { _, insets ->
            row.post(update)
            insets
        }
        fragment.onSideChromeActive { active ->
            sideBar = active
            update()
        }
        return update
    }
}
