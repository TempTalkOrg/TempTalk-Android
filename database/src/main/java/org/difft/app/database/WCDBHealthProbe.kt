package org.difft.app.database

import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.base.log.lumberjack.L
import com.tencent.wcdb.base.WCDBException
import java.io.File












enum class DbHealth { HEALTHY, CORRUPT, KEY_UNAVAILABLE }






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
}












fun WCDB.probeHealthy(): DbHealth = try {
    probeHealthyOnce()
} finally {
    markProbeFinished()
}

private fun WCDB.probeHealthyOnce(): DbHealth {
    
    
    
    if (keyUnavailable) return DbHealth.KEY_UNAVAILABLE
    if (dbCorrupted) return DbHealth.CORRUPT
    return try {
        db.execute("PRAGMA journal_mode") 
        healthyProbePassed = true 
        DbHealth.HEALTHY
    } catch (e: WCDBException) {
        val corruption = e.code == WCDBException.Code.Corrupt || e.code == WCDBException.Code.NotADatabase
        L.e { "[WCDB][DBRecovery] probe failed code=${e.code} corrupt=$corruption msg=${e.message}" }
        if (corruption) {
            reportCorruptionOnce("probe")
            markCorrupted()
            DbHealth.CORRUPT
        } else {
            DbHealth.HEALTHY 
        }
    } catch (e: WCDBKeyUnavailableException) {
        
        
        L.e { "[WCDB][DBRecovery] cipher key unavailable: ${e.stackTraceToString()}" }
        markKeyUnavailable()
        DbHealth.KEY_UNAVAILABLE
    } catch (e: Throwable) {
        L.e { "[WCDB][DBRecovery] probe unexpected error: ${e.stackTraceToString()}" }
        markCorrupted()
        DbHealth.CORRUPT 
    }
}
