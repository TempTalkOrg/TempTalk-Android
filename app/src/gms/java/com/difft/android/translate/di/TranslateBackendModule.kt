package com.difft.android.translate.di

import com.difft.android.chat.translate.backend.TranslateBackend
import com.difft.android.translate.MlKitTranslateBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Channel flavors that may ship Google code get the ML Kit backend. The F-Droid channel compiles a
 * module of the same name from `src/fdroid/java` instead, so the binding is chosen by the build
 * variant and never by a runtime check.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TranslateBackendModule {

    @Binds
    @Singleton
    abstract fun bindTranslateBackend(impl: MlKitTranslateBackend): TranslateBackend
}
