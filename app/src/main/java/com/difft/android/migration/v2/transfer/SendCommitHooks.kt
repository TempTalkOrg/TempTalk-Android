package com.difft.android.migration.v2.transfer

/**
 * What the send pipeline tells its owner around the receiver's commit point. Used by the message
 * pause of a device-replacement export (#1382); a send without one behaves as before.
 */
interface SendCommitHooks {

    /** Runs right before the container's end marker; throwing leaves the container uncommittable. */
    suspend fun beforeFinalize()

    /**
     * [beforeFinalize] passed and the end marker may be written from here, so a receiver may
     * commit. Until this runs the run can prove no receiver committed (#1382 A01).
     */
    fun onEndMarker() = Unit

    /**
     * The receiver's answer to the `/cancel` this sender sent after it stopped, or null when it gave
     * none (see `CancelState`).
     */
    fun onAbortAnswer(answer: String?)
}
