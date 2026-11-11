package com.difft.android.base.session

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

















object InactiveDeviceNotifier {

    private val _pending = MutableStateFlow(false)

    
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    
    @Volatile
    var isDialogShowing = false
        private set

    
    fun raise() {
        if (_pending.value) return
        L.w { "[InactiveDevice] 451 -> raising activation prompt" }
        _pending.value = true
    }

    



    fun markDialogShown() {
        isDialogShowing = true
    }

    
    fun clear() {
        isDialogShowing = false
        _pending.value = false
    }

    




    fun onDialogDismissed() {
        isDialogShowing = false
    }
}
