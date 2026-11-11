package com.difft.android.base.widget

import android.app.Dialog
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SecureModeUtil
import com.difft.android.base.utils.StatusBarSetting
import com.difft.android.base.utils.WindowSizeClassUtil
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment











abstract class BaseBottomSheetDialogFragment : BottomSheetDialogFragment() {

    

    




    protected open fun useDefaultContainer(): Boolean = true

    



    @LayoutRes
    protected open fun getCustomLayoutResId(): Int = 0

    

    




    @LayoutRes
    protected open fun getContentLayoutResId(): Int = 0

    




    protected open fun getContentFragment(): Fragment? = null

    

    




    protected open fun getPeekHeightRatio(): Float = 0f

    



    protected open fun isFullScreen(): Boolean = false

    




    protected open fun isExpandable(): Boolean = true

    



    protected open fun isCancelableByUser(): Boolean = true

    



    protected open fun isDraggable(): Boolean = true

    



    protected open fun showDragHandle(): Boolean = true

    




    protected open fun useDefaultBackground(): Boolean = true

    





    @DrawableRes
    protected open fun getContainerBackgroundRes(): Int = R.drawable.base_bg_bottom_sheet

    





    protected open fun reservesKeyboardInset(): Boolean = true

    

    




    protected open fun getMaxWidth(): Int {
        return resources.getDimensionPixelSize(R.dimen.bottom_sheet_max_width)
    }

    

