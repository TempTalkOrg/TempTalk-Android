package org.difft.app.database

import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.base.log.lumberjack.L
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tencent.wcdb.base.WCDBException
import java.io.File

/**
 * Database health-probe + corruption-telemetry helpers for [WCDB].
 *
 * Extracted from `WCDB.kt` to stay under the 500-line project rule. Lives as `WCDB` extension
 * functions in the same module/package so they can read the `internal` members they need
 * (`context`, `corruptionReported`, `healthyProbePassed`, `markKeyUnavailable`) without widening
 * WCDB's public surface; the mutable state stays on the [WCDB] instance since extensions can't
 * hold per-instance state.
 */

/** Result of a database health probe — see [probeHealthy]. */
enum class DbHealth { HEALTHY, CORRUPT, KEY_UNAVAILABLE }

/**
 * Observability for DB corruption. Logs + records a Crashlytics non-fatal exactly once
 * per process so we can measure real-world corruption frequency and, crucially, the
 * mid-session vs startup-open split. No sensitive data — only sizes / backup presence /
 * phase. Crashlytics is wrapped in [runCatching] so a telemetry failure on the WCDB
 * callback thread (or a headless wake) can never crash the process.
 */
internal fun WCDB.reportCorruptionOnce(source: String) {
    if (!corruptionReported.compareAndSet(false, true)) return
    val dbFile = context.getDatabasePath(WCDB.DATABASE_NAME)
    val phase = if (healthyProbePassed) "mid_session" else "startup_open"
    val firstMaterial = File("${dbFile.absolutePath}-first.material").exists()
    val lastMaterial = File("${dbFile.absolutePath}-last.material").exists()
    L.e {
        "[WCDB][DBRecovery] corruption detected source=$source phase=$phase " +
            "dbSizeBytes=${dbFile.length()} firstMaterial=$firstMaterial lastMaterial=$lastMaterial"
    }
    runCatching {
        FirebaseCrashlytics.getInstance().apply {
            setCustomKey("db_corruption_source", source)
            setCustomKey("db_corruption_phase", phase)
            setCustomKey("db_corruption_db_size", dbFile.length())
            setCustomKey("db_corruption_first_material", firstMaterial)
            setCustomKey("db_corruption_last_material", lastMaterial)
            recordException(RuntimeException("[WCDB] corruption detected source=$source phase=$phase"))
        }
    }
}

/**
 * Forces the lazy open and runs a lightweight `PRAGMA journal_mode` to detect corruption / wrong
 * cipher. Single owner of the probe logic — called by both the MainActivity recovery gate and the
 * early startup task.
 *
 * Short-circuits when [WCDB.dbCorrupted] or [WCDB.keyUnavailable] is already set, so a known-bad
 * DB is never re-probed. Returns the tri-state [DbHealth]: file corruption / unexpected error on
 * an existing DB → [DbHealth.CORRUPT] (wipe-eligible); cipher-key failure →
 * [DbHealth.KEY_UNAVAILABLE] (fail-soft, never sets `dbCorrupted`).
 */
fun WCDB.probeHealthy(): DbHealth {
    // keyUnavailable takes precedence over dbCorrupted: when both are set, a wipe-and-recreate
    // can't succeed (the fresh DB still needs the missing key), so fail-soft rather than route
    // to the destructive corruption path. Once the key resolves, a later probe re-detects CORRUPT.
    if (keyUnavailable) return DbHealth.KEY_UNAVAILABLE
    if (dbCorrupted) return DbHealth.CORRUPT
    return try {
        db.execute("PRAGMA journal_mode") // forces lazy open + detects corruption / wrong cipher
        healthyProbePassed = true // telemetry: confirms readable → later corruption counts as mid-session
        DbHealth.HEALTHY
    } catch (e: WCDBException) {
        val corruption = e.code == WCDBException.Code.Corrupt || e.code == WCDBException.Code.NotADatabase
        L.e { "[WCDB][DBRecovery] probe failed code=${e.code} corrupt=$corruption msg=${e.message}" }
        if (corruption) {
            reportCorruptionOnce("probe")
            markCorrupted()
            DbHealth.CORRUPT
        } else {
            DbHealth.HEALTHY // Busy etc. = transient, proceed
        }
    } catch (e: WCDBKeyUnavailableException) {
        // A cipher-key failure is not file corruption: set keyUnavailable, never dbCorrupted —
        // the wipe trigger reads dbCorrupted only, so this routes fail-soft.
        L.e { "[WCDB][DBRecovery] cipher key unavailable: ${e.stackTraceToString()}" }
        markKeyUnavailable()
        DbHealth.KEY_UNAVAILABLE
    } catch (e: Throwable) {
        L.e { "[WCDB][DBRecovery] probe unexpected error: ${e.stackTraceToString()}" }
        markCorrupted()
        DbHealth.CORRUPT // unexpected non-key error on an EXISTING DB → fail safe to recovery
    }
}
