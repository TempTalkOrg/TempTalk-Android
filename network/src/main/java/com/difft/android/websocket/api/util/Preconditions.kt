package com.difft.android.websocket.api.util

/**
 * Convenient ways to assert expected state.
 */
object Preconditions {

    @JvmStatic
    @JvmOverloads
    fun checkArgument(state: Boolean, message: String = "Condition must be true!") {
        if (!state) {
            throw IllegalArgumentException(message)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun checkState(state: Boolean, message: String = "Condition must be true!") {
        if (!state) {
            throw IllegalStateException(message)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun <E> checkNotNull(obj: E?, message: String = "Must not be null!"): E {
        if (obj == null) {
            throw NullPointerException(message)
        } else {
            return obj
        }
    }
}
