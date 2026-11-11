package com.difft.android.migration.v2.transfer

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.difft.android.migration.v2.transfer.login.MigrationP2pReceiveActivity
import com.difft.android.migration.v2.transfer.send.MigrationP2pSendActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull







@RunWith(RobolectricTestRunner::class)
class MigrationP2pManifestTest {

    @Test
    fun `MigrationP2pReceiveActivity is declared in the merged manifest`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MigrationP2pReceiveActivity::class.java)
        assertNotNull(
            context.packageManager.resolveActivity(intent, 0),
            "MigrationP2pReceiveActivity is not declared in AndroidManifest.xml",
        )
    }

    @Test
    fun `MigrationSuccessActivity is declared in the merged manifest`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, com.difft.android.migration.v2.login.MigrationSuccessActivity::class.java)
        assertNotNull(
            context.packageManager.resolveActivity(intent, 0),
            "MigrationSuccessActivity is not declared in AndroidManifest.xml",
        )
    }

    @Test
    fun `MigrationSourceChooserActivity is declared in the merged manifest`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, com.difft.android.migration.v2.login.MigrationSourceChooserActivity::class.java)
        assertNotNull(
            context.packageManager.resolveActivity(intent, 0),
            "MigrationSourceChooserActivity is not declared in AndroidManifest.xml",
        )
    }

    @Test
    fun `MigrationP2pSendActivity is declared in the merged manifest`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MigrationP2pSendActivity::class.java)
        assertNotNull(
            context.packageManager.resolveActivity(intent, 0),
            "MigrationP2pSendActivity is not declared in AndroidManifest.xml",
        )
    }

    @Test
    fun `MigrationStatusProvider is exported at the authority the peer will address`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val authority = com.difft.android.base.migration.PeerApps.STATUS_AUTHORITY_SUFFIX.let { context.packageName + it }
        val info = context.packageManager.resolveContentProvider(authority, 0)
        assertNotNull(
            info,
            "MigrationStatusProvider is not declared at $authority (or PeerApps names a different suffix)",
        )
        
        
        kotlin.test.assertTrue(info!!.exported, "the status provider must stay exported")
        kotlin.test.assertFalse(info.grantUriPermissions, "the status provider serves no URIs")
    }

    @Test
    fun `MigrationHandoffEntryActivity is declared in the merged manifest under the name the trampoline uses`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        
        
        val intent = Intent().setClassName(
            context.packageName,
            com.difft.android.base.migration.PeerApps.HANDOFF_ENTRY_ACTIVITY,
        )
        assertNotNull(
            context.packageManager.resolveActivity(intent, 0),
            "MigrationHandoffEntryActivity is not declared in AndroidManifest.xml " +
                "(or PeerApps.HANDOFF_ENTRY_ACTIVITY names the wrong class)",
        )
    }

    @Test
    fun `LinkLoginActivity is declared in the merged manifest and reachable by the scanner's class name`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent().setClassName(context.packageName, com.difft.android.base.migration.PeerApps.LINK_LOGIN_ACTIVITY)
        assertNotNull(
            context.packageManager.resolveActivity(intent, 0),
            "LinkLoginActivity is not declared in AndroidManifest.xml (or PeerApps.LINK_LOGIN_ACTIVITY names the wrong class)",
        )
    }
}
