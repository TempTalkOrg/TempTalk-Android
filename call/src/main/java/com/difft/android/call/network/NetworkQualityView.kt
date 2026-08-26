package com.difft.android.call.network

/**
 * Immutable weak-network snapshot the UI consumes. Produced only by [NetworkQualityTracker.view]:
 * both suppression rules are already applied here, so the UI never re-implements them.
 *
 * [remote] carries every non-excellent remote entry (GOOD included) to keep the snapshot shape
 * identical across platforms; Android renders only [badRemoteIdentities]. Rendering must therefore
 * never branch on `remote.isNotEmpty()` — a GOOD entry would falsely trigger it.
 */
data class NetworkQualityView(
    val local: NetworkQualityLevel = NetworkQualityLevel.EXCELLENT,
    val remote: Map<String, NetworkQualityLevel> = emptyMap(),
    /**
     * Diagnostic mirror of suppression rule 2 (the room is not connected). The snapshot is ALREADY
     * emptied when this is true — never branch rendering on it, or the suppression gets applied
     * twice and the render layer silently depends on a field whose shape may change. It exists so
     * a log line and the suppressed-transition test can observe the flag itself rather than only
     * its side effect.
     */
    val suppressed: Boolean = false,
) {
    /** Local participant is bad -> "your network is poor" banner, in every scene (1v1 and group). */
    val localIsBad: Boolean = local == NetworkQualityLevel.BAD

    /** The remote identities to decorate with a badge (group) or a banner (1v1). */
    val badRemoteIdentities: Set<String> = remote.filterValues { it == NetworkQualityLevel.BAD }.keys

    companion object {
        /** Healthy / not-yet-seeded call: nothing is rendered. */
        val NONE = NetworkQualityView()
    }
}

/**
 * Whether one participant's tile carries the weak-network badge.
 *
 * The badge decorates REMOTE participants in a MULTI-PARTY call and nothing else. The two exclusions
 * are load-bearing, not shortcuts:
 *  - the local tile never gets one. A bad local link is announced by the top banner in every scene,
 *    so badging the local tile as well would report the same fault twice — and it would read as "this
 *    person is the problem" on the one tile the user cannot interpret that way.
 *  - a two-person call hands a bad peer to the banner instead: with only one peer, "the other party"
 *    is unambiguous, and the banner is the more readable surface.
 *
 * Only the BAD tier renders. Both suppression rules are already applied upstream, so this never
 * re-derives them.
 *
 * @param localIdentity the same string the local tile passes as [identity]; both are
 *   `globalServices.myId` at every tile call site. The tracker keys its local entry by an internal
 *   sentinel, so this is a pure equality probe — it is never used as a map key. Passing the SDK's
 *   `room.localParticipant.identity?.value` here would break the probe: that value carries a
 *   `.deviceId` suffix the tile's identity does not.
 * @param participantCount headcount = local + the remote participants CURRENTLY in the room
 *   (`1 + room.remoteParticipants.count`). Invitees that are still ringing do not count, and the
 *   layout the user happens to see does not matter: the split is decided by how many people are in
 *   the call, so a 2-person group call behaves exactly like a 1v1 here.
 */
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

/** Local + one peer: the banner names the peer, no tile badge. */
private const val ONE_ON_ONE_PARTICIPANT_COUNT = 2
