package org.difft.app.database.models

import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects





@WCDBTableCoding
class JobSpecModel {

    
    @WCDBField(isPrimary = true, isNotNull = true)
    var jobSpecId: String = ""

    
    @WCDBField(isNotNull = true)
    var factoryKey: String = ""

    



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

    



    @WCDBField
    var serializedData: String? = null

    




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
