package com.difft.android.base.session

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/**
 * Process-wide hub that turns "the WebSocket handshake answered 451 — this device has been
 * inactive too long" into a single, de-duplicated activation prompt for the UI layer to present.
 *
 * Why a singleton object: the 451 arrives on a background socket thread that can be running with
 * no Activity on screen at all, and the payload is one boolean with no policy and no I/O.
 *
 * Lifecycle of a prompt:
 * 1. [raise] flips [pending] true.
 * 2. The UI presenter shows the dialog and calls [markDialogShown].
 * 3. Activation succeeds, or the socket connects again -> [clear].
 *
 * [pending] deliberately survives the hosting Activity: unlike a MITM warning there is no "ignore"
 * choice — the device stays unusable until it is activated — so if the host is destroyed before the
 * user decides, [onDialogDismissed] re-arms and the next resumed Activity shows it again.
 */
object InactiveDeviceNotifier {

    private val _pending = MutableStateFlow(false)

    /** True while an activation prompt is waiting to be shown by the foreground UI. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** True between [markDialogShown] and dismissal; blocks raising a second dialog. */
    @Volatile
    var isDialogShowing = false
        private set

    /** The WS handshake returned 451: this device has been inactive too long. */
    fun raise() {
        if (_pending.value) return
        L.w { "[InactiveDevice] 451 -> raising activation prompt" }
        _pending.value = true
    }

    /**
     * Called by the UI once the prompt is actually on screen. Keeps [pending] true on purpose
     * (see class doc); [isDialogShowing] is what prevents a second dialog.
     */
    fun markDialogShown() {
        isDialogShowing = true
    }

    /** Activation succeeded, or the socket connected again. */
    fun clear() {
        isDialogShowing = false
        _pending.value = false
    }

    /**
     * The dialog left the screen without a decision (e.g. its hosting Activity was destroyed by a
     * configuration change). Drop the showing flag but keep [pending] so the next resumed Activity
     * re-shows the still-unacknowledged prompt.
     */
    fun onDialogDismissed() {
        isDialogShowing = false
    }
}
