package com.difft.android.base.storage.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Wire-format lock-in tests for the encrypted DataStore payload schemas.
 *
 * Purpose: protect deployed users from accidental wire-format drift when
 * fields are added, removed, or reordered in [UserAuthData] / [GlobalConfigData].
 *
 * The hex constants below are the byte-exact ProtoBuf encoding of the
 * fixtures, captured from the implementation that shipped in PR #789
 * (issue #725 — initial DataStore migration). Any change to field ordering,
 * `@ProtoNumber` values, or default-value encoding behaviour that breaks
 * these constants is a **compatibility break** with already-installed apps
 * and must be evaluated explicitly (e.g. via a schema-version migration),
 * NOT silently merged.
 *
 * Migration safety note: when adding `@ProtoNumber(N)` annotations, set N to
 * match the implicit declaration-order tag from the unannotated schema. The
 * wire format must remain byte-identical to what `secure_user.pb` /
 * `secure_config.pb` on deployed devices already contain.
 */
@OptIn(ExperimentalSerializationApi::class)
class SchemaWireFormatTest {

    @Test
    fun `UserAuthData fixture wire format is byte-stable`() {
        val sample = UserAuthData(
            account = "alice",
            baseAuth = "auth-token",
            microToken = "micro-tok",
            aciIdentityKeyGenTime = 12345L,
            migrationV1Completed = true,
        )
        val encoded = ProtoBuf.encodeToByteArray(sample).toHexString()
        assertEquals(USER_AUTH_DATA_FIXTURE_HEX, encoded)

        // Round-trip preservation guards against silent field-shape changes.
        val decoded = ProtoBuf.decodeFromByteArray<UserAuthData>(
            encoded.hexToByteArray()
        )
        assertEquals(sample, decoded)
    }

    @Test
    fun `GlobalConfigData fixture wire format is byte-stable`() {
        val sample = GlobalConfigData(
            config = "{\"v\":1}",
            callServiceUrlStateV3 = "{\"url\":\"chat\"}",
            migrationV1Completed = true,
        )
        val encoded = ProtoBuf.encodeToByteArray(sample).toHexString()
        assertEquals(GLOBAL_CONFIG_DATA_FIXTURE_HEX, encoded)

        val decoded = ProtoBuf.decodeFromByteArray<GlobalConfigData>(
            encoded.hexToByteArray()
        )
        assertEquals(sample, decoded)
    }

    @Test
    fun `UserAuthData default-only instance encodes to zero bytes`() {
        // Confirms kotlinx-serialization-protobuf default behaviour:
        // encodeDefaults = false, so all-default values produce 0 bytes.
        // Locks this in so a config change (e.g. ProtoBuf { encodeDefaults = true })
        // can't quietly bloat every payload.
        val encoded = ProtoBuf.encodeToByteArray(UserAuthData())
        assertEquals(0, encoded.size, "empty UserAuthData must encode to 0 bytes")
    }

    @Test
    fun `GlobalConfigData default-only instance encodes to zero bytes`() {
        val encoded = ProtoBuf.encodeToByteArray(GlobalConfigData())
        assertEquals(0, encoded.size, "empty GlobalConfigData must encode to 0 bytes")
    }

    companion object {
        /**
         * Baseline hex captured from PR #789's implicit declaration-order tagging
         * (i.e. the bytes already written to `secure_user.pb` on every deployed device).
         * DO NOT regenerate without an explicit data-migration plan — any drift
         * means upgrading users will fail to read their existing on-disk payload.
         *
         * Decoded layout (verifiable by hand against the protobuf wire format):
         *   tag 1 (account)               = "alice"
         *   tag 2 (baseAuth)              = "auth-token"
         *   tag 3 (microToken)            = "micro-tok"
         *   tags 4..14                    = defaults (empty string) — not encoded
         *   tag 15 (aciIdentityKeyGenTime)= varint 12345
         *   tag 16 (migrationV1Completed) = true
         */
        private const val USER_AUTH_DATA_FIXTURE_HEX =
            "0a05616c696365120a617574682d746f6b656e1a096d6963726f2d746f6b78b960800101"

        /**
         * Same purpose for `secure_config.pb`. Decoded layout:
         *   tag 1 (config)                = "{"v":1}"
         *   tag 2 (callServiceUrlStateV3) = "{"url":"chat"}"
         *   tag 3 (migrationV1Completed)  = true
         */
        private const val GLOBAL_CONFIG_DATA_FIXTURE_HEX =
            "0a077b2276223a317d120e7b2275726c223a2263686174227d1801"
    }
}
