package com.difft.android.setting.notificationtest

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.fragment.app.Fragment
import com.difft.android.MainActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestOwner
import com.difft.android.base.notification.NotificationTestRepository
import com.difft.android.base.notification.NotificationTestRun
import com.difft.android.base.notification.validNotificationTestId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Validated internal navigation identity, separate from the ordinary scheme/link model. */
data class NotificationTestReturnRoute(val ownerId: String, val runId: String, val kind: NotificationTestKind) {
    fun applyTo(intent: Intent): Intent = intent.addCategory(CATEGORY)
        .putExtra(OWNER, ownerId).putExtra(RUN, runId).putExtra(KIND, kind.name)
        .setData(Uri.parse("temptalk-internal://notification-test/return/$ownerId/$runId/${kind.name}"))

    companion object {
        const val CATEGORY = "com.difft.android.category.NOTIFICATION_TEST_RETURN"
        private const val OWNER = "notification_test_owner"
        private const val RUN = "notification_test_run"
        private const val KIND = "notification_test_kind"

        fun of(run: NotificationTestRun) = NotificationTestReturnRoute(run.owner.ownerId, run.runId, run.kind)

        fun read(intent: Intent): NotificationTestReturnRoute? {
            if (!intent.hasCategory(CATEGORY)) return null
            val owner = intent.getStringExtra(OWNER)?.takeIf(::validNotificationTestId) ?: return null
            val run = intent.getStringExtra(RUN)?.takeIf(::validNotificationTestId) ?: return null
            val kind = NotificationTestKind.entries.firstOrNull { it.name == intent.getStringExtra(KIND) } ?: return null
            val route = NotificationTestReturnRoute(owner, run, kind)
            return route.takeIf { it.applyTo(Intent()).data == intent.data }
        }

        fun forward(from: Intent, to: Intent) {
            read(from)?.applyTo(to)
        }

        fun returnIntent(context: Context, run: NotificationTestRun): Intent {
            val intent = if (run.owner.host == NotificationTestOwner.Host.HOSTED) {
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                NotificationTestActivity.createIntent(context)
            }
            return of(run).applyTo(intent)
        }
    }
}

/** The navigator calls decide again inside its queue, using the then-current Me pages. */
@Singleton
class NotificationTestReturns @Inject constructor(
    private val poster: NotificationTestPoster,
    private val repository: NotificationTestRepository,
) {
    sealed interface Decision {
        data class Existing(val page: NotificationTestFragment) : Decision
        data object Idle : Decision
    }

    fun decide(route: NotificationTestReturnRoute, pages: List<Fragment>): Decision =
        pages.filterIsInstance<NotificationTestFragment>().firstOrNull { it.matchesReturn(route) }
            ?.let(Decision::Existing) ?: Decision.Idle

    /** Only this obsolete notification/ticket is removed; never cancels another run's kind id. */
    suspend fun discardStale(route: NotificationTestReturnRoute): Boolean = try {
        poster.cancel(route.runId, route.kind)
        if (route.kind == NotificationTestKind.CALL) repository.cancelCall(route.runId)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        L.w {
            "[NotificationTest] stale_cleanup_failed error=${e.javaClass.simpleName}"
        }
        false
    }
}
