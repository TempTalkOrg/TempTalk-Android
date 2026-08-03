package com.difft.android.chat.contacts.data

import org.difft.app.database.cache.OfficialAccountCache

/**
 * Server-driven official-account check. Backed by [OfficialAccountCache] (preloaded from
 * contactor.publicAccountType).
 */
fun String.isOfficialAccount(): Boolean = OfficialAccountCache.contains(this)
