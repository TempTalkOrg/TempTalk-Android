package com.difft.android.base.storage.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateMigrations
import com.difft.android.base.storage.EncryptedSerializer
import com.difft.android.base.storage.UnavailableDataStore
import com.difft.android.base.storage.StorageNames.APP_STATE_FILE
import com.difft.android.base.storage.StorageNames.KEYSTORE_SECURE_CONFIG_URI
import com.difft.android.base.storage.StorageNames.KEYSTORE_SECURE_USER_URI
import com.difft.android.base.storage.StorageNames.SECURE_CONFIG_AAD
import com.difft.android.base.storage.StorageNames.SECURE_CONFIG_FILE
import com.difft.android.base.storage.StorageNames.SECURE_CONFIG_KEYSET_PREFS_FILE
import com.difft.android.base.storage.StorageNames.SECURE_CONFIG_KEYSET_PREFS_KEY
import com.difft.android.base.storage.StorageNames.SECURE_USER_AAD
import com.difft.android.base.storage.StorageNames.SECURE_USER_FILE
import com.difft.android.base.storage.StorageNames.SECURE_USER_KEYSET_PREFS_FILE
import com.difft.android.base.storage.StorageNames.SECURE_USER_KEYSET_PREFS_KEY
import com.difft.android.base.storage.migration.SecureUserSpMigration
import com.difft.android.base.storage.schema.GlobalConfigData
import com.difft.android.base.storage.schema.GlobalConfigDataSerializer
import com.difft.android.base.storage.schema.SecureConfigMigration
import com.difft.android.base.storage.schema.UserAuthData
import com.difft.android.base.storage.schema.UserAuthDataSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hilt providers for the three storage DataStores:
 *
 * - `secure_user.pb`            — encrypted [DataStore]<[UserAuthData]> (auth/identity fields).
 * - `secure_config.pb`          — encrypted [DataStore]<[GlobalConfigData]> (global + call config).
 * - `app_state.preferences_pb`  — plain [DataStore]<[Preferences]> (UX state, DB-recovery flags).
 *
 * All three DataStores share a single [CoroutineScope] backed by [SupervisorJob] + [Dispatchers.IO].
 */
@Module
@InstallIn(SingletonComponent::class)
internal object StorageModule {

    /**
     * Shared coroutine scope for all DataStores. SupervisorJob + IO dispatcher
     * matches DataStore's actor model. Cancellation of one DataStore does not
     * tear down the others.
     */
    @Provides
    @Singleton
    @Named("storage")
    fun provideStorageScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    @SecureUserDataStore
    fun provideSecureUserDataStore(
        @ApplicationContext context: Context,
        @Named("storage") scope: CoroutineScope,
    ): DataStore<UserAuthData> {
        val aead = buildAeadOrNull(
            context,
            SECURE_USER_KEYSET_PREFS_FILE,
            SECURE_USER_KEYSET_PREFS_KEY,
            KEYSTORE_SECURE_USER_URI,
            namespace = "secure_user",
        ) ?: return UnavailableDataStore(UserAuthData.EMPTY)
        val encryptedSerializer = EncryptedSerializer(
            delegate = UserAuthDataSerializer,
            aead = aead,
            aad = SECURE_USER_AAD,
            label = "secure_user",
        )
        return DataStoreFactory.create(
            serializer = encryptedSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { UserAuthData.EMPTY },
            migrations = listOf(SecureUserSpMigration(context)),
            scope = scope,
            produceFile = { context.dataStoreFile(SECURE_USER_FILE) },
        )
    }

    @Provides
    @Singleton
    @SecureConfigDataStore
    fun provideSecureConfigDataStore(
        @ApplicationContext context: Context,
        @Named("storage") scope: CoroutineScope,
    ): DataStore<GlobalConfigData> {
        val aead = buildAeadOrNull(
            context,
            SECURE_CONFIG_KEYSET_PREFS_FILE,
            SECURE_CONFIG_KEYSET_PREFS_KEY,
            KEYSTORE_SECURE_CONFIG_URI,
            namespace = "secure_config",
        ) ?: return UnavailableDataStore(GlobalConfigData.EMPTY)
        val encryptedSerializer = EncryptedSerializer(
            delegate = GlobalConfigDataSerializer,
            aead = aead,
            aad = SECURE_CONFIG_AAD,
            label = "secure_config",
        )
        return DataStoreFactory.create(
            serializer = encryptedSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { GlobalConfigData.EMPTY },
            migrations = listOf(SecureConfigMigration(context)),
            scope = scope,
            produceFile = { context.dataStoreFile(SECURE_CONFIG_FILE) },
        )
    }

    @Provides
    @Singleton
    @AppStateDataStore
    fun provideAppStateDataStore(
        @ApplicationContext context: Context,
        @Named("storage") scope: CoroutineScope,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            migrations = AppStateMigrations.build(context),
            produceFile = { context.preferencesDataStoreFile(APP_STATE_FILE) },
        )

    /**
     * Builds a Tink [Aead], or returns `null` (after logging + Crashlytics report) when the
     * Android Keystore can't load the existing keyset (crash 8d61a948), so the caller falls
     * back to [UnavailableDataStore] instead of crashing during Hilt injection.
     *
     * Catch scope is deliberately narrow — only [GeneralSecurityException] (InvalidKeyException,
     * KeyStoreException, …) and [IOException] (keyset prefs read). Programming errors (NPE etc.)
     * propagate so real bugs still surface. `internal` for direct catch-boundary unit tests.
     */
    internal fun buildAeadOrNull(
        context: Context,
        keysetPrefsFile: String,
        keysetPrefsKey: String,
        keystoreUri: String,
        namespace: String,
    ): Aead? =
        try {
            buildAead(context, keysetPrefsFile, keysetPrefsKey, keystoreUri)
        } catch (e: GeneralSecurityException) {
            reportKeystoreFailure(namespace, e)
            null
        } catch (e: IOException) {
            reportKeystoreFailure(namespace, e)
            null
        }

    /** Logs and reports a Keystore-driven AEAD build failure. No sensitive data — `namespace` is a fixed enum string and `SDK_INT` is numeric. */
    private fun reportKeystoreFailure(namespace: String, e: Throwable) {
        L.e { "[Storage] aead build failed namespace=$namespace ${e.stackTraceToString()}" }
        // getInstance() can throw (Firebase uninitialized in a secondary process); reporting must
        // not become a new crash source on the onCreate path this fix protects. L.e above already logged.
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("keystore_fail_namespace", namespace)
                setCustomKey("keystore_fail_api", Build.VERSION.SDK_INT)
                recordException(e)
            }
        }.onFailure { L.w { "[Storage] crashlytics report skipped: ${it.javaClass.simpleName}" } }
    }

    /**
     * Constructs a Tink [Aead] primitive bound to the per-namespace Keystore
     * master key. Idempotent across calls — [AeadConfig.register] short-circuits
     * after the first invocation.
     */
    private fun buildAead(
        context: Context,
        keysetPrefsFile: String,
        keysetPrefsKey: String,
        keystoreUri: String,
    ): Aead {
        AeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, keysetPrefsKey, keysetPrefsFile)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(keystoreUri)
            .build()
            .keysetHandle
        return keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }
}
