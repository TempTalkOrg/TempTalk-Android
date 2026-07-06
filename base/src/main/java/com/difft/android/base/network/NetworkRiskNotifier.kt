package com.difft.android.base.network

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/**
 * Process-wide hub that turns "certificate validation failed on a pinned channel"
 * signals (from HTTP / WebSocket / call layers) into a single, de-duplicated
 * man-in-the-middle warning for the UI layer to present.
 *
 * Why a singleton object:
 * - Network failures happen on background threads with no Activity, far from the UI.
 * - Many requests can fail at once; without de-dup the user would be spammed.
 *
 * Lifecycle of a warning:
 * 1. [onCertValidationFailed] flips [warningPending] true (unless suppressed/showing).
 * 2. The UI observer shows the dialog and calls [markDialogShown].
 * 3. User picks "Ignore" -> [ignoreForSession] suppresses further warnings for the
 *    rest of this process lifetime; "Quit" tears the process down anyway.
 *
 * [warningPending] stays true for the whole visible lifetime of the dialog and is only
 * cleared by an explicit user choice ([ignoreForSession]). [isDialogShowing] alone guards
 * against duplicate dialogs. This way, if the hosting Activity is destroyed before the user
 * decides (e.g. a configuration change), [onDialogDismissed] re-arms the warning so the next
 * foreground Activity shows it again, instead of silently swallowing the threat forever.
 */
object NetworkRiskNotifier {

    private val _warningPending = MutableStateFlow(false)

    /** True while a MITM warning is waiting to be shown by the foreground UI. */
    val warningPending: StateFlow<Boolean> = _warningPending.asStateFlow()

    // Set once the user explicitly chose "Ignore"; no further warnings this session.
    @Volatile
    private var ignoredThisSession = false

    /** True between [markDialogShown] and dismissal; blocks raising a second dialog. */
    @Volatile
    var isDialogShowing = false
        private set

    /**
     * Report a certificate validation failure observed on a pinned channel.
     * @param source short tag for logging (e.g. "http:host", "websocket", "call:host").
     */
    fun onCertValidationFailed(source: String) {
        if (ignoredThisSession) return
        if (isDialogShowing || _warningPending.value) return
        L.w { "[NetworkRisk] certificate validation failure from $source -> raising MITM warning" }
        _warningPending.value = true
    }

    /**
     * Called by the UI once the warning dialog is actually on screen. Keeps [warningPending]
     * true on purpose (see class doc); [isDialogShowing] prevents a second dialog.
     */
    fun markDialogShown() {
        isDialogShowing = true
    }

    /** User chose "Ignore": stop warning for the rest of this process lifetime. */
    fun ignoreForSession() {
        ignoredThisSession = true
        isDialogShowing = false
        _warningPending.value = false
    }

    /**
     * The dialog left the screen without an explicit user choice (e.g. its hosting Activity
     * was destroyed). Drop the showing flag but keep [warningPending] so the next foreground
     * Activity re-shows the still-unacknowledged warning.
     */
    fun onDialogDismissed() {
        isDialogShowing = false
    }
}
