package com.difft.android.base.network

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile






















object NetworkRiskNotifier {

    private val _warningPending = MutableStateFlow(false)

    
    val warningPending: StateFlow<Boolean> = _warningPending.asStateFlow()

    
    @Volatile
    private var ignoredThisSession = false

    
    @Volatile
    var isDialogShowing = false
        private set

    



    fun onCertValidationFailed(source: String) {
        if (ignoredThisSession) return
        if (isDialogShowing || _warningPending.value) return
        L.w { "[NetworkRisk] certificate validation failure from $source -> raising MITM warning" }
        _warningPending.value = true
    }

    



    fun markDialogShown() {
        isDialogShowing = true
    }

    
    fun ignoreForSession() {
        ignoredThisSession = true
        isDialogShowing = false
        _warningPending.value = false
    }

    




    fun onDialogDismissed() {
        isDialogShowing = false
    }
}
