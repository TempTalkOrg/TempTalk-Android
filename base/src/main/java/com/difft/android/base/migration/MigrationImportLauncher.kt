package com.difft.android.base.migration

import android.content.Context
import android.content.Intent











interface MigrationImportLauncher {

    










    fun isFreshInstallTarget(): Boolean

    










    fun pendingRestoreSignIn(): PendingRestoreSignIn?

    




    fun markRestoreIdentityPublishFailed()

    









    fun markRestoreSignInComplete()

    





    fun isImportOffered(): Boolean

    








    fun isP2pMigrationOffered(context: Context): Boolean

    












    fun createP2pReceiveIntent(
        context: Context,
        account: String,
        sameDevice: Boolean = false,
        scanDesktop: Boolean = false,
        pendingSignIn: android.os.Parcelable? = null,
    ): Intent

    











    fun createSourceChooserIntent(
        context: Context,
        account: String,
        pendingSignIn: android.os.Parcelable? = null,
    ): Intent

    



    fun parseResult(resultCode: Int, data: Intent?): MigrationImportOutcome

    companion object {
        
        const val RESULT_RESTORED = 1001

        
        const val RESULT_SKIPPED = 1002
    }
}









data class PendingRestoreSignIn(
    val account: String,
    val useInstalledIdentity: Boolean,
)






sealed interface MigrationImportOutcome {

    




    data object Restored : MigrationImportOutcome

    


    data object Cancelled : MigrationImportOutcome
}
