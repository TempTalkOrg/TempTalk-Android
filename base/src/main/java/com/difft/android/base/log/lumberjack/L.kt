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




@Suppress("NOTHING_TO_INLINE")
object L {

    
    
    

    


    var enabled = true

    



    internal val advancedLambdaLogging = false

    





    var formatter: IFormatter = DefaultFormatter()

    




    var filter: IFilter? = null

    
    
    

    private data class LogEntry(
        val t: Throwable?,
        val t2: Throwable,
        val timestamp: Long,  
        val logBlock: () -> Unit
    )

    
    private val logDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "L-Logger").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()

    private val logScope = CoroutineScope(logDispatcher + SupervisorJob())

    
    private val logChannel = Channel<LogEntry>(Channel.UNLIMITED)

    init {
        
        logScope.launch {
            for (entry in logChannel) {
                try {
                    executeLog(entry.t, entry.t2, entry.timestamp, entry.logBlock)
                } catch (e: Exception) {
                    
                    if (com.difft.android.base.BuildConfig.DEBUG) {
                        android.util.Log.w("L", "[L] executeLog failed: ${e.message}")
                    }
                }
            }
        }
    }

    
    
    

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

    
    
    

    inline fun asTree(): Timber.Tree = Timber.asTree()

    inline fun plant(tree: Timber.Tree) = Timber.plant(tree)

    inline fun uproot(tree: Timber.Tree) = Timber.uproot(tree)

    inline fun uprootAll() = Timber.uprootAll()

    
    
    

    
    @PublishedApi
    internal fun log(t: Throwable?, t2: Throwable, logBlock: () -> Unit) {
        if (!enabled || Timber.treeCount == 0) {
            return
        }
        processLogAsync(t, t2, logBlock)
    }

    private fun processLogAsync(t: Throwable?, t2: Throwable, logBlock: () -> Unit) {
        
        val timestamp = System.currentTimeMillis()
        
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

    


    fun replaceUid(message: String): String {
        val regex = """(\+|ios|android|web|mac)(\d{7,12})(\d{3})""".toRegex()
        val result = regex.replace(message) { matchResult ->
            val prefix = matchResult.groups[1]?.value
            val middleDigits = matchResult.groups[2]?.value
            val lastDigits = matchResult.groups[3]?.value

            if (prefix != null && middleDigits != null && lastDigits != null) {
                "$prefix************$lastDigits"
            } else {
                matchResult.value
            }
        }
        return result
    }
}