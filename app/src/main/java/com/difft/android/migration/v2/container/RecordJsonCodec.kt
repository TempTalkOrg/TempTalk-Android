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

/**
 * JSONL codec for [NeutralRecord]: one record per JSON line, plus the [ContainerManifest] as a JSON
 * object. Stateless and thread-safe.
 *
 * Each line is a self-describing tagged-union envelope `{"type":"<TAG>","value":{…entity…}}`, so the
 * importer routes by variant with no positional assumptions and the codec stays independent of field
 * order.
 */
internal class RecordJsonCodec {

    private val gson: Gson = GsonBuilder()
        // Serialize nulls so optional sub-structures round-trip as explicit null rather than
        // being dropped (the importer/verifier can then distinguish absent vs default).
        .serializeNulls()
        // An unrecognized contentKind from a newer MINOR must land on the explicit UNKNOWN value.
        // Gson's default enum adapter yields null, bypassing Kotlin non-null, and would NPE deep in
        // the importer instead of taking the skip path.
        .registerTypeAdapter(ContentKind::class.java, ContentKindAdapter())
        // Optional Long fields (e.g. serverAttachmentId) accept a decimal string; a non-numeric value
        // reads as absent instead of failing the record. Primitive Long fields stay strict.
        .registerTypeAdapter(java.lang.Long::class.java, LenientLongAdapter())
        .create()

    /** Encode the manifest to a compact JSON string (single line, no trailing newline). */
    fun encodeManifest(manifest: ContainerManifest): String = gson.toJson(manifest)

    /** Decode a manifest JSON string. */
    fun decodeManifest(json: String): ContainerManifest =
        gson.fromJson(json, ContainerManifest::class.java)

    /** Encode one [NeutralRecord] to a single JSONL line; compact Gson never emits a newline. */
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

    /** Outcome of decoding one record line. */
    sealed interface Decoded {
        class Record(val record: NeutralRecord) : Decoded

        /**
         * The line is skipped: its `type` is not one this build knows, or its value did not decode.
         * [ownsBytes] is true for an attachment record, whose byte frame must be drained with it.
         * [mustNotSkip] is true for a record this build promised to import (a pending envelope,
         * #1382): the reader fails the import instead of skipping it.
         */
        class Skipped(
            val tag: String,
            val ownsBytes: Boolean,
            val undecodable: Boolean,
            val reason: String,
            val mustNotSkip: Boolean = false,
        ) : Decoded
    }

    /**
     * Decode one JSONL line. Only a line that is not a tagged envelope throws — that is corruption.
     * An unknown tag or a value that does not decode returns [Decoded.Skipped]; frames are
     * length-prefixed, so skipping keeps the stream in sync.
     */
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

    /**
     * [decode] for callers that want the record or null: an unknown tag reports to [onUnknownTag] and
     * returns null; a value that does not decode throws its cause.
     */
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

    /**
     * Strict, unlike the other records: Gson leaves a missing field null or 0 behind Kotlin's
     * non-null types, and a pending envelope with no id or bytes cannot be applied (#1382).
     */
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

    /**
     * [ContentKind] wire adapter: writes the enum name; reads unknown names (and null) as
     * [ContentKind.UNKNOWN] instead of Gson's default null. [ContentKind.UNKNOWN] itself is
     * decode-only and must never be exported — writing it fails fast (an exporter bug, not data).
     */
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

    /**
     * Boxed `Long?` wire adapter: reads a number or a decimal string; anything else (a non-numeric
     * string, a fraction, an object) reads as null with one warn line, so one bad optional field
     * never fails its record.
     */
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

        /** Frozen wire tag for the account identity record. */
        const val TAG_IDENTITY = "identity"

        /**
         * Frozen wire tag for the session-credential record (same-device no-login handoff). Only a
         * loopback handoff container carries it; every other reader skips it as an unknown tag.
         */
        const val TAG_CREDENTIAL = "credential"

        /** Frozen wire tag for [NeutralPendingEnvelope]; skipped by a reader that predates it. */
        const val TAG_PENDING_ENVELOPE = "pendingEnvelope"
    }
}

/** The `messageEnvelopBytes` text encoding of [NeutralPendingEnvelope]: standard base64, padded, no wrap. */
internal object PendingEnvelopeBytes {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Throws [IllegalArgumentException] on text that is not base64. */
    fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}
