package com.difft.android.base.widget

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.Parcelable
import android.util.AttributeSet
import android.view.SoundEffectConstants
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Checkable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.withStyledAttributes
import com.difft.android.base.R
import com.difft.android.base.concurrent.AppExecutors
import kotlinx.coroutines.Job




























abstract class DifftToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DifftComposeView(context, attrs, defStyleAttr), Checkable {

    private var checkedState by mutableStateOf(false)
    private var onCheckedChangeListener: ((DifftToggleView, Boolean) -> Unit)? = null
    private var onToggleRequestListener: ((DifftToggleView, Boolean) -> Unit)? = null

    










    var toggleRequestInFlight: Boolean = false

    








    fun guardWhile(job: Job, onRelease: (() -> Unit)? = null) {
        toggleRequestInFlight = true
        val release = {
            toggleRequestInFlight = false
            onRelease?.invoke()
        }
        job.invokeOnCompletion {
            
            if (Looper.myLooper() == Looper.getMainLooper()) {
                release()
            } else {
                AppExecutors.mainHandler().post { release() }
            }
        }
    }

    
    protected abstract val accessibilityClassName: String

    init {
        
        
        var clickable = true
        var focusable = true
        if (attrs != null) {
            context.withStyledAttributes(attrs, R.styleable.DifftToggleView) {
                clickable = getBoolean(R.styleable.DifftToggleView_android_clickable, true)
                focusable = getBoolean(R.styleable.DifftToggleView_android_focusable, true)
                checkedState = getBoolean(R.styleable.DifftToggleView_android_checked, false)
            }
        }
        isClickable = clickable
        isFocusable = focusable
    }

    

    override fun isChecked(): Boolean = checkedState

    
    override fun setChecked(checked: Boolean) {
        if (checkedState == checked) return
        checkedState = checked
        notifyCheckedChangedForAccessibility()
    }

    



    override fun toggle() = setChecked(!checkedState)

    







    fun setOnCheckedChangeListener(listener: ((view: DifftToggleView, isChecked: Boolean) -> Unit)?) {
        onCheckedChangeListener = listener
        if (listener != null) onToggleRequestListener = null
    }

    










    fun setOnToggleRequestListener(listener: ((view: DifftToggleView, requested: Boolean) -> Unit)?) {
        onToggleRequestListener = listener
        if (listener != null) onCheckedChangeListener = null
    }

    override fun performClick(): Boolean {
        if (isEnabled) {
            
            
            if (toggleRequestInFlight) return false
            dispatchUserToggle()
        }
        val handled = super.performClick()
        if (!handled) playSoundEffect(SoundEffectConstants.CLICK)
        return handled
    }

    




    private fun dispatchUserToggle() {
        val requested = !checkedState
        val controlled = onToggleRequestListener
        if (controlled != null) {
            controlled(this, requested)
            return
        }
        checkedState = requested
        
        notifyCheckedChangedForAccessibility()
        onCheckedChangeListener?.invoke(this, requested)
    }

    




    private fun notifyCheckedChangedForAccessibility() =
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

    

    override fun getAccessibilityClassName(): CharSequence = accessibilityClassName

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.isChecked = checkedState
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = checkedState
    }

    

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable(KEY_SUPER, super.onSaveInstanceState())
        putBoolean(KEY_CHECKED, checkedState)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            super.onRestoreInstanceState(state.getParcelable(KEY_SUPER))
            checkedState = state.getBoolean(KEY_CHECKED, false)
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    

    @Composable
    final override fun ThemedContent() = ToggleContent(checked = checkedState, enabled = enabledState)

    






    @Composable
    protected abstract fun ToggleContent(checked: Boolean, enabled: Boolean)

    private companion object {
        const val KEY_SUPER = "difft_toggle_super"
        const val KEY_CHECKED = "difft_toggle_checked"
    }
}
