package com.difft.android.base.log.lumberjack.data

import android.annotation.SuppressLint
import com.difft.android.base.log.lumberjack.L
import java.util.regex.Pattern

data class StackData(
    val stackTrace: List<StackTraceElement>,
    val callStackIndex: Int,
    val disableLine: Boolean
) {

    constructor(
        t: Throwable,
        callStackIndex: Int,
        disableLine: Boolean = false
    ) : this(t.stackTrace.toList(), callStackIndex, disableLine)

    companion object {
        private val ANONYMOUS_CLASS = Pattern.compile("(\\$\\d+)+$")
    }

    var element: StackTraceElement? = null
        private set
    var element2: StackTraceElement? = null
        private set

    val className by lazy {
        getClassName(element)
    }

    val className2 by lazy {
        getClassName(element2)
    }

    init {
        val index = callStackIndex
        element = getElement(stackTrace, index)
        if (L.advancedLambdaLogging) {
            
            val split = element?.className?.split("$")
            if (split?.lastOrNull()?.toIntOrNull() != null) {
                
                
                
                element2 = element
                element = getElement(stackTrace, index + 2)
            }
        }
    }

    
    
    

    fun getLink(): String {
        var link = "(${element?.fileName}${if (disableLine) "" else ":${element?.lineNumber}"})"
        
        element2?.let {
            link += " | (${it.fileName}${if (disableLine) "" else ":${it.lineNumber}"})"
        }
        return link
    }

    fun getCallingPackageName(): String {
        return className
    }

    
    
    

    @SuppressLint("LogNotTimber")
    private fun getElement(stackTrace: List<StackTraceElement>, index: Int): StackTraceElement? {
        if (stackTrace.isEmpty())
            return null
        var i = index
        if (index >= stackTrace.size) {
            i = stackTrace.size - 1
            L.e { "Synthetic stacktrace didn't have enough elements: are you using proguard?" }
        }
        return stackTrace[i]
    }

    private fun getClassName(element: StackTraceElement?): String {
        if (element == null)
            return ""
        var tag = element.className
        val m = ANONYMOUS_CLASS.matcher(tag)
        if (m.find()) {
            tag = m.replaceAll("")
        }
        return tag
    }
}