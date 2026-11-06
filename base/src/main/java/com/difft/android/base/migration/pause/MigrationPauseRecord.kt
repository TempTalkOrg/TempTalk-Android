package com.difft.android.base.migration.pause

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.security.MessageDigest
import java.util.Base64

/** Where a device-replacement export stands; see [MigrationPauseRecord]. Every phase pauses messaging. */
enum class MigrationPausePhase {
    /** The snapshot is being taken or sent; the receiver cannot have committed yet. */
    EXPORTING,

    /** The container's end marker may have been written; the receiver may commit. */
    COMMITTING,

    /** The receiver confirmed the import; the pause ends when this login becomes invalid. */
    AWAITING_LOGIN,

    /**
     * The outcome is unknown. Messaging stays paused until a definite answer (`/status`, the auth
     * probe's 401) or a logout ends it, and no new export may start (#1382 A01).
     */
    AWAITING_DECISION,
}

/** The verdict a sender reports to [MigrationPauseRecord.finishing]. */
enum class MigrationPauseOutcome {
    SUCCESS,
    FAILED,
    UNKNOWN,

    /** This process proved the container's end marker was never written, so no receiver can commit. */
    NOT_COMMITTED,
}

/** A record transition decided against the stored record (#1382 A11). */
sealed interface PauseStep {
    /** The transition is accepted; [next] is the record to store, null to clear it. */
    data class Applied(val next: MigrationPauseRecord?) : PauseStep

    /** The record's phase does not take this transition. */
    data class Skipped(val reason: String) : PauseStep

    /** The record belongs to another export (owner or session differs). */
    data object OtherExport : PauseStep
}

/**
 * The persisted message pause of one device-replacement export (#1382), mirroring iOS
 * `DTMigrationV2MessagePauseRecord`. Only hashes of the account and the credential are stored.
 *
 * Every transition is pure and checked against the owner, the session and the phase; a transition
 * the record does not take is [PauseStep.Skipped] and changes nothing.
 */
data class MigrationPauseRecord(
    /** Attempt id (UUID string) of the export that owns this pause. */
    val owner: String,
    val phase: MigrationPausePhase,
    /** [MigrationPauseScope.accountScope] of the login that started the export. */
    val accountScope: String,
    /** [MigrationPauseScope.credentialHash] of that login's basic-auth password. */
    val credentialHash: String,
    val sessionId: String? = null,
    /** Epoch millis of [MigrationMessagePause.begin]. */
    val startedAt: Long,
) {

    /**
     * Whether a settlement names this record: by [owner] when the caller knows it (and then by
     * [sessionId] too when given), otherwise by [sessionId] alone.
     */
    fun matches(owner: String?, sessionId: String?): Boolean = when {
        owner != null -> this.owner == owner && (sessionId == null || this.sessionId == sessionId)
        sessionId != null -> this.sessionId == sessionId
        else -> false
    }

    /** The commit marker: only the owner's export moves to [MigrationPausePhase.COMMITTING], once. */
    fun committing(owner: String, sessionId: String): PauseStep = when {
        this.owner != owner -> PauseStep.OtherExport
        phase == MigrationPausePhase.EXPORTING -> PauseStep.Applied(copy(phase = MigrationPausePhase.COMMITTING, sessionId = sessionId))
        phase == MigrationPausePhase.COMMITTING && this.sessionId == sessionId -> PauseStep.Applied(this)
        else -> PauseStep.Skipped(phase.skipReason())
    }

    /**
     * Applies a verdict. Only a definite failure, or proof that the receiver cannot have committed,
     * clears the record; an unknown outcome before the commit marker is such proof, since no end
     * marker was written. Success awaits the old login's end, which nothing reverses; an unknown
     * outcome past the commit marker awaits a decision, still paused.
     */
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

    /** True when the pause was started by the given login. */
    fun matchesScope(accountScope: String, credentialHash: String): Boolean =
        this.accountScope == accountScope && this.credentialHash == credentialHash

    private companion object {
        fun MigrationPausePhase.skipReason(): String = "phase_${name.lowercase()}"
    }
}

/** Hashes that identify a login without storing the account or its credential. */
object MigrationPauseScope {

    /** Environment plus account, so the same number on another environment is another scope. */
    fun accountScope(environment: String, account: String): String =
        sha256Base64(environment + "\n" + account.trim())

    fun credentialHash(password: String): String = sha256Base64(password)

    private fun sha256Base64(value: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
}

/** JSON form of [MigrationPauseRecord]; the names are pinned so a minified build keeps reading it. */
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

    /** The record, or null when [raw] is not a complete record (a corrupt value is never an open state). */
    @Suppress("TooGenericExceptionCaught") // Gson throws several unchecked types for malformed input.
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
