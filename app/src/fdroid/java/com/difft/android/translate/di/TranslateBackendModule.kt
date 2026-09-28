package com.difft.android.translate.di

import com.difft.android.chat.translate.backend.TranslateBackend
import com.difft.android.translate.UnavailableTranslateBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The F-Droid channel's binding. Same module name and same binding as the one under
 * `src/gms/java`, so nothing outside this source set has to know which one was compiled.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TranslateBackendModule {

    @Binds
    @Singleton
    abstract fun bindTranslateBackend(impl: UnavailableTranslateBackend): TranslateBackend
}
