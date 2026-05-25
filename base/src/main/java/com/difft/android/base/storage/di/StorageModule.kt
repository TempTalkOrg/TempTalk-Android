package com.difft.android.base.storage.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.difft.android.base.storage.AppStateMigrations
import com.difft.android.base.storage.EncryptedSerializer
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
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        val aead = buildAead(
            context,
            SECURE_USER_KEYSET_PREFS_FILE,
            SECURE_USER_KEYSET_PREFS_KEY,
            KEYSTORE_SECURE_USER_URI,
        )
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
        val aead = buildAead(
            context,
            SECURE_CONFIG_KEYSET_PREFS_FILE,
            SECURE_CONFIG_KEYSET_PREFS_KEY,
            KEYSTORE_SECURE_CONFIG_URI,
        )
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
        return keysetHandle.getPrimitive(Aead::class.java)
    }
}
