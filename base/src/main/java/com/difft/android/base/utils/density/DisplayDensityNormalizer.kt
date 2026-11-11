package com.difft.android.base.utils.density

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import androidx.annotation.VisibleForTesting
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs















object DisplayDensityNormalizer {

    
    enum class Mode { OFF, ON }

    
    data class Attach(val managed: Boolean, val overrideDpi: Int, val systemDensityDpi: Int)

    private const val PREF_NAME = "display_density_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_DISPLAY_SIZE = "display_size"

    
    private const val RECREATE_THRESHOLD = 0.02f

    
    private const val RECREATE_GUARD_MS = 3_000L

    
    private const val SAME_PANEL_ASPECT_TOLERANCE = 0.01f

    @Volatile
    private var cachedMode: Mode? = null

    @Volatile
    private var cachedSize: DisplaySize? = null
    private val eligibility = ConcurrentHashMap<Class<*>, Boolean>()

    
    private val managedActivities: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())
    private var lastRecreateKey: String? = null
    private var lastRecreateAt = 0L
    private var lastLoggedDecision: String? = null

    @VisibleForTesting
    internal fun resetForTest() {
        cachedMode = null
        cachedSize = null
        eligibility.clear()
        lastRecreateKey = null
        lastRecreateAt = 0L
        lastLoggedDecision = null
    }

    @VisibleForTesting
    internal var prefsProvider: (Context) -> SharedPreferences = { it.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }

    fun mode(context: Context): Mode {
        cachedMode?.let { return it }
        val stored = prefsProvider(context).getString(KEY_MODE, null)
        return (Mode.entries.firstOrNull { it.name == stored } ?: Mode.ON).also { cachedMode = it }
    }

    
    fun setMode(context: Context, mode: Mode) {
        prefsProvider(context).edit().putString(KEY_MODE, mode.name).commit()
        cachedMode = mode
    }

    
    fun storedDisplaySize(context: Context): DisplaySize? {
        cachedSize?.let { return it }
        val stored = prefsProvider(context).getString(KEY_DISPLAY_SIZE, null)
        return DisplaySize.entries.firstOrNull { it.name == stored }?.also { cachedSize = it }
    }

    fun displaySize(context: Context): DisplaySize = storedDisplaySize(context) ?: DisplaySize.STANDARD

    
    fun setDisplaySize(context: Context, size: DisplaySize) {
        prefsProvider(context).edit().putString(KEY_DISPLAY_SIZE, size.name).commit()
        cachedSize = size
    }

    fun registerManaged(activity: Activity) {
        managedActivities.add(activity)
    }

    fun unregisterManaged(activity: Activity) {
        managedActivities.remove(activity)
    }

    





    fun recreateManagedActivities(): List<Activity> {
        val live = managedActivities.filter { !it.isFinishing && !it.isDestroyed }
        L.i { "[Density] display size changed size=${cachedSize?.name?.lowercase()} recreate=${live.size}" }
        live.forEach { ActivityCompat.recreate(it) }
        return live
    }

    



    private fun resolveDisplaySize(context: Context, inputs: DensityNormalization.Inputs): DisplaySize {
        storedDisplaySize(context)?.let { return it }
        val ratio = DensityNormalization.decide(inputs).systemRatio
        val size = DisplaySize.closestTo(ratio)
        prefsProvider(context).edit().putString(KEY_DISPLAY_SIZE, size.name).apply()
        cachedSize = size
        L.i { "[Density] display size initialized size=${size.name.lowercase()} systemRatio=${String.format(Locale.US, "%.2f", ratio)}" }
        return size
    }

    
    fun attach(newBase: Context, activityClass: Class<*>): Attach {
        val systemDpi = newBase.resources.configuration.densityDpi
        val unmanaged = Attach(managed = false, overrideDpi = Configuration.DENSITY_DPI_UNDEFINED, systemDensityDpi = systemDpi)
        return try {
            if (mode(newBase) == Mode.OFF) return unmanaged
            if (!isEligible(newBase, activityClass)) {
                logOnce("cls=${activityClass.simpleName} reason=ineligible")
                return unmanaged
            }
            val display = hostDisplay(newBase, ContextCompat.getDisplayOrDefault(newBase).displayId)
            if (display == null || !isNormalizableDisplay(newBase, display)) {
                
                logOnce("cls=${activityClass.simpleName} display=${display?.displayId} reason=display")
                return unmanaged.copy(managed = true)
            }
            val inputs = readInputs(display, systemDpi, newBase.resources.configuration)
            val size = resolveDisplaySize(newBase, inputs)
            val decision = DensityNormalization.decide(inputs, size.scale)
            log(activityClass, display.displayId, inputs, decision, size)
            val override = if (decision.densityDpi != systemDpi) decision.densityDpi else Configuration.DENSITY_DPI_UNDEFINED
            Attach(managed = true, overrideDpi = override, systemDensityDpi = systemDpi)
        } catch (e: Exception) {
            L.w { "[Density] attach failed cls=${activityClass.simpleName} error=${e.javaClass.simpleName}" }
            unmanaged
        }
    }

    










    fun shouldRecreate(activity: Activity, displayId: Int, platformDensityDpi: Int, createdAt: Long): Boolean {
        return try {
            if (mode(activity) == Mode.OFF) return false
            val display = hostDisplay(activity, displayId) ?: return false
            val rendered = activity.resources.configuration.densityDpi
            val target: Int
            val cap: Int
            if (isNormalizableDisplay(activity, display)) {
                val inputs = readInputs(display, platformDensityDpi, activity.resources.configuration)
                target = DensityNormalization.decide(inputs, displaySize(activity).scale).densityDpi
                cap = DensityNormalization.enlargementCap(inputs)
            } else {
                target = platformDensityDpi
                cap = platformDensityDpi
            }
            if (!recreateNeeded(rendered, target, cap)) return false
            val key = "${activity.javaClass.name}:$target"
            val now = SystemClock.elapsedRealtime()
            if (isRecreateLoop(key, now, createdAt)) {
                L.w { "[Density] recreate suppressed cls=${activity.javaClass.simpleName} dpi=$rendered target=$target" }
                return false
            }
            lastRecreateKey = key
            lastRecreateAt = now
            L.i { "[Density] recreate cls=${activity.javaClass.simpleName} display=$displayId dpi=$rendered target=$target" }
            true
        } catch (e: Exception) {
            L.w { "[Density] reconcile failed cls=${activity.javaClass.simpleName} error=${e.javaClass.simpleName}" }
            false
        }
    }

    @VisibleForTesting
    internal fun isRecreateLoop(key: String, now: Long, createdAt: Long): Boolean =
        key == lastRecreateKey && now - lastRecreateAt < RECREATE_GUARD_MS && createdAt >= lastRecreateAt

    @VisibleForTesting
    internal fun markRecreateForTest(key: String, at: Long) {
        lastRecreateKey = key
        lastRecreateAt = at
    }

    @VisibleForTesting
    internal fun recreateNeeded(rendered: Int, target: Int, cap: Int): Boolean =
        rendered > cap || abs(target - rendered).toFloat() / rendered > RECREATE_THRESHOLD

    




    private fun hostDisplay(context: Context, displayId: Int): Display? {
        val app = context.applicationContext ?: context
        return app.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
    }

    @Suppress("DEPRECATION") 
    
    private fun readInputs(display: Display, densityDpi: Int, window: Configuration): DensityNormalization.Inputs {
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val mode = display.mode
        return DensityNormalization.Inputs(
            densityDpi = densityDpi,
            stableDensityDpi = DisplayMetrics.DENSITY_DEVICE_STABLE,
            xdpi = metrics.xdpi,
            ydpi = metrics.ydpi,
            modeWidthPx = mode.physicalWidth,
            modeHeightPx = mode.physicalHeight,
            maxModeLongPx = maxSamePanelModeLong(display, mode),
            realWidthPx = metrics.widthPixels,
            realHeightPx = metrics.heightPixels,
            windowWidthDp = window.screenWidthDp.takeIf { it != Configuration.SCREEN_WIDTH_DP_UNDEFINED } ?: 0,
            windowHeightDp = window.screenHeightDp.takeIf { it != Configuration.SCREEN_HEIGHT_DP_UNDEFINED } ?: 0,
        )
    }

    
    private fun maxSamePanelModeLong(display: Display, current: Display.Mode): Int {
        val long = maxOf(current.physicalWidth, current.physicalHeight)
        val short = minOf(current.physicalWidth, current.physicalHeight)
        if (short <= 0) return long
        val aspect = long.toFloat() / short
        return display.supportedModes
            .filter { mode ->
                val l = maxOf(mode.physicalWidth, mode.physicalHeight)
                val sh = minOf(mode.physicalWidth, mode.physicalHeight)
                sh > 0 && abs(l.toFloat() / sh - aspect) / aspect < SAME_PANEL_ASPECT_TOLERANCE
            }
            .maxOfOrNull { maxOf(it.physicalWidth, it.physicalHeight) }
            ?.coerceAtLeast(long) ?: long
    }

    
    private fun isNormalizableDisplay(context: Context, display: Display): Boolean {
        if (display.displayId != Display.DEFAULT_DISPLAY) return false
        val pm = context.packageManager
        return !pm.hasSystemFeature(PackageManager.FEATURE_PC) && !pm.hasSystemFeature(ARC_FEATURE)
    }

    private fun isEligible(context: Context, activityClass: Class<*>): Boolean = eligibility.getOrPut(activityClass) {
        val component = ComponentName(context, activityClass)
        val info = try {
            
            
            @Suppress("DEPRECATION")
            context.packageManager.getActivityInfo(component, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return@getOrPut false
        }
        isEligibleConfigChanges(info.configChanges)
    }

    
    fun isEligibleConfigChanges(configChanges: Int): Boolean =
        configChanges and (ActivityInfo.CONFIG_DENSITY or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE) == 0

    private fun log(
        activityClass: Class<*>,
        displayId: Int,
        inputs: DensityNormalization.Inputs,
        decision: DensityNormalization.Decision,
        size: DisplaySize,
    ) {
        logOnce(
            "display=$displayId dpi=${inputs.densityDpi} newDpi=${decision.densityDpi} " +
                "standard=${decision.standardDpi} size=${size.name.lowercase()} " +
                "xdpi=${String.format(Locale.US, "%.1f", inputs.xdpi)} ydpi=${String.format(Locale.US, "%.1f", inputs.ydpi)} " +
                "mode=${inputs.modeWidthPx}x${inputs.modeHeightPx} maxModeLong=${inputs.maxModeLongPx} " +
                "stable=${inputs.stableDensityDpi} baseline=${if (decision.baselineIsStable) "stable" else "current"} " +
                "f=${decision.factor} reason=${decision.reason.name.lowercase()}",
            suffix = " cls=${activityClass.simpleName}",
        )
    }

    
    private fun logOnce(decision: String, suffix: String = "") {
        if (decision == lastLoggedDecision) return
        lastLoggedDecision = decision
        L.i { "[Density] decide $decision$suffix" }
    }

    private const val ARC_FEATURE = "org.chromium.arc"
}
