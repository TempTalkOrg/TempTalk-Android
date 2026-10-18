package com.difft.android.call.session

import com.difft.android.base.call.CallRole
import com.difft.android.base.user.CallConfig
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.call.CallIntent
import com.difft.android.call.data.VoicePreset

/**
 * Immutable inputs of one call session, built once by [CallSessionLauncher] from the START/JOIN
 * intent before the call page exists.
 */
data class CallSessionParams(
    val e2eeEnable: Boolean,
    val callIntent: CallIntent,
    val callConfig: CallConfig,
    val callRole: CallRole,
    /**
     * Global voice-changer preference applied when the call starts. Per product spec, mid-call
     * changes via `setVoicePreset` do NOT write back to this preference — the next call restores
     * the user's saved value.
     */
    val initialVoicePreset: VoicePreset,
    /** Carried by the launch intent ([CallIntent.sessionKey]); a page whose key differs is stale. */
    val launchKey: String = "",
    /** App lock required while the call runs: the user has one set and the entry point asked for it. */
    val needAppLock: Boolean = false,
    /** The entry point's wait-dialog claim; released when the session ends. */
    val entryWaitToken: Int = ComposeDialogManager.NO_CLAIM,
)
