package com.difft.android.base.storage.di

import javax.inject.Qualifier

/**
 * Hilt qualifier for the encrypted user-auth DataStore (`secure_user.pb`).
 *
 * Holds 15 auth/identity fields (baseAuth, microToken, signaling key, identity keypairs,
 * passcode/pattern). Backed by Tink AEAD with a per-namespace keyset.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SecureUserDataStore

/**
 * Hilt qualifier for the encrypted global-config DataStore (`secure_config.pb`).
 *
 * Replaces the two-instance `secure_global_config.xml` EncryptedSharedPreferences
 * collision between `:network/GlobalConfigsManager` and `:call/CallServiceUrlManager`.
 * Backed by Tink AEAD with its own keyset, isolated from `secure_user`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SecureConfigDataStore

/**
 * Hilt qualifier for the plain (unencrypted) app-state Preferences DataStore (`app_state.preferences_pb`).
 *
 * Holds non-secret UX state and DB-recovery flags consolidated from multiple legacy SP files.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppStateDataStore
