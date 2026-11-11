package com.difft.android.migration.v2.restore

import com.difft.android.migration.model.MigrationError





sealed interface RestoreResult {

    




    data object ReadyForSignIn : RestoreResult

    







    data class Rejected(val error: MigrationError) : RestoreResult

    




    data class Failed(val error: MigrationError) : RestoreResult
}







data class RestoreProgress(
    val stage: String,
    val percent: Int,
)
