package com.difft.android.base.log.lumberjack

import com.difft.android.base.log.lumberjack.core.DefaultFormatter
import com.difft.android.base.log.lumberjack.data.StackData
import com.difft.android.base.log.lumberjack.interfaces.IFilter
import com.difft.android.base.log.lumberjack.interfaces.IFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.BaseTree
import timber.log.Timber
import java.util.concurrent.Executors

/*
logs LAZILY and at the callers place via kotlin inline functions
 */
@Suppress("NOTHING_TO_INLINE")
object L {

    // --------------
    // setup
    // --------------

    /*
     * if false, all logging is disabled
     */
    var enabled = true

    /*
     * if enabled, Lumberjack will try to find out a lambdas caller and append this info to the log tag
     * does not work perfectly, we would need to distinguish between lambdas called directly or by a coroutine and more...
     */
    internal val advancedLambdaLogging = false

    /*
     * provide a custom formatter to influence, how tags and class names are logged
     *
     * you can change the whole log prefix if desired; there's also an open default formatter
     * that you can use to extend your custom formatter from: [com.difft.android.base.log.lumberjack.core.DefaultFormatter]
     */
    var formatter: IFormatter = DefaultFormatter()

    /*
     * provide a custom filter to filter out logs based on content, tags, class names, ...
     *
     * by default nothing is filtered
     */
    var filter: IFilter? = null

    // --------------
    // 日志处理 - 单线程Channel保证顺序，避免Coroutine调度器锁竞争
    // --------------

    private data class LogEntry(
        val t: Throwable?,
        val t2: Throwable,
        val timestamp: Long,  // 调用时捕获的时间戳，保证时间准确性
        val logBlock: () -> Unit
    )

