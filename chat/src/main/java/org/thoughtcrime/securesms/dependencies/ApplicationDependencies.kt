package org.thoughtcrime.securesms.dependencies

import android.app.Application
import com.difft.android.base.utils.EnvironmentHelper
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.attachment.AttachmentStore
import com.difft.android.messageserialization.db.store.attachment.AttachmentManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies.init
import org.thoughtcrime.securesms.jobmanager.JobManager
import org.thoughtcrime.securesms.video.exo.SimpleExoPlayerPool
import com.difft.android.websocket.api.SignalServiceAccountManager
import com.difft.android.websocket.internal.configuration.SignalServiceConfiguration

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
    fun getAttachmentStore(): AttachmentStore = attachmentStoreInstance

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

    private val attachmentStoreInstance: AttachmentStore by lazy {
        val entryPoint = EntryPointAccessors.fromApplication(_application, DependenciesEntryPoint::class.java)
        entryPoint.attachmentStore
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
        val attachmentStore: AttachmentManager
        val environmentHelper: EnvironmentHelper
    }
}
