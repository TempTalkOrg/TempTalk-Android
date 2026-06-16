package com.difft.android.app

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.FragmentActivity
import com.difft.android.IndexActivity
import com.difft.android.MainActivity
import com.difft.android.base.BuildConfig
import com.difft.android.base.application.ScopeApplication
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.log.LogHelper
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.PendingLastUseTime
import com.difft.android.base.storage.StoragePreloader
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.storage.user.StorageBoundUserManagerImpl
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.AppStartup
import org.difft.app.database.wcdb
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallEngine
import com.difft.android.call.LCallManager
import com.difft.android.call.service.ForegroundService
import com.difft.android.call.LIncomingCallActivity
import com.difft.android.call.manager.CriticalAlertManager
import com.difft.android.call.state.CriticalAlertStateManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.state.InComingCallStateManager
import com.difft.android.base.utils.SecureModeUtil
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.login.PasscodeUtil
import com.difft.android.login.ScreenLockActivity
import com.difft.android.network.config.FeatureGrayManager
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.speedtest.DomainSpeedTestCoordinator
import com.difft.android.security.SecurityLib
import com.github.anrwatchdog.ANRWatchDog
import com.difft.android.base.utils.appScope
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.signal.libsignal.protocol.logging.SignalProtocolLogger
import org.signal.libsignal.protocol.logging.SignalProtocolLoggerProvider
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.dependencies.ApplicationDependencyProvider
import util.AppForegroundObserver
import com.difft.android.chat.util.MessageNotificationUtil
import util.ScreenLockUtil
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltAndroidApp
class TempTalkApplication : ScopeApplication(), CoroutineScope by MainScope().plus(CoroutineName("TempTalkApplication")), AppForegroundObserver.Listener {
    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var environmentHelper: EnvironmentHelper

    @Inject
    lateinit var storagePreloader: StoragePreloader

    @Inject
    lateinit var pendingLastUseTime: PendingLastUseTime

    // @field: is required so Hilt sees the qualifier on the Java field, not the Kotlin property.
    @Inject
    @field:AppStateDataStore
    lateinit var appStateDataStore: DataStore<Preferences>

    @Inject
    lateinit var globalConfigsManager: dagger.Lazy<GlobalConfigsManager>

    @Inject
    lateinit var messageNotificationUtil: dagger.Lazy<MessageNotificationUtil>

    @Inject
    lateinit var coordinator: dagger.Lazy<DomainSpeedTestCoordinator>

    @Inject
    lateinit var onGoingCallStateManager: dagger.Lazy<OnGoingCallStateManager>

    @Inject
    lateinit var inComingCallStateManager: dagger.Lazy<InComingCallStateManager>

    @Inject
    lateinit var criticalAlertManager: dagger.Lazy<CriticalAlertManager>

    @Inject
    lateinit var criticalAlertStateManager: dagger.Lazy<CriticalAlertStateManager>

    @Inject
    lateinit var messageArchiveManager: dagger.Lazy<com.difft.android.chat.setting.archive.MessageArchiveManager>

    // 追踪当前 resumed 的 Activity
    private var currentResumedActivity: WeakReference<FragmentActivity>? = null

    private var lockCheckJob: Job? = null

    // Activity 计数，用于准确判断前后台切换
    private var startedActivityCount = 0

