package com.difft.android.base.log.lumberjack

import android.util.Log
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import ch.qos.logback.core.rolling.TriggeringPolicy
import ch.qos.logback.core.util.FileSize
import com.difft.android.base.log.lumberjack.data.StackData
import org.slf4j.LoggerFactory
import org.slf4j.MarkerFactory
import timber.log.BaseTree
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/**
 * Created by Michael on 17.10.2016.
 */

class FileLoggingTree(
    setup: FileLoggingSetup?
) : BaseTree() {

    init {
        if (setup == null) {
            throw RuntimeException("You can't create a FileLoggingTree without providing a setup!")
        }

        init(setup)
    }

    private fun init(setup: FileLoggingSetup) {
        val lc = LoggerFactory.getILoggerFactory() as LoggerContext
        lc.reset()

        // 1) FileLoggingSetup - Encoder for File
        val encoder1 = PatternLayoutEncoder()
        encoder1.context = lc
        encoder1.pattern = setup.setup.logPattern
        encoder1.start()

        // 2) FileLoggingSetup - rolling file appender
        val rollingFileAppender = RollingFileAppender<ILoggingEvent>()
        rollingFileAppender.isAppend = true
        rollingFileAppender.context = lc
        //rollingFileAppender.setFile(setup.folder + "/" + setup.fileName + "." + setup.fileExtension);

        // 3) FileLoggingSetup - Rolling policy (one log per day)
        var triggeringPolicy: TriggeringPolicy<ILoggingEvent>? = null
        when (setup) {
            is FileLoggingSetup.DateFiles -> {
                val timeBasedRollingPolicy = TimeBasedRollingPolicy<ILoggingEvent>()
                timeBasedRollingPolicy.fileNamePattern =
                    setup.folder + "/" + setup.setup.fileName + "_%d{yyyyMMdd}." + setup.setup.fileExtension
                timeBasedRollingPolicy.maxHistory = setup.setup.logsToKeep
                timeBasedRollingPolicy.isCleanHistoryOnStart = true
                timeBasedRollingPolicy.setParent(rollingFileAppender)
                timeBasedRollingPolicy.context = lc

                triggeringPolicy = timeBasedRollingPolicy
            }

            is FileLoggingSetup.NumberedFiles -> {
                val fixedWindowRollingPolicy = FixedWindowRollingPolicy()
                fixedWindowRollingPolicy.fileNamePattern =
                    setup.folder + "/" + setup.setup.fileName + "%i." + setup.setup.fileExtension
                fixedWindowRollingPolicy.minIndex = 1
                fixedWindowRollingPolicy.maxIndex = setup.setup.logsToKeep
                fixedWindowRollingPolicy.setParent(rollingFileAppender)
                fixedWindowRollingPolicy.context = lc

                val sizeBasedTriggeringPolicy = SizeBasedTriggeringPolicy<ILoggingEvent>()
                sizeBasedTriggeringPolicy.maxFileSize =
                    FileSize.valueOf(setup.sizeLimit)

                triggeringPolicy = sizeBasedTriggeringPolicy

                rollingFileAppender.file = setup.baseFilePath
                rollingFileAppender.rollingPolicy = fixedWindowRollingPolicy
                fixedWindowRollingPolicy.start()
            }
        }
        triggeringPolicy.start()

        rollingFileAppender.triggeringPolicy = triggeringPolicy
        rollingFileAppender.encoder = encoder1
        rollingFileAppender.start()

        // add the newly created appenders to the root logger;
        // qualify Logger to disambiguate from org.slf4j.Logger
        val root = mLogger as ch.qos.logback.classic.Logger
        root.detachAndStopAllAppenders()
        root.addAppender(rollingFileAppender)

        // enable all log level
        root.level = Level.ALL
    }

    // 时间格式化器，使用调用时捕获的时间戳
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS", Locale.US)

    override fun log(priority: Int, prefix: String, message: String, t: Throwable?, stackData: StackData) {
        // 获取调用时捕获的时间戳（如果有），否则使用当前时间
        val timestamp = getLogTimestamp() ?: System.currentTimeMillis()
        val formattedTime = dateFormatter.format(Date(timestamp))

        val logMessage = formatLine(prefix, message)
        // 在 message 前添加调用时的时间戳，保证时间准确性
        val logMessageWithTime = "$formattedTime $logMessage"

        //L.kt已经将日志处理整个放在子线程
        doRealLog(priority, logMessageWithTime)
    }

    private val WTF_MARKER = MarkerFactory.getMarker("WTF-")

    private fun doRealLog(priority: Int, logMessage: String) {
        // slf4j:   TRACE   <   DEBUG < INFO < WARN < ERROR < FATAL
        // Android: VERBOSE <   DEBUG < INFO < WARN < ERROR < ASSERT

        if (priority == Log.VERBOSE || priority == Log.DEBUG) return

        when (priority) {
//            Log.VERBOSE -> mLogger.trace(logMessage)
//            Log.DEBUG -> mLogger.debug(logMessage)
            Log.INFO -> mLogger.info(logMessage)
            Log.WARN -> mLogger.warn(logMessage)
            Log.ERROR -> mLogger.error(logMessage)
            Log.ASSERT -> mLogger.error(WTF_MARKER, logMessage)
        }
    }

    companion object {

        const val DATE_FILE_NAME_PATTERN = "%s_\\d{8}.%s"
        const val NUMBERED_FILE_NAME_PATTERN = "%s\\d*.%s"

        internal var mLogger =
            LoggerFactory.getLogger(FileLoggingTree::class.java)//Logger.ROOT_LOGGER_NAME);
    }
}