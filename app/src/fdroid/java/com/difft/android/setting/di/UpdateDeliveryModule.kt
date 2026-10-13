package com.difft.android.setting.di

import com.difft.android.setting.AppStore
import com.difft.android.setting.StoreUpdateDelivery
import com.difft.android.setting.UpdateDelivery
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The F-Droid channel's binding: updates open the F-Droid listing. Same module name as the ones
 * under `src/google/java` and `src/sideload/java`; the variant picks it, never a runtime check.
 */
@Module
@InstallIn(SingletonComponent::class)
object UpdateDeliveryModule {

    @Provides
    fun provideUpdateDelivery(): UpdateDelivery = StoreUpdateDelivery(AppStore.FDroid)
}
