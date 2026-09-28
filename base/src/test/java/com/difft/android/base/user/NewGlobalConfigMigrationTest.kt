package com.difft.android.base.user

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remote gate for the old app's migration entry is read straight off the global-config JSON;
 * pin the wire key and the "absent means closed" default so a server-side rename or a missing
 * section can never open the entry by accident.
 */
class NewGlobalConfigMigrationTest {

    private val gson = Gson()

    @Test
    fun `androidLegacyEntryEnabled true is parsed from the migration section`() {
        val config = gson.fromJson("""{"code":0,"data":{"migration":{"androidLegacyEntryEnabled":true}}}""", NewGlobalConfig::class.java)
        assertTrue(config.data?.migration?.androidLegacyEntryEnabled == true)
    }

    @Test
    fun `androidLegacyEntryEnabled false is parsed as an explicit close`() {
        val config = gson.fromJson("""{"code":0,"data":{"migration":{"androidLegacyEntryEnabled":false}}}""", NewGlobalConfig::class.java)
        assertEquals(false, config.data?.migration?.androidLegacyEntryEnabled)
    }

    @Test
    fun `a config without the migration section leaves the flag absent`() {
        val config = gson.fromJson("""{"code":0,"data":{"maxStickCount":3}}""", NewGlobalConfig::class.java)
        assertNull(config.data?.migration)
        assertNull(config.data?.migration?.androidLegacyEntryEnabled)
        assertEquals(3, config.data?.maxStickCount)
    }

    @Test
    fun `a migration section without the key leaves the flag absent`() {
        val config = gson.fromJson("""{"code":0,"data":{"migration":{}}}""", NewGlobalConfig::class.java)
        assertNull(config.data?.migration?.androidLegacyEntryEnabled)
    }
}