    override fun onCreate() {
        AppStartup.onApplicationCreate()
        super.onCreate()

        AppStartup
            .addBlocking("init ApplicationHelper") {
                ApplicationHelper.init(this)
            }
            .addBlocking("init log", this::initLog)
            .addBlocking("init Logger", this::initializeLogging)
            .addBlocking("init SecurityCheck") {
                startTracerPidMonitor()
                checkDebuggerAndHook()
            }
            .addBlocking("init ApplicationDependencies") {
                ApplicationDependencies.init(this, ApplicationDependencyProvider(this))
                AppForegroundObserver.begin()
            }
            .addBlocking("init Storage", this::initStorageLayer)
            .addBlocking("init UserData", this::initUserData)
            .addBlocking("init theme", this::initAppTheme)
            .addBlocking("lifecycle-observer") {
                AppForegroundObserver.addListener(this)
            }
            .addBlocking("init notification", this::initNotification)
            .addBlocking("prepareScreenLockListener", this::prepareScreenLockListener)
            .addBlocking("installCrashFilter", this::installCrashFilter)
            .addNonBlocking("reapply locale") {
                // Refresh the Application's Configuration with the user locale so legacy
                // callers that read `application.resources` directly see the right locale.
                LanguageUtils.getLanguage(this@TempTalkApplication)
                LanguageUtils.reapplyLocaleToAppResources(this@TempTalkApplication)
            }
            .addNonBlocking("cleanup legacy sqlcipher", this::cleanupLegacySqlCipherArtifacts)
            // Probe DB health early (off main, individually guarded) so DB-touching consumers
            // below can fast-skip a corrupt DB via wcdb.dbCorrupted. Best-effort ordering, NOT a
            // barrier — consumer-side safety (runCatching in ContactRemarkCache.preload, the
            // soft-fail catches in job storage) is what guarantees correctness.
            .addNonBlocking("probe db health") { wcdb.probeHealthy() }
            .addNonBlocking("sweep stale sending messages", this::sweepStaleSendingMessages)
            .addNonBlocking("begin job loop") { ApplicationDependencies.getJobManager().beginJobLoop() }
            .addNonBlocking("init call engine") { initCallEngine() }
            .addNonBlocking("cleanup stale call notification") { cleanupStaleCallNotification() }
            .addNonBlocking("monitor main thread blocking") { monitorMainThreadBlocking() }
            .addNonBlocking("init contactor") { ContactorUtil.init() }
            .addNonBlocking("init global configs") { initGlobalConfigs() }
            .addNonBlocking("init coordinator") { coordinator.get().initialize() }
            .execute()

        L.i { "[AppStartup] application onCreate() took " + (System.currentTimeMillis() - AppStartup.getApplicationStartTime()) + " ms" }
    }

