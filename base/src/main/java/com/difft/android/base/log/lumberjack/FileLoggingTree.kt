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
import java.util.TimeZone






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

        
        val encoder1 = PatternLayoutEncoder()
        encoder1.context = lc
        encoder1.pattern = setup.setup.logPattern
        encoder1.start()

        
        val rollingFileAppender = RollingFileAppender<ILoggingEvent>()
        rollingFileAppender.isAppend = true
        rollingFileAppender.context = lc

        
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

        
        
        val root = mLogger as ch.qos.logback.classic.Logger
        root.detachAndStopAllAppenders()
        root.addAppender(rollingFileAppender)

        
        root.level = Level.ALL
    }

    
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS", Locale.US)

    override fun log(priority: Int, prefix: String, message: String, t: Throwable?, stackData: StackData) {
        val timestamp = getLogTimestamp() ?: System.currentTimeMillis()
        
        
        val formattedTime = synchronized(dateFormatter) {
            
            val zone = TimeZone.getDefault()
            if (zone.id != dateFormatter.timeZone.id) dateFormatter.timeZone = zone
            dateFormatter.format(Date(timestamp))
        }

        
        
        val logMessage = formatLine(prefix, LogScrubber.scrub(message))
        
        val logMessageWithTime = "$formattedTime $logMessage"

        
        doRealLog(priority, logMessageWithTime)
    }

    private val WTF_MARKER = MarkerFactory.getMarker("WTF-")

    private val dailyBudget = DailyLogBudget(DAILY_INFO_BUDGET_BYTES)

    @Synchronized
    private fun doRealLog(priority: Int, logMessage: String) {
        
        

        if (priority == Log.VERBOSE || priority == Log.DEBUG) return

        
        val day = logMessage.take(DAY_PREFIX_LENGTH)
        when (dailyBudget.admit(day, priority, logMessage.length + 1)) {
            DailyLogBudget.Admission.WRITE -> Unit
            DailyLogBudget.Admission.DROP -> return
            DailyLogBudget.Admission.WRITE_EXHAUSTED_MARKER -> {
                mLogger.warn("${logMessage.take(TIME_PREFIX_LENGTH)} [FileLoggingTree] daily INFO budget of ${DAILY_INFO_BUDGET_BYTES / 1024 / 1024} MB exhausted, dropping INFO until the next day")
                return
            }
        }

        when (priority) {
            Log.INFO -> mLogger.info(logMessage)
            Log.WARN -> mLogger.warn(logMessage)
            Log.ERROR -> mLogger.error(logMessage)
            Log.ASSERT -> mLogger.error(WTF_MARKER, logMessage)
        }
    }

    companion object {

        
        private const val DAILY_INFO_BUDGET_BYTES = 10L * 1024 * 1024
        private const val DAY_PREFIX_LENGTH = "yyyy-MM-dd".length
        private const val TIME_PREFIX_LENGTH = "yyyy-MM-dd HH:mm:ss,SSS".length

        const val DATE_FILE_NAME_PATTERN = "%s_\\d{8}.%s"
        const val NUMBERED_FILE_NAME_PATTERN = "%s\\d*.%s"

        internal var mLogger =
            LoggerFactory.getLogger(FileLoggingTree::class.java)
    }
}
