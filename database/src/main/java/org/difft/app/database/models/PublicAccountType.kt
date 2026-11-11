package org.difft.app.database.models


object PublicAccountType {
    const val NORMAL = 0
    const val OFFICIAL = 1

    




    fun resolve(serverValue: Int?, priorValue: Int?): Int = serverValue ?: priorValue ?: NORMAL
}
