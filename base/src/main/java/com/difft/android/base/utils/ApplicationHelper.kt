package com.difft.android.base.utils

import com.difft.android.base.application.ScopeApplication

object ApplicationHelper {
    
    lateinit var instance: ScopeApplication
        private set

    fun init(application: ScopeApplication) {
        instance = application
    }
}
