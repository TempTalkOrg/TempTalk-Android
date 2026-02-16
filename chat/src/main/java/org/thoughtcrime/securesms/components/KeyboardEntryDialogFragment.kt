package org.thoughtcrime.securesms.components

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.LayoutRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import com.difft.android.chat.R

/**
 * Fullscreen Dialog Fragment which will dismiss itself when the keyboard is closed
 */
abstract class KeyboardEntryDialogFragment(@LayoutRes contentLayoutId: Int) :
  DialogFragment(contentLayoutId),
  KeyboardAwareLinearLayout.OnKeyboardShownListener,
  KeyboardAwareLinearLayout.OnKeyboardHiddenListener {

  private var hasShown = false
  private var imeWasVisible = false

  protected open val withDim: Boolean = false

  protected open val themeResId: Int = R.style.Theme_TT_RoundedBottomSheet

  override fun onCreate(savedInstanceState: Bundle?) {
    setStyle(STYLE_NORMAL, themeResId)
    super.onCreate(savedInstanceState)
  }

  @Suppress("DEPRECATION")
  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    val dialog = super.onCreateDialog(savedInstanceState)

    if (!withDim) {
      dialog.window?.setDimAmount(0f)
    }

    // Use ADJUST_NOTHING for edge-to-edge compatibility - IME insets handled via WindowInsets API
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    // Enable edge-to-edge for the dialog window
    dialog.window?.let { window ->
      WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    return dialog
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    hasShown = false
    imeWasVisible = false

    val view = super.onCreateView(inflater, container, savedInstanceState)
    return if (view is KeyboardAwareLinearLayout) {
      view.addOnKeyboardShownListener(this)
      view.addOnKeyboardHiddenListener(this)
      // Setup IME insets listener for edge-to-edge support
      setupImeInsetsListener(view)
      view
    } else {
      throw IllegalStateException("Expected parent of view hierarchy to be keyboard aware.")
    }
  }

  private fun setupImeInsetsListener(view: View) {
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
      val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
      val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
      val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())

      // Apply bottom padding for IME or navigation bar
      val bottomPadding = if (imeVisible && imeInsets.bottom > 0) imeInsets.bottom else systemBarsInsets.bottom
      v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomPadding)

      // Track IME visibility for dismiss-on-keyboard-close behavior
      if (imeVisible) {
        imeWasVisible = true
      } else if (imeWasVisible) {
        // Keyboard was visible and now hidden - dismiss
        dismissAllowingStateLoss()
      }

      windowInsets
    }
  }

  override fun onPause() {
    super.onPause()
    hasShown = false
  }

  override fun onKeyboardShown() {
    hasShown = true
  }

  override fun onKeyboardHidden() {
    if (hasShown) {
      dismissAllowingStateLoss()
    }
  }
}
