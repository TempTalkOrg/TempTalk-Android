/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.difft.android.base.utils

import com.difft.android.base.log.lumberjack.L
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit
import kotlin.time.measureTimedValue














class Stopwatch @JvmOverloads constructor(private val title: String, private val decimalPlaces: Int = 0) {
    private val startTimeNanos: Long = System.nanoTime()
    private val splits: MutableList<Split> = mutableListOf()

    


    fun split(label: String) {
        val now = System.nanoTime()

        val previousTime = if (splits.isEmpty()) {
            startTimeNanos
        } else {
            splits.last().nanoTime
        }

        splits += Split(
            nanoTime = now,
            durationNanos = now - previousTime,
            label = label
        )
    }

    


    fun stop(tag: String) {
        L.i { "[$tag] ${stopAndGetLogString()}" }
    }

    


    private fun stopAndGetLogString(): String {
        val now = System.nanoTime()

        splits += Split(
            nanoTime = now,
            durationNanos = now - startTimeNanos,
            label = "total"
        )

        val splitString = splits
            .joinToString(separator = ", ", transform = { it.displayString(decimalPlaces) })

        return "[$title] $splitString"
    }

    private data class Split(val nanoTime: Long, val durationNanos: Long, val label: String) {
        fun displayString(decimalPlaces: Int): String {
            val timeMs: String = durationNanos.nanoseconds.toDouble(DurationUnit.MILLISECONDS).roundedString(decimalPlaces)
            return "$label: $timeMs"
        }
    }
}




inline fun <T> logTime(tag: String, decimalPlaces: Int = 0, block: () -> T): T {
    val result = measureTimedValue(block)
    L.d { "[$tag]: ${result.duration.toDouble(DurationUnit.MILLISECONDS).roundedString(decimalPlaces)}" }
    return result.value
}
