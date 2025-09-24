package com.difft.android.base.log.lumberjack.interfaces

import android.os.Parcelable
import java.io.File

interface IFileLoggingSetup : Parcelable {
    fun getAllExistingLogFiles(): List<File>
    fun getLatestLogFiles(): File?
    fun clearLogFiles()
}