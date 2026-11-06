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

/**
 * Foreground catch-up for a warm process (#1395). A process woken in the background never ran
 * IndexActivity, so the archive loop may not be running and the FCM binding may never have been
 * retried; this re-runs both on every foreground. [MessageArchiveManager.startCheckTask] starts
 * the loop at most once per process and [PushUtil.resyncBind] applies its own cooldown.
 *
 * The gate runs first, because on a cold start the foreground callback fires as soon as
 * MainActivity starts, before its database-health routing and local-token check: the database
 * must have passed this process's health probe and be accessible, and the stored session must
 * pass the same two checks MainActivity applies ([LocalTokenVerifier.isLocalTokenValid] and
 * [needsIdentityKeyRelogin]). A gate that fails skips everything and changes no state. When the
 * probe has not finished yet the gate waits for it, bounded, then checks once more.
 *
 * Runs outside the Application's foreground/background mutex, so a slow task never delays
 * `onBackground`.
 */
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

    // One task's failure never skips the next; cancellation still propagates.
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
