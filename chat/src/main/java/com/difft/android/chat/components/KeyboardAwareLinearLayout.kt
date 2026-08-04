/**
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.difft.android.chat.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.preference.PreferenceManager
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import androidx.appcompat.widget.LinearLayoutCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.R
import com.difft.android.chat.util.ServiceUtil
import com.difft.android.chat.util.Util
import com.difft.android.chat.util.ViewUtil
import java.util.HashSet

/**
 * LinearLayout that, when a view container, will report back when it thinks a soft keyboard
 * has been opened and what its height would be.
 */
open class KeyboardAwareLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayoutCompat(context, attrs, defStyle) {

    private val rect = Rect()
    private val hiddenListeners: MutableSet<OnKeyboardHiddenListener> = HashSet()
    private val shownListeners: MutableSet<OnKeyboardShownListener> = HashSet()
    private val displayMetrics = DisplayMetrics()

    private val minKeyboardSize = resources.getDimensionPixelSize(R.dimen.min_keyboard_size)
    private val minCustomKeyboardSize = resources.getDimensionPixelSize(R.dimen.min_custom_keyboard_size)
    private val defaultCustomKeyboardSize = resources.getDimensionPixelSize(R.dimen.default_custom_keyboard_size)
    private val minCustomKeyboardTopMarginPortrait = resources.getDimensionPixelSize(R.dimen.min_custom_keyboard_top_margin_portrait)
    private val minCustomKeyboardTopMarginLandscape = resources.getDimensionPixelSize(R.dimen.min_custom_keyboard_top_margin_portrait)
    private val minCustomKeyboardTopMarginLandscapeBubble = resources.getDimensionPixelSize(R.dimen.min_custom_keyboard_top_margin_landscape_bubble)
    private val statusBarHeight = ViewUtil.getStatusBarHeight(this)

    private var viewInset = getViewInset()

    private var keyboardOpen = false
    private var rotation = 0
    private var isBubble = false
    private var openedAt: Long = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateRotation()
        updateKeyboardState()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun setIsBubble(isBubble: Boolean) {
        this.isBubble = isBubble
    }

    private fun updateRotation() {
        val oldRotation = rotation
        rotation = getDeviceRotation()
        if (oldRotation != rotation) {
            L.i { "$TAG rotation changed" }
            onKeyboardClose()
        }
    }