    // 独立的单线程调度器，避免Dispatchers.Default的Worker锁竞争
    private val logDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "L-Logger").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()

    private val logScope = CoroutineScope(logDispatcher + SupervisorJob())

    // UNLIMITED容量确保不丢失日志
    private val logChannel = Channel<LogEntry>(Channel.UNLIMITED)

    init {
        // 单个消费者顺序处理，保证日志顺序
        logScope.launch {
            for (entry in logChannel) {
                try {
                    executeLog(entry.t, entry.t2, entry.timestamp, entry.logBlock)
                } catch (e: Exception) {
                    // 单条日志失败不影响后续日志，仅在 debug 模式输出
                    if (com.difft.android.base.BuildConfig.DEBUG) {
                        android.util.Log.w("L", "[L] executeLog failed: ${e.message}")
                    }
                }
            }
        }
    }

    // --------------
    // special functions
    // --------------

    fun logIf(block: () -> Boolean): L? {
        if (block()) {
            return L
        } else {
            return null
        }
    }

    fun tag(tag: String): L {
        Timber.tag(tag)
        return L
    }

    fun callStackCorrection(value: Int): L {
        setCallStackCorrection(value)
        return L
    }

    // --------------
    // log functions - lazy
    // --------------

    @JvmStatic
    fun v(t: Throwable, message: () -> String) = log(t, t) { Timber.v(t, replaceUid(message())) }

    @JvmStatic
    fun v(t: Throwable) = log(t, t) { Timber.v(t) }

    @JvmStatic
    fun v(message: () -> String) = log(null, Throwable()) { Timber.v(replaceUid(message())) }

    @JvmStatic
    fun d(t: Throwable, message: () -> String) = log(t, t) { Timber.d(t, replaceUid(message())) }

    @JvmStatic
    fun d(t: Throwable) = log(t, t) { Timber.d(t) }

    @JvmStatic
    fun d(message: () -> String) = log(null, Throwable()) { Timber.d(replaceUid(message())) }

    @JvmStatic
    fun i(t: Throwable, message: () -> String) = log(t, t) { Timber.i(t, replaceUid(message())) }

    @JvmStatic
    fun i(t: Throwable) = log(t, t) { Timber.i(t) }

    @JvmStatic
    fun i(message: () -> String) = log(null, Throwable()) { Timber.i(replaceUid(message())) }

    @JvmStatic
    fun w(t: Throwable, message: () -> String) = log(t, t) { Timber.w(t, replaceUid(message())) }

    @JvmStatic
    fun w(t: Throwable) = log(t, t) { Timber.w(t) }

    @JvmStatic
    fun w(message: () -> String) = log(null, Throwable()) { Timber.w(replaceUid(message())) }

    @JvmStatic
    fun e(t: Throwable, message: () -> String) = log(t, t) { Timber.e(t, replaceUid(message())) }

    @JvmStatic
    fun e(t: Throwable) = log(t, t) { Timber.e(t) }

    @JvmStatic
    fun e(message: () -> String) = log(null, Throwable()) { Timber.e(replaceUid(message())) }

    @JvmStatic
    fun wtf(t: Throwable, message: () -> String) = log(t, t) { Timber.wtf(t, replaceUid(message())) }

    @JvmStatic
    fun wtf(t: Throwable) = log(t, t) { Timber.wtf(t) }

    @JvmStatic
    fun wtf(message: () -> String) = log(null, Throwable()) { Timber.wtf(replaceUid(message())) }

    @JvmStatic
    fun log(priority: Int, t: Throwable, message: () -> String) = log(t, t) { Timber.log(priority, t, replaceUid(message())) }

    @JvmStatic
    fun log(priority: Int, t: Throwable) = log(t, t) { Timber.log(priority, t) }

    @JvmStatic
    fun log(priority: Int, message: () -> String) = log(null, Throwable()) { Timber.log(priority, replaceUid(message())) }

    // --------------
    // timber forward functions
    // --------------

    inline fun asTree(): Timber.Tree = Timber.asTree()

    inline fun plant(tree: Timber.Tree) = Timber.plant(tree)

    inline fun uproot(tree: Timber.Tree) = Timber.uproot(tree)

    inline fun uprootAll() = Timber.uprootAll()

    // --------------
    // helper function
    // --------------

    /** @suppress */
    @PublishedApi
    internal fun log(t: Throwable?, t2: Throwable, logBlock: () -> Unit) {
        if (!enabled || Timber.treeCount() == 0) {
            return
        }
        processLogAsync(t, t2, logBlock)
    }

    private fun processLogAsync(t: Throwable?, t2: Throwable, logBlock: () -> Unit) {
        // 调用时立即捕获时间戳，保证时间准确性
        val timestamp = System.currentTimeMillis()
        // 非阻塞发送到Channel，UNLIMITED容量不会失败
        logChannel.trySend(LogEntry(t, t2, timestamp, logBlock))
    }

    private fun executeLog(t: Throwable?, t2: Throwable, timestamp: Long, logBlock: () -> Unit) {
        try {
            val stackTrace = StackData(t ?: t2, if (t == null) 1 else 0)
            if (filter?.isPackageNameEnabled(stackTrace.getCallingPackageName()) != false) {
                setStackTraceData(stackTrace)
                setLogTimestamp(timestamp)
                logBlock()
            }
        } catch (e: Exception) {
            // 如果StackData创建失败，降级到直接执行，仅在 debug 模式输出警告
            if (com.difft.android.base.BuildConfig.DEBUG) {
                android.util.Log.w("L", "[L] StackData creation failed: ${e.message}")
            }
            logBlock()
        }
    }

    private fun setLogTimestamp(timestamp: Long) {
        val forest = Timber.forest()
        for (tree in forest) {
            if (tree is BaseTree) {
                tree.setLogTimestamp(timestamp)
            }
        }
    }

    internal fun setCallStackCorrection(correction: Int) {
        val forest = Timber.forest()
        for (tree in forest) {
            if (tree is BaseTree) {
                tree.setCallStackCorrection(correction)
            }
        }
    }

    fun setStackTraceData(stackTraceData: StackData) {
        val forest = Timber.forest()
        for (tree in forest) {
            if (tree is BaseTree) {
                tree.setStackTrace(stackTraceData)
            }
        }
    }

    fun tag(clazz: Class<*>): String {
        val simpleName = clazz.simpleName
        return if (simpleName.length > 23) {
            simpleName.substring(0, 23)
        } else simpleName
    }

    /**
     * 日志UID脱敏
     */
    private fun replaceUid(message: String): String {
        // 定义正则表达式
        val regex = """(\+|ios|android|web|mac)(\d{7,12})(\d{3})""".toRegex()
        // 使用正则表达式查找匹配的部分
        val result = regex.replace(message) { matchResult ->
            val prefix = matchResult.groups[1]?.value // 匹配的前缀（+、ios、android、web、mac）
            val middleDigits = matchResult.groups[2]?.value // 中间的数字部分
            val lastDigits = matchResult.groups[3]?.value // 最后的3位数字

            if (prefix != null && middleDigits != null && lastDigits != null) {
                // 替换中间的数字部分为 *
                "$prefix************$lastDigits"
            } else {
                // 如果无法匹配，则返回原始字符串
                matchResult.value
            }
        }
        return result
    }
}