    private var contentView: View? = null

    

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return if (useDefaultContainer()) {
            inflater.inflate(R.layout.base_bottom_sheet_container, container, false)
        } else {
            val customLayoutId = getCustomLayoutResId()
            if (customLayoutId != 0) {
                inflater.inflate(customLayoutId, container, false)
            } else {
                null
            }
        }
    }

    
    override fun getTheme(): Int = R.style.Theme_TT_BottomSheetDialog

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (useDefaultContainer()) {
            applyInsetCard(view, keepBackground = !useDefaultBackground())
            
            ViewCompat.setOnApplyWindowInsetsListener(view) { card, insets ->
                val navigationBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                val keyboard = if (reservesKeyboardInset()) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
                bottomInsetPx = maxOf(navigationBar, keyboard)
                insetFromSheetEdges(card)
                insets
            }

            val dragHandle = view.findViewById<View>(R.id.drag_handle)
            dragHandle?.visibility = if (showDragHandle()) View.VISIBLE else View.GONE

            val contentContainer = view.findViewById<FrameLayout>(R.id.content_container)

            val contentFragment = getContentFragment()
            if (contentFragment != null) {
                childFragmentManager.beginTransaction()
                    .replace(R.id.content_container, contentFragment)
                    .commit()
            } else {
                val layoutResId = getContentLayoutResId()
                if (layoutResId != 0) {
                    contentView = layoutInflater.inflate(layoutResId, contentContainer, false)
                    contentContainer.addView(contentView)
                    onContentViewCreated(contentView!!, savedInstanceState)
                }
            }
        }
        
    }

    
    private var bottomInsetPx = 0

    




    private fun applyInsetCard(view: View, keepBackground: Boolean) {
        val radius = resources.getDimension(R.dimen.radius_floating)
        if (keepBackground) {
            view.setBackgroundResource(getContainerBackgroundRes())
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            view.elevation = resources.getDimension(R.dimen.bottom_sheet_elevation)
            view.applyFloatingCardShadow()
        } else {
            val fill = (ContextCompat.getDrawable(view.context, getContainerBackgroundRes()) as? GradientDrawable)?.color?.defaultColor
            view.applyFloatingSurface(cornerRadiusPx = radius, fillColor = fill, clipsContent = true)
        }
        view.clipToOutline = true
        insetFromSheetEdges(view)
    }

    





    private fun insetFromSheetEdges(view: View) {
        val sheet = view.parent as? ViewGroup ?: return
        sheet.clipChildren = false
        sheet.clipToPadding = false
        (sheet.parent as? ViewGroup)?.clipChildren = false
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val inset = resources.getDimensionPixelSize(R.dimen.bottom_sheet_inset)
        val bottom = inset + bottomInsetPx
        if (params.leftMargin == inset && params.rightMargin == inset && params.bottomMargin == bottom) return
        params.setMargins(inset, params.topMargin, inset, bottom)
        view.layoutParams = params
    }

    



    protected open fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
        
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog

        
        
        dialog.behavior.applyMaxWidth(getMaxWidth())

        
        
        
        
        if (getPeekHeightRatio() <= 0f && !isFullScreen()) {
            dialog.behavior.isFitToContents = true
            dialog.behavior.peekHeight = BottomSheetBehavior.PEEK_HEIGHT_AUTO
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        
        dialog.window?.setDimAmount(SheetScrim.dimAmount(requireContext()))
        dialog.window?.let { window ->
            StatusBarSetting.attach(requireContext(), window)
            
            
            
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.attributes = window.attributes.apply { fitInsetsTypes = 0 }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }

        isCancelable = isCancelableByUser()
        dialog.setCanceledOnTouchOutside(isCancelableByUser())

        dialog.setOnShowListener { dialogInterface ->
            
            
            val host = activity ?: run {
                val sheetName = javaClass.simpleName
                L.w { "[BottomSheet] onShow after detach sheet=$sheetName" }
                return@setOnShowListener
            }
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            
            
            
            bottomSheetDialog.dismissWithAnimation = true
            val bottomSheet = bottomSheetDialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )

            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                val peekHeightRatio = getPeekHeightRatio()

                behavior.isDraggable = isDraggable()
                behavior.isHideable = isCancelableByUser()

                val screenHeight = WindowSizeClassUtil.getWindowHeightPx(host)

                if (peekHeightRatio > 0 && !isExpandable()) {
                    
                    val fixedHeight = (screenHeight * peekHeightRatio).toInt()

                    val layoutParams = sheet.layoutParams
                    layoutParams.height = fixedHeight
                    sheet.layoutParams = layoutParams

                    behavior.isFitToContents = true
                    behavior.skipCollapsed = true
                    behavior.peekHeight = fixedHeight
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                } else if (isFullScreen() || peekHeightRatio > 0) {
                    
                    behavior.isFitToContents = false
                    behavior.skipCollapsed = isFullScreen()

                    if (isFullScreen()) {
                        behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    } else {
                        behavior.peekHeight = (screenHeight * peekHeightRatio).toInt()
                        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    }

                    val layoutParams = sheet.layoutParams
                    layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                    sheet.layoutParams = layoutParams
                } else {
                    
                    behavior.isFitToContents = true
                    behavior.skipCollapsed = false
                    behavior.peekHeight = BottomSheetBehavior.PEEK_HEIGHT_AUTO
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                }

                
                if (useDefaultBackground()) {
                    sheet.setBackgroundResource(android.R.color.transparent)
                    if (useDefaultContainer()) view?.let(::insetFromSheetEdges)
                }

                behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                            dismissAllowingStateLoss()
                        }
                        onBottomSheetStateChanged(newState)
                    }

                    override fun onSlide(bottomSheet: View, slideOffset: Float) {
                        onBottomSheetSlide(slideOffset)
                    }
                })

                onBottomSheetReady(sheet, behavior)
            }
        }

        
        SecureModeUtil.applySecureFlagToDialog(dialog)

        return dialog
    }


    






    protected open fun onBottomSheetReady(sheet: View, behavior: BottomSheetBehavior<*>) {
        
    }

    



    protected open fun onBottomSheetStateChanged(newState: Int) {
        
    }

    



    protected open fun onBottomSheetSlide(slideOffset: Float) {
        
    }

    override fun onDestroyView() {
        dialog?.window?.let(StatusBarSetting::detach)
        super.onDestroyView()
        contentView = null
    }

    companion object {
        


        fun <T : BaseBottomSheetDialogFragment> T.show(activity: FragmentActivity, tag: String? = null) {
            show(activity.supportFragmentManager, tag ?: this::class.java.simpleName)
        }

        


        fun <T : BaseBottomSheetDialogFragment> T.show(fragment: Fragment, tag: String? = null) {
            show(fragment.parentFragmentManager, tag ?: this::class.java.simpleName)
        }
    }
}