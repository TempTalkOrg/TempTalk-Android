package com.difft.android.websocket.util

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.google.protobuf.ByteString

class ByteStringTypeAdapter : TypeAdapter<ByteString>() {

    override fun write(out: JsonWriter, value: ByteString) {
        // 将 ByteString 作为 Base64 编码的字符串写入 JSON
        out.value(value.toStringUtf8())
    }

    override fun read(`in`: JsonReader): ByteString {
        // 从 JSON 中读取 Base64 编码的字符串，并将其转换为 ByteString
        val value = `in`.nextString()
        return ByteString.copyFromUtf8(value)
    }
}