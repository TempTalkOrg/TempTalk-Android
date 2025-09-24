package com.difft.android.base.utils

import com.difft.android.base.application.ScopeApplication

object ApplicationHelper {
    /**
     * application context 不应该被用于 UI 相关的动作，比如用来 inflate layout 会启用系统的默认 theme
     */
    lateinit var instance: ScopeApplication
        private set

    fun init(application: ScopeApplication) {
        instance = application
    }
}
