package com.difft.android.base.utils

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.os.Build
import com.difft.android.base.log.lumberjack.L
import java.util.concurrent.ConcurrentHashMap

/**
 * Safe wrapper around [Service.startForeground] that absorbs the API 31+
 * BG-FGS-not-allowed throw at the **server-side** entry point.
 *
 * ## Why this exists
 *
 * Android 12 (API 31) added a process-level `mAllowStartForeground` gate that
 * Service.startForeground() consults via binder transaction inside
 * `IActivityManager.setServiceForeground` ->
 * `ActiveServices#setServiceForegroundInnerLocked`. When the gate is closed
 * (process in BACKGROUND with no active FGS-start exemption window), the
 * call throws [ForegroundServiceStartNotAllowedException] with the message
 * `Service.startForeground() not allowed due to mAllowStartForeground false`.
 *
 * Two-layer client-side protection that this codebase already has does **not**
 * cover this throw:
 *
 *  1. `com.difft.android.chat.util.ForegroundServiceUtil.start` wraps
 *     `Context.startForegroundService` — the **client** throw site. Already
 *     catches FGSNAE and converts to `UnableToStartException` for retry via
 *     `blockUntilCapable`. Port of Signal-Android's
 *     `org/thoughtcrime/securesms/jobs/ForegroundServiceUtil.kt:78-86, 137-173`.
 *  2. `com.difft.android.chat.messages.MessageServiceManager.doStartService`
 *     adds an outer `try/catch (Exception)` + `appScope.launch { startWhenCapable }`
 *     fallback around the client call.
 *
 * Neither covers `Service.startForeground` at `Service.java:776`, because that
 * throw originates inside `ActivityThread.handleCreateService` on the binder
 * dispatch — the exception travels up the ServiceThread, not back through
 * the original caller's stack. The result is `RuntimeException: Unable to
 * create service ...` -> process FATAL kill.
 *
 * ## Why we keep the existing Signal pattern
 *
 * Signal-Android itself does NOT wrap `Service.startForeground` (verified in
 * `IncomingMessageObserver.ForegroundService.postForegroundNotification` —
 * calls `startForeground(FOREGROUND_ID, notification)` raw, no try/catch;
 * `onCreate` + `onStartCommand` both invoke it; returns `START_STICKY`).
 * Signal accepts the FATAL kill at the OS boundary because (a) the gate is
 * extremely narrow in practice, (b) `START_STICKY` + AlarmManager recovery
 * handles re-creation, and (c) blanket catching erodes the ability to spot
 * a real `NotificationChannel missing` bug.
 *
 * TempTalk deviates from Signal here by adding a *narrow* catch — only
 * FGSNAE -> `stopSelf()` — for two reasons specific to our cohort:
 *
 *  - HyperOS / EMUI BG kill rate + GMS-unavailable cohort sees more frequent
 *    sticky-recreate-in-BG events than Signal's Pixel-skewed user base.
 *  - The frequency is enough to generate user-visible Crashlytics noise
 *    even though the OS-mediated recreate loop is self-healing within
 *    ~5 minutes via AlarmManager.
 *
 * The catch is narrow on purpose: any [IllegalStateException] that is *not*
 * FGSNAE is re-thrown so it surfaces as FATAL (NotificationChannel "BACKGROUND"
 * missing, ServiceCompat mis-config, etc. — these are real bugs and must
 * fail fast).
 *
 * ## Return value contract
 *
 *  - `true`  -> `startForeground` succeeded. Caller may set its own "is running"
 *               flag, launch worker coroutines, return `START_STICKY`, etc.
 *  - `false` -> OS refused (FGSNAE caught). The helper has **already called
 *               `service.stopSelf()`** — caller MUST NOT continue with FG-only
 *               work (e.g. setting `isForegroundStarted = true`, launching
 *               WebSocket). Returning early with `START_NOT_STICKY` is the
 *               safest follow-up to avoid a sticky-recreate loop on the
 *               same OS-state.
 *  - Throws  -> A non-FGSNAE [IllegalStateException] surfaces. Treat as a
 *               real bug. Do NOT catch it at the call site.
 *
 * ## Caller MAY ignore the return value — under what conditions
 *
 *  - `MessageForegroundService.postForegroundNotification()` does not read
 *    the return value (matches the existing Signal-pattern flow); the helper
 *    has already called `stopSelf()`, so `onCreate` will set `isRunning=true`
 *    briefly (<100ms typical, bounded by Main Looper dispatch latency), then
 *    `onDestroy` will reset it to `false`. The `isRunning` flag is only read
 *    by `MessageServiceManager.checkAndRecover` on a 5-minute cadence, so
 *    the window has no observable effect.
 *
 *    **This "MAY ignore" is SAFE if-and-only-if a specific external invariant
 *    holds:** `MessageServiceManager.checkAndRecover` calls `doStartService`
 *    **unconditionally** w.r.t. the `wasRunning` check (verified at
 *    `MessageServiceManager.kt:145-158` — the `wasRunning` check only
 *    influences log level at lines 149-153, `doStartService()` at line 158
 *    runs regardless). If a future change (e.g. Tier 2 — currently
 *    cancelled but not deleted as a possibility) adds a guard like
 *    `if (!wasRunning || foregrounded) doStartService()`, then the helper's
 *    "set isRunning=true briefly, then back to false" sequence ceases to be
 *    safe: `checkAndRecover` could observe `isRunning==true` during the
 *    transient window and skip the recovery. **In that future, the caller
 *    MUST check the helper return value and skip the `isRunning = true`
 *    assignment on `false`.** This is a Tier-1-only safety invariant.
 *
 *  - `call/ForegroundService` reads the return value because it has its own
 *    `isForegroundStarted` state machine that must reflect actual FG state
 *    (see `updateServiceType` for the state-collapse fix).
 *
 * @param service               the Service instance; must be on its main
 *                              thread (Android requirement for `startForeground`)
 * @param id                    notification id; must be > 0 (Android requirement)
 * @param notification          the Notification to display; must use a
 *                              channel that has been created
 * @param foregroundServiceType bitmask of `ServiceInfo.FOREGROUND_SERVICE_TYPE_*`;
 *                              0 = use the 2-arg overload (no explicit type).
 *                              Non-0 requires API 29+ (`Q`) for the 3-arg
 *                              overload; API 34+ enforces type-context match.
 * @return                      true if `startForeground` succeeded, false if
 *                              FGSNAE was caught and `stopSelf()` was called.
 */
