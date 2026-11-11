package com.difft.android.chat.contacts

import org.difft.app.database.models.PublicAccountType
import org.junit.Test
import kotlin.test.assertEquals









class ResolvePublicAccountTypeTest {

    
    @Test
    fun `server OFFICIAL wins over any prior`() {
        assertEquals(PublicAccountType.OFFICIAL, PublicAccountType.resolve(serverValue = 1, priorValue = null))
        assertEquals(PublicAccountType.OFFICIAL, PublicAccountType.resolve(serverValue = 1, priorValue = PublicAccountType.NORMAL))
        assertEquals(PublicAccountType.OFFICIAL, PublicAccountType.resolve(serverValue = 1, priorValue = PublicAccountType.OFFICIAL))
    }

    
    @Test
    fun `server NORMAL is an explicit demote and wins over prior OFFICIAL`() {
        assertEquals(PublicAccountType.NORMAL, PublicAccountType.resolve(serverValue = 0, priorValue = PublicAccountType.OFFICIAL))
    }

    
    @Test
    fun `absent server value keeps prior OFFICIAL`() {
        assertEquals(PublicAccountType.OFFICIAL, PublicAccountType.resolve(serverValue = null, priorValue = PublicAccountType.OFFICIAL))
    }

    
    @Test
    fun `absent server value keeps prior NORMAL`() {
        assertEquals(PublicAccountType.NORMAL, PublicAccountType.resolve(serverValue = null, priorValue = PublicAccountType.NORMAL))
    }

    
    @Test
    fun `absent server value and no prior defaults to NORMAL`() {
        assertEquals(PublicAccountType.NORMAL, PublicAccountType.resolve(serverValue = null, priorValue = null))
    }

    



    @Test
    fun `by-ids refetch with absent field keeps OFFICIAL`() {
        val serverOmittedField: Int? = null
        assertEquals(
            PublicAccountType.OFFICIAL,
            PublicAccountType.resolve(serverOmittedField, PublicAccountType.OFFICIAL),
        )
    }
}
