package com.difft.android.base.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File







class FileUtilWipeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun File.child(path: String): File = File(this, path).also {
        it.parentFile?.mkdirs()
        it.writeText("x")
    }

    @Test
    fun `secure_config store and its keyset survive the wipe, everything else goes`() {
        val dataDir = tempFolder.newFolder("data")
        val secureConfig = dataDir.child("files/datastore/secure_config.pb")
        val keyset = dataDir.child("shared_prefs/tt_storage_secure_config_keyset.xml")
        val log = dataDir.child("files/log/org.difft.temptalk_log_20260923.txt")
        val secureUser = dataDir.child("files/datastore/secure_user.pb")
        val appState = dataDir.child("files/datastore/app_state.preferences_pb")
        val userKeyset = dataDir.child("shared_prefs/tt_storage_secure_user_keyset.xml")
        val otherPrefs = dataDir.child("shared_prefs/settings.xml")
        val attachment = dataDir.child("files/attachments/1.jpg")
        val database = dataDir.child("databases/msg.db")

        FileUtil.deleteDirectoryContents(dataDir, preserveLogs = true)

        assertTrue(secureConfig.exists())
        assertTrue(keyset.exists())
        assertTrue(log.exists())
        for (gone in listOf(secureUser, appState, userKeyset, otherPrefs, attachment, database)) {
            assertFalse(gone.path, gone.exists())
        }
        
        assertTrue(File(dataDir, "files/datastore").isDirectory)
        assertTrue(File(dataDir, "shared_prefs").isDirectory)
        assertFalse(File(dataDir, "files/attachments").exists())
        assertFalse(File(dataDir, "databases").exists())
    }

    @Test
    fun `the secure_config names are preserved only at their own locations`() {
        val dataDir = tempFolder.newFolder("data")
        val strayPb = dataDir.child("files/secure_config.pb")
        val strayKeyset = dataDir.child("files/tt_storage_secure_config_keyset.xml")
        val strayDir = dataDir.child("files/datastore/secure_config.pb/nested")

        FileUtil.deleteDirectoryContents(dataDir, preserveLogs = false)

        assertFalse(strayPb.exists())
        assertFalse(strayKeyset.exists())
        assertFalse(strayDir.exists())
        assertFalse(File(dataDir, "files").exists())
    }
}
