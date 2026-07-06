package org.difft.app.database.models

import com.tencent.wcdb.MultiPrimary
import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects

/**
 * WCDB ORM model for JobManager constraint specs.
 *
 * Replaces the legacy SQLCipher table `constraint_spec` from
 * `chat/src/main/java/com/difft/android/chat/database/JobDatabase.kt`.
 *
 * The legacy `_id INTEGER PRIMARY KEY AUTOINCREMENT` column is dropped;
 * the legacy `UNIQUE(job_spec_id, factory_key)` constraint is replaced by the
 * composite primary key `(jobSpecId, factoryKey)` via [MultiPrimary] — same
 * SQLite unique-index shape. Mirrors the `ReadInfoModel` precedent
 * (see `MultiPrimary(columns = "roomId", "uid")`).
 *
 * No SQL foreign key is declared. The legacy schema also had no `REFERENCES`
 * clause (only an enabled-but-unused `PRAGMA foreign_keys=ON`); cascade delete
 * is done explicitly inside `WcdbJobStorage.deleteJobs`.
 */
@WCDBTableCoding(multiPrimaries = [MultiPrimary(columns = ["jobSpecId", "factoryKey"])])
class JobConstraintModel {

    /**
     * FK-in-intent only — no SQL FK declared. Cascade is done explicitly
     * in `WcdbJobStorage.deleteJobs`. Indexed because cascade/lookup
     * queries filter by this column.
     */
    @WCDBIndex
    @WCDBField(isNotNull = true)
    var jobSpecId: String = ""

    /** Constraint factory key. */
    @WCDBField(isNotNull = true)
    var factoryKey: String = ""

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as JobConstraintModel
        return jobSpecId == other.jobSpecId && factoryKey == other.factoryKey
    }

    override fun hashCode(): Int = Objects.hash(jobSpecId, factoryKey)
}
