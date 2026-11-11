package com.difft.android.app

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.difft.android.app.startup.LocalTokenVerifier
import com.difft.android.base.activity.AppUnlockListener
import com.difft.android.base.activity.NotificationPopupGate
import com.difft.android.network.auth.AuthTokenProvider
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.IndexActivity
import com.difft.android.MainActivity
import com.difft.android.base.BuildConfig
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.utils.pane.ConversationOpener
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.dualpane.ConversationRouter
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.log.LogHelper
import com.difft.android.base.migration.PeerApps
import com.difft.android.migration.v2.handoff.MigrationHandoffReconciler
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
import com.difft.android.chat.focus.settings.FocusGroupPickerActivity


@Suppress("LargeClass")
@HiltAndroidApp
class TempTalkApplication : ScopeApplication(), CoroutineScope by MainScope().plus(CoroutineName("TempTalkApplication")).plus(dbKeyFailSoftExceptionHandler), AppForegroundObserver.Listener, androidx.work.Configuration.Provider, ConversationOpener, NotificationPopupGate, AppUnlockListener {
    
    
    
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
    lateinit var warmProcessResync: dagger.Lazy<WarmProcessResync>

    @Inject
    lateinit var restoreColdStartRecovery: dagger.Lazy<com.difft.android.migration.v2.restore.RestoreColdStartRecovery>

    @Inject
    lateinit var authTokenProvider: dagger.Lazy<AuthTokenProvider>

    @Inject
    lateinit var conversationSettingsManager: dagger.Lazy<com.difft.android.chat.setting.ConversationSettingsManager>

    @Inject
    lateinit var groupFocusRepository: dagger.Lazy<com.difft.android.chat.focus.GroupFocusRepository>

    @Inject
    lateinit var translateModelStore: dagger.Lazy<com.difft.android.chat.translate.model.TranslateModelStore>

    @Inject
    lateinit var translateEngine: dagger.Lazy<com.difft.android.chat.translate.TranslateEngine>

    @Inject
    lateinit var processExitProbe: com.difft.android.base.monitor.ProcessExitProbe

    @Inject
    lateinit var inactiveDeviceDialogPresenter: dagger.Lazy<InactiveDeviceDialogPresenter>

    @Inject
    lateinit var focusInvalidDialogPresenter: dagger.Lazy<com.difft.android.chat.focus.invalid.FocusInvalidDialogPresenter>

    @Inject
    lateinit var focusGroupNameCache: dagger.Lazy<com.difft.android.chat.focus.invalid.FocusGroupNameCache>

    



    @Inject
    lateinit var migrationHandoffReconciler: dagger.Lazy<MigrationHandoffReconciler>

    
    @Inject
    lateinit var migrationMessagePause: com.difft.android.base.migration.pause.MigrationMessagePause

    
    @Inject
    lateinit var messagingPauseReceiveCoordinator: com.difft.android.chat.messages.MessagingPauseReceiveCoordinator

    
    @Inject
    lateinit var migrationPauseRecovery: dagger.Lazy<com.difft.android.migration.v2.pause.MigrationPauseRecovery>

    @Inject
    lateinit var migrationPauseAuthProbe: dagger.Lazy<com.difft.android.chat.websocket.MigrationPauseAuthProbe>

    
    @Inject
    lateinit var migrationPauseLoginScope: dagger.Lazy<com.difft.android.base.migration.pause.MigrationPauseLoginScope>

    
    @Inject
    lateinit var promptSessionTracker: com.difft.android.startup.prompt.PromptSessionTracker

    
    
    @Volatile
    private var currentResumedActivity: WeakReference<FragmentActivity>? = null

    



    fun hasResumedActivity(): Boolean = currentResumedActivity?.get() != null

    private var lockCheckJob: Job? = null

    private val screenLockPending = ScreenLockPending()

    
    
    private var lockCheckDeferred = false

    
    
    
    private var foregroundUnlocked = false

    
    private val liveIndexActivities = mutableSetOf<IndexActivity>()
    private val liveMainActivities = mutableSetOf<MainActivity>()
    private val liveScreenLockActivities = mutableSetOf<ScreenLockActivity>()
    private var restoreIncomingCallAfterDeferredCheck = false
    private var deferredRerunJob: Job? = null

