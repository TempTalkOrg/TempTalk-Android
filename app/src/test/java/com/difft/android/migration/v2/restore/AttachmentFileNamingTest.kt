package com.difft.android.migration.v2.restore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the "preserve, don't rewrite" naming rule.
 *
 * The regression this guards: the restore used to scrub everything outside `[A-Za-z0-9._-]`, so a
 * Chinese-named attachment landed on disk as `__…______.pdf` while the DB kept the real name. Every
 * reader derives its path from the DB name, so the bytes became unreachable and the attachment fell
 * back to a re-download whose server pointer had expired — the user saw "file expired" for a file
 * that had migrated successfully.
 */
class AttachmentFileNamingTest {

    @Test
    fun `preserves a non-ASCII name verbatim`() {
        val name = "订单1128150203646773-电子普通发票.pdf"
        assertEquals(name, AttachmentFileNaming.asPathComponent(name))
    }

    @Test
    fun `preserves characters that are legal in a file name`() {
        // Spaces, parentheses, '+', '&', '#', emoji and accents are all writable and must survive:
        // scrubbing any of them reintroduces the disk-vs-DB divergence.
        val name = "Q3 report (final) v2 +draft &notes #1 café 🎉.xlsx"
        assertEquals(name, AttachmentFileNaming.asPathComponent(name))
    }

    @Test
    fun `substitutes only characters that cannot be a path component`() {
        assertEquals("a_b_c", AttachmentFileNaming.asPathComponent("a/b\\c"))
        assertEquals("re_port_.txt", AttachmentFileNaming.asPathComponent("re:port?.txt"))
        // A name made only of separators still yields a usable component rather than being rejected.
        assertEquals("_", AttachmentFileNaming.asPathComponent("/"))
    }

    @Test
    fun `rejects names that cannot address a file`() {
        // These would make File(dir, name) resolve to the directory itself.
        assertNull(AttachmentFileNaming.asPathComponent(""))
        assertNull(AttachmentFileNaming.asPathComponent("   "))
        assertNull(AttachmentFileNaming.asPathComponent("."))
        assertNull(AttachmentFileNaming.asPathComponent(".."))
    }

    @Test
    fun `keeps a traversal attempt inside one component`() {
        // Separators are substituted, so the result can never escape the attachment directory.
        val cleaned = AttachmentFileNaming.asPathComponent("../../etc/passwd")
        assertEquals(".._.._etc_passwd", cleaned)
    }
}
