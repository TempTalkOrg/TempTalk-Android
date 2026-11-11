package com.difft.android.base.storage.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Test
import kotlin.test.assertEquals




















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
        
        
        
        
        val encoded = ProtoBuf.encodeToByteArray(UserAuthData())
        assertEquals(0, encoded.size, "empty UserAuthData must encode to 0 bytes")
    }

    @Test
    fun `GlobalConfigData written before tag 4 existed reads back with an empty pendingLogin`() {
        
        
        val old = ProtoBuf.decodeFromByteArray<GlobalConfigData>(GLOBAL_CONFIG_DATA_FIXTURE_HEX.hexToByteArray())
        assertEquals("", old.pendingLogin)
        val withHeld = old.copy(pendingLogin = "{\"account\":\"+1\"}")
        val decoded = ProtoBuf.decodeFromByteArray<GlobalConfigData>(ProtoBuf.encodeToByteArray(withHeld))
        assertEquals(withHeld, decoded)
    }

    @Test
    fun `GlobalConfigData default-only instance encodes to zero bytes`() {
        val encoded = ProtoBuf.encodeToByteArray(GlobalConfigData())
        assertEquals(0, encoded.size, "empty GlobalConfigData must encode to 0 bytes")
    }

    companion object {
        













        private const val USER_AUTH_DATA_FIXTURE_HEX =
            "0a05616c696365120a617574682d746f6b656e1a096d6963726f2d746f6b78b960800101"

        





        private const val GLOBAL_CONFIG_DATA_FIXTURE_HEX =
            "0a077b2276223a317d120e7b2275726c223a2263686174227d1801"
    }
}
