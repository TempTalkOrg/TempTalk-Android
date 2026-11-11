package com.difft.android.chat.jobmanager

import com.difft.android.chat.util.JsonUtils
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue





class DataSerializationCompatTest {
    

    private fun buildOldJavaJson(
        strings: String = "{}",
        stringArrays: String = "{}",
        integers: String = "{}",
        integerArrays: String = "{}",
        longs: String = "{}",
        longArrays: String = "{}",
        floats: String = "{}",
        floatArrays: String = "{}",
        doubles: String = "{}",
        doubleArrays: String = "{}",
        booleans: String = "{}",
        booleanArrays: String = "{}",
        byteArrays: String = "{}",
    ): String = """
        {
          "strings": $strings,
          "stringArrays": $stringArrays,
          "integers": $integers,
          "integerArrays": $integerArrays,
          "longs": $longs,
          "longArrays": $longArrays,
          "floats": $floats,
          "floatArrays": $floatArrays,
          "doubles": $doubles,
          "doubleArrays": $doubleArrays,
          "booleans": $booleans,
          "booleanArrays": $booleanArrays,
          "byteArrays": $byteArrays
        }
    """.trimIndent()
    

    @Test
    fun `normal roundtrip preserves all data types`() {
        val original = Data.Builder()
            .putString("key", "value")
            .putInt("num", 42)
            .putLong("big", 123456789L)
            .putBoolean("flag", true)
            .putFloat("pi", 3.14f)
            .putDouble("e", 2.718281828)
            .putByteArray("bytes", byteArrayOf(1, 2, 3))
            .putLongArray("arr", longArrayOf(10L, 20L))
            .putStringArray("tags", arrayOf("a", "b"))
            .putIntArray("ids", intArrayOf(100, 200))
            .putFloatArray("ratios", floatArrayOf(0.5f, 1.5f))
            .putDoubleArray("coords", doubleArrayOf(1.0, 2.0))
            .putBooleanArray("flags", booleanArrayOf(true, false))
            .build()

        val json = JsonUtils.toJson(original)
        val restored = JsonUtils.fromJson(json, Data::class.java)

        assertEquals("value", restored.getString("key"))
        assertEquals(42, restored.getInt("num"))
        assertEquals(123456789L, restored.getLong("big"))
        assertTrue(restored.getBoolean("flag"))
        assertEquals(3.14f, restored.getFloat("pi"))
        assertEquals(2.718281828, restored.getDouble("e"))
        assertContentEquals(byteArrayOf(1, 2, 3), restored.getByteArray("bytes"))
        assertContentEquals(longArrayOf(10L, 20L), restored.getLongArray("arr"))
        assertContentEquals(arrayOf("a", "b"), restored.getStringArray("tags"))
        assertContentEquals(intArrayOf(100, 200), restored.getIntegerArray("ids"))
        assertContentEquals(floatArrayOf(0.5f, 1.5f), restored.getFloatArray("ratios"))
        assertContentEquals(doubleArrayOf(1.0, 2.0), restored.getDoubleArray("coords"))
        assertContentEquals(booleanArrayOf(true, false), restored.getBooleanArray("flags"))
    }
    

    @Test
    fun `old Java JSON with all 13 fields deserializes correctly`() {
        val json = buildOldJavaJson(
            strings = """{"greeting": "hello", "name": "world"}""",
            longs = """{"timestamp": 1700000000}""",
            booleans = """{"active": true}""",
            integers = """{"count": 5}""",
            floats = """{"ratio": 0.75}""",
            doubles = """{"precise": 3.141592653589793}""",
        )

        val data = JsonUtils.fromJson(json, Data::class.java)

        assertEquals("hello", data.getString("greeting"))
        assertEquals("world", data.getString("name"))
        assertEquals(1700000000L, data.getLong("timestamp"))
        assertTrue(data.getBoolean("active"))
        assertEquals(5, data.getInt("count"))
        assertEquals(0.75f, data.getFloat("ratio"))
        assertEquals(3.141592653589793, data.getDouble("precise"))

        
        assertFalse(data.hasStringArray("anything"))
        assertFalse(data.hasLongArray("anything"))
        assertFalse(data.hasByteArray("anything"))
    }

    private fun Data.hasByteArray(key: String): Boolean = try {
        getByteArray(key)
        true
    } catch (_: IllegalStateException) {
        false
    }
    

