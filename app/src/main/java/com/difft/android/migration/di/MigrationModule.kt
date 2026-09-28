package com.difft.android.migration.di

import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.crypto.BackupCryptoImpl
import com.difft.android.migration.snapshot.BackupSnapshotter
import com.difft.android.migration.snapshot.BackupSnapshotterImpl
import com.difft.android.migration.v2.container.ContainerReader
import com.difft.android.migration.v2.container.ContainerReaderImpl
import com.difft.android.migration.v2.container.ContainerWriter
import com.difft.android.migration.v2.container.ContainerWriterImpl
import com.difft.android.migration.v2.export.LiveReadSource
import com.difft.android.migration.v2.export.MigrationReadSource
import com.difft.android.migration.v2.export.NeutralExporter
import com.difft.android.migration.v2.export.NeutralExporterImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the data-migration engine.
 *
 * `NeutralImporter`, `WcdbWriteTargetImpl` and `RoundTripVerifier` are deliberately not bound:
 * they take a runtime staging-DB handle and are constructed at the call site.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MigrationModule {

    @Binds
    @Singleton
    abstract fun bindBackupCrypto(impl: BackupCryptoImpl): BackupCrypto

    @Binds
    @Singleton
    abstract fun bindBackupSnapshotter(impl: BackupSnapshotterImpl): BackupSnapshotter

    /**
     * Default read source for the exporter is the live WCDB. Callers needing a consistent snapshot
     * read construct a `SnapshotReadSource` at the call site because they own its lifecycle.
     */
    @Binds
    @Singleton
    abstract fun bindMigrationReadSource(impl: LiveReadSource): MigrationReadSource

    @Binds
    @Singleton
    abstract fun bindNeutralExporter(impl: NeutralExporterImpl): NeutralExporter

    @Binds
    @Singleton
    abstract fun bindContainerWriter(impl: ContainerWriterImpl): ContainerWriter

    @Binds
    @Singleton
    abstract fun bindContainerReader(impl: ContainerReaderImpl): ContainerReader
}
