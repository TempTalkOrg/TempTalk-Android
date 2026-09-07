package com.difft.android.app

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.IndexActivity
import com.difft.android.MainActivity
import com.difft.android.base.BuildConfig
import com.difft.android.base.application.ScopeApplication
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.log.LogHelper
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.network.NetworkRiskNotifier
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.storage.PendingLastUseTime
import com.difft.android.base.storage.StoragePreloader
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.storage.user.StorageBoundUserManagerImpl
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.AppStartup
import com.difft.android.base.utils.dbKeyFailSoftExceptionHandler
import org.difft.app.database.DbHealth
import org.difft.app.database.probeHealthy
import org.difft.app.database.wcdb
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.GmsHealth
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
class TempTalkApplication : ScopeApplication(), CoroutineScope by MainScope().plus(CoroutineName("TempTalkApplication")).plus(dbKeyFailSoftExceptionHandler), AppForegroundObserver.Listener, androidx.work.Configuration.Provider {
    // On-demand WorkManager init: WorkManagerInitializer is disabled in the manifest, so this lets
    // WorkManager self-initialize lazily if a still-merged component (e.g. SystemForegroundService)
    // calls getInstance(), instead of crashing. Never invoked on the normal startup path.
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

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

    @Inject
    lateinit var conversationSettingsManager: dagger.Lazy<com.difft.android.chat.setting.ConversationSettingsManager>

    @Inject
    lateinit var processExitProbe: com.difft.android.base.monitor.ProcessExitProbe

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
            .addBlocking("init tls provider", this::initTlsProvider)
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
            .addBlocking("installCrashFilter") { CrashFilter.install() }
            .addNonBlocking("reapply locale") {
                // Refresh the Application's Configuration with the user locale so legacy
                // callers that read `application.resources` directly see the right locale.
                LanguageUtils.getLanguage(this@TempTalkApplication)
                LanguageUtils.reapplyLocaleToAppResources(this@TempTalkApplication)
            }
            .addNonBlocking("cleanup legacy sqlcipher", this::cleanupLegacySqlCipherArtifacts)
            .addNonBlocking("sweep avatar crop temp", this::sweepAvatarCropTemp)
            // Probe DB health early (off main, individually guarded) so DB-touching consumers
            // below can fast-skip a corrupt DB via wcdb.dbCorrupted. Best-effort ordering, NOT a
            // barrier — consumer-side safety (runCatching in ContactRemarkCache.preload, the
            // soft-fail catches in job storage) is what guarantees correctness.
            .addNonBlocking("probe db health") {
                // #971: verify synchronous=NORMAL reached the write handle right after the health
                // probe — same off-main startup task, only when the probe reports healthy (skips a
                // known-corrupt DB). Soft-fails internally; never blocks startup.
                if (wcdb.probeHealthy() == DbHealth.HEALTHY) {
                    wcdb.verifySynchronousApplied()
                }
            }
            .addNonBlocking("sweep stale sending messages", this::sweepStaleSendingMessages)
            .addNonBlocking("begin job loop") { ApplicationDependencies.getJobManager().beginJobLoop() }
            .addNonBlocking("init call engine") { initCallEngine() }
            .addNonBlocking("cleanup stale call notification") { cleanupStaleCallNotification() }
            // Warm the per-process verdict off the main thread so later main-thread readers
            // (SmsRetrieverHelper on the verify-code screen) hit the cache, not PackageManager.
            .addNonBlocking("warm GmsHealth") { GmsHealth.isGmsBroken(this) }
            .addNonBlocking("monitor main thread blocking") { monitorMainThreadBlocking() }
            .addNonBlocking("init contactor") { ContactorUtil.init() }
            .addNonBlocking("init global configs") { initGlobalConfigs() }
            .addNonBlocking("observe network risk") { observeNetworkRiskWarning() }
            .addNonBlocking("init coordinator") { coordinator.get().initialize() }
            // Already on Dispatchers.IO per AppStartup; single runBlocking bridge, no re-dispatch.
            .addNonBlocking("probe process exit reasons") {
                @Suppress("BanRunBlockingOutsideTests")
                runBlocking { processExitProbe.probe() }
            }
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

    private fun sweepAvatarCropTemp() {
        com.difft.android.app.startup.sweepAvatarCropTemp(applicationContext)
    }

