package com.difft.android.chat.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.ViewTreeObserver
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import com.difft.android.chat.util.concurrent.ListenableFuture
import com.difft.android.chat.util.concurrent.SettableFuture
import com.difft.android.chat.util.views.Stub

object ViewUtil {

    @JvmStatic
    fun focusAndMoveCursorToEndAndOpenKeyboard(input: EditText) {
        val numberLength = input.text.length
        input.setSelection(numberLength, numberLength)

        focusAndShowKeyboard(input)
    }

    @JvmStatic
    fun focusAndShowKeyboard(view: View) {
        view.requestFocus()
        if (view.hasWindowFocus()) {
            showTheKeyboardNow(view)
        } else {
            view.viewTreeObserver.addOnWindowFocusChangeListener(object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (hasFocus) {
                        showTheKeyboardNow(view)
                        view.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                    }
                }
            })
        }
    }

    private fun showTheKeyboardNow(view: View) {
        if (view.isFocused) {
            view.post {
                val inputMethodManager = ServiceUtil.getInputMethodManager(view.context)
                inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <T : View> inflateStub(parent: View, @IdRes stubId: Int): T {
        return (parent.findViewById<ViewStub>(stubId)).inflate() as T
    }

    @JvmStatic
    fun <T : View> findStubById(parent: Activity, @IdRes resId: Int): Stub<T> {
        return Stub(parent.findViewById(resId))
    }

    @JvmStatic
    fun <T : View> findStubById(parent: View, @IdRes resId: Int): Stub<T> {
        return Stub(parent.findViewById(resId))
    }

    private fun getAlphaAnimation(from: Float, to: Float, duration: Int): Animation {
        val anim: Animation = AlphaAnimation(from, to)
        anim.interpolator = FastOutSlowInInterpolator()
        anim.duration = duration.toLong()
        return anim
    }

    @JvmStatic
    fun fadeIn(view: View, duration: Int) {
        animateIn(view, getAlphaAnimation(0f, 1f, duration))
    }

    @JvmStatic
    fun fadeOut(view: View, duration: Int): ListenableFuture<Boolean> {
        return fadeOut(view, duration, View.GONE)
    }

    @JvmStatic
    fun fadeOut(view: View, duration: Int, visibility: Int): ListenableFuture<Boolean> {
        return animateOut(view, getAlphaAnimation(1f, 0f, duration), visibility)
    }

    @JvmStatic
    fun animateOut(view: View, animation: Animation): ListenableFuture<Boolean> {
        return animateOut(view, animation, View.GONE)
    }

    @JvmStatic
    fun animateOut(view: View, animation: Animation, visibility: Int): ListenableFuture<Boolean> {
        val future = SettableFuture<Boolean>()
        if (view.visibility == visibility) {
            future.set(true)
        } else {
            view.clearAnimation()
            animation.reset()
            animation.startTime = 0
            animation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}

                override fun onAnimationRepeat(animation: Animation?) {}

                override fun onAnimationEnd(animation: Animation?) {
                    view.visibility = visibility
                    future.set(true)
                }
            })
            view.startAnimation(animation)
        }
        return future
    }

    @JvmStatic
    fun animateIn(view: View, animation: Animation) {
        if (view.visibility == View.VISIBLE) return

        view.clearAnimation()
        animation.reset()
        animation.startTime = 0
        view.visibility = View.VISIBLE
        view.startAnimation(animation)
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <T : View> inflate(inflater: LayoutInflater, parent: ViewGroup, @LayoutRes layoutResId: Int): T {
        return inflater.inflate(layoutResId, parent, false) as T
    }

    @JvmStatic
    fun isLtr(view: View): Boolean {
        return isLtr(view.context)
    }

    @JvmStatic
    fun isLtr(context: Context): Boolean {
        return context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR
    }

    @JvmStatic
    fun isRtl(view: View): Boolean {
        return isRtl(view.context)
    }

    @JvmStatic
    fun isRtl(context: Context): Boolean {
        return context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    }

    @JvmStatic
    fun pxToDp(px: Float): Float {
        return px / Resources.getSystem().displayMetrics.density
    }

    @JvmStatic
    fun dpToPx(context: Context, dp: Int): Int {
        return ((dp * context.resources.displayMetrics.density) + 0.5).toInt()
    }

    @JvmStatic
    fun dpToPx(dp: Int): Int {
        return Math.round(dp * Resources.getSystem().displayMetrics.density)
    }

    @JvmStatic
    fun spToPx(sp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, Resources.getSystem().displayMetrics).toInt()
    }

    @JvmStatic
    fun updateLayoutParams(view: View, width: Int, height: Int) {
        view.layoutParams.width = width
        view.layoutParams.height = height
        view.requestLayout()
    }

    @JvmStatic
    fun setLeftMargin(view: View, margin: Int) {
        if (isLtr(view)) {
            (view.layoutParams as ViewGroup.MarginLayoutParams).leftMargin = margin
        } else {
            (view.layoutParams as ViewGroup.MarginLayoutParams).rightMargin = margin
        }
        view.forceLayout()
        view.requestLayout()
    }

    @JvmStatic
    fun setRightMargin(view: View, margin: Int) {
        if (isLtr(view)) {
            (view.layoutParams as ViewGroup.MarginLayoutParams).rightMargin = margin
        } else {
            (view.layoutParams as ViewGroup.MarginLayoutParams).leftMargin = margin
        }
        view.forceLayout()
        view.requestLayout()
    }

    @JvmStatic
    fun setTopMargin(view: View, margin: Int) {
        (view.layoutParams as ViewGroup.MarginLayoutParams).topMargin = margin
        view.requestLayout()
    }

    @JvmStatic
    fun setBottomMargin(view: View, margin: Int) {
        (view.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = margin
        view.requestLayout()
    }

    @JvmStatic
    fun getWidth(view: View): Int {
        return view.layoutParams.width
    }

    @JvmStatic
    fun setPadding(view: View, padding: Int) {
        view.setPadding(padding, padding, padding, padding)
    }

    @JvmStatic
    fun isPointInsideView(view: View, x: Float, y: Float): Boolean {
        val location = IntArray(2)

        view.getLocationOnScreen(location)

        val viewX = location[0]
        val viewY = location[1]

        return x > viewX && x < viewX + view.width &&
            y > viewY && y < viewY + view.height
    }

    // Pre-API-30 fallback uses the framework's internal "status_bar_height" /
    // "navigation_bar_height" dimen resource; getIdentifier() is necessary
    // because these are platform resources, not app resources (R.* doesn't expose them).
    @SuppressLint("DiscouragedApi")
    @JvmStatic
    fun getStatusBarHeight(view: View): Int {
        val rootWindowInsets = ViewCompat.getRootWindowInsets(view)
        return if (Build.VERSION.SDK_INT > 29 && rootWindowInsets != null) {
            rootWindowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        } else {
            var result = 0
            val resourceId = view.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) {
                result = view.resources.getDimensionPixelSize(resourceId)
            }
            result
        }
    }

    @SuppressLint("DiscouragedApi")
    @JvmStatic
    fun getNavigationBarHeight(view: View): Int {
        val rootWindowInsets = ViewCompat.getRootWindowInsets(view)
        return if (Build.VERSION.SDK_INT > 29 && rootWindowInsets != null) {
            rootWindowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        } else {
            var result = 0
            val resourceId = view.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (resourceId > 0) {
                result = view.resources.getDimensionPixelSize(resourceId)
            }
            result
        }
    }

    @JvmStatic
    fun hideKeyboard(context: Context, view: View) {
        val inputManager = context.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /**
     * Enables or disables a view and all child views recursively.
     */
    @JvmStatic
    fun setEnabledRecursive(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setEnabledRecursive(view.getChildAt(i), enabled)
            }
        }
    }

    @JvmStatic
    fun getActivityLifecycle(view: View): Lifecycle? {
        return getActivityLifecycle(view.context)
    }

    private fun getActivityLifecycle(context: Context?): Lifecycle? {
        if (context is ContextThemeWrapper) {
            return getActivityLifecycle(context.baseContext)
        }

        if (context is AppCompatActivity) {
            return context.lifecycle
        }

        return null
    }
}