    @Test
    fun `JSON with only 2 of 13 fields deserializes without crash`() {
        val json = """
            {
              "strings": {"key": "value"},
              "longs": {"big": 123}
            }
        """.trimIndent()

        val data = JsonUtils.fromJson(json, Data::class.java)

        
        assertEquals("value", data.getString("key"))
        assertEquals(123L, data.getLong("big"))

        
        assertFalse(data.hasInt("anything"))
        assertFalse(data.hasBoolean("anything"))
        assertFalse(data.hasFloat("anything"))
        assertFalse(data.hasDouble("anything"))
        assertFalse(data.hasStringArray("anything"))
        assertFalse(data.hasLongArray("anything"))
    }
    

    @Test
    fun `JSON with unknown extra fields deserializes without error`() {
        val json = """
            {
              "strings": {"key": "value"},
              "stringArrays": {},
              "integers": {},
              "integerArrays": {},
              "longs": {},
              "longArrays": {},
              "floats": {},
              "floatArrays": {},
              "doubles": {},
              "doubleArrays": {},
              "booleans": {},
              "booleanArrays": {},
              "byteArrays": {},
              "unknownField": "should be ignored",
              "anotherUnknown": 42,
              "nestedUnknown": {"a": 1}
            }
        """.trimIndent()

        val data = JsonUtils.fromJson(json, Data::class.java)

        assertEquals("value", data.getString("key"))
    }
    

    @Test
    fun `empty JSON object deserializes without crash`() {
        val json = "{}"

        val data = JsonUtils.fromJson(json, Data::class.java)

        
        assertFalse(data.hasString("anything"))
        assertFalse(data.hasInt("anything"))
        assertFalse(data.hasLong("anything"))
        assertFalse(data.hasBoolean("anything"))
        assertFalse(data.hasFloat("anything"))
        assertFalse(data.hasDouble("anything"))
    }
    

    @Test
    fun `PushTextSendJob old serialized format deserializes correctly`() {
        
        
        
        
        val textMessageJson = """{"id":"msg-123","content":"Hello"}"""
        val escapedTextMessageJson = textMessageJson
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val json = buildOldJavaJson(
            strings = """{"message_out": "$escapedTextMessageJson", "notification": ""}""",
        )

        val data = JsonUtils.fromJson(json, Data::class.java)

        assertEquals(textMessageJson, data.getString("message_out"))
        assertEquals("", data.getString("notification"))
        assertFalse(data.hasLong("anything"))
        assertFalse(data.hasBoolean("anything"))
    }
    

    @Test
    fun `DownloadAttachmentJob old serialized format deserializes correctly`() {
        
        
        
        
        
        val fileKeyBase64 = "AQID" 
        val json = buildOldJavaJson(
            strings = """{"message_id": "msg-456", "attachment_id": "att-789", "file_path": "/data/files/image.jpg"}""",
            longs = """{"authorized_id": 1001}""",
            byteArrays = """{"file_key": "$fileKeyBase64"}""",
            booleans = """{"should_decrypt": true, "auto_save": false}""",
        )

        val data = JsonUtils.fromJson(json, Data::class.java)

        assertEquals("msg-456", data.getString("message_id"))
        assertEquals("att-789", data.getString("attachment_id"))
        assertEquals("/data/files/image.jpg", data.getString("file_path"))
        assertEquals(1001L, data.getLong("authorized_id"))
        assertTrue(data.getBoolean("should_decrypt"))
        assertFalse(data.getBoolean("auto_save"))
        
        assertContentEquals(byteArrayOf(1, 2, 3), data.getByteArray("file_key"))
    }
    

    @Test
    fun `PushReadReceiptSendJob old serialized format deserializes correctly`() {
        
        
        
        
        val json = buildOldJavaJson(
            strings = """{
                "recipient_id": "user-abc",
                "message_read_position": "{\"position\":5}",
                "message_message_mode": "{\"mode\":\"normal\"}",
                "message_conversation_id": "conv-xyz"
            }""",
            longArrays = """{"message_sent_timestamps": [1700000001, 1700000002, 1700000003]}""",
            booleans = """{"send_receipt_to_sender": true, "send_sync_to_self": false}""",
        )

        val data = JsonUtils.fromJson(json, Data::class.java)

        assertEquals("user-abc", data.getString("recipient_id"))
        assertEquals("{\"position\":5}", data.getString("message_read_position"))
        assertEquals("{\"mode\":\"normal\"}", data.getString("message_message_mode"))
        assertEquals("conv-xyz", data.getString("message_conversation_id"))
        assertContentEquals(
            longArrayOf(1700000001L, 1700000002L, 1700000003L),
            data.getLongArray("message_sent_timestamps"),
        )
        assertTrue(data.getBoolean("send_receipt_to_sender"))
        assertFalse(data.getBoolean("send_sync_to_self"))
    }
}
