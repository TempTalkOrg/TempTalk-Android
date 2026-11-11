package com.difft.android.messageserialization.db.store

import android.content.Context
import kotlinx.coroutines.test.TestScope
import org.difft.app.database.WCDB








object TestWcdbFactory {

    






    fun createInMemoryWcdb(context: Context): WCDB {
        return WCDB(
            context,
            TestScope(),
        )
    }
}
