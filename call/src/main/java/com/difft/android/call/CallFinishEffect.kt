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

/**
 * Renders the session's finish request on the call page: the tip (if any), then [endCall].
 *
 * `Dispatchers.Main`, not `.immediate`: the end sequence always runs one main-looper pass after the
 * request is set, never re-entrantly inside the RTM handler or state write that set it. Not gated on
 * STARTED: a remote end must finish the call while the page is in the background (in PiP or behind
 * another app), not when the user next returns to it.
 *
 * A request the session already handled without a page ([CallFinishRequest.handledWithoutUi]) still
 * finishes this page, without a second tip, so a page attaching to a finished session cannot linger.
 */
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
