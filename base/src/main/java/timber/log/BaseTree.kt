package timber.log

import android.os.Build
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.log.lumberjack.L.replaceUid
import com.difft.android.base.log.lumberjack.data.StackData

abstract class BaseTree : Timber.Tree() {

    // Override the canonical log() entry. Timber's prepareLog (private) already
    // resolves the per-call tag from `Timber.tag(...)` and passes it here, so we
    // no longer need our own convenience-method overrides nor `super.getTag()`
    // (which became `internal` in Timber 5 and is unreachable from this module).
    // The custom work — stack-data resolution, UID redaction, prefix formatting —
    // happens here and then delegates to the abstract extended-signature log().
    final override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?
    ) {
        val callStackCorrection = getCallStackCorrection() ?: 0
        val stackDataLocal = getStackTrace()
        var needReplaceUid = false

        val stackData = stackDataLocal?.let {
            stackDataLocal.copy(callStackIndex = stackDataLocal.callStackIndex + callStackCorrection)
        } ?: run {
            needReplaceUid = true
            t?.let {
                StackData(t, 0, true)
            } ?: run {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val frame = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                        .walk { frames ->
                            frames.skip(CALL_STACK_INDEX_LIVEKIT.toLong())
                                .filter { f -> !f.declaringClass.name.startsWith("timber.log") }
                                .findFirst()
                        }
                    if (frame.isPresent) {
                        StackData(listOf(frame.get().toStackTraceElement()), 0, true)
                    } else {
                        return
                    }
                } else {
                    // Mirror the StackWalker semantics above: skip the baseline, then
                    // filter out any further timber.log.* frames. Required because
                    // Timber 5's `prepareLog` adds an extra frame between this method
                    // and the user-meaningful caller.
                    val syntheticTrace = Throwable()
                    val frames = syntheticTrace.stackTrace
                    val callerIndex = (CALL_STACK_INDEX_LIVEKIT until frames.size)
                        .firstOrNull { !frames[it].className.startsWith("timber.log") }
                        ?: return
                    StackData(syntheticTrace, callerIndex, true)
                }
            }
        }

        val resolvedMessage = if (needReplaceUid) replaceUid(message) else message
        val prefix = getPrefix(tag, stackData)
        log(priority, prefix, resolvedMessage, t, stackData)
    }

    override fun isLoggable(tag: String?, priority: Int): Boolean {
        return tag == null || (L.filter?.isTagEnabled(this, tag) ?: true)
    }

    abstract fun log(
        priority: Int,
        prefix: String,
        message: String,
        t: Throwable?,
        stackData: StackData
    )

    // --------------------
    // custom code - extended tag
    // --------------------

    protected fun formatLine(prefix: String, message: String) =
        L.formatter.formatLine(this, prefix, message)

    private fun getPrefix(customTag: String?, stackData: StackData): String {
        return L.formatter.formatLogPrefix(customTag, stackData)
    }

    // --------------------
    // custom code - callstack depth
    // --------------------

    companion object {
        internal const val CALL_STACK_INDEX = 4
        internal const val CALL_STACK_INDEX_LIVEKIT = CALL_STACK_INDEX + 1
    }

    private val callStackCorrection = ThreadLocal<Int>()
    private fun getCallStackCorrection(): Int? {
        val correction = callStackCorrection.get()
        if (correction != null) {
            callStackCorrection.remove()
        }
        return correction
    }

    internal fun setCallStackCorrection(value: Int) {
        callStackCorrection.set(value)
    }

    private val stackTrace = ThreadLocal<StackData>()
    private fun getStackTrace(): StackData? {
        val trace = stackTrace.get()
        stackTrace.remove()
        return trace
    }

    internal fun setStackTrace(trace: StackData) {
        stackTrace.set(trace)
    }

    // --------------------
    // custom code - timestamp (调用时捕获，保证时间准确)
    // --------------------

    private val logTimestamp = ThreadLocal<Long>()

    internal fun getLogTimestamp(): Long? {
        val ts = logTimestamp.get()
        logTimestamp.remove()
        return ts
    }

    internal fun setLogTimestamp(timestamp: Long) {
        logTimestamp.set(timestamp)
    }
}
