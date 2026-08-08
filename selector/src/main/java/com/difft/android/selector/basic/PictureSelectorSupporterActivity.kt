package com.difft.android.selector.basic

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import com.difft.android.base.BaseActivity
import com.difft.android.selector.PictureSelectorFragment
import com.difft.android.selector.R
import com.difft.android.selector.config.SelectorConfig
import com.difft.android.selector.config.SelectorProviders
import com.difft.android.selector.language.LanguageConfig
import com.difft.android.selector.language.PictureLanguageUtils

class PictureSelectorSupporterActivity : BaseActivity() {
    private var selectorConfig: SelectorConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSelectorConfig()
        setContentView(R.layout.ps_activity_container)
        setupFragment()
    }

    private fun initSelectorConfig() {
        selectorConfig = SelectorProviders.getInstance().selectorConfig
    }

    private fun setupFragment() {
        FragmentInjectManager.injectFragment(
            this, PictureSelectorFragment.TAG,
            PictureSelectorFragment.newInstance()
        )
    }

    /** set app language */
    fun initAppLanguage() {
        val config = selectorConfig
        if (config != null && config.language != LanguageConfig.UNKNOWN_LANGUAGE && !config.isOnlyCamera) {
            PictureLanguageUtils.setAppLanguage(this, config.language, config.defaultLanguage)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        initAppLanguage()
    }

    override fun attachBaseContext(newBase: Context) {
        val config = SelectorProviders.getInstance().selectorConfig
        super.attachBaseContext(PictureContextWrapper.wrap(newBase, config.language, config.defaultLanguage))
    }

    override fun finish() {
        super.finish()
        selectorConfig?.let {
            val windowAnimationStyle = it.selectorStyle.windowAnimationStyle!!
            overridePendingTransition(0, windowAnimationStyle.activityExitAnimation)
        }
    }
}