    override fun onTerminate() {
        cancel()
        super.onTerminate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Cover the case where the system reclaims memory before onActivityStopped fires.
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            appScope.launch {
                pendingLastUseTime.flush(appStateDataStore)
            }
        }
    }

    override fun attachBaseContext(context: Context) {
        super.attachBaseContext(LanguageUtils.createConfiguredContext(context))
    }

    private fun initializeLogging() {
        SignalProtocolLoggerProvider.setProvider { priority, tag, message ->
            when (priority) {
                SignalProtocolLogger.VERBOSE, SignalProtocolLogger.DEBUG -> L.d { "[$tag] $message" }
                SignalProtocolLogger.INFO -> L.i { "[$tag] $message" }
                SignalProtocolLogger.WARN -> L.w { "[$tag] $message" }
                SignalProtocolLogger.ERROR, SignalProtocolLogger.ASSERT -> L.e { "[$tag] $message" }
            }
        }
    }

    private fun cleanupLegacySqlCipherArtifacts() {
        com.difft.android.app.startup.cleanupLegacySqlCipherArtifacts(applicationContext)
    }

    /**
     * Flip stale `message.sendType == Sending` rows to `SentFailed` via
     * [com.difft.android.app.startup.sweepStaleSendingMessages].
     *
     * Runs off the main thread via `addNonBlocking` — the narrow race against a
     * user-initiated fresh Sending message is recoverable: the misflagged row
     * gains a visible retry button and a user-initiated resend goes through the
     * normal PushTextSendJob path.
     */
    private fun sweepStaleSendingMessages() {
        com.difft.android.app.startup.sweepStaleSendingMessages(this)
    }

    /**
     * Warms up the three DataStores (`secure_user`, `secure_config`, `app_state`) so
     * subsequent reads hit memory. Also primes the [PendingLastUseTime] holder so the
     * very first screen-lock check reads the persisted value. Bounded at 2 s.
     */
    private fun initStorageLayer() {
        val job = async(Dispatchers.IO) {
            storagePreloader.preload()
            pendingLastUseTime.loadInitial(appStateDataStore)
        }
        // Startup-only 2s bounded block; design承重墙 per #722. Without warm caches
        // here, downstream `userManager.getUserData()` etc. would all need
        // dispatcher wrappers at dozens of call sites.
        @Suppress("BanRunBlockingOutsideTests")
        val ok = runBlocking { withTimeoutOrNull(2000) { job.await() } }
        if (ok == null) L.w { "[Startup] initStorageLayer timed out at 2s — first-use may hit cold cache" }
    }

    /**
     * Composes the in-memory [UserData] snapshot from `secure_user.pb` + `app_state.preferences_pb`
     * via [StorageBoundUserManagerImpl.warmUp]. Falls back to [UserManager.getUserData] lazy-load
     * if the impl type doesn't match (should not happen in production). Bounded at 2 s.
     */
    private fun initUserData() {
        val job = async(Dispatchers.IO) {
            (userManager as? StorageBoundUserManagerImpl)?.warmUp()
                ?: userManager.getUserData()
        }
        // Startup-only 2s bounded block; design承重墙 per #722.
        @Suppress("BanRunBlockingOutsideTests")
        val ok = runBlocking { withTimeoutOrNull(2000) { job.await() } }
        if (ok == null) L.w { "[Startup] initUserData timed out at 2s — snapshot may be empty until next access" }
    }

    private fun initAppTheme() {
        val theme = userManager.getUserData()?.theme
        L.i { "[TempTalkApplication] loadUserTheme theme: $theme" }
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                AppCompatDelegate.MODE_NIGHT_YES -> AppCompatDelegate.MODE_NIGHT_YES
                AppCompatDelegate.MODE_NIGHT_NO -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun initLog() {
        LogHelper.init(this)
    }

    private fun initNotification() {
        messageNotificationUtil.get().checkAndCreateNotificationChannels()
    }

    private fun initGlobalConfigs() {
        globalConfigsManager.get().getAndSaveGlobalConfigs(this)
    }

    // Serializes onForeground/onBackground bodies so the IO-pool dispatch order matches
    // the upstream main-thread invocation order from AppForegroundObserver.
    private val fgBgMutex = Mutex()

    // Body runs on appScope (IO). Do not touch UI directly here; wrap in withContext(Main).
    // Deferred from main to avoid first-time Hilt Lazy<>.get() resolution + L.i flush blocking
    // ANRWatchDog (issue 433594181bc9db4301347d9a9da209c6).
    override fun onForeground() {
        appScope.launch {
            fgBgMutex.withLock {
                recordLastUseTime()
                scheduleGrayConfigUpdateCheck()
                LCallManager.restoreIncomingCallScreenIfActive()
                LCallManager.onAppForegroundedForCallServiceUrls()
                globalConfigsManager.get().onAppStateChanged(isForeground = true)
                messageArchiveManager.get().onAppStateChanged(isForeground = true)
                coordinator.get().startPeriodicTest(isForeground = true)
            }
        }
    }

    // Mirror of onForeground: body runs on appScope (IO). Same constraints apply.
    override fun onBackground() {
        appScope.launch {
            fgBgMutex.withLock {
                recordLastUseTimeJob?.cancel()
                globalConfigsManager.get().onAppStateChanged(isForeground = false)
                messageArchiveManager.get().onAppStateChanged(isForeground = false)
                coordinator.get().startPeriodicTest(isForeground = false)
            }
        }
    }

    /**
     * 通过 Activity 计数判断的真实前台事件（仅用于锁屏检查）
     * 因为AppForegroundObserver在快速前后台切换时不会触发
     */
    private fun onAppForeground() {
        L.d { "[ScreenLock] onForeground called" }
        scheduleQuickScreenLockCheck()
    }

    /**
     * 通过 Activity 计数判断的真实后台事件（仅用于锁屏检查）
     */
    private fun onAppBackground() {
        L.d { "[ScreenLock] onBackground called" }
        // 取消待处理的锁屏检查
        lockCheckJob?.cancel()
        lockCheckJob = null
    }

    private fun prepareScreenLockListener() {
        this.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            }

            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                AppForegroundObserver.notifyActivityStarted()
                L.d { "[ScreenLock] onActivityStarted: ${activity::class.simpleName}, count=$startedActivityCount" }

                // 从后台进入前台：计数从 0 变为 1
                if (startedActivityCount == 1) {
                    L.d { "[ScreenLock] App entered foreground" }
                    onAppForeground()
                } else if (shouldCheckScreenLockForCall())
                {
                    scheduleQuickScreenLockCheck()
                }

                // 进入会话列表时停止critical alert
                if (activity is IndexActivity && criticalAlertManager.get().isCriticalAlertRunning() && !criticalAlertStateManager.get().isJoining()) {
                    LCallManager.dismissCriticalAlertIfActive()
                }
            }

            override fun onActivityResumed(activity: Activity) {
                L.d { "[ScreenLock] onActivityResumed: ${activity::class.simpleName}" }

                // 维护当前 Activity 引用
                if (activity is FragmentActivity) {
                    currentResumedActivity = WeakReference(activity)

                    // 刷新截屏状态（根据屏幕锁决定）
                    SecureModeUtil.refreshByScreenLock(activity)
                }

                // 原有的 Call 反馈逻辑
                if (activity !is LCallActivity && activity !is MainActivity && activity !is LIncomingCallActivity) {
                    launch(Dispatchers.IO) {
                        val callInfo = LCallManager.getAndClearCallFeedbackInfo()
                        if (callInfo != null && !activity.isDestroyed) {
                            withContext(Dispatchers.Main) {
                                LCallManager.showCallFeedbackView(activity, callInfo)
                            }
                        }
                    }
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentResumedActivity?.get() == activity) {
                    currentResumedActivity = null
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                AppForegroundObserver.notifyActivityStopped()
                L.d { "[ScreenLock] onActivityStopped: ${activity::class.simpleName}, count=$startedActivityCount" }

                // 从前台进入后台：计数从 1 变为 0
                if (startedActivityCount == 0) {
                    onAppBackground()
                    appScope.launch {
                        pendingLastUseTime.flush(appStateDataStore)
                    }
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            }

            override fun onActivityDestroyed(activity: Activity) {
            }

        })
    }


    @Volatile
    private var recordLastUseTimeJob: kotlinx.coroutines.Job? = null

    private fun recordLastUseTime() {
        recordLastUseTimeJob?.cancel()
        recordLastUseTimeJob = appScope.launch {
            delay(1_000)
            while (true) {
                try {
                    if (PasscodeUtil.needRecordLastUseTime) {
                        pendingLastUseTime.record(System.currentTimeMillis())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w { "[TempTalkApplication] recordLastUseTime error: ${e.stackTraceToString()}" }
                }
                delay(10_000)
            }
        }
    }

    /**
     * 触发完整的锁屏检查（用于 deeplink 场景）
     * 包含两次检查：100ms 快速检查 + 1100ms 等待目标页面启动
     */
    fun triggerScreenLockCheck() {
        L.d { "[ScreenLock] Trigger full screen lock check (for deeplink)" }
        scheduleFullScreenLockCheck()
    }

    /**
     * 前后台切换时检查是否显示锁屏
     *  Deeplink 场景会在 handleDeeplink() 中再次触发检查
     */
    private fun scheduleQuickScreenLockCheck() {
        // 取消之前的检查
        lockCheckJob?.cancel()

        lockCheckJob = launch(Dispatchers.Main) {
            delay(100)
            L.d { "[ScreenLock] Quick check after 100ms" }
            showScreenLockIfNeeded()

            // 检查完成后重置临时豁免标志
            ScreenLockUtil.temporarilyDisabled = false
        }
    }

    /**
     * 完整的锁屏检查（用于 deeplink 场景）
     * 两次检查：100ms + 1100ms
     */
    private fun scheduleFullScreenLockCheck() {
        // 取消之前的检查
        lockCheckJob?.cancel()

        lockCheckJob = launch(Dispatchers.Main) {
            // 第一次检查
            delay(100)
            L.d { "[ScreenLock] First check after 100ms" }
            showScreenLockIfNeeded()

            // 第二次检查：等待 deeplink 目标页面启动
            delay(1000)
            L.d { "[ScreenLock] Second check after 1100ms" }
            showScreenLockIfNeeded()

            // 检查完成后重置临时豁免标志
            ScreenLockUtil.temporarilyDisabled = false
        }
    }

    private fun showScreenLockIfNeeded() {
        val userData = userManager.getUserData()
        val activity = currentResumedActivity?.get()

        // 如果当前就是锁屏页，不需要重复启动
        if (activity is ScreenLockActivity) {
            L.d { "[ScreenLock] Already showing ScreenLockActivity" }
            return
        }

        if (userData != null && shouldShowScreenLock(userData)) {
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                L.i { "[ScreenLock] Starting ScreenLockActivity from ${activity::class.simpleName}" }
                ScreenLockActivity.startActivity(activity)
            } else {
                L.w { "[ScreenLock] No valid activity to start ScreenLockActivity" }
            }
        } else {
            L.d { "[ScreenLock] Lock not needed" }
        }
    }

    private fun shouldShowScreenLock(userData: UserData): Boolean {
        // 1. 通用的临时豁免
        if (ScreenLockUtil.temporarilyDisabled) {
            L.d { "[ScreenLock] Skip: temporarily disabled" }
            return false
        }

        // 2. 通话相关
        if (criticalAlertStateManager.get().isShowing()) {
            L.d { "[ScreenLock] Skip: critical alert" }
            return false
        }

        if (onGoingCallStateManager.get().isInCalling() && !onGoingCallStateManager.get().needAppLock) {
            L.d { "[ScreenLock] Skip: in call" }
            return false
        }

        if (inComingCallStateManager.get().isActivityShowing() && !inComingCallStateManager.get().isNeedAppLock()) {
            L.d { "[ScreenLock] Skip: incoming call" }
            return false
        }

        // 3. 用户配置检查
        if (userData.passcode.isNullOrEmpty() && userData.pattern.isNullOrEmpty()) {
            L.d { "[ScreenLock] Skip: no lock set" }
            return false
        }

        if (userData.baseAuth.isNullOrEmpty()) {
            L.d { "[ScreenLock] Skip: not authenticated" }
            return false
        }

        // 4. 超时检查
        val lastUseTime = pendingLastUseTime.current()
        val isTimeout = userData.passcodeTimeout == 0 ||
                System.currentTimeMillis() - lastUseTime >= userData.passcodeTimeout.seconds.inWholeMilliseconds

        if (!isTimeout) {
            L.d { "[ScreenLock] Skip: not timeout yet" }
        }

        return isTimeout
    }

    /**
     * Intercept crashes triggered by Hook frameworks on abnormal devices (rooted emulators,
     * automation tools).
     *
     * Filtered crashes:
     * 1. [android.util.SuperNotCalledException] — Hook frameworks intercept Activity.onCreate()
     *    without calling through to the original implementation.
     * 2. UCropMultipleActivity "Missing required parameters" — Hook frameworks on virtual devices
     *    (ladroid/redroid emulators) launch UCropMultipleActivity directly via Intent without
     *    the required CropTotalDataSource parameter. Normal users cannot trigger this because
     *    ImageFileCropEngine and PictureCommonFragment already validate parameters before
     *    launching UCrop (see PR #363). The library throws in onCreate() → initCropFragments()
     *    before any ActivityLifecycleCallbacks can intercept it, so UncaughtExceptionHandler
     *    is the only viable interception point.
     */
    private fun installCrashFilter() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (throwable.javaClass.name == "android.util.SuperNotCalledException") {
                L.w { "[CrashFilter] Suppressed SuperNotCalledException: ${throwable.message}" }
                android.os.Process.killProcess(android.os.Process.myPid())
            } else if (isUCropMissingParametersCrash(throwable)) {
                L.w { "[CrashFilter] Suppressed UCrop missing parameters crash from abnormal device" }
                android.os.Process.killProcess(android.os.Process.myPid())
            } else {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
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

    private fun initCallEngine() {
        LCallEngine.init(this, this, environmentHelper)
    }

    /**
     * On a fresh process start (after native crash or force-stop), no call can be active,
     * but the system may not have cleaned up the foreground notification from the previous
     * process. Cancel it proactively to avoid a ghost "call in progress" notification.
     */
    private fun cleanupStaleCallNotification() {
        if (onGoingCallStateManager.get().isInCalling()) return
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ForegroundService.DEFAULT_NOTIFICATION_ID)
        } catch (e: Exception) {
            L.w { "[AppStartup] Failed to cleanup stale call notification: ${e.message}" }
        }
    }

    private fun monitorMainThreadBlocking() {
        // 700ms aligns with Google's "frozen frame" threshold in Android Vitals.
        val threshold = 700

        ANRWatchDog(threshold)
            .setIgnoreDebugger(true)
            .setANRListener { anrError ->
                L.w { "ANR(${threshold}ms) detected: ${anrError.stackTraceToString()}" }
            }
            .setReportMainThreadOnly()
            .start()
    }

    private fun scheduleGrayConfigUpdateCheck() {
        val userData = userManager.getUserData() ?: return
        launch(Dispatchers.IO) {
            FeatureGrayManager.checkUpdateConfigFromServer(userData.lastUseTime)
        }
    }

    private fun shouldCheckScreenLockForCall(): Boolean {
        val incomingCallNeedsLock = inComingCallStateManager.get().isActivityShowing() && inComingCallStateManager.get().isNeedAppLock()
        val activeCallNeedsLock = onGoingCallStateManager.get().isInCalling() && onGoingCallStateManager.get().needAppLock
        return incomingCallNeedsLock || activeCallNeedsLock
    }

    private fun checkDebuggerAndHook() {
        if (BuildConfig.DEBUG) return
        val timestamp = System.currentTimeMillis()
        val hasDebugger = SecurityLib.checkDebuggerConnected()
        val hasHook = SecurityLib.checkHookFramework()
        val consumeTime = System.currentTimeMillis() - timestamp
        L.i { "[security] security check result: consumeTime=$consumeTime, hasDebugger=$hasDebugger, hasHook=$hasHook" }
        if (hasDebugger || hasHook) {
            SecurityLib.terminateAppProcess()
        }
    }

    private fun startTracerPidMonitor() {
        if (BuildConfig.DEBUG) return
        SecurityLib.startTracerPidMonitor()
    }
}