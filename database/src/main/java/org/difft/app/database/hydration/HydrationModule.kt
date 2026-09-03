package org.difft.app.database.hydration

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HydrationModule {

    @Binds
    @Singleton
    abstract fun bindMessageChildRowLoader(impl: WcdbMessageChildRowLoader): MessageChildRowLoader
}
