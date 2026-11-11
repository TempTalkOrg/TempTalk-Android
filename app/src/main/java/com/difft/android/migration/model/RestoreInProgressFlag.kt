package com.difft.android.migration.model
















enum class RestorePhase {
    IMPORTING,
    PROMOTING,
    SIGNING_IN,
    COMPLETED;

    companion object {
        
        fun fromName(name: String?): RestorePhase =
            entries.firstOrNull { it.name == name } ?: IMPORTING
    }
}


















data class RestoreInProgressFlag(
    val state: String,
    val startedAt: Long,
    val sourceBackupUri: String,
    val phase: String = RestorePhase.IMPORTING.name,
    val account: String? = null,
    val identityPublishFailed: Boolean = false,
)
