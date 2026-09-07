package com.difft.android.app

import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L

/**
 * Process-wide uncaught-exception filter installed ahead of Crashlytics' handler.
 *
 * Suppresses crashes triggered by Hook frameworks on abnormal devices (rooted emulators,
 * automation tools) so they don't pollute Firebase Crashlytics metrics, and keeps the process
 * alive across one class of GMS-internal failure that no app code can catch (case 4).
 *
 * Handled crashes:
 * 1. [android.util.SuperNotCalledException] — Hook frameworks intercept Activity.onCreate()
 *    without calling through to the original implementation.
 * 2. UCropMultipleActivity "Missing required parameters" — Hook frameworks on virtual devices
 *    (ladroid/redroid emulators) launch UCropMultipleActivity directly via Intent without
 *    the required CropTotalDataSource parameter. Normal users cannot trigger this because
 *    ImageFileCropEngine and PictureCommonFragment already validate parameters before
 *    launching UCrop (see PR #363). The library throws in onCreate() → initCropFragments()
 *    before any ActivityLifecycleCallbacks can intercept it, so UncaughtExceptionHandler
 *    is the only viable interception point.
 * 3. FinalizerWatchdogDaemon timeouts — daemon-thread misfires after OEM background-freeze.
 * 4. Main-thread SecurityException parcelled back from a broken GMS install and re-thrown inside
 *    play-services' own Handler — the main Looper is resumed instead of killing the process
 *    (see [isGmsClientSecurityException] / [resumeMainLooper]).
 *
 * Crashlytics initializes via ContentProvider (before Application.onCreate()),
 * so calling [install] in Application.onCreate() guarantees our handler wraps theirs.
 */
object CrashFilter {

