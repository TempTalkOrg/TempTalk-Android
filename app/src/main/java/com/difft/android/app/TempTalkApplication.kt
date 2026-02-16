package com.difft.android.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.FragmentActivity
import com.difft.android.IndexActivity
import com.difft.android.MainActivity
import com.difft.android.base.BuildConfig
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.log.LogHelper
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.AppStartup
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.RxUtil
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallEngine
import com.difft.android.call.LCallManager
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
import com.github.anrwatchdog.ANRWatchDog
import com.google.android.gms.security.ProviderInstaller
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.HiltAndroidApp
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.plugins.RxJavaPlugins
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.signal.libsignal.protocol.logging.SignalProtocolLogger
import org.signal.libsignal.protocol.logging.SignalProtocolLoggerProvider
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.dependencies.ApplicationDependencyProvider
import util.AppForegroundObserver
import org.thoughtcrime.securesms.util.MessageNotificationUtil
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
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var environmentHelper: EnvironmentHelper

    @Inject
    lateinit var onGoingCallStateManager: OnGoingCallStateManager

    @Inject
    lateinit var inComingCallStateManager: InComingCallStateManager

    @Inject
    lateinit var criticalAlertManager: CriticalAlertManager

    @Inject
    lateinit var criticalAlertStateManager: CriticalAlertStateManager

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
            .addBlocking("init ApplicationDependencies") {
                ApplicationDependencies.init(this, ApplicationDependencyProvider(this))
                AppForegroundObserver.begin()
            }
            .addBlocking("init UserData", this::initUserData)
            .addBlocking("init theme", this::initAppTheme)
            .addBlocking("lifecycle-observer") {
                AppForegroundObserver.addListener(this)
            }
            .addBlocking("init rxJava plugins", this::initRxJavaPlugins)
            .addBlocking("init notification", this::initNotification)
            .addBlocking("upgradeSecurityProvider", this::upgradeSecurityProvider)
            .addBlocking("prepareScreenLockListener", this::prepareScreenLockListener)
            .addBlocking("thirdPartyActivityGuard") { ThirdPartyActivityGuard.register(this) }
            .addBlocking("installCrashFilter", this::installCrashFilter)
            .addNonBlocking { ApplicationDependencies.getJobManager().beginJobLoop() }
            .addNonBlocking { initCallEngine() }
            .addNonBlocking { monitorMainThreadBlocking() }
            .addNonBlocking { ContactorUtil.init() }
            .addNonBlocking { initGlobalConfigs() }
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

    override fun attachBaseContext(context: Context) {
        super.attachBaseContext(LanguageUtils.updateBaseContextLocale(context))
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

    /**
     * Initialize user data cache (inMemoryUserData) from EncryptedSharedPreferences.
     * This prevents ANR when multiple threads try to access SecureSharedPrefsUtil concurrently.
     * Uses timeout (2s) to prevent ANR on extremely slow devices - falls back to lazy init if timeout.
     */
    private fun initUserData() {
        val job = async(Dispatchers.IO) { userManager.getUserData() }
        runBlocking { withTimeoutOrNull(2000) { job.await() } }
    }

    private fun initAppTheme() {
        launch(Dispatchers.IO) {
            val theme = userManager.getUserData()?.theme
            L.i { "[TempTalkApplication] loadUserThemeAsync theme: $theme" }

            withContext(Dispatchers.Main) {
                when (theme) {
                    AppCompatDelegate.MODE_NIGHT_YES -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    }

                    AppCompatDelegate.MODE_NIGHT_NO -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    }

                    else -> {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    }
                }
            }
        }
    }

    private fun initRxJavaPlugins() {
        if (!com.difft.android.BuildConfig.DEBUG) {
            RxJavaPlugins.setErrorHandler {
                L.i { ("[RX] rx exception ->  $it") }
                FirebaseCrashlytics.getInstance().recordException(it)
            }
        }
    }

    private fun initLog() {
        LogHelper.init(this)
    }

    private fun initNotification() {
        messageNotificationUtil.checkAndCreateNotificationChannels()
    }

    private fun initGlobalConfigs() {
        globalConfigsManager.getAndSaveGlobalConfigs(this)
    }

    override fun onForeground() {
        recordLastUseTime()
        scheduleGrayConfigUpdateCheck()
        LCallManager.restoreIncomingCallScreenIfActive()
        globalConfigsManager.onAppStateChanged(isForeground = true)
        messageArchiveManager.get().onAppStateChanged(isForeground = true)
    }

    override fun onBackground() {
        recordLastUseTimeDisposable?.dispose()
        globalConfigsManager.onAppStateChanged(isForeground = false)
        messageArchiveManager.get().onAppStateChanged(isForeground = false)
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
                if (activity is IndexActivity && criticalAlertManager.isCriticalAlertRunning() && !criticalAlertStateManager.isJoining()) {
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
                L.d { "[ScreenLock] onActivityStopped: ${activity::class.simpleName}, count=$startedActivityCount" }

                // 从前台进入后台：计数从 1 变为 0
                if (startedActivityCount == 0) {
                    L.d { "[ScreenLock] App entered background" }
                    onAppBackground()
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            }

            override fun onActivityDestroyed(activity: Activity) {
            }

        })
    }


    private var recordLastUseTimeDisposable: Disposable? = null

    private fun recordLastUseTime() {
        recordLastUseTimeDisposable = Observable.interval(1, 10, TimeUnit.SECONDS)
            .compose(RxUtil.getSchedulerComposer())
            .subscribe({
                if (PasscodeUtil.needRecordLastUseTime) {
                    userManager.update {
                        this.lastUseTime = System.currentTimeMillis()
                    }
                }
            }, { e -> L.w { "[TempTalkApplication] recordLastUseTime error: ${e.stackTraceToString()}" } })
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

    private suspend fun showScreenLockIfNeeded() {
        withContext(Dispatchers.IO) {
            val userData = userManager.getUserData()

            withContext(Dispatchers.Main) {
                val activity = currentResumedActivity?.get()

                // 如果当前就是锁屏页，不需要重复启动
                if (activity is ScreenLockActivity) {
                    L.d { "[ScreenLock] Already showing ScreenLockActivity" }
                    return@withContext
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
        }
    }

    private fun shouldShowScreenLock(userData: UserData): Boolean {
        // 1. 通用的临时豁免
        if (ScreenLockUtil.temporarilyDisabled) {
            L.d { "[ScreenLock] Skip: temporarily disabled" }
            return false
        }

        // 2. 通话相关
        if (criticalAlertStateManager.isShowing()) {
            L.d { "[ScreenLock] Skip: critical alert" }
            return false
        }

        if (onGoingCallStateManager.isInCalling() && !onGoingCallStateManager.needAppLock) {
            L.d { "[ScreenLock] Skip: in call" }
            return false
        }

        if (inComingCallStateManager.isActivityShowing() && !inComingCallStateManager.isNeedAppLock()) {
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
        val isTimeout = userData.passcodeTimeout == 0 ||
                System.currentTimeMillis() - userData.lastUseTime >= userData.passcodeTimeout.seconds.inWholeMilliseconds

        if (!isTimeout) {
            L.d { "[ScreenLock] Skip: not timeout yet" }
        }

        return isTimeout
    }

    /**
     * Intercept [android.util.SuperNotCalledException] to prevent it from being reported to
     * Firebase Crashlytics. This exception is triggered by Hook frameworks (e.g., lhook on
     * rooted emulators) that intercept Activity.onCreate() without calling through to the
     * original implementation. It cannot occur during normal app usage and only pollutes
     * crash-free metrics.
     *
     * Crashlytics initializes via ContentProvider (before Application.onCreate()),
     * so calling this in Application.onCreate() guarantees our handler wraps theirs.
     */
    private fun installCrashFilter() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (throwable.javaClass.name == "android.util.SuperNotCalledException") {
                L.w { "[CrashFilter] Suppressed SuperNotCalledException: ${throwable.message}" }
                android.os.Process.killProcess(android.os.Process.myPid())
            } else {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun upgradeSecurityProvider() {
        // Ensure an updated security provider is installed into the system when a new one is
        // available via Google Play services.
        // https://developer.android.com/privacy-and-security/security-gms-provider?hl=zh-cn
        try {
            ProviderInstaller.installIfNeededAsync(this, object :
                ProviderInstaller.ProviderInstallListener {
                override fun onProviderInstalled() {}
                override fun onProviderInstallFailed(errorCode: Int, recoveryIntent: Intent?) {}
            })
        } catch (ignorable: Exception) {
            L.w { "[TempTalkApplication] error: ${ignorable.stackTraceToString()}" }
        }
    }

    private fun initCallEngine() {
        LCallEngine.init(this, this, environmentHelper)
    }

    private fun monitorMainThreadBlocking() {
        // 700ms aligns with Google's "frozen frame" threshold in Android Vitals.
        val threshold = 700

        ANRWatchDog(threshold)
            .setIgnoreDebugger(true)
            .setANRListener { anrError ->
                L.w { "ANR(${threshold}ms) detected: ${anrError.stackTraceToString()}" }
                if (!BuildConfig.DEBUG) { // only report in release mode
                    // Background ANRs are mostly false positives (OS deprioritizes the process),
                    // skip reporting to reduce noise
                    if (!AppForegroundObserver.isForegrounded()) return@setANRListener

                    val anrException = Exception("ANR(${threshold}ms)_main_thread_blocking - ${anrError.message}")
                    // Use main thread's actual blocking stack trace for Crashlytics grouping,
                    // so events are grouped by real blocking location instead of this lambda
                    anrError.cause?.stackTrace?.let { mainThreadStack ->
                        anrException.stackTrace = mainThreadStack
                    }
                    anrException.initCause(anrError)

                    FirebaseCrashlytics.getInstance().recordException(anrException)
                }
            }
            .setReportMainThreadOnly()
            .start()
    }

    private fun scheduleGrayConfigUpdateCheck() {
        launch(Dispatchers.IO){
            userManager.getUserData()?.let {
                FeatureGrayManager.checkUpdateConfigFromServer(it.lastUseTime)
            }
        }
    }

    private fun shouldCheckScreenLockForCall(): Boolean {
        val incomingCallNeedsLock = inComingCallStateManager.isActivityShowing() && inComingCallStateManager.isNeedAppLock()
        val activeCallNeedsLock = onGoingCallStateManager.isInCalling() && onGoingCallStateManager.needAppLock
        return incomingCallNeedsLock || activeCallNeedsLock
    }
}