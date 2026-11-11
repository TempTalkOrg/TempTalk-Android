package com.difft.android.chat.contacts

import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.isOfficialAccount
import com.difft.android.network.responses.ContactResponse
import com.difft.android.network.responses.PublicConfigs
import org.difft.app.database.cache.OfficialAccountCache
import org.difft.app.database.models.PublicAccountType
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue





@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PublicAccountTypeMappingTest {

    @Before
    fun setUp() {
        OfficialAccountCache.clear()
    }

    @After
    fun tearDown() {
        OfficialAccountCache.clear()
    }

    
    @Test
    fun `T4 isOfficialAccount reflects server-driven cache not id length`() {
        OfficialAccountCache.replaceAll(setOf("+10000"))
        assertTrue("+10000".isOfficialAccount())

        assertFalse("12345".isOfficialAccount())
    }

    
    @Test
    fun `T5 from maps publicAccountType OFFICIAL`() {
        val model = ContactorUtil.from(
            ContactResponse(number = "+10000", name = "Official", publicConfigs = PublicConfigs(publicAccountType = 1))
        )
        assertEquals(PublicAccountType.OFFICIAL, model?.publicAccountType)
    }

    
    @Test
    fun `T5 from maps publicAccountType NORMAL`() {
        val model = ContactorUtil.from(
            ContactResponse(number = "u1", name = "User", publicConfigs = PublicConfigs(publicAccountType = 0))
        )
        assertEquals(PublicAccountType.NORMAL, model?.publicAccountType)
    }

    
    @Test
    fun `T5 from defaults to NORMAL when field absent`() {
        val nullConfigs = ContactorUtil.from(ContactResponse(number = "u2", name = "User"))
        assertEquals(PublicAccountType.NORMAL, nullConfigs?.publicAccountType)

        val nullField = ContactorUtil.from(
            ContactResponse(number = "u3", name = "User", publicConfigs = PublicConfigs(publicAccountType = null))
        )
        assertEquals(PublicAccountType.NORMAL, nullField?.publicAccountType)
    }
}
