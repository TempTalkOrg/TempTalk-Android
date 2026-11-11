package com.difft.android.setting.di

import com.difft.android.setting.SideloadUpdateDelivery
import com.difft.android.setting.UpdateDelivery
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent






@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateDeliveryModule {

    @Binds
    abstract fun bindUpdateDelivery(impl: SideloadUpdateDelivery): UpdateDelivery
}