    private fun updateKeyboardState() {
        if (viewInset == 0) viewInset = getViewInset()

        getWindowVisibleDisplayFrame(rect)

        val availableHeight = getAvailableHeight()
        val keyboardHeight = availableHeight - rect.bottom

        if (keyboardHeight > minKeyboardSize) {
            if (getKeyboardHeight() != keyboardHeight) {
                if (isLandscape()) {
                    setKeyboardLandscapeHeight(keyboardHeight)
                } else {
                    setKeyboardPortraitHeight(keyboardHeight)
                }
            }
            if (!keyboardOpen) {
                onKeyboardOpen(keyboardHeight)
            }
        } else if (keyboardOpen) {
            onKeyboardClose()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rotation = getDeviceRotation()
        if (rootWindowInsets != null) {
            val bottomInset: Int
            val windowInsets = rootWindowInsets

            bottomInset = if (Build.VERSION.SDK_INT >= 30) {
                windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                windowInsets.stableInsetBottom
            }

            if (bottomInset != 0 && (viewInset == 0 || viewInset == statusBarHeight)) {
                L.i { "$TAG Updating view inset based on WindowInsets. viewInset: $viewInset windowInset: $bottomInset" }
                viewInset = bottomInset
            }
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun getViewInset(): Int {
        try {
            val attachInfoField = View::class.java.getDeclaredField("mAttachInfo")
            attachInfoField.isAccessible = true
            val attachInfo = attachInfoField.get(this)
            if (attachInfo != null) {
                val stableInsetsField = attachInfo.javaClass.getDeclaredField("mStableInsets")
                stableInsetsField.isAccessible = true
                val insets = stableInsetsField.get(attachInfo) as? Rect
                if (insets != null) {
                    return insets.bottom
                }
            }
        } catch (e: Exception) {
            // Catch Exception (not Throwable) — Android 11+ hidden-API enforcement throws
            // RuntimeException wrapping the reflection error, which the original narrow
            // multi-catch missed and would bubble up through onMeasure → activity crash.
            // Errors (OOM, StackOverflow) intentionally propagate.
            L.w(e) { "$TAG getViewInset failed" }
        }
        return statusBarHeight
    }

    private fun getAvailableHeight(): Int {
        val availableHeight = this.rootView.height - viewInset
        val availableWidth = this.rootView.width

        if (isLandscape() && availableHeight > availableWidth) {
            return availableWidth
        }

        return availableHeight
    }

    protected open fun onKeyboardOpen(keyboardHeight: Int) {
        L.i { "$TAG onKeyboardOpen($keyboardHeight)" }
        keyboardOpen = true
        openedAt = System.currentTimeMillis()

        notifyShownListeners()
    }

    protected open fun onKeyboardClose() {
        if (System.currentTimeMillis() - openedAt < KEYBOARD_DEBOUNCE) {
            L.i { "$TAG Delaying onKeyboardClose()" }
            postDelayed({ updateKeyboardState() }, KEYBOARD_DEBOUNCE)
            return
        }

        L.i { "$TAG onKeyboardClose()" }
        keyboardOpen = false
        openedAt = 0
        notifyHiddenListeners()
    }

    fun isKeyboardOpen(): Boolean = keyboardOpen

    fun getKeyboardHeight(): Int =
        if (isLandscape()) getKeyboardLandscapeHeight() else getKeyboardPortraitHeight()

    fun isLandscape(): Boolean {
        val rotation = getDeviceRotation()
        return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    }

    private fun getDeviceRotation(): Int {
        if (isInEditMode) {
            return Surface.ROTATION_0
        }

        if (Build.VERSION.SDK_INT >= 30) {
            context.display.getRealMetrics(displayMetrics)
        } else {
            @Suppress("DEPRECATION")
            ServiceUtil.getWindowManager(context).defaultDisplay.getRealMetrics(displayMetrics)
        }
        return if (displayMetrics.widthPixels > displayMetrics.heightPixels) Surface.ROTATION_90 else Surface.ROTATION_0
    }

    private fun getKeyboardLandscapeHeight(): Int {
        if (isBubble) {
            return rootView.height - minCustomKeyboardTopMarginLandscapeBubble
        }

        val keyboardHeight = PreferenceManager.getDefaultSharedPreferences(context)
            .getInt("keyboard_height_landscape", defaultCustomKeyboardSize)
        return Util.clamp(keyboardHeight, minCustomKeyboardSize, rootView.height - minCustomKeyboardTopMarginLandscape)
    }

    private fun getKeyboardPortraitHeight(): Int {
        if (isBubble) {
            val height = rootView.height
            return height - (height * 0.45).toInt()
        }

        val keyboardHeight = PreferenceManager.getDefaultSharedPreferences(context)
            .getInt("keyboard_height_portrait", defaultCustomKeyboardSize)
        return Util.clamp(keyboardHeight, minCustomKeyboardSize, rootView.height - minCustomKeyboardTopMarginPortrait)
    }

    private fun setKeyboardPortraitHeight(height: Int) {
        if (isBubble) {
            return
        }

        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putInt("keyboard_height_portrait", height).apply()
    }

    private fun setKeyboardLandscapeHeight(height: Int) {
        if (isBubble) {
            return
        }

        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putInt("keyboard_height_landscape", height).apply()
    }

    fun postOnKeyboardClose(runnable: Runnable) {
        if (keyboardOpen) {
            addOnKeyboardHiddenListener(object : OnKeyboardHiddenListener {
                override fun onKeyboardHidden() {
                    removeOnKeyboardHiddenListener(this)
                    runnable.run()
                }
            })
        } else {
            runnable.run()
        }
    }

    fun postOnKeyboardOpen(runnable: Runnable) {
        if (!keyboardOpen) {
            addOnKeyboardShownListener(object : OnKeyboardShownListener {
                override fun onKeyboardShown() {
                    removeOnKeyboardShownListener(this)
                    runnable.run()
                }
            })
        } else {
            runnable.run()
        }
    }

    fun addOnKeyboardHiddenListener(listener: OnKeyboardHiddenListener) {
        hiddenListeners.add(listener)
    }

    fun removeOnKeyboardHiddenListener(listener: OnKeyboardHiddenListener) {
        hiddenListeners.remove(listener)
    }

    fun addOnKeyboardShownListener(listener: OnKeyboardShownListener) {
        shownListeners.add(listener)
    }

    fun removeOnKeyboardShownListener(listener: OnKeyboardShownListener) {
        shownListeners.remove(listener)
    }

    private fun notifyHiddenListeners() {
        val listeners: Set<OnKeyboardHiddenListener> = HashSet(hiddenListeners)
        for (listener in listeners) {
            listener.onKeyboardHidden()
        }
    }

    private fun notifyShownListeners() {
        val listeners: Set<OnKeyboardShownListener> = HashSet(shownListeners)
        for (listener in listeners) {
            listener.onKeyboardShown()
        }
    }

    interface OnKeyboardHiddenListener {
        fun onKeyboardHidden()
    }

    interface OnKeyboardShownListener {
        fun onKeyboardShown()
    }

    companion object {
        private const val TAG = "KeyboardAwareLinearLayout"
        private const val KEYBOARD_DEBOUNCE: Long = 150
    }
}
