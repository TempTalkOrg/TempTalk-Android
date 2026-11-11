package com.difft.android.app

import com.difft.android.app.startup.LocalTokenVerifier
import com.difft.android.app.startup.needsIdentityKeyRelogin
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.network.auth.AuthTokenProvider
import com.difft.android.push.PushUtil
import com.difft.android.push.ResyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.difft.app.database.WCDB
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

















@Singleton
class WarmProcessResync @Inject constructor(
    private val userManager: UserManager,
    private val wcdb: WCDB,
    private val authTokenProvider: AuthTokenProvider,
    private val messageArchiveManager: MessageArchiveManager,
    private val pushUtil: PushUtil,
) {
    private val loggedGateReasons = ConcurrentHashMap.newKeySet<String>()

    suspend fun onForeground() {
        var blocked = gateBlockReason()
        if (blocked == REASON_PROBE && !wcdb.probeFinished.value) {
            withTimeoutOrNull(PROBE_WAIT_MS) { wcdb.probeFinished.first { it } }
            blocked = gateBlockReason()
        }
        if (blocked != null) {
            if (loggedGateReasons.add(blocked)) L.i { "[WarmResync] gate closed reason=$blocked" }
            return
        }
        runTask(TASK_ARCHIVE) { if (messageArchiveManager.startCheckTask()) ResyncResult.OK else ResyncResult.SKIP }
        runTask(TASK_FCM) { pushUtil.resyncBind() }
    }

    private fun gateBlockReason(): String? = when {
        wcdb.isDbInaccessible -> REASON_INACCESSIBLE
        !wcdb.isHealthyProbed -> REASON_PROBE
        !isSessionUsable() -> REASON_SESSION
        else -> null
    }

    private fun isSessionUsable(): Boolean {
        val user = userManager.getUserData()
        return LocalTokenVerifier.isLocalTokenValid(
            user?.baseAuth,
            user?.account,
            authTokenProvider.peek(),
            logInconsistency = false,
        ) &&
            !needsIdentityKeyRelogin(user)
    }

    
    private suspend fun runTask(name: String, task: suspend () -> ResyncResult) {
        try {
            when (task()) {
                ResyncResult.OK -> L.i { "[WarmResync] task=$name result=ok" }
                ResyncResult.FAIL -> L.i { "[WarmResync] task=$name result=fail" }
                ResyncResult.SKIP -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e { "[WarmResync] task=$name result=fail error=${e.stackTraceToString()}" }
        }
    }

    private companion object {
        const val PROBE_WAIT_MS = 10_000L
        const val TASK_ARCHIVE = "archive"
        const val TASK_FCM = "fcm"
        const val REASON_PROBE = "probe"
        const val REASON_INACCESSIBLE = "inaccessible"
        const val REASON_SESSION = "session"
    }
}
