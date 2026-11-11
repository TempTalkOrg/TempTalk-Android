package com.difft.android.migration.v2.login

import com.difft.android.base.migration.MigrationImportLauncher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton





@Module
@InstallIn(SingletonComponent::class)
abstract class MigrationImportModule {

    @Binds
    @Singleton
    abstract fun bindMigrationImportLauncher(impl: MigrationImportLauncherImpl): MigrationImportLauncher
}
