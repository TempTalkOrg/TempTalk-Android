package com.difft.android.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
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
 * 5. `UnsatisfiedLinkError` (possibly wrapped in `NoClassDefFoundError`) on a Play App Bundle
 *    base APK installed without any of its split APKs — a third-party APK download sideloaded
 *    into an emulator. No native library exists in such an install, so whichever library is
 *    touched first fails and the app can never work; the process is stopped without a report
 *    (see [isMissingNativeLibraryCrash] / [isBaseOnlyInstall]).
 * 6. `NoSuchMethodError` for a listed framework method on a device whose `SDK_INT` says the method
 *    must exist — the ROM misreports its version (device spoofing tools). The process is stopped
 *    without a report; any other missing method keeps reporting (see [isSpoofedSdkFrameworkMiss]).
 *
 * Crashlytics initializes via ContentProvider (before Application.onCreate()),
 * so calling [install] in Application.onCreate() guarantees our handler wraps theirs.
 */
object CrashFilter {

    /** Evaluated once in [install]; read by the handler, which must not call into PackageManager. */
    @Volatile
    @VisibleForTesting
    internal var baseOnlyInstall: Boolean = false

    /**
     * Runs on the main thread inside the blocking startup chain on purpose: [baseOnlyInstall]
     * must be settled before the handler is registered, because the crashes it gates come from
     * arbitrary threads right after startup and an off-main warm-up (the `GmsHealth` pattern)
     * would race the first native-library touch. The cost is one `getApplicationInfo` binder
     * call, well under the 100 ms blocking-task budget.
     */
    fun install(context: Context) {
        baseOnlyInstall = runCatching { isBaseOnlyInstall(context) }
            .onFailure { L.w { "[CrashFilter] base-only install check failed: ${it.message}" } }
            .getOrDefault(false)
        if (baseOnlyInstall) L.w { "[CrashFilter] Base APK installed without its split APKs; native libraries are missing" }
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
        } else if (isMissingNativeLibraryCrash(throwable)) {
            L.w { "[CrashFilter] Suppressed missing native library crash on base-only install: ${throwable.message}" }
            android.os.Process.killProcess(android.os.Process.myPid())
        } else if (isSpoofedSdkFrameworkMiss(throwable)) {
            L.w { "[CrashFilter] Suppressed framework API miss on a ROM that misreports its SDK level: ${throwable.message}" }
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

    /** Injected by bundletool / Play into the base APK manifest of every bundle that has split APKs. */
    private const val SPLITS_REQUIRED_META_DATA = "com.android.vending.splits.required"
    private const val MAX_CAUSE_DEPTH = 8

    /** D8 routes calls to framework APIs newer than minSdk through synthetic classes with this name. */
    private const val API_MODEL_OUTLINE_MARKER = "\$\$ExternalSyntheticApiModelOutline"

    /** ART's message: `No static method name(args)ret in class Lowner; or its super classes ...`. */
    private val NO_SUCH_METHOD_MESSAGE =
        Regex("""No (?:static|virtual|direct|interface) method (\w+)\([^)]*\)\S* in class (L[^;]+;)""")

    /** `owner.method` → API level that introduced it (Crashlytics 41c8b4ad / 85d9ee38). Exact entries only. */
    private val SPOOFED_SDK_SIGNATURES = mapOf(
        "Landroid/view/WindowInsets\$Type;.systemOverlays" to Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        "Landroid/app/NotificationManager;.canUseFullScreenIntent" to Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
    )

    /**
     * True when the base APK declares that split APKs are required but none is installed. Only a
     * Play-built base APK carries the meta-data, so universal APKs (official / F-Droid channels)
     * and any install with its splits present return false — a genuinely missing library in a
     * complete install is a packaging bug and must keep reporting.
     */
    @VisibleForTesting
    internal fun isBaseOnlyInstall(context: Context): Boolean {
        val info = applicationInfoWithMetaData(context)
        val splitsRequired = info.metaData?.getBoolean(SPLITS_REQUIRED_META_DATA, false) == true
        return splitsRequired && info.splitSourceDirs.isNullOrEmpty()
    }

    private fun applicationInfoWithMetaData(context: Context): ApplicationInfo {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(
                context.packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        }
    }

    /**
     * Matches `dlopen failed: library "libX.so" not found` anywhere in the cause chain (ART wraps
     * the second and later hits of a failed `<clinit>` in `NoClassDefFoundError`), regardless of
     * which library was touched first. Gated on [baseOnlyInstall] so a real packaging defect is
     * never masked.
     */
    @VisibleForTesting
    internal fun isMissingNativeLibraryCrash(throwable: Throwable, baseOnlyInstall: Boolean = this.baseOnlyInstall): Boolean {
        if (!baseOnlyInstall) return false
        return generateSequence(throwable) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .any { it is UnsatisfiedLinkError && it.message?.contains("dlopen failed") == true && it.message?.contains("not found") == true }
    }

    /**
     * A listed method missing although [sdkInt] is at or above the level that introduced it. The
     * `SDK_INT` gate matters: an unguarded call on an older device misses the same method through
     * the same outline, and that is a real defect that must keep reporting.
     */
    @VisibleForTesting
    internal fun isSpoofedSdkFrameworkMiss(throwable: Throwable, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        val error = generateSequence(throwable) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .firstOrNull { it is NoSuchMethodError } ?: return false
        if (error.stackTrace.firstOrNull()?.className?.contains(API_MODEL_OUTLINE_MARKER) != true) return false
        val (method, owner) = NO_SUCH_METHOD_MESSAGE.find(error.message.orEmpty())?.destructured ?: return false
        val introducedIn = SPOOFED_SDK_SIGNATURES["$owner.$method"] ?: return false
        return sdkInt >= introducedIn
    }

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
