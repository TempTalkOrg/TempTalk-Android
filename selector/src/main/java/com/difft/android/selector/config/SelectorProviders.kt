package com.difft.android.selector.config

import java.util.LinkedList

class SelectorProviders {

    private val selectionConfigsQueue = LinkedList<SelectorConfig>()

    fun addSelectorConfigQueue(config: SelectorConfig) {
        selectionConfigsQueue.add(config)
    }

    /** Exposed as a property so Java sees getSelectorConfig() and Kotlin can use `.selectorConfig`. */
    val selectorConfig: SelectorConfig
        get() = if (selectionConfigsQueue.size > 0) selectionConfigsQueue.last() else SelectorConfig()

    fun destroy() {
        val config = selectorConfig
        config.destroy()
        selectionConfigsQueue.remove(config)
    }

    fun reset() {
        for (i in selectionConfigsQueue.indices) {
            selectionConfigsQueue[i].destroy()
        }
        selectionConfigsQueue.clear()
    }

    companion object {
        @Volatile
        private var selectorProviders: SelectorProviders? = null

        @JvmStatic
        fun getInstance(): SelectorProviders {
            if (selectorProviders == null) {
                synchronized(SelectorProviders::class.java) {
                    if (selectorProviders == null) {
                        selectorProviders = SelectorProviders()
                    }
                }
            }
            return selectorProviders!!
        }
    }
}
