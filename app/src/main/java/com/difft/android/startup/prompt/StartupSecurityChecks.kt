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


enum class SecurityWarning(@param:StringRes val messageRes: Int) {
    SIGNATURE(R.string.app_sign_error_tips),
    EMULATOR(R.string.emulator_risk_tips),
    ROOT(R.string.root_risk_tips),
}





@Singleton
class StartupSecurityChecks @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val checked = AtomicBoolean(false)

    private val _pending = MutableStateFlow<List<SecurityWarning>>(emptyList())

    
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
