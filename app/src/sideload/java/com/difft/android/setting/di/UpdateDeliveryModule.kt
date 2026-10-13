package com.difft.android.setting.di

import com.difft.android.setting.SideloadUpdateDelivery
import com.difft.android.setting.UpdateDelivery
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The sideload channels' binding (official, insider): updates download and install in-app. Same
 * module name as the ones under `src/google/java` and `src/fdroid/java`; the variant picks it,
 * never a runtime check.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateDeliveryModule {

    @Binds
    abstract fun bindUpdateDelivery(impl: SideloadUpdateDelivery): UpdateDelivery
}
