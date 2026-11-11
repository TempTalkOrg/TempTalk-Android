package com.difft.android.base.utils

import kotlin.math.min











object ProximityNearRule {

    
    const val SYSTEM_NEAR_THRESHOLD_CM = 5.0f

    




    fun isNear(distanceCm: Float, maximumRange: Float): Boolean {
        val threshold = if (maximumRange > 0f) min(maximumRange, SYSTEM_NEAR_THRESHOLD_CM) else SYSTEM_NEAR_THRESHOLD_CM
        return distanceCm >= 0f && distanceCm < threshold
    }
}
