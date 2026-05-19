package org.difft.app.database.models;

import com.tencent.wcdb.MultiPrimary;
import com.tencent.wcdb.WCDBField;
import com.tencent.wcdb.WCDBIndex;
import com.tencent.wcdb.WCDBTableCoding;

import java.util.Objects;

/**
 * WCDB ORM model for JobManager constraint specs.
 *
 * <p>Replaces the legacy SQLCipher table {@code constraint_spec} from
 * {@code chat/src/main/java/com/difft/android/chat/database/JobDatabase.kt}.
 *
 * <p>The legacy {@code _id INTEGER PRIMARY KEY AUTOINCREMENT} column is dropped;
 * the legacy {@code UNIQUE(job_spec_id, factory_key)} constraint is replaced by the
 * composite primary key {@code (jobSpecId, factoryKey)} via
 * {@link MultiPrimary} &mdash; same SQLite unique-index shape. Mirrors the
 * {@code ReadInfoModel} precedent (see {@code MultiPrimary(columns = "roomId", "uid")}).
 *
 * <p>No SQL foreign key is declared. The legacy schema also had no {@code REFERENCES}
 * clause (only an enabled-but-unused {@code PRAGMA foreign_keys=ON}); cascade delete
 * is done explicitly inside {@code WcdbJobStorage.deleteJobs}.
 */
@WCDBTableCoding(multiPrimaries = @MultiPrimary(columns = {"jobSpecId", "factoryKey"}))
public class JobConstraintModel {

    /**
     * FK-in-intent only &mdash; no SQL FK declared. Cascade is done explicitly
     * in {@code WcdbJobStorage.deleteJobs}. Indexed because cascade/lookup
     * queries filter by this column.
     */
    @WCDBIndex
    @WCDBField(isNotNull = true)
    public String jobSpecId;

    /** Constraint factory key. */
    @WCDBField(isNotNull = true)
    public String factoryKey;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JobConstraintModel that = (JobConstraintModel) o;
        return Objects.equals(jobSpecId, that.jobSpecId)
                && Objects.equals(factoryKey, that.factoryKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobSpecId, factoryKey);
    }
}
