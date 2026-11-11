package org.difft.app.database.models

import com.tencent.wcdb.MultiPrimary
import com.tencent.wcdb.WCDBField
import com.tencent.wcdb.WCDBIndex
import com.tencent.wcdb.WCDBTableCoding
import java.util.Objects





@WCDBTableCoding(multiPrimaries = [MultiPrimary(columns = ["jobSpecId", "factoryKey"])])
class JobConstraintModel {

    




    @WCDBIndex
    @WCDBField(isNotNull = true)
    var jobSpecId: String = ""

    
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