    @VisibleForTesting
    internal var restoreIncomingCallScreen: () -> Unit = { LCallManager.restoreIncomingCallScreenIfActive() }

    
    val screenLockPendingState: kotlinx.coroutines.flow.StateFlow<Boolean> get() = screenLockPending.state

    fun isScreenLockPending(): Boolean = screenLockPending.state.value

    
    override fun openConversation(context: Context, target: ConversationTarget) =
        ConversationRouter.open(context, target)

    override fun closeConversation(conversationId: String) = ConversationRouter.close(conversationId)

    override fun opensInDetailPane(context: Context): Boolean =
        ConversationRouter.route(context) == ConversationRouter.Route.BRING_HOME

    
    private var startedActivityCount = 0

    override fun onCreate() {
        AppStartup.onApplicationCreate()
        super.onCreate()

        AppStartup
            .addBlocking("init ApplicationHelper") {
                ApplicationHelper.init(this)
            }
            .addBlocking("init log", this::initLog)
            
            .addBlocking("installCrashFilter") { CrashFilter.install(this) }
            
            
            .addBlocking("start migration pause load") { migrationMessagePause.startLoad() }
            .addBlocking("observe migration pause") { messagingPauseReceiveCoordinator.start() }
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
            
            
            
            
            
            .addBlocking("restore cold-start resume", this::resumeRestoreColdStart)
            .addBlocking("init theme", this::initAppTheme)
            .addBlocking("lifecycle-observer") {
                AppForegroundObserver.addListener(this)
            }
            .addBlocking("init notification", this::initNotification)
            .addBlocking("prepareScreenLockListener", this::prepareScreenLockListener)
            .addBlocking("startup prompt sessions") { registerActivityLifecycleCallbacks(promptSessionTracker) }
            .addNonBlocking("reapply locale") {
                
                
                LanguageUtils.getLanguage(this@TempTalkApplication)
                LanguageUtils.reapplyLocaleToAppResources(this@TempTalkApplication)
            }
            .addNonBlocking("cleanup legacy sqlcipher", this::cleanupLegacySqlCipherArtifacts)
            .addNonBlocking("sweep avatar crop temp", this::sweepAvatarCropTemp)
            
            
            
            
            .addNonBlocking("probe db health") {
                
                
                
                if (wcdb.probeHealthy() == DbHealth.HEALTHY) {
                    wcdb.verifySynchronousApplied()
                }
            }
            .addNonBlocking("sweep stale sending messages", this::sweepStaleSendingMessages)
            .addNonBlocking("begin job loop") { ApplicationDependencies.getJobManager().beginJobLoop() }
            .addNonBlocking("init call engine") { initCallEngine() }
            .addNonBlocking("cleanup stale call notification") { cleanupStaleCallNotification() }
            
            
            .addNonBlocking("warm GmsHealth") { GmsHealth.isGmsBroken(this) }
            .addNonBlocking("monitor main thread blocking") { monitorMainThreadBlocking() }
            .addNonBlocking("init contactor") { ContactorUtil.init() }
            .addNonBlocking("init global configs") { initGlobalConfigs() }
            .addNonBlocking("observe network risk") { observeNetworkRiskWarning() }
            .addNonBlocking("observe inactive device") {
                inactiveDeviceDialogPresenter.get().observe { currentResumedActivity?.get() }
            }
            
            .addNonBlocking("observe focus invalid") {
                focusInvalidDialogPresenter.get().observe { currentResumedActivity?.get()?.takeIf(::isFocusDialogHost) }
            }
            .addNonBlocking("init focus name cache") { focusGroupNameCache.get().start() }
            .addNonBlocking("init coordinator") { coordinator.get().initialize() }
            
            
            
            .addNonBlocking("migration handoff reconcile") {
                if (PeerApps.isLegacy(this)) {
                    @Suppress("BanRunBlockingOutsideTests")
                    runBlocking { migrationHandoffReconciler.get().onColdStart() }
                }
            }
            
            .addNonBlocking("migration pause recovery") {
                migrationPauseRecovery.get().start()
                migrationPauseLoginScope.get().watchCredentialChanges(appScope)
                migrationPauseAuthProbe.get().start()
            }
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
        
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            appScope.launch {
                pendingLastUseTime.flush(appStateDataStore)
            }
        }
        
        
        appScope.launch {
            translateEngine.get().onTrimMemory(level)
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

    
    private fun showNetworkRiskWarningIfNeeded(activity: FragmentActivity) {
        if (!NetworkRiskNotifier.warningPending.value || NetworkRiskNotifier.isDialogShowing) return
        if (activity.isFinishing || activity.isDestroyed) return

        NetworkRiskNotifier.markDialogShown()
        
        
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

    








    private fun sweepStaleSendingMessages() {
        com.difft.android.app.startup.sweepStaleSendingMessages(this)
    }

    




    private fun initStorageLayer() {
        val job = async(Dispatchers.IO) {
            storagePreloader.preload()
            pendingLastUseTime.loadInitial(appStateDataStore)
        }
        
        @Suppress("BanRunBlockingOutsideTests")
        val ok = runBlocking { withTimeoutOrNull(2000) { job.await() } }
        if (ok == null) L.w { "[Startup] initStorageLayer timed out at 2s — first-use may hit cold cache" }
    }

    




    private fun initUserData() {
        val job = async(Dispatchers.IO) {
            (userManager as? StorageBoundUserManagerImpl)?.warmUp()
                ?: userManager.getUserData()
        }
        
        @Suppress("BanRunBlockingOutsideTests")
        val ok = runBlocking { withTimeoutOrNull(2000) { job.await() } }
        if (ok == null) L.w { "[Startup] initUserData timed out at 2s — snapshot may be empty until next access" }
    }

    













    private fun resumeRestoreColdStart() {
        val started = SystemClock.elapsedRealtime()
        @Suppress("BanRunBlockingOutsideTests")
        val outcome = runCatching {
            runBlocking { restoreColdStartRecovery.get().resumeIfNeeded() }
        }.getOrElse { e ->
            
            L.e { "[Startup] restore cold-start resume error: ${e.stackTraceToString()}" }
            null
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        when (outcome) {
            null -> L.w { "[Startup] restore cold-start resume errored (launch continues)" }
            is com.difft.android.migration.v2.restore.RestoreColdStartRecovery.Outcome.Nothing ->
                Unit 
            is com.difft.android.migration.v2.restore.RestoreColdStartRecovery.Outcome.PromoteResumed ->
                L.i { "[Startup] restore cold-start: promote resumed → SIGNING_IN elapsedMs=$elapsed" }
            is com.difft.android.migration.v2.restore.RestoreColdStartRecovery.Outcome.AbortedToCleanState ->
                L.i { "[Startup] restore cold-start: aborted to clean net-install elapsedMs=$elapsed" }
            is com.difft.android.migration.v2.restore.RestoreColdStartRecovery.Outcome.RetryableFailure ->
                L.w {
                    "[Startup] restore cold-start: retryable failure code=${outcome.error::class.simpleName} " +
                        "elapsedMs=$elapsed (next launch retries)"
                }
        }
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

    














    private fun initTlsProvider() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
        runCatching {
            
            
            
            
            
            
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

    
    
    private val fgBgMutex = Mutex()

    
    
    override fun onForeground() {
        appScope.launch {
            fgBgMutex.withLock {
                recordLastUseTime()
                
                runCatching { messageNotificationUtil.get().refreshChannelNames() }
                scheduleGrayConfigUpdateCheck()
                
                util.TimeFormatter.refreshHourFormat()
                
                
                LCallManager.onAppForegroundedForCallServiceUrls()
                globalConfigsManager.get().onAppStateChanged(isForeground = true)
                messageArchiveManager.get().onAppStateChanged(isForeground = true)
                coordinator.get().startPeriodicTest(isForeground = true)
                
                conversationSettingsManager.get().syncConversationSettings()
                
                
                groupFocusRepository.get().apply {
                    recomputeLocally()
                    requestRefresh("foreground")
                }
                
                
                translateModelStore.get().onForeground()
                
                processExitProbe.reportHealthIfDue()
            }
        }
        
        
        appScope.launch { warmProcessResync.get().onForeground() }
    }

    
    override fun onBackground() {
        appScope.launch {
            fgBgMutex.withLock {
                recordLastUseTimeJob?.cancel()
                globalConfigsManager.get().onAppStateChanged(isForeground = false)
                messageArchiveManager.get().onAppStateChanged(isForeground = false)
                coordinator.get().startPeriodicTest(isForeground = false)
                translateModelStore.get().onBackground()
            }
        }
    }

    
    private fun onAppForeground() {
        L.d { "[ScreenLock] onForeground called" }
        
        
        
        
        scheduleQuickScreenLockCheck(restoreIncomingCallAfterCheck = true)
    }

    
    private fun onAppBackground() {
        L.d { "[ScreenLock] onBackground called" }
        foregroundUnlocked = false
        lockCheckJob?.cancel()
        lockCheckJob = null
        
        deferredRerunJob?.cancel()
        deferredRerunJob = null
        restoreIncomingCallAfterDeferredCheck = false
        screenLockPending.onDecided()
    }

    private fun prepareScreenLockListener() {
        this.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is IndexActivity) liveIndexActivities.add(activity)
                if (activity is MainActivity) liveMainActivities.add(activity)
                if (activity is ScreenLockActivity) liveScreenLockActivities.add(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                AppForegroundObserver.notifyActivityStarted()
                L.d { "[ScreenLock] onActivityStarted: ${activity::class.simpleName}, count=$startedActivityCount" }

                if (startedActivityCount == 1) {
                    L.d { "[ScreenLock] App entered foreground" }
                    onAppForeground()
                } else if (shouldCheckScreenLockForCall())
                {
                    scheduleQuickScreenLockCheck()
                }

                
                if (activity is IndexActivity && criticalAlertManager.get().shouldDismissWhenIndexStarts()) {
                    LCallManager.dismissCriticalAlertIfActive()
                }
            }

            override fun onActivityResumed(activity: Activity) {
                L.d { "[ScreenLock] onActivityResumed: ${activity::class.simpleName}" }
                AppForegroundObserver.notifyActivityResumed(activity)

                if (activity is FragmentActivity) {
                    currentResumedActivity = WeakReference(activity)

                    rerunDeferredLockCheck(activity)

                    SecureModeUtil.refreshByScreenLock(activity)

                    
                    showNetworkRiskWarningIfNeeded(activity)

                    
                    inactiveDeviceDialogPresenter.get().onActivityResumed(activity)

                    
                    if (isFocusDialogHost(activity)) focusInvalidDialogPresenter.get().onActivityResumed(activity)
                }

                
                
                if (activity is ComponentActivity && activity !is LCallActivity && activity !is MainActivity && activity !is LIncomingCallActivity) {
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
                AppForegroundObserver.notifyActivityStopped(activity)
                L.d { "[ScreenLock] onActivityStopped: ${activity::class.simpleName}, count=$startedActivityCount" }

                
                
                
                if (activity is LIncomingCallActivity || activity is LCallActivity) {
                    ScreenLockUtil.temporarilyDisabled = false
                }

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
                if (activity is IndexActivity) liveIndexActivities.remove(activity)
                if (activity is MainActivity) liveMainActivities.remove(activity)
                if (activity is ScreenLockActivity) liveScreenLockActivities.remove(activity)
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

    
    fun triggerScreenLockCheck() {
        L.d { "[ScreenLock] Trigger full screen lock check (for deeplink)" }
        scheduleFullScreenLockCheck()
    }

    





    fun triggerScreenLockCheckOnce() {
        scheduleQuickScreenLockCheck()
    }

    



    private fun scheduleQuickScreenLockCheck(restoreIncomingCallAfterCheck: Boolean = false) {
        
        lockCheckJob?.cancel()
        screenLockPending.onCheckScheduled()

        lockCheckJob = launch(Dispatchers.Main) {
            delay(100)
            L.d { "[ScreenLock] Quick check after 100ms" }
            showScreenLockIfNeeded()

            
            ScreenLockUtil.temporarilyDisabled = false

            
            
            if (restoreIncomingCallAfterCheck) {
                if (lockCheckDeferred) {
                    restoreIncomingCallAfterDeferredCheck = true
                } else {
                    LCallManager.restoreIncomingCallScreenIfActive()
                }
            }
        }
    }

    
    private fun scheduleFullScreenLockCheck() {
        lockCheckJob?.cancel()
        screenLockPending.onCheckScheduled()

        lockCheckJob = launch(Dispatchers.Main) {
            delay(100)
            L.d { "[ScreenLock] First check after 100ms" }
            showScreenLockIfNeeded()
            
            screenLockPending.onCheckScheduled()

            
            delay(1000)
            L.d { "[ScreenLock] Second check after 1100ms" }
            showScreenLockIfNeeded()

            ScreenLockUtil.temporarilyDisabled = false
        }
    }

    



    private fun isFocusDialogHost(activity: Activity): Boolean =
        activity !is ScreenLockActivity &&
            activity !is LIncomingCallActivity &&
            activity !is LCallActivity &&
            
            activity !is FocusGroupPickerActivity &&
            !activity.javaClass.name.startsWith(LOGIN_MODULE_PACKAGE)

    







    fun isScreenLockRequiredOrShowing(): Boolean {
        if (ScreenLockUtil.recentlyUnlocked) return false
        if (currentResumedActivity?.get() is ScreenLockActivity) return true
        if (foregroundLockBlocker() == null) return false
        val userData = userManager.getUserData() ?: return false
        if (lockCheckDeferred && isLockConfigured(userData)) return true
        return shouldShowScreenLock(userData)
    }

    




    private fun foregroundLockBlocker(): String? = when {
        liveScreenLockActivities.any { !it.isFinishing } -> "lock_shown"
        
        
        lockCheckDeferred || isScreenLockPending() || lockCheckJob?.isActive == true -> "lock_pending"
        !foregroundUnlocked -> "lock_unconfirmed"
        
        
        isCallOrAlertActive() -> "lock_unconfirmed"
        else -> null
    }

    private fun isCallOrAlertActive(): Boolean =
        onGoingCallStateManager.get().isInCalling() ||
            inComingCallStateManager.get().isActivityShowing() ||
            criticalAlertStateManager.get().isShowing()

    @MainThread
    override fun onAppUnlocked() {
        foregroundUnlocked = true
    }

    @MainThread
    override fun isMainActivityHostingRecovery(): Boolean =
        liveMainActivities.any { !it.isFinishing && it.hostsLockScreen }

    






    @MainThread
    override fun inPlacePopupBlocker(): String? {
        if (liveIndexActivities.none { !it.isFinishing }) return "no_home"
        if (liveMainActivities.any { !it.isFinishing }) return "main_alive"
        val userData = userManager.getUserData()
        val sessionValid = LocalTokenVerifier.isLocalTokenValid(
            baseAuth = userData?.baseAuth,
            account = userData?.account,
            token = authTokenProvider.get().peek(),
            logInconsistency = false,
        )
        if (!sessionValid) return "session"
        return foregroundLockBlocker()
    }

    






    private fun showScreenLockIfNeeded(retriesLeft: Int = 3) {
        val activity = currentResumedActivity?.get()

        
        if (activity is ScreenLockActivity) {
            L.d { "[ScreenLock] Already showing ScreenLockActivity" }
            lockCheckDeferred = false
            screenLockPending.onDecided()
            return
        }

        val userData = userManager.getUserData()
        if (userData == null || !shouldShowScreenLock(userData)) {
            L.d { "[ScreenLock] Lock not needed" }
            
            if (userData == null || !isLockConfigured(userData) || !isLockTimedOut(userData)) foregroundUnlocked = true
            
            
            if (lockCheckDeferred && (userData == null || !isLockConfigured(userData))) {
                L.i { "[ScreenLock] deferred lock dropped reason=not_configured" }
                lockCheckDeferred = false
                restoreIncomingCallAfterDeferredCheck = false
                PasscodeUtil.needRecordLastUseTime = true
            }
            screenLockPending.onDecided()
            return
        }

        
        
        
        
        if (activity != null && !isLockHost(activity)) {
            L.i { "[ScreenLock] deferred host=${activity::class.simpleName}" }
            lockCheckDeferred = true
            foregroundUnlocked = false
            PasscodeUtil.needRecordLastUseTime = false
            return
        }

        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            L.i { "[ScreenLock] Starting ScreenLockActivity from ${activity::class.simpleName}" }
            ScreenLockActivity.startActivity(activity)
            lockCheckDeferred = false
            foregroundUnlocked = false
            screenLockPending.onDecided()
        } else if (retriesLeft > 0 && startedActivityCount > 0) {
            L.i { "[ScreenLock] No valid activity yet, retrying (retriesLeft=$retriesLeft)" }
            screenLockPending.onRetryScheduled()
            launch(Dispatchers.Main) {
                delay(150)
                showScreenLockIfNeeded(retriesLeft - 1)
            }
        } else {
            L.w { "[ScreenLock] Gave up starting ScreenLockActivity: retriesLeft=$retriesLeft, startedActivityCount=$startedActivityCount" }
            screenLockPending.onDecided()
        }
    }

    
    fun onLockHostReady(activity: Activity) = rerunDeferredLockCheck(activity)

    




    private fun rerunDeferredLockCheck(activity: Activity) {
        if (!lockCheckDeferred || !isLockHost(activity)) return
        L.i { "[ScreenLock] deferred check resumed host=${activity::class.simpleName}" }
        
        screenLockPending.onCheckScheduled()
        deferredRerunJob?.cancel()
        deferredRerunJob = launch(Dispatchers.Main) {
            delay(100)
            showScreenLockIfNeeded()
            
            
            
            if (restoreIncomingCallAfterDeferredCheck) {
                restoreIncomingCallAfterDeferredCheck = false
                restoreIncomingCallScreen()
            }
        }
    }

    
    private fun isLockHost(activity: Activity): Boolean =
        activity !is MainActivity || activity.hostsLockScreen

    private fun isLockConfigured(userData: UserData): Boolean =
        !(userData.passcode.isNullOrEmpty() && userData.pattern.isNullOrEmpty()) &&
            !userData.baseAuth.isNullOrEmpty()

    private fun shouldShowScreenLock(userData: UserData): Boolean {
        if (ScreenLockUtil.temporarilyDisabled) {
            L.d { "[ScreenLock] Skip: temporarily disabled" }
            return false
        }

        if (criticalAlertStateManager.get().isShowing()) {
            L.d { "[ScreenLock] Skip: critical alert" }
            return false
        }

        
        
        if (onGoingCallStateManager.get().isInForeground()) {
            L.d { "[ScreenLock] Skip: in call (foreground)" }
            return false
        }

        
        
        if (inComingCallStateManager.get().isInForeground()) {
            L.d { "[ScreenLock] Skip: incoming call (foreground)" }
            return false
        }

        if (userData.passcode.isNullOrEmpty() && userData.pattern.isNullOrEmpty()) {
            L.d { "[ScreenLock] Skip: no lock set" }
            return false
        }

        if (userData.baseAuth.isNullOrEmpty()) {
            L.d { "[ScreenLock] Skip: not authenticated" }
            return false
        }

        val isTimeout = isLockTimedOut(userData)

        if (!isTimeout) {
            L.d { "[ScreenLock] Skip: not timeout yet" }
        }

        return isTimeout
    }

    private fun isLockTimedOut(userData: UserData): Boolean {
        
        
        val lastUseTime = pendingLastUseTime.current()
        return lockCheckDeferred || userData.passcodeTimeout == 0 ||
            System.currentTimeMillis() - lastUseTime >= userData.passcodeTimeout.seconds.inWholeMilliseconds
    }

    private fun initCallEngine() {
        LCallEngine.init(this, this, environmentHelper)
    }

    




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
        launch(Dispatchers.IO) {
            FeatureGrayManager.checkUpdateConfigFromServer()
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

private const val LOGIN_MODULE_PACKAGE = "com.difft.android.login."
