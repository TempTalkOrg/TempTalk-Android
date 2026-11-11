package com.difft.android.translate.di

import com.difft.android.chat.translate.backend.TranslateBackend
import com.difft.android.translate.MlKitTranslateBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton






@Module
@InstallIn(SingletonComponent::class)
abstract class TranslateBackendModule {

    @Binds
    @Singleton
    abstract fun bindTranslateBackend(impl: MlKitTranslateBackend): TranslateBackend
}
