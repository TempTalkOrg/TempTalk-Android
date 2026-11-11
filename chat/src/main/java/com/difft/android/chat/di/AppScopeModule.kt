package com.difft.android.chat.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import com.difft.android.base.utils.dbKeyFailSoftExceptionHandler
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton





@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppCoroutineScope







@Module
@InstallIn(SingletonComponent::class)
object AppScopeModule {
    @Provides
    @Singleton
    @AppCoroutineScope
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("AppCoroutineScope") + dbKeyFailSoftExceptionHandler)
}
