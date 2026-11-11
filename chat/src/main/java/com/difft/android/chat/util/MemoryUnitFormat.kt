package com.difft.android.chat.util

import java.text.DecimalFormat




enum class MemoryUnitFormat(private val unitString: String) {
    BYTES(" B"),
    KILO_BYTES(" kB"),
    MEGA_BYTES(" MB"),
    GIGA_BYTES(" GB"),
    TERA_BYTES(" TB");

    fun fromBytes(bytes: Long): Double = bytes / Math.pow(1000.0, ordinal.toDouble())

    companion object {
        private val ONE_DP = DecimalFormat("#,##0.0")
        private val OPTIONAL_ONE_DP = DecimalFormat("#,##0.#")

        






        @JvmStatic
        @JvmOverloads
        fun formatBytes(bytes: Long, minimumUnit: MemoryUnitFormat = BYTES, forceOneDp: Boolean = false): String {
            var value = bytes
            if (value <= 0) value = 0

            var ordinal = if (value != 0L) (Math.log10(value.toDouble()) / 3).toInt() else 0

            if (ordinal >= values().size) {
                ordinal = values().size - 1
            }

            var unit = values()[ordinal]

            if (unit.ordinal < minimumUnit.ordinal) {
                unit = minimumUnit
            }

            return (if (forceOneDp) ONE_DP else OPTIONAL_ONE_DP).format(unit.fromBytes(value)) + unit.unitString
        }
    }
}
