package util

import com.difft.android.base.log.lumberjack.L
import util.logging.Scrubber
import java.io.ByteArrayOutputStream
import java.io.PrintStream

object ExceptionUtil {

    


















    @JvmStatic
    fun <E : Throwable> joinStackTrace(original: E, inferred: Throwable): E {
        original.stackTrace = joinStackTrace(original.stackTrace, inferred.stackTrace)
        return original
    }

    


    @JvmStatic
    fun joinStackTrace(
        originalTrace: Array<StackTraceElement>,
        inferredTrace: Array<StackTraceElement>
    ): Array<StackTraceElement> =
        originalTrace + arrayOf(
            StackTraceElement("[[ ↑↑ Original Trace ↑↑ ]]", "", "", 0),
            StackTraceElement("[[ ↓↓ Inferred Trace ↓↓ ]]", "", "", 0)
        ) + inferredTrace

    













    @JvmStatic
    fun <E : Throwable> joinStackTraceAndMessage(original: E): E {
        var message = Scrubber.scrub(original.message ?: "null").toString()
        if (message.startsWith("Context.startForegroundService")) {
            try {
                val service = message.substring(message.lastIndexOf('.') + 1, message.length - 1)
                message = "$service did not call startForeground"
            } catch (ignored: Exception) {
                L.w { "[ExceptionUtil] parse service name failed$ignored" }
            }
        }

        original.stackTrace = original.stackTrace + arrayOf(
            StackTraceElement("[[ ↑↑ Original Trace ↑↑ ]]", "", "", 0),
            StackTraceElement("[[ ↓↓ Exception Message ↓↓ ]]", "", "", 0),
            StackTraceElement(message, "", "", 0)
        )
        return original
    }

    @JvmStatic
    fun convertThrowableToString(throwable: Throwable): String {
        val outputStream = ByteArrayOutputStream()
        throwable.printStackTrace(PrintStream(outputStream))
        return outputStream.toString()
    }
}
