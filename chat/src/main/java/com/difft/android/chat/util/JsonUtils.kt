package com.difft.android.chat.util

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.IOException
import java.io.InputStream
import java.io.Reader

object JsonUtils {

    private val objectMapper: ObjectMapper = ObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING)
        enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING)
        registerKotlinModule()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun <T> fromJson(serialized: ByteArray, clazz: Class<T>): T = fromJson(String(serialized), clazz)

    @JvmStatic
    @Throws(IOException::class)
    fun <T> fromJson(serialized: String, clazz: Class<T>): T = objectMapper.readValue(serialized, clazz)

    @JvmStatic
    @Throws(IOException::class)
    fun <T> fromJson(serialized: InputStream, clazz: Class<T>): T = objectMapper.readValue(serialized, clazz)

    @JvmStatic
    @Throws(IOException::class)
    fun <T> fromJson(serialized: Reader, clazz: Class<T>): T = objectMapper.readValue(serialized, clazz)

    @JvmStatic
    @Throws(IOException::class)
    fun toJson(obj: Any): String = objectMapper.writeValueAsString(obj)
}