object ForegroundServiceStarter {

    /**
     * Per-service-class first-occurrence guard for the full stacktrace log.
     * On HyperOS / GMS-unavailable cohorts the alarm fires every 5 min in
     * steady-state BG, so without throttling each user generates ~288
     * `L.w "startForeground refused"` lines per day with full stacktrace.
     * Threshold check in `.claude/rules/logging-standards.md` puts that
     * over the noise budget. We keep the FIRST occurrence with full trace
     * (still needed for offline forensics) and downgrade subsequent
     * occurrences to a one-line `L.i` reference.
     *
     * Keyed by `service.javaClass.simpleName` (e.g. `MessageForegroundService`,
     * `ForegroundService`). `ConcurrentHashMap.newKeySet()` chosen because
     * `add` is atomic and returns `true` only on first insertion.
     *
     * **F-R3-L2 — subclass `simpleName` collision risk:** `call/ForegroundService`
     * is declared `open class`. If a future subclass shares the same
     * `simpleName` (rare — Kotlin / Java compute `simpleName` from the
     * declared name and the JVM disallows two classes with the same
     * fully-qualified name in one classloader; collisions can only happen
     * across modules or across class loaders), the throttle key would
     * be shared and the FIRST-OCCURRENCE trace would be suppressed on
     * the second class. **If a future subclass is introduced, upgrade
     * this key to the fully-qualified name** (`service.javaClass.name`)
     * to disambiguate. No action needed today — `MessageForegroundService`
     * is `final` and `call/ForegroundService` has no subclasses.
     */
    private val fullStackLoggedOnce: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @JvmStatic
    @JvmOverloads
    fun startForegroundSafely(
        service: Service,
        id: Int,
        notification: Notification,
        foregroundServiceType: Int = 0,
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && foregroundServiceType != 0) {
                service.startForeground(id, notification, foregroundServiceType)
            } else {
                service.startForeground(id, notification)
            }
            true
        } catch (e: IllegalStateException) {
            // FGSNAE is API 31+ only. `is` check on older API levels would fail
            // class-load — guard with SDK_INT first so the class reference is
            // never resolved on pre-31 runtimes.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                val key = service.javaClass.simpleName
                if (fullStackLoggedOnce.add(key)) {
                    L.w {
                        "[$key] startForeground refused " +
                                "state=BG cause=FgsNotAllowed mAllowStartForeground=false " +
                                "action=stopSelf ${e.stackTraceToString()}"
                    }
                } else {
                    // L.i still writes to the local log file per logging-standards
                    // (INFO+ goes through FileLoggingTree); we just drop the full
                    // stacktrace to keep noise bounded. First-trace already captured.
                    L.i {
                        "[$key] startForeground refused (repeated, " +
                                "see first-occurrence trace) cause=FgsNotAllowed action=stopSelf"
                    }
                }
                service.stopSelf()
                false
            } else {
                // Any other IllegalStateException (NotificationChannel missing,
                // ServiceCompat mis-config, etc.) is a real bug — fail fast so
                // Crashlytics gets the signature.
                throw e
            }
        }
    }
}
