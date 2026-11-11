package com.difft.android.setting.di

import com.difft.android.setting.AppStore
import com.difft.android.setting.StoreUpdateDelivery
import com.difft.android.setting.UpdateDelivery
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent





@Module
@InstallIn(SingletonComponent::class)
object UpdateDeliveryModule {

    @Provides
    fun provideUpdateDelivery(): UpdateDelivery = StoreUpdateDelivery(AppStore.FDroid)
}
