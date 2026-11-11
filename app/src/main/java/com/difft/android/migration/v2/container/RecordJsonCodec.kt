package com.difft.android.migration.v2.container

import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.v2.model.ContentKind
import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralConversationLocal
import com.difft.android.migration.v2.model.NeutralCredential
import com.difft.android.migration.v2.model.NeutralGroupKey
import com.difft.android.migration.v2.model.NeutralIdentity
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralPendingEnvelope
import com.difft.android.migration.v2.model.NeutralRecord
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.util.Base64









internal class RecordJsonCodec {

    private val gson: Gson = GsonBuilder()
        
        
        .serializeNulls()
        
        
        
        .registerTypeAdapter(ContentKind::class.java, ContentKindAdapter())
        
        
        .registerTypeAdapter(java.lang.Long::class.java, LenientLongAdapter())
        .create()

    
    fun encodeManifest(manifest: ContainerManifest): String = gson.toJson(manifest)

    
    fun decodeManifest(json: String): ContainerManifest =
        gson.fromJson(json, ContainerManifest::class.java)

    
    fun encodeRecord(record: NeutralRecord): String {
        val tag = tagOf(record)
        val value: Any = when (record) {
            is NeutralRecord.Message -> record.value
            is NeutralRecord.Attachment -> record.value
            is NeutralRecord.ConversationLocal -> record.value
            is NeutralRecord.GroupKey -> record.value
            is NeutralRecord.Identity -> record.value
            is NeutralRecord.Credential -> record.value
            is NeutralRecord.PendingEnvelope -> record.value
        }
        val envelope = JsonObject().apply {
            addProperty(KEY_TYPE, tag)
            add(KEY_VALUE, gson.toJsonTree(value))
        }
        return gson.toJson(envelope)
    }

    
    sealed interface Decoded {
        class Record(val record: NeutralRecord) : Decoded

        





        class Skipped(
            val tag: String,
            val ownsBytes: Boolean,
            val undecodable: Boolean,
            val reason: String,
            val mustNotSkip: Boolean = false,
        ) : Decoded
    }

    




    fun decode(line: String): Decoded {
        val envelope = JsonParser.parseString(line).asJsonObject
        val tag = envelope.get(KEY_TYPE).asString
        val value = envelope.get(KEY_VALUE)
        return try {
            val record = decodeValue(tag, value)
                ?: return Decoded.Skipped(tag, ownsBytes = false, undecodable = false, reason = "unknown type")
            Decoded.Record(record.also(AbsentDefaults::restore))
        } catch (e: RuntimeException) {
            Decoded.Skipped(
                tag,
                ownsBytes = tag == TAG_ATTACHMENT,
                undecodable = true,
                reason = e.message ?: e.javaClass.simpleName,
                mustNotSkip = tag == TAG_PENDING_ENVELOPE,
            )
        }
    }

    



    fun decodeRecord(line: String, onUnknownTag: (String) -> Unit = {}): NeutralRecord? =
        when (val decoded = decode(line)) {
            is Decoded.Record -> decoded.record
            is Decoded.Skipped -> {
                if (decoded.undecodable) throw IllegalArgumentException("record ${decoded.tag} did not decode: ${decoded.reason}")
                onUnknownTag(decoded.tag)
                null
            }
        }

    private fun decodeValue(tag: String, value: JsonElement): NeutralRecord? =
        when (tag) {
            TAG_MESSAGE -> NeutralRecord.Message(gson.fromJson(value, NeutralMessage::class.java))
            TAG_ATTACHMENT -> NeutralRecord.Attachment(gson.fromJson(value, NeutralAttachment::class.java))
            TAG_CONVERSATION_LOCAL -> NeutralRecord.ConversationLocal(gson.fromJson(value, NeutralConversationLocal::class.java))
            TAG_GROUP_KEY -> NeutralRecord.GroupKey(gson.fromJson(value, NeutralGroupKey::class.java))
            TAG_IDENTITY -> NeutralRecord.Identity(gson.fromJson(value, NeutralIdentity::class.java))
            TAG_CREDENTIAL -> NeutralRecord.Credential(gson.fromJson(value, NeutralCredential::class.java))
            TAG_PENDING_ENVELOPE -> NeutralRecord.PendingEnvelope(pendingEnvelope(value))
            else -> null
        }

    



    private fun pendingEnvelope(value: JsonElement): NeutralPendingEnvelope {
        val decoded = gson.fromJson(value, NeutralPendingEnvelope::class.java)
            ?: throw IllegalArgumentException("pending envelope is null")
        @Suppress("SENSELESS_COMPARISON")
        require(decoded.messageId != null && decoded.messageId.isNotBlank()) { "pending envelope has no messageId" }
        require(decoded.originalMessageTimeStamp > 0) { "pending envelope has no originalMessageTimeStamp" }
        @Suppress("SENSELESS_COMPARISON")
        require(decoded.messageEnvelopBytes != null) { "pending envelope has no messageEnvelopBytes" }
        require(PendingEnvelopeBytes.decode(decoded.messageEnvelopBytes).isNotEmpty()) { "pending envelope bytes are empty" }
        return decoded
    }

    private fun tagOf(record: NeutralRecord): String = when (record) {
        is NeutralRecord.Message -> TAG_MESSAGE
        is NeutralRecord.Attachment -> TAG_ATTACHMENT
        is NeutralRecord.ConversationLocal -> TAG_CONVERSATION_LOCAL
        is NeutralRecord.GroupKey -> TAG_GROUP_KEY
        is NeutralRecord.Identity -> TAG_IDENTITY
        is NeutralRecord.Credential -> TAG_CREDENTIAL
        is NeutralRecord.PendingEnvelope -> TAG_PENDING_ENVELOPE
    }

    




    private class ContentKindAdapter : TypeAdapter<ContentKind>() {
        override fun write(out: JsonWriter, value: ContentKind?) {
            require(value != ContentKind.UNKNOWN) { "ContentKind.UNKNOWN is decode-only" }
            if (value == null) out.nullValue() else out.value(value.name)
        }

        override fun read(reader: JsonReader): ContentKind {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                return ContentKind.UNKNOWN
            }
            val name = reader.nextString()
            return ContentKind.entries.firstOrNull { it.name == name } ?: ContentKind.UNKNOWN
        }
    }

    




    private class LenientLongAdapter : TypeAdapter<Long?>() {
        override fun write(out: JsonWriter, value: Long?) {
            if (value == null) out.nullValue() else out.value(value)
        }

        override fun read(reader: JsonReader): Long? = when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }
            JsonToken.NUMBER, JsonToken.STRING -> {
                val raw = reader.nextString()
                raw.toLongOrNull().also { if (it == null) L.w { "[MigrationV2][Codec] non-numeric long len=${raw.length} read as absent" } }
            }
            else -> {
                reader.skipValue()
                L.w { "[MigrationV2][Codec] non-scalar long read as absent" }
                null
            }
        }
    }

    private companion object {
        const val KEY_TYPE = "type"
        const val KEY_VALUE = "value"

        const val TAG_MESSAGE = "message"
        const val TAG_ATTACHMENT = "attachment"
        const val TAG_CONVERSATION_LOCAL = "conversationLocal"
        const val TAG_GROUP_KEY = "groupKey"

        
        const val TAG_IDENTITY = "identity"

        



        const val TAG_CREDENTIAL = "credential"

        
        const val TAG_PENDING_ENVELOPE = "pendingEnvelope"
    }
}


internal object PendingEnvelopeBytes {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    
    fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}
