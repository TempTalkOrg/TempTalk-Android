package com.difft.android.base.log.lumberjack

import android.content.Context
import android.os.Parcelable
import com.difft.android.base.log.lumberjack.interfaces.IFileLoggingSetup
import kotlinx.parcelize.Parcelize
import java.io.File
import java.io.PrintWriter
import java.util.regex.Pattern

sealed class FileLoggingSetup : IFileLoggingSetup {

    protected abstract val pattern: String
    abstract val folder: String
    abstract val setup: Setup

    override fun getAllExistingLogFiles() = getFilesInFolder().filter { Pattern.matches(pattern, it.name) }
    override fun getLatestLogFiles() = getAllExistingLogFiles().sortedByDescending { it.lastModified() }.firstOrNull()

    override fun clearLogFiles() {
        val newestFile = getLatestLogFiles()
        val filesToDelete = getAllExistingLogFiles().filter { it != newestFile }
        filesToDelete.forEach {
            it.delete()
        }
        newestFile?.let {
            val writer = PrintWriter(it)
            writer.print("")
            writer.close()
        }
    }

    @Parcelize
    class NumberedFiles(
        override val folder: String,
        val sizeLimit: String,
        override val setup: Setup
    ) : FileLoggingSetup() {

        constructor(context: Context,
                    folder: String = context.getFileStreamPath("").absolutePath,
                    sizeLimit: String = "1MB",
                    setup: Setup = Setup()
        ) : this(folder, sizeLimit, setup)

        override val pattern = String.format(
            FileLoggingTree.NUMBERED_FILE_NAME_PATTERN,
            setup.fileName,
            setup.fileExtension
        )
        val baseFilePath = folder + "/" + setup.fileName + "." + setup.fileExtension
        


        override fun getLatestLogFiles() = File(baseFilePath)
    }

    @Parcelize
    class DateFiles(
        override val folder: String,
        override val setup: Setup
    ) : FileLoggingSetup() {

        constructor(context: Context,
                    folder: String = context.getFileStreamPath("").absolutePath,
                    setup: Setup = Setup()
        ) : this(folder, setup)

        override val pattern = String.format(
            FileLoggingTree.DATE_FILE_NAME_PATTERN,
            setup.fileName,
            setup.fileExtension
        )
    }

    







    @Parcelize
    class Setup(
        val logsToKeep: Int = 7,
        
        val logPattern: String = "%marker%-5level %msg%n",
        val fileName: String = "log",
        val fileExtension: String = "log"
    ) : Parcelable

    protected fun getFilesInFolder(): List<File> {
        val folder = File(folder)
        if (!folder.exists()) {
            return emptyList()
        }
        return folder.listFiles()?.filter { it.isFile } ?: emptyList()
    }
}