package com.difft.android.migration.v2.restore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test





class AttachmentFileNamingTest {

    @Test
    fun `preserves a non-ASCII name verbatim`() {
        val name = "订单1128150203646773-电子普通发票.pdf"
        assertEquals(name, AttachmentFileNaming.asPathComponent(name))
    }

    @Test
    fun `preserves characters that are legal in a file name`() {
        
        
        val name = "Q3 report (final) v2 +draft &notes #1 café 🎉.xlsx"
        assertEquals(name, AttachmentFileNaming.asPathComponent(name))
    }

    @Test
    fun `substitutes only characters that cannot be a path component`() {
        assertEquals("a_b_c", AttachmentFileNaming.asPathComponent("a/b\\c"))
        assertEquals("re_port_.txt", AttachmentFileNaming.asPathComponent("re:port?.txt"))
        
        assertEquals("_", AttachmentFileNaming.asPathComponent("/"))
    }

    @Test
    fun `rejects names that cannot address a file`() {
        
        assertNull(AttachmentFileNaming.asPathComponent(""))
        assertNull(AttachmentFileNaming.asPathComponent("   "))
        assertNull(AttachmentFileNaming.asPathComponent("."))
        assertNull(AttachmentFileNaming.asPathComponent(".."))
    }

    @Test
    fun `keeps a traversal attempt inside one component`() {
        
        val cleaned = AttachmentFileNaming.asPathComponent("../../etc/passwd")
        assertEquals(".._.._etc_passwd", cleaned)
    }
}
