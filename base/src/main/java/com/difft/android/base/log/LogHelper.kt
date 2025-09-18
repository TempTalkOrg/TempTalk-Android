package com.difft.android.base.log

import android.content.Context
import com.difft.android.base.BuildConfig
import com.difft.android.base.log.lumberjack.FileLoggingSetup
import com.difft.android.base.log.lumberjack.FileLoggingTree
import com.difft.android.base.log.lumberjack.L
import okhttp3.OkHttpClient
import timber.log.ConsoleTree
import java.util.logging.Level
import java.util.logging.Logger

object LogHelper {

    lateinit var FILE_LOGGING_SETUP: FileLoggingSetup

    fun init(context: Context) {
        if (com.difft.android.base.BuildConfig.DEBUG) {
            L.plant(ConsoleTree())
        }
        // OPTIONAL: we could plant a file logger here if desired
        FILE_LOGGING_SETUP = FileLoggingSetup.DateFiles(
            context,
            setup = FileLoggingSetup.Setup(
                fileName = context.packageName + "_log",
                fileExtension = "txt"
            )
        )
        L.plant(FileLoggingTree(FILE_LOGGING_SETUP))

        // we disable logs in release inside this demo
//        L.enabled = true

        configureOkHttpLogging()
    }

    private fun configureOkHttpLogging() {
        // Set the logging level to FINE to capture connection leaks
        if (BuildConfig.DEBUG) {
            Logger.getLogger(OkHttpClient::class.java.name).level = Level.FINE
        }
    }

    fun clearLogFiles() {
        // do NOT delete all files directly, just delete old ones and clear the newest one => the following function does do that for you
        FILE_LOGGING_SETUP.clearLogFiles()
    }
}