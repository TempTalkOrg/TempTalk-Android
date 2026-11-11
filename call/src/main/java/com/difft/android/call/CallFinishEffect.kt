package com.difft.android.call

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.session.CallFinishRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch












internal fun LifecycleOwner.launchFinishRequestCollector(
    finishRequest: StateFlow<CallFinishRequest?>,
    showTip: (message: String, onDismiss: () -> Unit) -> Unit,
    endCall: () -> Unit,
): Job = lifecycleScope.launch(Dispatchers.Main) {
    val request = finishRequest.filterNotNull().first()
    L.i { "[Call] LCallActivity finish request reason=${request.reason} handledWithoutUi=${request.handledWithoutUi}" }
    val tipRes = request.tipRes
    if (request.handledWithoutUi || tipRes == null) {
        endCall()
    } else {
        showTip(ResUtils.getString(tipRes), endCall)
    }
}
