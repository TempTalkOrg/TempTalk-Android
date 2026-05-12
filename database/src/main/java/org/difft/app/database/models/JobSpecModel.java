package org.difft.app.database.models;

import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

/**
 * WCDB ORM model for JobManager persistent job specs.
 *
 * <p>Replaces the legacy SQLCipher table {@code job_spec} from
 * {@code chat/src/main/java/com/difft/android/chat/database/JobDatabase.kt}. Schema is
 * 1:1 with the legacy DDL except that the synthetic
 * {@code _id INTEGER PRIMARY KEY AUTOINCREMENT} column is dropped in favor of the
 * business {@code jobSpecId} as the primary key directly &mdash; project convention
 * across all 23 existing models (see {@code PublicKeyInfoModel} precedent).
 *
 * <p>No data is migrated from the old SQLCipher DB; the old file is deleted at first
 * launch of this version.
 *
 * <p>Serialized payload is {@code TEXT} (String), not {@code BLOB} &mdash; matches
 * legacy (hard constraint A).
 *
 * <p>{@code queueKey} is indexed via {@link WCDBIndex} because
 * {@code FastJobStorage} filters/groups by this column on every job-loop tick.
 */
@WCDBTableCoding
public class JobSpecModel {

    /** UUID issued at enqueue time. Sole primary key. */
    @WCDBField(isPrimary = true, isNotNull = true)
    public String jobSpecId;

    /** Factory key identifying the concrete {@code Job} subclass. */
    @WCDBField(isNotNull = true)
    public String factoryKey;

    /**
     * Serial queue key; {@code null} means no queue constraint.
     * Indexed because {@code FastJobStorage} filters/groups by this column.
     */
    @WCDBIndex
    @WCDBField
    public String queueKey;

    @WCDBField
    public long createTime;

    @WCDBField
    public long nextRunAttemptTime;

    @WCDBField
    public int runAttempt;

    @WCDBField
    public int maxAttempts;

    @WCDBField
    public long lifespan;

    /**
     * JSON-serialized job payload (produced by {@code JsonDataSerializer}).
     * Nullable &mdash; legacy allowed NULL; business code always emits non-null for real jobs.
     */
    @WCDBField
    public String serializedData;

    /**
     * Running flag. WCDB auto-maps {@code boolean} to {@code INTEGER 0/1}
     * (see {@code ForwardContextModel.isFromGroup} precedent).
     * Reset to {@code false} on startup by {@code updateAllJobsToBePending()}.
     */
    @WCDBField
    public boolean isRunning;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JobSpecModel that = (JobSpecModel) o;
        return createTime == that.createTime
                && nextRunAttemptTime == that.nextRunAttemptTime
                && runAttempt == that.runAttempt
                && maxAttempts == that.maxAttempts
                && lifespan == that.lifespan
                && isRunning == that.isRunning
                && Objects.equals(jobSpecId, that.jobSpecId)
                && Objects.equals(factoryKey, that.factoryKey)
                && Objects.equals(queueKey, that.queueKey)
                && Objects.equals(serializedData, that.serializedData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
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
        );
    }
}
