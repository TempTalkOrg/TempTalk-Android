package com.difft.android.migration.v2.login

import com.difft.android.base.migration.MigrationImportLauncher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the `:base` [MigrationImportLauncher] bridge to its `:app` implementation, so the `:login`
 * verify flow can drive the migration import without depending on `:app` (mirrors `ActivityModule`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MigrationImportModule {

    @Binds
    @Singleton
    abstract fun bindMigrationImportLauncher(impl: MigrationImportLauncherImpl): MigrationImportLauncher
}
