package com.difft.android.base.migration.pause

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.security.MessageDigest
import java.util.Base64


enum class MigrationPausePhase {
    
    EXPORTING,

    
    COMMITTING,

    
    AWAITING_LOGIN,

    



    AWAITING_DECISION,
}


enum class MigrationPauseOutcome {
    SUCCESS,
    FAILED,
    UNKNOWN,

    
    NOT_COMMITTED,
}


sealed interface PauseStep {
    
    data class Applied(val next: MigrationPauseRecord?) : PauseStep

    
    data class Skipped(val reason: String) : PauseStep

    
    data object OtherExport : PauseStep
}








data class MigrationPauseRecord(
    
    val owner: String,
    val phase: MigrationPausePhase,
    
    val accountScope: String,
    
    val credentialHash: String,
    val sessionId: String? = null,
    
    val startedAt: Long,
) {

    



    fun matches(owner: String?, sessionId: String?): Boolean = when {
        owner != null -> this.owner == owner && (sessionId == null || this.sessionId == sessionId)
        sessionId != null -> this.sessionId == sessionId
        else -> false
    }

    
    fun committing(owner: String, sessionId: String): PauseStep = when {
        this.owner != owner -> PauseStep.OtherExport
        phase == MigrationPausePhase.EXPORTING -> PauseStep.Applied(copy(phase = MigrationPausePhase.COMMITTING, sessionId = sessionId))
        phase == MigrationPausePhase.COMMITTING && this.sessionId == sessionId -> PauseStep.Applied(this)
        else -> PauseStep.Skipped(phase.skipReason())
    }

    





    fun finishing(owner: String?, sessionId: String?, outcome: MigrationPauseOutcome): PauseStep {
        if (!matches(owner, sessionId)) return PauseStep.OtherExport
        if (phase == MigrationPausePhase.AWAITING_LOGIN) {
            return if (outcome == MigrationPauseOutcome.SUCCESS) PauseStep.Applied(this) else PauseStep.Skipped(phase.skipReason())
        }
        return when (outcome) {
            MigrationPauseOutcome.SUCCESS -> PauseStep.Applied(copy(phase = MigrationPausePhase.AWAITING_LOGIN))
            MigrationPauseOutcome.FAILED -> PauseStep.Applied(null)
            MigrationPauseOutcome.UNKNOWN -> when (phase) {
                MigrationPausePhase.EXPORTING -> PauseStep.Applied(null)
                else -> PauseStep.Applied(copy(phase = MigrationPausePhase.AWAITING_DECISION))
            }
            MigrationPauseOutcome.NOT_COMMITTED -> when (phase) {
                MigrationPausePhase.EXPORTING, MigrationPausePhase.COMMITTING -> PauseStep.Applied(null)
                else -> PauseStep.Skipped(phase.skipReason())
            }
        }
    }

    
    fun matchesScope(accountScope: String, credentialHash: String): Boolean =
        this.accountScope == accountScope && this.credentialHash == credentialHash

    private companion object {
        fun MigrationPausePhase.skipReason(): String = "phase_${name.lowercase()}"
    }
}


object MigrationPauseScope {

    
    fun accountScope(environment: String, account: String): String =
        sha256Base64(environment + "\n" + account.trim())

    fun credentialHash(password: String): String = sha256Base64(password)

    private fun sha256Base64(value: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
}


internal object MigrationPauseRecordCodec {

    private val gson = Gson()

    private data class Json(
        @SerializedName("owner") val owner: String? = null,
        @SerializedName("phase") val phase: String? = null,
        @SerializedName("accountScope") val accountScope: String? = null,
        @SerializedName("credentialHash") val credentialHash: String? = null,
        @SerializedName("sessionId") val sessionId: String? = null,
        @SerializedName("startedAt") val startedAt: Long? = null,
    )

    fun encode(record: MigrationPauseRecord): String = gson.toJson(
        Json(
            owner = record.owner,
            phase = record.phase.name,
            accountScope = record.accountScope,
            credentialHash = record.credentialHash,
            sessionId = record.sessionId,
            startedAt = record.startedAt,
        )
    )

    
    @Suppress("TooGenericExceptionCaught") 
    fun decode(raw: String): MigrationPauseRecord? {
        val json = try {
            gson.fromJson(raw, Json::class.java)
        } catch (e: RuntimeException) {
            null
        } ?: return null
        val phase = MigrationPausePhase.entries.firstOrNull { it.name == json.phase }
        val owner = json.owner
        val accountScope = json.accountScope
        val credentialHash = json.credentialHash
        val startedAt = json.startedAt
        if (phase == null || owner.isNullOrBlank() || accountScope == null || credentialHash == null || startedAt == null) {
            return null
        }
        return MigrationPauseRecord(owner, phase, accountScope, credentialHash, json.sessionId, startedAt)
    }
}
