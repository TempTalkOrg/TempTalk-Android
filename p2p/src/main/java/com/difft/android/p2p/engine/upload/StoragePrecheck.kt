package com.difft.android.p2p.engine.upload

/**
 * The free-space seam at `prepare-upload`: `:p2p` has no `StatFs` and no idea where the import
 * lands, so the `:app` receiver supplies the answer and this module only decides what to do with it.
 * Null on paths with no storage rule (the byte-pipe test receiver, generic file transfer).
 */
fun interface StoragePrecheck {
    /**
     * @param declaredBytes the sum of the declared file sizes; 0 when the sender does not know it.
     * @return true to accept the session. Must not throw — a throw becomes a 500 instead of the
     *   typed 403 `insufficient-storage` the sender needs to show a specific message.
     */
    fun hasRoomFor(declaredBytes: Long): Boolean
}
