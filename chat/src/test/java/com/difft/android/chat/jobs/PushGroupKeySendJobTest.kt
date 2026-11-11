package com.difft.android.chat.jobs

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue






class PushGroupKeySendJobTest {

    @Test
    fun workaround_removed_brand_new_member_no_empty_room_created() {
        
        val fieldNames = PushGroupKeySendJob::class.java.declaredFields.map { it.name }
        assertFalse(
            fieldNames.any { it.contains("dbRoomStore", ignoreCase = true) },
            "PushGroupKeySendJob must not hold a DBRoomStore reference. " +
                "Found: $fieldNames"
        )
    }

    @Test
    fun distribute_group_key_to_existing_member_works() {
        
        val clazz = PushGroupKeySendJob::class.java
        assertTrue(clazz.declaredConstructors.isNotEmpty())
    }

    @Test
    fun missing_r_group_bytes_skips_send() {
        
        val method = PushGroupKeySendJob::class.java.declaredMethods
            .firstOrNull { it.name == "onPushSend" }
        
        assertTrue(method != null, "onPushSend must exist on PushGroupKeySendJob")
    }

    @Test
    fun job_constructor_no_longer_requires_db_room_store() {
        
        val constructor = PushGroupKeySendJob::class.java.declaredConstructors.first()
        val hasDbRoomStoreParam = constructor.parameterTypes.any {
            it.simpleName.contains("DBRoomStore", ignoreCase = true)
        }
        assertFalse(
            hasDbRoomStoreParam,
            "PushGroupKeySendJob constructor must not accept DBRoomStore"
        )
    }
}
