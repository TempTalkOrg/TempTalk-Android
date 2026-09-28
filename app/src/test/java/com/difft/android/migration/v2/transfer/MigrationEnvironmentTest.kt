package com.difft.android.migration.v2.transfer

import com.difft.android.base.utils.AppScheme
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.p2p.engine.qr.QrPayloadV2Codec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The build-config spelling never reaches the wire; each build maps to the one neutral name. */
class MigrationEnvironmentTest {

    private fun helper(current: String) = object : EnvironmentHelper {
        override val currentEnvironment: String = current
        override val ENVIRONMENT_DEVELOPMENT: String = "EnvironmentDevelopment"
        override val ENVIRONMENT_ONLINE: String = "EnvironmentOnline"
        override fun isThatEnvironment(environment: String): Boolean = currentEnvironment == environment
        override fun isGoogleChannel(): Boolean = false
        override fun isInsiderChannel(): Boolean = false
        override fun getChannelName(): String = "test"
        override fun getScheme(): String = AppScheme.SCHEME_CHATIVE
    }

    @Test
    fun `the online build is named online`() {
        assertEquals(QrPayloadV2Codec.Environment.PROD, MigrationEnvironment.wireName(helper("EnvironmentOnline")))
        assertEquals("online", MigrationEnvironment.wireName(helper("EnvironmentOnline")))
    }

    @Test
    fun `the development build is named test`() {
        assertEquals(QrPayloadV2Codec.Environment.TEST, MigrationEnvironment.wireName(helper("EnvironmentDevelopment")))
        assertEquals("test", MigrationEnvironment.wireName(helper("EnvironmentDevelopment")))
    }

    @Test
    fun `an environment this build cannot name is an error, not a silent default`() {
        assertThrows(IllegalStateException::class.java) { MigrationEnvironment.wireName(helper("EnvironmentStaging")) }
    }
}
