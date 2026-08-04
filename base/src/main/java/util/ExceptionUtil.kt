package util

import com.difft.android.base.log.lumberjack.L
import util.logging.Scrubber
import java.io.ByteArrayOutputStream
import java.io.PrintStream

object ExceptionUtil {

    /**
     * Joins the stack trace of the inferred call site with the original exception. This is
     * useful for when exceptions are thrown inside of asynchronous systems (like runnables in an
     * executor) where you'd otherwise lose important parts of the stack trace. This lets you save a
     * throwable at the entry point, and then combine it with any caught exceptions later.
     *
     * The resulting stack trace will look like this:
     *
     * Inferred
     * Stack
     * Trace
     * [[ ↑↑ Inferred Trace ↑↑ ]]
     * [[ ↓↓ Original Trace ↓↓ ]]
     * Original
     * Stack
     * Trace
     *
     * @return The provided original exception, for convenience.
     */
    @JvmStatic
    fun <E : Throwable> joinStackTrace(original: E, inferred: Throwable): E {
        original.stackTrace = joinStackTrace(original.stackTrace, inferred.stackTrace)
        return original
    }

    /**
     * See [joinStackTrace].
     */
    @JvmStatic
    fun joinStackTrace(
        originalTrace: Array<StackTraceElement>,
        inferredTrace: Array<StackTraceElement>
    ): Array<StackTraceElement> =
        originalTrace + arrayOf(
            StackTraceElement("[[ ↑↑ Original Trace ↑↑ ]]", "", "", 0),
            StackTraceElement("[[ ↓↓ Inferred Trace ↓↓ ]]", "", "", 0)
        ) + inferredTrace

    /**
     * Joins the stack trace with the exception's [Throwable.getMessage].
     *
     * The resulting stack trace will look like this:
     *
     * Original
     * Stack
     * Trace
     * [[ ↑↑ Original Trace ↑↑ ]]
     * [[ ↓↓ Exception Message ↓↓ ]]
     * Exception Message
     *
     * @return The provided original exception, for convenience.
     */
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
