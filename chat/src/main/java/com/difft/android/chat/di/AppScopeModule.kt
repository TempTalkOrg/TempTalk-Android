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

/**
 * Qualifier for the application-lifetime CoroutineScope: work launched on it survives the fragment,
 * ViewModel or Activity that started it, and keeps running with nothing on screen.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppCoroutineScope

/**
 * Provides the singleton application-scoped CoroutineScope: SupervisorJob so one consumer's failure
 * does not cancel its siblings, Dispatchers.IO because every consumer is network or disk bound, and
 * [dbKeyFailSoftExceptionHandler] so an uncaught WCDB-key failure degrades instead of killing the
 * process — the session/keep-alive consumers exist precisely to make failures graceful.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppScopeModule {
    @Provides
    @Singleton
    @AppCoroutineScope
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("AppCoroutineScope") + dbKeyFailSoftExceptionHandler)
}
