package com.difft.android.chat.jobmanager.impl

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.jobmanager.Data
import com.difft.android.chat.util.JsonUtils
import java.io.IOException

class JsonDataSerializer : Data.Serializer {

    override fun serialize(data: Data): String {
        return try {
            JsonUtils.toJson(data)
        } catch (e: IOException) {
            L.e(e) { "Failed to serialize to JSON." }
            throw AssertionError(e)
        }
    }

    override fun deserialize(serialized: String): Data {
        return try {
            JsonUtils.fromJson(serialized, Data::class.java)
        } catch (e: IOException) {
            L.e(e) { "Failed to deserialize JSON." }
            throw AssertionError(e)
        }
    }
}
