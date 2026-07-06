package com.difft.android.chat.gif.favorite

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the application-lifetime CoroutineScope: work launched on it survives the fragment /
 * ViewModel that started it. Used by the optimistic-favorite background flow (resolve preview →
 * transStore → CAS PUT) so favoriting a gif and immediately closing the panel still completes.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppCoroutineScope

/** Provides the singleton application-scoped CoroutineScope (SupervisorJob so one failure doesn't
 *  cancel siblings; IO dispatcher — the background favorite flow is network + disk bound). */
@Module
@InstallIn(SingletonComponent::class)
object AppScopeModule {
    @Provides
    @Singleton
    @AppCoroutineScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
