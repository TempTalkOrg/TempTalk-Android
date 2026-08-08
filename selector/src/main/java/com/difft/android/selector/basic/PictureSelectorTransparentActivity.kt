package com.difft.android.selector.basic

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import androidx.core.content.ContextCompat
import com.difft.android.base.BaseActivity
import com.difft.android.selector.PictureSelectorPreviewFragment
import com.difft.android.selector.R
import com.difft.android.selector.config.PictureConfig
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.config.SelectorProviders
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.style.SelectMainStyle
import com.difft.android.selector.utils.StyleUtils

class PictureSelectorTransparentActivity : BaseActivity() {
    private lateinit var selectorConfig: SelectorConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSelectorConfig()
        immersive()
        setContentView(R.layout.ps_empty)
        if (!isExternalPreview()) {
            setActivitySize()
        }
        setupFragment()
    }

    private fun initSelectorConfig() {
        selectorConfig = SelectorProviders.getInstance().selectorConfig
    }

    private fun isExternalPreview(): Boolean {
        val modeTypeSource = intent.getIntExtra(PictureConfig.EXTRA_MODE_TYPE_SOURCE, 0)
        return modeTypeSource == PictureConfig.MODE_TYPE_EXTERNAL_PREVIEW_SOURCE
    }

    private fun immersive() {
        val mainStyle: SelectMainStyle = selectorConfig.selectorStyle.selectMainStyle!!
        var statusBarColor = mainStyle.statusBarColor
        var navigationBarColor = mainStyle.navigationBarColor
        if (!StyleUtils.checkStyleValidity(statusBarColor)) {
            statusBarColor = ContextCompat.getColor(this, R.color.ps_color_grey)
        }
        if (!StyleUtils.checkStyleValidity(navigationBarColor)) {
            navigationBarColor = ContextCompat.getColor(this, R.color.ps_color_grey)
        }
    }

    private fun setupFragment() {
        // Transparent activity now hosts only external preview.
        if (!isExternalPreview()) {
            finish()
            return
        }
        val injected = selectorConfig.onInjectActivityPreviewListener?.onInjectPreviewFragment()
        val fragmentTag: String
        val previewFragment: PictureSelectorPreviewFragment
        if (injected != null) {
            previewFragment = injected
            fragmentTag = previewFragment.getFragmentTag()
        } else {
            fragmentTag = PictureSelectorPreviewFragment.TAG
            previewFragment = PictureSelectorPreviewFragment.newInstance()
        }
        val position = intent.getIntExtra(PictureConfig.EXTRA_PREVIEW_CURRENT_POSITION, 0)
        val previewData = ArrayList(selectorConfig.selectedPreviewResult)
        val isDisplayDelete = intent
            .getBooleanExtra(PictureConfig.EXTRA_EXTERNAL_PREVIEW_DISPLAY_DELETE, false)
        previewFragment.setExternalPreviewData(position, previewData.size, previewData, isDisplayDelete)

        val supportFragmentManager = supportFragmentManager
        val fragment = supportFragmentManager.findFragmentByTag(fragmentTag)
        if (fragment != null) {
            supportFragmentManager.beginTransaction().remove(fragment).commitAllowingStateLoss()
        }
        FragmentInjectManager.injectFragment(this, fragmentTag, previewFragment)
    }

    @SuppressLint("RtlHardcoded")
    private fun setActivitySize() {
        val window = window
        window.setGravity(Gravity.LEFT or Gravity.TOP)
        val params = window.attributes
        params.x = 0
        params.y = 0
        params.height = 1
        params.width = 1
        window.attributes = params
    }

    override fun finish() {
        super.finish()
        val modeTypeSource = intent.getIntExtra(PictureConfig.EXTRA_MODE_TYPE_SOURCE, 0)
        if (modeTypeSource == PictureConfig.MODE_TYPE_EXTERNAL_PREVIEW_SOURCE && !selectorConfig.isPreviewZoomEffect) {
            val windowAnimationStyle = selectorConfig.selectorStyle.windowAnimationStyle!!
            overridePendingTransition(0, windowAnimationStyle.activityExitAnimation)
        } else {
            overridePendingTransition(0, R.anim.ps_anim_fade_out)
        }
    }
}
