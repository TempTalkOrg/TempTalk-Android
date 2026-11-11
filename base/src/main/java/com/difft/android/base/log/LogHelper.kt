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
        
        FILE_LOGGING_SETUP = FileLoggingSetup.DateFiles(
            context,
            setup = FileLoggingSetup.Setup(
                fileName = context.packageName + "_log",
                fileExtension = "txt"
            )
        )
        L.plant(FileLoggingTree(FILE_LOGGING_SETUP))

        


        configureOkHttpLogging()
    }

    private fun configureOkHttpLogging() {
        
        if (BuildConfig.DEBUG) {
            Logger.getLogger(OkHttpClient::class.java.name).level = Level.FINE
        }
    }

    fun clearLogFiles() {
        
        FILE_LOGGING_SETUP.clearLogFiles()
    }
}