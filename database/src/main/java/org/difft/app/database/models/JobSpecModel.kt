package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

/**
 * WCDB ORM model for JobManager persistent job specs.
 *
 * Replaces the legacy SQLCipher table `job_spec` from
 * `chat/src/main/java/com/difft/android/chat/database/JobDatabase.kt`. Schema is
 * 1:1 with the legacy DDL except that the synthetic
 * `_id INTEGER PRIMARY KEY AUTOINCREMENT` column is dropped in favor of the
 * business `jobSpecId` as the primary key directly — project convention
 * across all existing models (see `PublicKeyInfoModel` precedent).
 *
 * No data is migrated from the old SQLCipher DB; the old file is deleted at first
 * launch of this version.
 *
 * Serialized payload is `TEXT` (String), not `BLOB` — matches legacy (hard constraint A).
 *
 * `queueKey` is indexed via [WCDBIndex] because `FastJobStorage` filters/groups by
 * this column on every job-loop tick.
 */
@WCDBTableCoding
class JobSpecModel {

    /** UUID issued at enqueue time. Sole primary key. */
    @WCDBField(isPrimary = true, isNotNull = true)
    var jobSpecId: String = ""

    /** Factory key identifying the concrete `Job` subclass. */
    @WCDBField(isNotNull = true)
    var factoryKey: String = ""

    /**
     * Serial queue key; `null` means no queue constraint.
     * Indexed because `FastJobStorage` filters/groups by this column.
     */
    @WCDBIndex
    @WCDBField
    var queueKey: String? = null

    @WCDBField
    var createTime: Long = 0

    @WCDBField
    var nextRunAttemptTime: Long = 0

    @WCDBField
    var runAttempt: Int = 0

    @WCDBField
    var maxAttempts: Int = 0

    @WCDBField
    var lifespan: Long = 0

    /**
     * JSON-serialized job payload (produced by `JsonDataSerializer`).
     * Nullable — legacy allowed NULL; business code always emits non-null for real jobs.
     */
    @WCDBField
    var serializedData: String? = null

    /**
     * Running flag. WCDB auto-maps `boolean` to `INTEGER 0/1`
     * (see `ForwardContextModel.isFromGroup` precedent).
     * Reset to `false` on startup by `updateAllJobsToBePending()`.
     */
    @WCDBField
    var isRunning: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as JobSpecModel
        return createTime == other.createTime &&
                nextRunAttemptTime == other.nextRunAttemptTime &&
                runAttempt == other.runAttempt &&
                maxAttempts == other.maxAttempts &&
                lifespan == other.lifespan &&
                isRunning == other.isRunning &&
                jobSpecId == other.jobSpecId &&
                factoryKey == other.factoryKey &&
                queueKey == other.queueKey &&
                serializedData == other.serializedData
    }

    override fun hashCode(): Int = Objects.hash(
        jobSpecId,
        factoryKey,
        queueKey,
        createTime,
        nextRunAttemptTime,
        runAttempt,
        maxAttempts,
        lifespan,
        serializedData,
        isRunning
    )
}
