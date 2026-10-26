package com.difft.android.startup.prompt

import android.os.SystemClock
import com.difft.android.BuildConfig
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.appScope
import com.difft.android.setting.data.CheckUpdateResponse
import com.difft.android.setting.repo.SettingRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

/** Latest launch-time update check result. */
sealed interface UpdateCheckState {
    /** Not checked yet, or every check so far failed. */
    data object Unknown : UpdateCheckState
    data object NoUpdate : UpdateCheckState
    data class Optional(val response: CheckUpdateResponse) : UpdateCheckState
    data class Forced(val response: CheckUpdateResponse) : UpdateCheckState
}

/**
 * Identity of an update offer: the APK hash when the server sends one, else the URL. A different key
 * resets the OPTIONAL_UPDATE record. Gson may leave the declared non-null fields null.
 */
fun updateOfferKey(apkHash: String?, url: String?): String? =
    apkHash?.takeIf { it.isNotBlank() } ?: url?.takeIf { it.isNotBlank() }

/**
 * The non-insider launch-time update check: once per process, and again at a session start when the
 * last check is at least 30 minutes old. A failed check keeps the previous result (fail-open
 * offline, as before). The insider feed keeps its own silent path in IndexActivity.
 *
 * [blockingForced] is the forced offer that gates the app. After the delivery hands the forced offer
 * off (a sideload download started), it stops gating after 30 minutes, so an abandoned download never
 * blocks every other prompt for the rest of the process; the next recheck restores it if the server
 * still forces the update.
 */
@Singleton
class StartupUpdateChecker internal constructor(
    private val settingRepo: SettingRepo,
    private val environmentHelper: EnvironmentHelper,
    private val userManager: UserManager,
    private val scope: CoroutineScope,
    private val elapsed: () -> Long,
) {
    @Inject
    constructor(settingRepo: SettingRepo, environmentHelper: EnvironmentHelper, userManager: UserManager) :
        this(settingRepo, environmentHelper, userManager, appScope, SystemClock::elapsedRealtime)

    private val _state = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Unknown)
    val state: StateFlow<UpdateCheckState> = _state.asStateFlow()

    private val _blockingForced = MutableStateFlow<CheckUpdateResponse?>(null)

    /** The forced offer that currently gates the app, or null. */
    val blockingForced: StateFlow<CheckUpdateResponse?> = _blockingForced.asStateFlow()

    @Volatile
    private var lastCheckElapsed = 0L

    @Volatile
    private var job: Job? = null

    @Volatile
    private var handOffExpiry: Job? = null

    /** The forced offer whose hand-off expiry is running. */
    @Volatile
    private var handedOff: CheckUpdateResponse? = null

    fun onSessionStart() {
        if (environmentHelper.isInsiderChannel()) return
        if (job?.isActive == true) return
        val last = lastCheckElapsed
        if (last != 0L && elapsed() - last < RECHECK_MS) return
        lastCheckElapsed = elapsed()
        job = scope.launch {
            // Same settle time as before, so the check does not join the cold-start request wave.
            delay(START_DELAY_MS)
            check()
        }
    }

    /** The delivery took over the forced offer; it stops gating after [HAND_OFF_GATE_MS]. */
    fun onForcedHandOff() {
        val forced = _blockingForced.value ?: return
        handOffExpiry?.cancel()
        handedOff = forced
        handOffExpiry = scope.launch {
            delay(HAND_OFF_GATE_MS)
            handedOff = null
            if (_blockingForced.value == forced) {
                L.w { "[StartupPrompt] forced gate expired after hand-off" }
                _blockingForced.value = null
            }
        }
    }

    private suspend fun check() {
        try {
            val result = settingRepo.checkUpdate(PackageUtil.getAppVersionName() ?: "", BuildConfig.APP_CHANNEL)
            if (result.status == 0) {
                val response = result.data
                val next = when {
                    response == null || !response.update -> UpdateCheckState.NoUpdate
                    response.force -> UpdateCheckState.Forced(response)
                    else -> UpdateCheckState.Optional(response)
                }
                // A recheck that returns the offer already handed off keeps its expiry running: its
                // dialog is closed, so restarting the gate would block every prompt with nothing shown.
                val sameHandedOff = next is UpdateCheckState.Forced && next.response == handedOff && handOffExpiry?.isActive == true
                if (!sameHandedOff) {
                    handOffExpiry?.cancel()
                    handedOff = null
                }
                _state.value = next
                _blockingForced.value = (next as? UpdateCheckState.Forced)?.response
                L.i { "[StartupPrompt] update check result=${next.javaClass.simpleName}" }
            } else {
                L.w { "[StartupPrompt] update check failed status=${result.status}" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[StartupPrompt] update check failed error=${e.javaClass.simpleName}" }
        }
        userManager.update { lastCheckUpdateTime = System.currentTimeMillis() }
    }

    internal companion object {
        val RECHECK_MS = 30.minutes.inWholeMilliseconds
        val HAND_OFF_GATE_MS = 30.minutes.inWholeMilliseconds
        const val START_DELAY_MS = 2_000L
    }
}
