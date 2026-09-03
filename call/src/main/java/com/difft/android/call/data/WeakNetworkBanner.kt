package com.difft.android.call.data

import com.difft.android.call.network.NetworkQualityView

/**
 * Which weak-network banner the single floating-pill slot should carry. Mirrors the shape of
 * [MediaSendIssueState]: a small enum plus one pure `resolve`, so the decision matrix stays
 * directly unit-testable and `callStatusNotification` grows by exactly one parameter.
 */
enum class WeakNetworkBanner {
    NONE,

    /** Local side is bad — shown in EVERY scene (1v1 and group). */
    LOCAL,

    /** A remote is bad AND only two people are in the call — otherwise the tile badge carries it. */
    REMOTE;

    companion object {
        /**
         * Pure mapping. Reads ONLY the two fields the render contract exposes:
         * [NetworkQualityView.localIsBad] and [NetworkQualityView.badRemoteIdentities].
         *
         * Deliberately does NOT look at `view.remote` (it also carries GOOD entries, which are
         * never rendered — `remote.isNotEmpty()` would falsely trigger the banner) and NOT at
         * `view.suppressed` (a suppressed snapshot is already emptied upstream, so branching on
         * the flag would apply the suppression twice).
         *
         * Local wins over remote both by this ordering and by construction: the upstream
         * suppression already empties the remote map whenever the local verdict is bad.
         *
         * @param participantCount headcount = local + the remote participants CURRENTLY in the
         *   room (`1 + room.remoteParticipants.count`). Ringing invitees do not count, and the
         *   layout the user happens to see does not matter: with exactly two people "the other
         *   party" is unambiguous and earns the banner, so a 2-person GROUP call takes this
         *   branch too. Crossing the boundary only switches which surface renders — it never
         *   restarts the verdict, which is held upstream.
         */
        fun resolve(view: NetworkQualityView, participantCount: Int): WeakNetworkBanner = when {
            view.localIsBad -> LOCAL
            participantCount == ONE_ON_ONE_PARTICIPANT_COUNT &&
                view.badRemoteIdentities.isNotEmpty() -> REMOTE
            else -> NONE
        }

        /** Local + one peer. */
        private const val ONE_ON_ONE_PARTICIPANT_COUNT = 2
    }
}
