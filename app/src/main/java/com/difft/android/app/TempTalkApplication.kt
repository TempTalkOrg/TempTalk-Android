package com.difft.android.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.difft.android.MainActivity
import com.difft.android.base.BuildConfig
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.log.LogHelper
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.AppStartup
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.RxUtil
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallEngine
import com.difft.android.call.LCallManager
import com.difft.android.call.LIncomingCallActivity
import com.difft.android.chat.common.ScreenShotUtil
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.login.PasscodeUtil
import com.difft.android.login.ScreenLockActivity
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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.logging.SignalProtocolLoggerProvider
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.dependencies.ApplicationDependencyProvider
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.util.AppForegroundObserver
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import util.ScreenLockUtil
import util.concurrent.TTExecutors
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
            .addBlocking("init theme", this::initAppTheme)
            .addBlocking("lifecycle-observer") {
                AppForegroundObserver.addListener(this)
            }
            .addBlocking("init rxJava plugins", this::initRxJavaPlugins)
            .addBlocking("init notification", this::initNotification)
            .addBlocking("upgradeSecurityProvider", this::upgradeSecurityProvider)
            .addBlocking("prepareScreenLockListener", this::prepareScreenLockListener)
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
        SignalProtocolLoggerProvider.setProvider(com.difft.android.logging.CustomSignalProtocolLogger())
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
        if (!ScreenLockUtil.noNeedShowScreenLock && !ScreenLockUtil.pictureSelectorIsShowing) {
            PasscodeUtil.disableScreenLock = false
        }
        ScreenLockUtil.noNeedShowScreenLock = false

        recordLastUseTime()

        TTExecutors.BOUNDED.execute {
//            FeatureFlags.refreshIfNecessary()
            KeyCachingService.onAppForegrounded(this)
            SignalStore.misc().lastForegroundTime = System.currentTimeMillis()
        }
        LCallManager.restoreCallActivityIfInCalling()
        LCallManager.restoreIncomingCallActivityIfIncoming()
    }

    override fun onBackground() {
        KeyCachingService.onAppBackgrounded(this)

        recordLastUseTimeDisposable?.dispose()

        ScreenLockUtil.appIsForegroundBeforeHandleDeeplink = false
    }

    private fun prepareScreenLockListener() {
        this.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            }

            override fun onActivityStarted(activity: Activity) {
            }

            override fun onActivityResumed(activity: Activity) {
                if (activity !is ScreenLockActivity && activity !is MainActivity) {
                    launch(Dispatchers.IO) {
                        val userData = userManager.getUserData()

                        withContext(Dispatchers.Main) {
                            if (userData != null) {
                                checkAndShowScreenLock(activity, userData)
                                ScreenShotUtil.setScreenShotEnable(activity, userData.passcode.isNullOrEmpty() && userData.pattern.isNullOrEmpty())
                            }
                        }
                    }
                }

                if(activity !is LCallActivity && activity !is MainActivity && activity !is LIncomingCallActivity) {
                    launch(Dispatchers.IO) {
                        val callInfo = LCallManager.getCallFeedbackInfo()
                        if(callInfo != null && !activity.isDestroyed){
                            withContext(Dispatchers.Main) {
                                LCallManager.showCallFeedbackView(activity, callInfo)
                            }
                        }
                    }
                }
            }

            override fun onActivityPaused(activity: Activity) {
            }

            override fun onActivityStopped(activity: Activity) {
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
            }, { e -> e.printStackTrace() })
    }

    private fun checkAndShowScreenLock(activity: Activity, userData: UserData) {
//        L.i { "[screenLock] checkAndShowScreenLock:" + PasscodeUtil.disableScreenLock }
        if (!userData.baseAuth.isNullOrEmpty()
            && !LCallActivity.isInCalling()
            && !LIncomingCallActivity.isActivityShowing()
            && !ScreenLockUtil.appIsForegroundBeforeHandleDeeplink
            && !PasscodeUtil.disableScreenLock
            && (!userData.passcode.isNullOrEmpty() || !userData.pattern.isNullOrEmpty())
            && (userData.passcodeTimeout == 0 || System.currentTimeMillis() - userData.lastUseTime >= userData.passcodeTimeout.seconds.inWholeMilliseconds)
        ) {
            ScreenLockActivity.startActivity(activity)
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
            });
        } catch (ignorable: Exception) {
            ignorable.printStackTrace()
        }
    }

    private fun initCallEngine() {
        LCallEngine.init(this, this)
    }

    private fun monitorMainThreadBlocking() {
        // Define blocking time thresholds for different modes
        val thresholds = if (BuildConfig.DEBUG) {
            listOf(200) // Debug mode: only 200ms for quick detection
        } else {
            // Release mode: 500ms, 1000ms, 2000ms for detailed monitoring
            //listOf(500, 1000, 2000)
            listOf(500)
        }

        // Create multiple ANRWatchDog instances for different thresholds
        thresholds.forEach { threshold ->
            ANRWatchDog(threshold)
                .setIgnoreDebugger(true)
                .setANRListener {
                    L.w { "ANR(${threshold}ms) detected: ${it.stackTraceToString()}" }
                    if (!BuildConfig.DEBUG) { // only report in release mode
                        // Create a custom exception with ANR threshold info for better filtering
                        val anrException = Exception("ANR(${threshold}ms)_main_thread_blocking - ${it.message}")
                        anrException.initCause(it)

                        // Record the exception without modifying global custom keys
                        FirebaseCrashlytics.getInstance().recordException(anrException)
                    }
                }
                .setReportMainThreadOnly()
                .start()
        }
    }
}