    /**
     * Bridge the background-thread MITM signal ([NetworkRiskNotifier]) to the foreground UI.
     * When a warning is raised while an Activity is resumed we show it immediately here;
     * if none is resumed yet, [onActivityResumed] retries once the app returns to foreground.
     */
    private fun observeNetworkRiskWarning() {
        appScope.launch {
            NetworkRiskNotifier.warningPending.collect { pending ->
                if (!pending) return@collect
                withContext(Dispatchers.Main) {
                    currentResumedActivity?.get()?.let { showNetworkRiskWarningIfNeeded(it) }
                }
            }
        }
    }

    /** Must run on the main thread. Shows the MITM warning once, reusing the shared dialog. */
    private fun showNetworkRiskWarningIfNeeded(activity: FragmentActivity) {
        if (!NetworkRiskNotifier.warningPending.value || NetworkRiskNotifier.isDialogShowing) return
        if (activity.isFinishing || activity.isDestroyed) return

        NetworkRiskNotifier.markDialogShown()
        // If the host Activity is destroyed before the user decides (e.g. a config change tears
        // down the ComposeView), re-arm so the next foreground Activity re-shows the warning.
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                owner.lifecycle.removeObserver(this)
                NetworkRiskNotifier.onDialogDismissed()
            }
        })
        ComposeDialogManager.showMessageDialog(
            context = activity,
            title = activity.getString(com.difft.android.base.R.string.net_risk_warning_title),
            message = activity.getString(com.difft.android.base.R.string.net_risk_warning_message),
            // "Quit" is the highlighted primary action (safer default than ignoring the risk).
            confirmText = activity.getString(com.difft.android.base.R.string.net_risk_warning_quit),
            cancelText = activity.getString(com.difft.android.base.R.string.net_risk_warning_ignore),
            showCancel = true,
            cancelable = false,
            confirmButtonColor = Color(ContextCompat.getColor(activity, com.difft.android.base.R.color.primary)),
            onConfirm = { quitApp(activity) },
            onCancel = { NetworkRiskNotifier.ignoreForSession() },
            onDismiss = { NetworkRiskNotifier.onDialogDismissed() }
        )
    }

    private fun quitApp(activity: Activity) {
        NetworkRiskNotifier.onDialogDismissed()
        try {
            activity.finishAffinity()
        } catch (e: Exception) {
            L.w { "[NetworkRisk] finishAffinity failed: ${e.message}" }
        }
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
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

    /**
     * On Android 10 / API 29 and below, the platform (APEX) Conscrypt creates
     * raw-fd TLS sockets (`*FileDescriptorSocket`). Layered over the self-hosted
     * proxy's outer TLS tunnel (TLS-in-TLS), they write the inner ClientHello on
     * the underlying RAW fd, bypassing the outer encryption — the proxy resets it
     * ("Broken pipe"). API 30+ already defaults to the stream-based engine socket.
     *
     * Registering the bundled Conscrypt as the top JSSE provider and enabling
     * engine sockets by default makes every default-provider TLS consumer (OkHttp,
     * HttpsURLConnection, and crucially LiveKit's internally-built signaling
     * SSLSocketFactory, which app code cannot inject) use the stream-based socket,
     * so the proxy tunnel works. Scoped to API < 30; API 30+ is left untouched.
     *
     * Best-effort and fail-safe: if Conscrypt fails to load (e.g. unsupported ABI)
     * we keep the platform default — proxy on that device simply stays broken,
     * which is no worse than before this change.
     */
    private fun initTlsProvider() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
        runCatching {
            // Build the bundled provider first so a failure (e.g. UnsatisfiedLinkError
            // on an unsupported ABI) can never leave us having already removed an
            // existing provider. removeProvider only targets a previously inserted
            // bundled "Conscrypt"; the platform provider registers as "AndroidOpenSSL".
            // insertProviderAt returns -1 (no exception) on a name clash, so we verify
            // the actual slot instead of assuming success.
            val bundledProvider = org.conscrypt.Conscrypt.newProvider()
            java.security.Security.removeProvider("Conscrypt")
            val position = java.security.Security.insertProviderAt(bundledProvider, 1)
            org.conscrypt.Conscrypt.setUseEngineSocketByDefault(true)
            if (position == 1) {
                L.i { "[Proxy][tls] bundled Conscrypt registered as top JSSE provider (API ${Build.VERSION.SDK_INT}), engine-socket default ON" }
            } else {
                L.w { "[Proxy][tls] bundled Conscrypt insertProviderAt returned $position (expected 1), API ${Build.VERSION.SDK_INT}" }
            }
        }.onFailure {
            L.w(it) { "[Proxy][tls] failed to register bundled Conscrypt provider: ${it.javaClass.simpleName}: ${it.message}" }
        }
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
                // NOTE: incoming-call restore is intentionally NOT triggered here. On a cold start this
                // fires too early — the launcher's IndexActivity + the app lock come up afterwards and
                // bury the ringing screen. Restore now runs at the end of scheduleQuickScreenLockCheck,
                // i.e. AFTER the app-lock decision, so the call reliably ends up above the lock.
                LCallManager.onAppForegroundedForCallServiceUrls()
                globalConfigsManager.get().onAppStateChanged(isForeground = true)
                messageArchiveManager.get().onAppStateChanged(isForeground = true)
                coordinator.get().startPeriodicTest(isForeground = true)
                // Full conversation-config refetch on foreground (covers background drift); throttled internally.
                conversationSettingsManager.get().syncConversationSettings()
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
        // Genuine app foreground entry (cold start / return from background): after the app-lock
        // decision, (re)surface any active incoming call above the lock. Other callers of
        // scheduleQuickScreenLockCheck (call-end re-lock, in-call re-check) must NOT restore, or a
        // just-rejected call whose notifying flag hasn't cleared yet would be relaunched.
        scheduleQuickScreenLockCheck(restoreIncomingCallAfterCheck = true)
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

                    // 网络层在后台线程检测到证书失败时只置位标志；待前台 Activity 可用再弹窗
                    showNetworkRiskWarningIfNeeded(activity)
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

                // Clear the temporary bypass flag whenever a call/incoming-call screen LEAVES the foreground
                // (stopped), not only when it finishes. wakeUpDevice sets this flag to true while showing the
                // call screen in the foreground; it is only meaningful while that screen is actually visible.
                // If we cleared it just on isFinishing, minimizing the call (swipe up to home / launcher —
                // a stop that is NOT finishing) would leave the flag true, and returning via the launcher icon
                // would short-circuit shouldShowScreenLock at the "temporarily disabled" check (before the
                // isInForeground branch) and skip the app lock, briefly exposing the main UI. Clearing on any
                // stop also covers the finishing case (last Activity → count reaches zero below).
                //
                // Important: do NOT proactively re-apply the app lock here. The lock state during a call is
                // decided by the back stack:
                //  · Locked before the call (cold start / already locked): the app lock already sits beneath
                //    the call screen and is revealed automatically once the call finishes.
                //  · Already unlocked in the foreground before the call: leaving returns to the previous
                //    screen, and we must never conjure a lock out of nowhere (otherwise rejecting an incoming
                //    call while unlocked in the foreground would wrongly pop the app lock).
                if (activity is LIncomingCallActivity || activity is LCallActivity) {
                    ScreenLockUtil.temporarilyDisabled = false
                }

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
     * Single-pass lock check for the notification-gate path. Unlike [triggerScreenLockCheck]'s full
     * 1100ms second pass, this won't re-show the lock right after a fast unlock+replay (which for
     * lock-immediately users would force a second unlock). The bounded retry inside
     * showScreenLockIfNeeded still covers a not-yet-resumed activity.
     */
    fun triggerScreenLockCheckOnce() {
        scheduleQuickScreenLockCheck()
    }

    /**
     * Check whether to show the screen lock on foreground/background transitions.
     * The deeplink flow re-triggers this check in handleDeeplink().
     */
    private fun scheduleQuickScreenLockCheck(restoreIncomingCallAfterCheck: Boolean = false) {
        // Cancel the previous check
        lockCheckJob?.cancel()

        lockCheckJob = launch(Dispatchers.Main) {
            delay(100)
            L.d { "[ScreenLock] Quick check after 100ms" }
            showScreenLockIfNeeded()

            // Reset the temporary bypass flag once the check is done
            ScreenLockUtil.temporarilyDisabled = false

            // Only on a genuine foreground entry, after the lock decision is done, bring the incoming
            // call screen to the front so it stays above the app lock (telephony-style). Launching it
            // too early in onForeground on a cold start would get it buried by IndexActivity + the app
            // lock; doing it here (lock already up) reliably keeps the call screen on top. No-op when
            // there is no active incoming call.
            // Note: other callers (call-end re-lock, in-call re-check) must NOT restore, otherwise a
            // just-rejected call whose notifying flag hasn't cleared yet would be relaunched (showing
            // "app lock + another incoming-call screen").
            if (restoreIncomingCallAfterCheck) {
                LCallManager.restoreIncomingCallScreenIfActive()
            }
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

    /**
     * Popup gate for [MainActivity.processIntent]. The positional `is ScreenLockActivity` is an
     * early-out only (the lock screen is already paused at popup-decision time, so it false-negatives
     * in the bug scenario); shouldShowScreenLock is the authoritative check. recentlyUnlocked lets a
     * just-unlocked replay through.
     */
    fun isScreenLockRequiredOrShowing(): Boolean {
        if (ScreenLockUtil.recentlyUnlocked) return false
        if (currentResumedActivity?.get() is ScreenLockActivity) return true
        val userData = userManager.getUserData() ?: return false
        return shouldShowScreenLock(userData)
    }

    /**
     * Shows the app lock, rescheduling a bounded retry (3×150ms) when no resumed Activity is
     * available yet instead of giving up. Re-evaluates shouldShowScreenLock each hop and
     * self-terminates once backgrounded (startedActivityCount == 0), so it never outlives a
     * background transition.
     */
    private fun showScreenLockIfNeeded(retriesLeft: Int = 3) {
        val activity = currentResumedActivity?.get()

        // Never stack a second lock screen.
        if (activity is ScreenLockActivity) {
            L.d { "[ScreenLock] Already showing ScreenLockActivity" }
            return
        }

        val userData = userManager.getUserData()
        if (userData == null || !shouldShowScreenLock(userData)) {
            L.d { "[ScreenLock] Lock not needed" }
            return
        }

        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            L.i { "[ScreenLock] Starting ScreenLockActivity from ${activity::class.simpleName}" }
            ScreenLockActivity.startActivity(activity)
        } else if (retriesLeft > 0 && startedActivityCount > 0) {
            L.i { "[ScreenLock] No valid activity yet, retrying (retriesLeft=$retriesLeft)" }
            launch(Dispatchers.Main) {
                delay(150)
                showScreenLockIfNeeded(retriesLeft - 1)
            }
        } else {
            L.w { "[ScreenLock] Gave up starting ScreenLockActivity: retriesLeft=$retriesLeft, startedActivityCount=$startedActivityCount" }
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

        // Telephony-style: the incoming / ongoing call screen is always kept above the app lock
        // (visible and answerable without unlocking first), regardless of needAppLock. When the app
        // was locked before the call, ScreenLockActivity is shown underneath (by the foreground lock
        // check, before the call is restored on top) and is revealed again once the call finishes —
        // so the app content behind the call stays protected without any extra re-lock.
        //
        // Skip only while the ongoing call is actually full-screen in the foreground (isInForeground),
        // NOT merely while a call exists (isInCalling). When the call is minimized to the background or
        // to a PIP window and the user opens app content via the launcher, that content must still be
        // gated by the app lock ("must unlock before reaching app content"). A PIP call window keeps
        // floating above the lock by the system, so the call itself stays visible/answerable while the
        // app content behind it is protected.
        if (onGoingCallStateManager.get().isInForeground()) {
            L.d { "[ScreenLock] Skip: in call (foreground)" }
            return false
        }

        // Skip the app lock only while the incoming-call screen is actually visible in the foreground
        // (telephony-style: the call screen always stays above the lock and can be seen/answered
        // without unlocking first). This must use isInForeground, not isActivityShowing:
        //  · isActivityShowing means the Activity merely exists (between onCreate and onDestroy).
        //    Minimizing it (top-left back button / swipe up to home) only triggers onPause/onStop —
        //    the Activity is not destroyed, so isActivityShowing stays true.
        //  · Using it here would wrongly match this branch when the user re-opens the app from the
        //    launcher icon after minimizing the call, skipping the lock and exposing app content while
        //    a lock is configured.
        // isInForeground is set true in onResume and false in onPause, which exactly represents
        // whether the incoming-call screen is currently visible in the foreground.
        if (inComingCallStateManager.get().isInForeground()) {
            L.d { "[ScreenLock] Skip: incoming call (foreground)" }
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