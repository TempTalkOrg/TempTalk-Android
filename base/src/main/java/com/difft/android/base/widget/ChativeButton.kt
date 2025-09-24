package com.difft.android.base.widget

import android.content.Context
import android.graphics.ColorFilter
import android.util.AttributeSet
import android.util.SparseArray
import android.util.TypedValue
import android.view.LayoutInflater
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.view.isVisible
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.SimpleColorFilter
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback
import com.difft.android.base.R
import com.difft.android.base.databinding.BaseButtonChativeBinding
import java.io.InputStream

class ChativeButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {
    companion object {
        const val TYPE_PRIMARY = 0
        const val TYPE_SECONDARY = 1
    }

    private val binding: BaseButtonChativeBinding

    private val buttonResources: SparseArray<Pair<Int, Int>> = SparseArray()

    init {
        binding = BaseButtonChativeBinding.inflate(LayoutInflater.from(context), this)
        buttonResources.run {
            put(TYPE_PRIMARY, R.drawable.base_bg_chative_button_primary to R.color.textcolor_chative_button_primary)
            put(TYPE_SECONDARY, R.drawable.base_bg_chative_button_secondary to R.color.textcolor_chative_button_secondary)
        }

        if (attrs != null) {
            context
                .obtainStyledAttributes(attrs, R.styleable.ChativeButton)
                .use {
                    isLoading = it.getBoolean(R.styleable.ChativeButton_isLoading, false)
                    type = it.getInteger(R.styleable.ChativeButton_type, TYPE_PRIMARY)

                    val iconLoadingColor = when (type) {
                        TYPE_PRIMARY -> R.color.loading_primary_icon
                        TYPE_SECONDARY -> R.color.loading_secondary_icon
                        else -> throw IllegalArgumentException("Wrong type detected.")
                    }.let { res -> ContextCompat.getColor(context, res) }
                    val filter = SimpleColorFilter(iconLoadingColor)
                    val keyPath = KeyPath("**")
                    val callback = LottieValueCallback<ColorFilter>(filter)
                    binding.lottieViewLoading.addValueCallback(keyPath, LottieProperty.COLOR_FILTER, callback)


                    binding.textviewLabel.text = it.getText(R.styleable.ChativeButton_android_text)

                    val textSizeDefault = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, 12f, context.resources.displayMetrics
                    )

                    binding.textviewLabel.setTextSize(
                        TypedValue.COMPLEX_UNIT_PX,
                        it.getDimensionPixelSize(R.styleable.ChativeButton_android_textSize, textSizeDefault.toInt()).toFloat()
                    )

                    val lottieRawFileName = it.getString(R.styleable.ChativeButton_lottie_fileName)
                    if (lottieRawFileName != null) {
                        binding.lottieViewLoading.setAnimation(lottieRawFileName)
                    } else {
                        val defaultAnimationAssetName = "login_loading_anim.json"
                        binding.lottieViewLoading.setAnimation(defaultAnimationAssetName)
                    }

                    isEnabled = it.getBoolean(R.styleable.ChativeButton_android_enabled, true)
                }
        }
    }

    var type: Int = TYPE_PRIMARY
        set(value) {
            setupButton(value)

            field = value
        }

    var isLoading: Boolean = false
        set(value) {
            if (value) {
                binding.root.isClickable = false
                binding.root.isFocusable = false

                binding.textviewLabel.isVisible = false
                binding.lottieViewLoading.isVisible = true
                binding.lottieViewLoading.playAnimation()
            } else {
                binding.root.isClickable = true
                binding.root.isFocusable = true

                binding.textviewLabel.isVisible = true
                binding.lottieViewLoading.cancelAnimation()
                binding.lottieViewLoading.isVisible = false
            }

            field = value
        }

    var textSize: Float
        get() = binding.textviewLabel.textSize
        set(value) {
            binding.textviewLabel.textSize = value
        }

    var text: CharSequence
        get() = binding.textviewLabel.text
        set(value) {
            binding.textviewLabel.text = value
        }


    private fun setupButton(type: Int) {
        val (bgId, textColorId) = buttonResources.get(type)
            ?: throw UnsupportedOperationException("Unsupported type")

        setupButton(bgId, textColorId)
    }

    private fun setupButton(@DrawableRes backgroundResId: Int, @ColorRes textColorResId: Int) {
        val background = ContextCompat.getDrawable(context, backgroundResId)
        val textColor = ContextCompat.getColorStateList(context, textColorResId)

        this.background = background
        binding.textviewLabel.setTextColor(textColor)
    }

    fun setLoadingAnimation(@RawRes id: Int) {
        binding.lottieViewLoading.setAnimation(id)
    }

    fun setLoadingAnimation(stream: InputStream, cacheKey: String?) {
        binding.lottieViewLoading.setAnimation(stream, cacheKey)
    }

    fun setLoadingAnimation(assetName: String) {
        binding.lottieViewLoading.setAnimation(assetName)
    }

    fun setLoadingAnimationFromJson(jsonString: String, cacheKey: String?) {
        binding.lottieViewLoading.setAnimationFromJson(jsonString, cacheKey)
    }

    fun setLoadingAnimationFromUrl(url: String, cacheKey: String? = null) {
        binding.lottieViewLoading.setAnimationFromUrl(url)
    }
}