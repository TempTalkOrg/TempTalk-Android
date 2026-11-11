package com.difft.android.test.di

import com.difft.android.base.di.module.BaseHiltProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import javax.inject.Named
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [BaseHiltProvider::class]
)
object TestBaseHiltModule {

    
    
    
    @Singleton
    @Provides
    @Named("application")
    fun provideApplicationCoroutineScope(): CoroutineScope = TestScope()
}
