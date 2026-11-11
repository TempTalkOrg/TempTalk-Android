package com.difft.android.call.network









data class NetworkQualityView(
    val local: NetworkQualityLevel = NetworkQualityLevel.EXCELLENT,
    val remote: Map<String, NetworkQualityLevel> = emptyMap(),
    






    val suppressed: Boolean = false,
) {
    
    val localIsBad: Boolean = local == NetworkQualityLevel.BAD

    
    val badRemoteIdentities: Set<String> = remote.filterValues { it == NetworkQualityLevel.BAD }.keys

    companion object {
        
        val NONE = NetworkQualityView()
    }
}

























fun resolveBadge(
    view: NetworkQualityView,
    localIdentity: String,
    participantCount: Int,
    identity: String,
): Boolean {
    if (identity == localIdentity) return false
    if (participantCount <= ONE_ON_ONE_PARTICIPANT_COUNT) return false
    return identity in view.badRemoteIdentities
}


private const val ONE_ON_ONE_PARTICIPANT_COUNT = 2
