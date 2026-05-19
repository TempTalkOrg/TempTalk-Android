package com.difft.android.chat.jobmanager

import com.difft.android.base.utils.Base64
import com.fasterxml.jackson.annotation.JsonProperty

class Data(
    @field:JsonProperty("strings")
    @param:JsonProperty("strings")
    private val strings: Map<String, String?> = emptyMap(),

    @field:JsonProperty("stringArrays")
    @param:JsonProperty("stringArrays")
    private val stringArrays: Map<String, Array<String>> = emptyMap(),

    @field:JsonProperty("integers")
    @param:JsonProperty("integers")
    private val integers: Map<String, Int> = emptyMap(),

    @field:JsonProperty("integerArrays")
    @param:JsonProperty("integerArrays")
    private val integerArrays: Map<String, IntArray> = emptyMap(),

    @field:JsonProperty("longs")
    @param:JsonProperty("longs")
    private val longs: Map<String, Long> = emptyMap(),

    @field:JsonProperty("longArrays")
    @param:JsonProperty("longArrays")
    private val longArrays: Map<String, LongArray> = emptyMap(),

    @field:JsonProperty("floats")
    @param:JsonProperty("floats")
    private val floats: Map<String, Float> = emptyMap(),

    @field:JsonProperty("floatArrays")
    @param:JsonProperty("floatArrays")
    private val floatArrays: Map<String, FloatArray> = emptyMap(),

    @field:JsonProperty("doubles")
    @param:JsonProperty("doubles")
    private val doubles: Map<String, Double> = emptyMap(),

    @field:JsonProperty("doubleArrays")
    @param:JsonProperty("doubleArrays")
    private val doubleArrays: Map<String, DoubleArray> = emptyMap(),

    @field:JsonProperty("booleans")
    @param:JsonProperty("booleans")
    private val booleans: Map<String, Boolean> = emptyMap(),

    @field:JsonProperty("booleanArrays")
    @param:JsonProperty("booleanArrays")
    private val booleanArrays: Map<String, BooleanArray> = emptyMap(),

    @field:JsonProperty("byteArrays")
    @param:JsonProperty("byteArrays")
    private val byteArrays: Map<String, ByteArray> = emptyMap(),
) {

    fun hasString(key: String): Boolean = strings.containsKey(key)

    fun getString(key: String): String? {
        throwIfAbsent(strings, key)
        return strings[key]
    }

    fun getStringAsBlob(key: String): ByteArray? {
        val raw = getString(key) ?: return null
        return try {
            Base64.decode(raw)
        } catch (e: java.io.IOException) {
            throw AssertionError("Failed to decode Base64 string for key: $key", e)
        }
    }

    fun getStringOrDefault(key: String, defaultValue: String?): String? =
        if (hasString(key)) getString(key) else defaultValue

    fun hasStringArray(key: String): Boolean = stringArrays.containsKey(key)

    fun getStringArray(key: String): Array<String>? {
        throwIfAbsent(stringArrays, key)
        return stringArrays[key]
    }

    /**
     * Helper method for [getStringArray] that returns the value as a list.
     */
    fun getStringArrayAsList(key: String): List<String> {
        throwIfAbsent(stringArrays, key)
        return stringArrays[key]!!.toList()
    }

    fun hasInt(key: String): Boolean = integers.containsKey(key)

    fun getInt(key: String): Int {
        throwIfAbsent(integers, key)
        return integers[key]!!
    }

    fun getIntOrDefault(key: String, defaultValue: Int): Int =
        if (hasInt(key)) getInt(key) else defaultValue

    fun hasIntegerArray(key: String): Boolean = integerArrays.containsKey(key)

    fun getIntegerArray(key: String): IntArray? {
        throwIfAbsent(integerArrays, key)
        return integerArrays[key]
    }

    fun hasLong(key: String): Boolean = longs.containsKey(key)

    fun getLong(key: String): Long {
        throwIfAbsent(longs, key)
        return longs[key]!!
    }

    fun getLongOrDefault(key: String, defaultValue: Long): Long =
        if (hasLong(key)) getLong(key) else defaultValue

    fun hasLongArray(key: String): Boolean = longArrays.containsKey(key)

    fun getLongArray(key: String): LongArray? {
        throwIfAbsent(longArrays, key)
        return longArrays[key]
    }

    fun getLongArrayAsList(key: String): List<Long> {
        throwIfAbsent(longArrays, key)
        return longArrays[key]!!.toList()
    }

    fun hasFloat(key: String): Boolean = floats.containsKey(key)

    fun getFloat(key: String): Float {
        throwIfAbsent(floats, key)
        return floats[key]!!
    }

    fun getFloatOrDefault(key: String, defaultValue: Float): Float =
        if (hasFloat(key)) getFloat(key) else defaultValue

    fun hasFloatArray(key: String): Boolean = floatArrays.containsKey(key)

    fun getFloatArray(key: String): FloatArray? {
        throwIfAbsent(floatArrays, key)
        return floatArrays[key]
    }

    fun hasDouble(key: String): Boolean = doubles.containsKey(key)

    fun getDouble(key: String): Double {
        throwIfAbsent(doubles, key)
        return doubles[key]!!
    }

    fun getDoubleOrDefault(key: String, defaultValue: Double): Double =
        if (hasDouble(key)) getDouble(key) else defaultValue

    fun hasDoubleArray(key: String): Boolean = doubleArrays.containsKey(key)

    fun getDoubleArray(key: String): DoubleArray? {
        throwIfAbsent(doubleArrays, key)
        return doubleArrays[key]
    }

    fun hasBoolean(key: String): Boolean = booleans.containsKey(key)

    fun getBoolean(key: String): Boolean {
        throwIfAbsent(booleans, key)
        return booleans[key]!!
    }

    fun getBooleanOrDefault(key: String, defaultValue: Boolean): Boolean =
        if (hasBoolean(key)) getBoolean(key) else defaultValue

    fun hasBooleanArray(key: String): Boolean = booleanArrays.containsKey(key)

    fun getBooleanArray(key: String): BooleanArray? {
        throwIfAbsent(booleanArrays, key)
        return booleanArrays[key]
    }

    fun getByteArray(key: String): ByteArray? {
        throwIfAbsent(byteArrays, key)
        return byteArrays[key]
    }

    private fun throwIfAbsent(map: Map<*, *>, key: String) {
        check(map.containsKey(key)) { "Tried to retrieve a value with key '$key', but it wasn't present." }
    }

    fun buildUpon(): Builder = Builder(this)

    class Builder {
        private val strings = HashMap<String, String?>()
        private val stringArrays = HashMap<String, Array<String>>()
        private val integers = HashMap<String, Int>()
        private val integerArrays = HashMap<String, IntArray>()
        private val longs = HashMap<String, Long>()
        private val longArrays = HashMap<String, LongArray>()
        private val floats = HashMap<String, Float>()
        private val floatArrays = HashMap<String, FloatArray>()
        private val doubles = HashMap<String, Double>()
        private val doubleArrays = HashMap<String, DoubleArray>()
        private val booleans = HashMap<String, Boolean>()
        private val booleanArrays = HashMap<String, BooleanArray>()
        private val byteArrays = HashMap<String, ByteArray>()

        constructor()

        internal constructor(oldData: Data) {
            strings.putAll(oldData.strings)
            stringArrays.putAll(oldData.stringArrays)
            integers.putAll(oldData.integers)
            integerArrays.putAll(oldData.integerArrays)
            longs.putAll(oldData.longs)
            longArrays.putAll(oldData.longArrays)
            floats.putAll(oldData.floats)
            floatArrays.putAll(oldData.floatArrays)
            doubles.putAll(oldData.doubles)
            doubleArrays.putAll(oldData.doubleArrays)
            booleans.putAll(oldData.booleans)
            booleanArrays.putAll(oldData.booleanArrays)
            byteArrays.putAll(oldData.byteArrays)
        }

        fun putString(key: String, value: String?): Builder {
            strings[key] = value
            return this
        }

        fun putStringArray(key: String, value: Array<String>): Builder {
            stringArrays[key] = value
            return this
        }

        /**
         * Helper method for [putStringArray] that takes a list.
         */
        fun putStringListAsArray(key: String, value: List<String>): Builder {
            stringArrays[key] = value.toTypedArray()
            return this
        }

        fun putInt(key: String, value: Int): Builder {
            integers[key] = value
            return this
        }

        fun putIntArray(key: String, value: IntArray): Builder {
            integerArrays[key] = value
            return this
        }

        fun putLong(key: String, value: Long): Builder {
            longs[key] = value
            return this
        }

        fun putLongArray(key: String, value: LongArray): Builder {
            longArrays[key] = value
            return this
        }

        fun putLongListAsArray(key: String, value: List<Long>): Builder {
            longArrays[key] = value.toLongArray()
            return this
        }

        fun putFloat(key: String, value: Float): Builder {
            floats[key] = value
            return this
        }

        fun putFloatArray(key: String, value: FloatArray): Builder {
            floatArrays[key] = value
            return this
        }

        fun putDouble(key: String, value: Double): Builder {
            doubles[key] = value
            return this
        }

        fun putDoubleArray(key: String, value: DoubleArray): Builder {
            doubleArrays[key] = value
            return this
        }

        fun putBoolean(key: String, value: Boolean): Builder {
            booleans[key] = value
            return this
        }

        fun putBooleanArray(key: String, value: BooleanArray): Builder {
            booleanArrays[key] = value
            return this
        }

        fun putByteArray(key: String, value: ByteArray): Builder {
            byteArrays[key] = value
            return this
        }

        fun putBlobAsString(key: String, value: ByteArray?): Builder {
            strings[key] = value?.let { Base64.encodeBytes(it) }
            return this
        }

        fun build(): Data {
            return Data(
                strings,
                stringArrays,
                integers,
                integerArrays,
                longs,
                longArrays,
                floats,
                floatArrays,
                doubles,
                doubleArrays,
                booleans,
                booleanArrays,
                byteArrays,
            )
        }
    }

    interface Serializer {
        fun serialize(data: Data): String
        fun deserialize(serialized: String): Data
    }

    companion object {
        @JvmField
        val EMPTY: Data = Builder().build()
    }
}
