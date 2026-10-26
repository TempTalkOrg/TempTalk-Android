package com.difft.android.startup.prompt

import android.content.Context
import androidx.annotation.StringRes
import com.difft.android.BuildConfig
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.chat.R
import com.difft.android.security.SecurityLib
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Security warnings, shown one at a time in declaration order. */
enum class SecurityWarning(@param:StringRes val messageRes: Int) {
    SIGNATURE(R.string.app_sign_error_tips),
    EMULATOR(R.string.emulator_risk_tips),
    ROOT(R.string.root_risk_tips),
}

/**
 * Signature, emulator and root checks, run once per process (not per Activity recreation). "Ignore"
 * removes a warning for the rest of the process; the next cold start checks again.
 */
@Singleton
class StartupSecurityChecks @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val checked = AtomicBoolean(false)

    private val _pending = MutableStateFlow<List<SecurityWarning>>(emptyList())

    /** Warnings still to show, in order; the gate is active while this is not empty. */
    val pending: StateFlow<List<SecurityWarning>> = _pending.asStateFlow()

    fun ensureChecked() {
        if (BuildConfig.DEBUG || !checked.compareAndSet(false, true)) return
        appScope.launch {
            val failed = buildList {
                if (!SecurityLib.checkEmulator()) add(SecurityWarning.EMULATOR)
                if (!SecurityLib.checkRoot()) add(SecurityWarning.ROOT)
            }
            if (failed.isNotEmpty()) {
                L.w { "[StartupPrompt] security warnings=${failed.joinToString(",")}" }
                _pending.value = failed
            }
        }
    }

    fun ignore(warning: SecurityWarning) {
        _pending.update { it - warning }
    }
}
