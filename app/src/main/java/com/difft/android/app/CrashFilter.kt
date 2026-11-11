package com.difft.android.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L



































object CrashFilter {

    
    @Volatile
    @VisibleForTesting
    internal var baseOnlyInstall: Boolean = false

    






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
            
            
            L.w { "[CrashFilter] Suppressed FinalizerWatchdogDaemon timeout: ${throwable.message}" }
        } else {
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    










    @VisibleForTesting
    internal fun isGmsClientSecurityException(throwable: Throwable): Boolean {
        if (throwable !is SecurityException) return false
        val frames = throwable.stackTrace
        if (frames.firstOrNull()?.className != "android.os.Parcel") return false
        if (frames.any { it.className.startsWith("com.difft.") || it.className.startsWith("org.difft.") }) return false
        return frames.any { it.className.startsWith(GMS_CLIENT_PACKAGE) }
    }

    







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

    
    private const val SPLITS_REQUIRED_META_DATA = "com.android.vending.splits.required"
    private const val MAX_CAUSE_DEPTH = 8

    
    private const val API_MODEL_OUTLINE_MARKER = "\$\$ExternalSyntheticApiModelOutline"

    
    private val NO_SUCH_METHOD_MESSAGE =
        Regex("""No (?:static|virtual|direct|interface) method (\w+)\([^)]*\)\S* in class (L[^;]+;)""")

    
    private val SPOOFED_SDK_SIGNATURES = mapOf(
        "Landroid/view/WindowInsets\$Type;.systemOverlays" to Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        "Landroid/app/NotificationManager;.canUseFullScreenIntent" to Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
    )

    





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

    





    @VisibleForTesting
    internal fun isMissingNativeLibraryCrash(throwable: Throwable, baseOnlyInstall: Boolean = this.baseOnlyInstall): Boolean {
        if (!baseOnlyInstall) return false
        return generateSequence(throwable) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .any { it is UnsatisfiedLinkError && it.message?.contains("dlopen failed") == true && it.message?.contains("not found") == true }
    }

    




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

    












    private fun isUCropMissingParametersCrash(throwable: Throwable): Boolean {
        
        
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

    







    private fun isFinalizerWatchdogTimeout(thread: Thread, throwable: Throwable): Boolean {
        if (thread.name != "FinalizerWatchdogDaemon") return false
        if (throwable !is java.util.concurrent.TimeoutException) return false
        return throwable.message?.contains("finalize() timed out") == true
    }
}
