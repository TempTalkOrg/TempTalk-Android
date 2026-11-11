package com.difft.android.chat.contacts.data

import org.difft.app.database.cache.OfficialAccountCache





fun String.isOfficialAccount(): Boolean = OfficialAccountCache.contains(this)
