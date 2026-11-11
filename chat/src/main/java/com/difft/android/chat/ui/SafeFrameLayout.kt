package com.difft.android.chat.ui

import android.content.Context
import android.content.res.Resources
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewParent
import android.widget.FrameLayout
import com.difft.android.base.log.lumberjack.L







class SafeFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    



    private var lastReportedAtMs: Long = 0L

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        try {
            super.onLayout(changed, left, top, right, bottom)
        } catch (e: NullPointerException) {
            
            
            
            
            reportThrottled(e)
        }
    }

    private fun reportThrottled(e: NullPointerException) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReportedAtMs < REPORT_COOLDOWN_MS) return
        lastReportedAtMs = now

        
        
        
        try {
            val idLabel = resolveIdLabel()
            val parentChain = parentChainSnapshot()
            val childSnapshot = childSnapshot()
            val msg = "[SafeFrameLayout] onLayout NPE swallowed " +
                    "id=$idLabel parents=$parentChain childCount=$childCount $childSnapshot"
            L.e { "$msg: ${e.stackTraceToString()}" }
        } catch (t: Exception) {
            
            
            L.e { "[SafeFrameLayout] reportThrottled failed: ${t.stackTraceToString()}" }
        }
    }

    
    
    private fun parentChainSnapshot(): String {
        val sb = StringBuilder()
        var p: ViewParent? = parent
        var hops = 0
        while (p != null && hops < MAX_PARENT_HOPS) {
            if (sb.isNotEmpty()) sb.append('>')
            val view = p as? View
            sb.append(view?.javaClass?.simpleName ?: p.javaClass.simpleName)
            view?.id?.takeIf { it != NO_ID }?.let { id ->
                sb.append('#').append(safeResourceName(id))
            }
            p = p.parent
            hops++
        }
        return sb.ifEmpty { "(no-parent)" }.toString()
    }

    private fun childSnapshot(): String {
        val sb = StringBuilder("children=[")
        for (i in 0 until childCount) {
            if (i > 0) sb.append(',')
            val c = try {
                getChildAt(i)
            } catch (_: Exception) {
                null
            }
            if (c == null) {
                sb.append("null")
            } else {
                sb.append(c.javaClass.simpleName)
                sb.append('@').append(visibilityChar(c.visibility))
            }
        }
        sb.append(']')
        return sb.toString()
    }

    private fun visibilityChar(v: Int): Char = when (v) {
        VISIBLE -> 'V'
        INVISIBLE -> 'I'
        GONE -> 'G'
        else -> '?'
    }

    private fun safeResourceName(id: Int): String =
        try {
            resources.getResourceEntryName(id)
        } catch (_: Resources.NotFoundException) {
            "0x${Integer.toHexString(id)}"
        } catch (_: NullPointerException) {
            "0x${Integer.toHexString(id)}"
        }

    private fun resolveIdLabel(): String =
        if (id == NO_ID) "NO_ID" else safeResourceName(id)

    companion object {
        
        private const val REPORT_COOLDOWN_MS = 10_000L

        
        private const val MAX_PARENT_HOPS = 8
    }
}
