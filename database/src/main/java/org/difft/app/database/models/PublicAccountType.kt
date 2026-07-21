package org.difft.app.database.models

/** Server-driven account classification carried in contacts-directory publicConfigs. */
object PublicAccountType {
    const val NORMAL = 0
    const val OFFICIAL = 1

    /**
     * Resolve the persisted account type when ingesting a contact update. A present server value
     * wins (including an explicit demote to NORMAL); an absent (null) value keeps the prior row's
     * type so a field-less update never demotes a known OFFICIAL; no prior row → NORMAL.
     */
    fun resolve(serverValue: Int?, priorValue: Int?): Int = serverValue ?: priorValue ?: NORMAL
}
