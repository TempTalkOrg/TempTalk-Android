package com.difft.android.migration.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.difft.android.base.log.lumberjack.L

/**
 * Outbound links used by the migration screens: the official download page, an app's store page,
 * and the system storage settings. Every launch is best-effort and returns whether it happened, so
 * the caller can surface a failure instead of leaving a dead button.
 */
object MigrationLinks {

    /** Official download page; it does the channel split, so no store URL lives in the app. */
    const val DOWNLOAD_PAGE = "https://quicall.app/download"

    fun openDownloadPage(context: Context): Boolean = view(context, Uri.parse(DOWNLOAD_PAGE))

    /** Opens [packageName]'s store listing when a store is installed, otherwise the download page. */
    fun openUpdateFor(context: Context, packageName: String): Boolean =
        view(context, Uri.parse("market://details?id=$packageName")) || openDownloadPage(context)

    fun openStorageSettings(context: Context): Boolean =
        launch(context, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) ||
            launch(context, Intent(Settings.ACTION_SETTINGS))

    private fun view(context: Context, uri: Uri): Boolean = launch(context, Intent(Intent.ACTION_VIEW, uri))

    private fun launch(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { e -> L.w { "[MigrationV2][Links] could not open ${intent.action}: ${e.message}" } }
            .isSuccess
}
