package org.thoughtcrime.securesms.dependencies

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

    @JvmStatic
    fun getJobManager(): JobManager = jobManagerInstance

    @JvmStatic
    fun getSignalServiceAccountManager(): SignalServiceAccountManager = signalServiceAccountManagerInstance

    @JvmStatic
    fun getMessageStore(): MessageStore = messageStoreInstance

    @JvmStatic
    fun getExoPlayerPool(): SimpleExoPlayerPool = exoPlayerPoolInstance

    @JvmStatic
    fun getEnvironmentHelper(): EnvironmentHelper = environmentHelperInstance

    // Lazy instances for internal use
    private val jobManagerInstance: JobManager by lazy {
        provider.provideJobManager()
    }

    private val signalServiceAccountManagerInstance: SignalServiceAccountManager by lazy {
        val entryPoint = EntryPointAccessors.fromApplication(_application, DependenciesEntryPoint::class.java)
        provider.provideSignalServiceAccountManager(entryPoint.chatConfig)
    }

    private val messageStoreInstance: MessageStore by lazy {
        val entryPoint = EntryPointAccessors.fromApplication(_application, DependenciesEntryPoint::class.java)
        entryPoint.messageStore
    }

    private val exoPlayerPoolInstance: SimpleExoPlayerPool by lazy {
        provider.provideExoPlayerPool()
    }

    private val environmentHelperInstance: EnvironmentHelper by lazy {
        val entryPoint = EntryPointAccessors.fromApplication(_application, DependenciesEntryPoint::class.java)
        entryPoint.environmentHelper
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
