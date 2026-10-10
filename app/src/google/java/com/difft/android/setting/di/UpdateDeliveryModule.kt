package com.difft.android.setting.di

import com.difft.android.setting.AppStore
import com.difft.android.setting.StoreUpdateDelivery
import com.difft.android.setting.UpdateDelivery
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The Google Play channel's binding: updates open the Play listing. Same module name as the ones
 * under `src/fdroid/java` and `src/sideload/java`; the variant picks it, never a runtime check.
 */
@Module
@InstallIn(SingletonComponent::class)
object UpdateDeliveryModule {

    @Provides
    fun provideUpdateDelivery(): UpdateDelivery = StoreUpdateDelivery(AppStore.GooglePlay)
}