    fun install() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (thread === Looper.getMainLooper().thread && isGmsClientSecurityException(throwable)) {
                resumeMainLooper(throwable, previousHandler)
            } else {
                filterOrForward(thread, throwable, previousHandler)
            }
        }
    }

    /** The suppress-or-forward chain; shared by the installed handler and the resumed main loop. */
    private fun filterOrForward(thread: Thread, throwable: Throwable, previousHandler: Thread.UncaughtExceptionHandler?) {
        if (throwable.javaClass.name == "android.util.SuperNotCalledException") {
            L.w { "[CrashFilter] Suppressed SuperNotCalledException: ${throwable.message}" }
            android.os.Process.killProcess(android.os.Process.myPid())
        } else if (isUCropMissingParametersCrash(throwable)) {
            L.w { "[CrashFilter] Suppressed UCrop missing parameters crash from abnormal device" }
            android.os.Process.killProcess(android.os.Process.myPid())
        } else if (isFinalizerWatchdogTimeout(thread, throwable)) {
            // Daemon-thread timeout, not a main-thread crash: swallow it. The watchdog thread
            // dies but the process keeps running, so don't report and don't kill the process.
            L.w { "[CrashFilter] Suppressed FinalizerWatchdogDaemon timeout: ${throwable.message}" }
        } else {
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * A GMS-side failure during a GmsClient binder call (e.g. a sideloaded GMS whose gservices
     * provider is missing: `SecurityException: Failed to find provider
     * com.google.android.gsf.gservices`) is parcelled back and re-thrown inside play-services'
     * own main-thread Handler. No app frame is on the stack, so no call site can catch it, and
     * Analytics cannot be kept from binding GMS at runtime (its init-time connect ignores
     * `setAnalyticsCollectionEnabled(false)`). Crashlytics issue 8d782ca9.
     *
     * Three-layer match: the type, a Binder origin (`android.os.Parcel.readException` family on
     * top, i.e. the remote side threw, not in-process permission enforcement), and a GmsClient
     * frame below it with no app frame anywhere.
     */
    @VisibleForTesting
    internal fun isGmsClientSecurityException(throwable: Throwable): Boolean {
        if (throwable !is SecurityException) return false
        val frames = throwable.stackTrace
        if (frames.firstOrNull()?.className != "android.os.Parcel") return false
        if (frames.any { it.className.startsWith("com.difft.") || it.className.startsWith("org.difft.") }) return false
        return frames.any { it.className.startsWith(GMS_CLIENT_PACKAGE) }
    }

    /**
     * Re-enter the main Looper instead of letting the process die. Runs on the main thread inside
     * the uncaught-exception dispatch, so `Looper.loop()` simply resumes draining the queue; the
     * aborted GMS message is dropped and the SDK falls back to a retry. Repeat occurrences are
     * caught here (same stack depth, no nesting); anything else, or too many repeats, goes through
     * the regular filter chain and then to the previous handler, which reports and kills the
     * process as before. A main thread that survives that chain has no looper left, so kill.
     */
    private fun resumeMainLooper(first: Throwable, previousHandler: Thread.UncaughtExceptionHandler?) {
        var resumed = 0
        var current = first
        while (true) {
            resumed++
            L.e { "[CrashFilter] Resumed main looper after GMS client SecurityException #$resumed: ${current.message}" }
            try {
                Looper.loop()
                return
            } catch (t: Throwable) {
                if (resumed < MAX_MAIN_LOOPER_RESUMES && isGmsClientSecurityException(t)) {
                    current = t
                    continue
                }
                filterOrForward(Thread.currentThread(), t, previousHandler)
                L.e { "[CrashFilter] Main thread not terminated by handler chain; killing process: ${t.stackTraceToString()}" }
                android.os.Process.killProcess(android.os.Process.myPid())
                return
            }
        }
    }

    private const val GMS_CLIENT_PACKAGE = "com.google.android.gms.common.internal."
    private const val MAX_MAIN_LOOPER_RESUMES = 50

    /**
     * Matches the exact crash: Hook framework launches UCropMultipleActivity directly without
     * the required CropTotalDataSource parameter, causing `initCropFragments()` to throw.
     *
     * Three-layer matching to avoid false positives:
     * 1. Cause type: `IllegalArgumentException`
     * 2. Cause message: exact match of UCrop library's error string
     * 3. Cause stacktrace: must originate from `UCropMultipleActivity.initCropFragments`
     *
     * Note: `UCrop.of()` throws the same message but from a different call site — the stacktrace
     * check distinguishes the two. Our code already guards `UCrop.of()` with null checks (PR #363),
     * so that path cannot reach here under normal usage.
     */
    private fun isUCropMissingParametersCrash(throwable: Throwable): Boolean {
        // IllegalArgumentException may be the top-level throwable or wrapped as cause
        // (Android framework wraps Activity.onCreate() exceptions in RuntimeException)
        val cause = when {
            throwable is IllegalArgumentException -> throwable
            throwable.cause is IllegalArgumentException -> throwable.cause as IllegalArgumentException
            else -> return false
        }
        if (cause.message != "Missing required parameters, count cannot be less than 1") return false
        return cause.stackTrace.any {
            it.className == "com.yalantis.ucrop.UCropMultipleActivity" &&
                it.methodName == "initCropFragments"
        }
    }

    /**
     * Android's FinalizerWatchdogDaemon throws TimeoutException when a finalize() takes >10s.
     * Triggered by OEM background-freeze: wall-clock keeps advancing while the frozen process
     * can't schedule the finalizer thread, so the watchdog misfires on resume. The blamed object
     * (e.g. WCDB winq Expression, which only releases native memory via finalize() — no close API)
     * is just the queue head, not the real cause. Normal foreground devices never hit this.
     * Equivalent to disabling the watchdog, but without hidden-API reflection (blocked on API 28+).
     */
    private fun isFinalizerWatchdogTimeout(thread: Thread, throwable: Throwable): Boolean {
        if (thread.name != "FinalizerWatchdogDaemon") return false
        if (throwable !is java.util.concurrent.TimeoutException) return false
        return throwable.message?.contains("finalize() timed out") == true
    }
}
