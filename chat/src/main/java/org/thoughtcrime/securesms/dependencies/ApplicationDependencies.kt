package org.thoughtcrime.securesms.dependencies

import android.annotation.SuppressLint
import android.app.Application
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.websocket.api.SignalServiceAccountManager
import com.difft.android.websocket.internal.configuration.SignalServiceConfiguration
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.MessageStore
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies.init
import org.thoughtcrime.securesms.jobmanager.JobManager
import org.thoughtcrime.securesms.video.exo.SimpleExoPlayerPool

/**
 * Location for storing and retrieving application-scoped singletons. Users must call
 * [init] before using any of the methods, preferably early on in
 * [Application.onCreate].
 *
 * All future application-scoped singletons should be written as normal objects, then placed here
 * to manage their singleton-ness.
 */
object ApplicationDependencies {

    private lateinit var _application: Application
    private lateinit var provider: Provider

    @JvmStatic
    @Synchronized
    fun init(application: Application, provider: Provider) {
        if (this::_application.isInitialized || this::provider.isInitialized) {
            return
        }

        _application = application
        this.provider = provider
    }

    @JvmStatic
    fun isInitialized(): Boolean = this::_application.isInitialized

    @JvmStatic
    fun getApplication(): Application = _application

    // Use separate locks for each component to avoid blocking across different initializations
    private val jobManagerLock = Any()
    private val signalServiceAccountManagerLock = Any()
    private val messageStoreLock = Any()
    private val exoPlayerPoolLock = Any()
    private val environmentHelperLock = Any()

    @Volatile
    private var jobManagerInstance: JobManager? = null
    @Volatile
    private var signalServiceAccountManagerInstance: SignalServiceAccountManager? = null
    @Volatile
    private var messageStoreInstance: MessageStore? = null
    @Volatile
    @SuppressLint("StaticFieldLeak")
    private var exoPlayerPoolInstance: SimpleExoPlayerPool? = null
    @Volatile
    private var environmentHelperInstance: EnvironmentHelper? = null

    @JvmStatic
    fun getJobManager(): JobManager {
        jobManagerInstance?.let { return it }
        synchronized(jobManagerLock) {
            return jobManagerInstance ?: run {
                provider.provideJobManager().also { jobManagerInstance = it }
            }
        }
    }

    @JvmStatic
    fun getSignalServiceAccountManager(): SignalServiceAccountManager {
        signalServiceAccountManagerInstance?.let { return it }
        synchronized(signalServiceAccountManagerLock) {
            return signalServiceAccountManagerInstance ?: run {
                val entryPoint = EntryPointAccessors.fromApplication(_application, DependenciesEntryPoint::class.java)
                provider.provideSignalServiceAccountManager(entryPoint.chatConfig).also {
                    signalServiceAccountManagerInstance = it
                }
            }
        }
    }

    @JvmStatic
    fun getMessageStore(): MessageStore {
        messageStoreInstance?.let { return it }
        synchronized(messageStoreLock) {
            return messageStoreInstance ?: run {
                val entryPoint = EntryPointAccessors.fromApplication(_application, DependenciesEntryPoint::class.java)
                entryPoint.messageStore.also { messageStoreInstance = it }
            }
        }
    }

    @JvmStatic
    fun getExoPlayerPool(): SimpleExoPlayerPool {
        exoPlayerPoolInstance?.let { return it }
        synchronized(exoPlayerPoolLock) {
            return exoPlayerPoolInstance ?: run {
                provider.provideExoPlayerPool().also { exoPlayerPoolInstance = it }
            }
        }
    }

    @JvmStatic
    fun getEnvironmentHelper(): EnvironmentHelper {
        environmentHelperInstance?.let { return it }
        synchronized(environmentHelperLock) {
            return environmentHelperInstance ?: run {
                val entryPoint = EntryPointAccessors.fromApplication(_application, DependenciesEntryPoint::class.java)
                entryPoint.environmentHelper.also { environmentHelperInstance = it }
            }
        }
    }

    interface Provider {
        fun provideJobManager(): JobManager

        fun provideSignalServiceAccountManager(signalServiceConfiguration: SignalServiceConfiguration): SignalServiceAccountManager

        fun provideExoPlayerPool(): SimpleExoPlayerPool
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DependenciesEntryPoint {
        val chatConfig: SignalServiceConfiguration
        val messageStore: MessageStore
        val environmentHelper: EnvironmentHelper
    }
}
