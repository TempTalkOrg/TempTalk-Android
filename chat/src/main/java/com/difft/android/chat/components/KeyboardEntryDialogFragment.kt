package com.difft.android.chat.components

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.LayoutRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import com.difft.android.base.utils.StatusBarSetting
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.chat.R




abstract class KeyboardEntryDialogFragment(@LayoutRes contentLayoutId: Int) :
  androidx.fragment.app.DialogFragment(contentLayoutId),
  KeyboardAwareLinearLayout.OnKeyboardShownListener,
  KeyboardAwareLinearLayout.OnKeyboardHiddenListener {

  private var hasShown = false
  private var imeWasVisible = false

  protected open val withDim: Boolean = false

  protected open val themeResId: Int = R.style.Theme_TT_RoundedBottomSheet

  



  protected open val capToSheetWidth: Boolean = true

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

    dialog.window?.let { window ->
      StatusBarSetting.attach(requireContext(), window)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
      } else {
        
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
      }
    }

    return dialog
  }

  override fun onDestroyView() {
    dialog?.window?.let(StatusBarSetting::detach)
    super.onDestroyView()
  }

  override fun onStart() {
    super.onStart()
    applyMaxWidth()
  }

  



  private fun applyMaxWidth() {
    if (!capToSheetWidth) return
    val window = dialog?.window ?: return
    val maxWidth = resources.getDimensionPixelSize(com.difft.android.base.R.dimen.bottom_sheet_max_width)
    if (WindowSizeClassUtil.getWindowWidthPx(requireActivity()) > maxWidth) {
      window.setLayout(maxWidth, WindowManager.LayoutParams.MATCH_PARENT)
      window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
    }
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    hasShown = false
    imeWasVisible = false

    val view = super.onCreateView(inflater, container, savedInstanceState)
    return if (view is KeyboardAwareLinearLayout) {
      view.addOnKeyboardShownListener(this)
      view.addOnKeyboardHiddenListener(this)
      
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        setupImeInsetsListener(view)
      }
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

      
      val bottomPadding = if (imeVisible && imeInsets.bottom > 0) imeInsets.bottom else systemBarsInsets.bottom
      v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomPadding)

      
      if (imeVisible) {
        imeWasVisible = true
      } else if (imeWasVisible) {
        
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
