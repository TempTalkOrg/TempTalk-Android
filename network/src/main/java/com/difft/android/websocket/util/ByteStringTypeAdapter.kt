package com.difft.android.websocket.util

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.google.protobuf.ByteString

class ByteStringTypeAdapter : TypeAdapter<ByteString>() {

    override fun write(out: JsonWriter, value: ByteString) {
        out.value(value.toStringUtf8())
    }

    override fun read(`in`: JsonReader): ByteString {
        val value = `in`.nextString()
        return ByteString.copyFromUtf8(value)
    }
}