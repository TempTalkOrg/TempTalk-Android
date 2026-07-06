package com.difft.android.network

import com.difft.android.base.user.Data
import com.difft.android.base.user.Domain
import com.difft.android.base.user.Service
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ServiceUrlResolver]. Verifies label→host
 * resolution, prod/dev shapes, and the null/drop fallbacks.
 *
 * The [prodDomains]/[prodServices] fixture is a wide N-host shape (4 chat
 * hosts) used to exercise order preservation and drop behavior — it does NOT
 * mirror the bundled `default_global_config.json`. The bundled TTOnline assets
 * currently ship chat → [chat1, chat4] (2 hosts) and TTDev ships chat →
 * [chat1] (1 host); both shapes are covered explicitly below so the resolver
 * is verified against the shapes the app actually cold-starts with.
 */
class ServiceUrlResolverTest {

    private val prodDomains = listOf(
        Domain(label = "chat1", domain = "chat.chative.im", certType = "self"),
        Domain(label = "chat2", domain = "chat.chative.online", certType = "self"),
        Domain(label = "chat3", domain = "chat.chative.ninja", certType = "self"),
        Domain(label = "chat4", domain = "chat.temptalk.net", certType = "self"),
        Domain(label = "avatar", domain = "d272r1ud4wbyy4.cloudfront.net", certType = "authority"),
    )

    private val prodServices = listOf(
        Service(name = "chat", path = "/chat", domains = listOf("chat1", "chat2", "chat3", "chat4")),
        Service(name = "call", path = "/call", domains = listOf("chat1", "chat2", "chat3", "chat4")),
        Service(name = "avatar", path = "", domains = listOf("avatar")),
    )

    private val prodData = Data(domains = prodDomains, services = prodServices)

    @Test
    fun `prod chat resolves to all four hosts in configured order`() {
        val resolved = ServiceUrlResolver.resolve(prodData, "chat")
        assertEquals(
            listOf("chat.chative.im", "chat.chative.online", "chat.chative.ninja", "chat.temptalk.net"),
            resolved?.hosts,
        )
        assertEquals("/chat", resolved?.path)
    }

    @Test
    fun `prod chat host set equals the expected fixture set`() {
        val hosts = ServiceUrlResolver.resolve(prodData, "chat")?.hosts.orEmpty().toSet()
        assertEquals(
            setOf("chat.chative.im", "chat.chative.online", "chat.chative.ninja", "chat.temptalk.net"),
            hosts,
        )
    }

    @Test
    fun `bundled TTOnline two-host chat resolves to chat1 and chat4 in order`() {
        // Mirrors app/src/TTOnline/assets/default_global_config.json (the shape
        // the app cold-starts with): chat → [chat1, chat4], no chat2/chat3.
        val assetsData = Data(
            domains = listOf(
                Domain(label = "chat1", domain = "chat.chative.im", certType = "self"),
                Domain(label = "chat4", domain = "chat.temptalk.net", certType = "self"),
            ),
            services = listOf(Service(name = "chat", path = "/chat", domains = listOf("chat1", "chat4"))),
        )

        val resolved = ServiceUrlResolver.resolve(assetsData, "chat")
        assertEquals(listOf("chat.chative.im", "chat.temptalk.net"), resolved?.hosts)
        assertEquals("/chat", resolved?.path)
    }

    @Test
    fun `dev chat resolves to a single host`() {
        val devData = Data(
            domains = listOf(Domain(label = "chat1", domain = "chat.test.chative.im", certType = "self")),
            services = listOf(Service(name = "chat", path = "/chat", domains = listOf("chat1"))),
        )

        val resolved = ServiceUrlResolver.resolve(devData, "chat")
        assertEquals(1, resolved?.hosts?.size)
        assertEquals(listOf("chat.test.chative.im"), resolved?.hosts)
        assertEquals(setOf("chat.test.chative.im"), resolved?.hosts?.toSet())
        assertEquals("/chat", resolved?.path)
    }

    @Test
    fun `null data returns null`() {
        assertNull(ServiceUrlResolver.resolve(null, "chat"))
    }

    @Test
    fun `missing services returns null`() {
        val data = Data(domains = prodDomains, services = null)
        assertNull(ServiceUrlResolver.resolve(data, "chat"))
    }

    @Test
    fun `missing domains returns null because no label resolves`() {
        val data = Data(domains = null, services = prodServices)
        assertNull(ServiceUrlResolver.resolve(data, "chat"))
    }

    @Test
    fun `unmatched label is dropped and matched labels are kept`() {
        // chat2 has no matching top-level domain → dropped; the other three remain.
        val data = Data(
            domains = listOf(
                Domain(label = "chat1", domain = "chat.chative.im", certType = "self"),
                Domain(label = "chat3", domain = "chat.chative.ninja", certType = "self"),
                Domain(label = "chat4", domain = "chat.temptalk.net", certType = "self"),
            ),
            services = listOf(
                Service(name = "chat", path = "/chat", domains = listOf("chat1", "chat2", "chat3", "chat4")),
            ),
        )

        val hosts = ServiceUrlResolver.resolve(data, "chat")?.hosts
        assertEquals(listOf("chat.chative.im", "chat.chative.ninja", "chat.temptalk.net"), hosts)
        assertTrue(hosts?.none { it == "chat.chative.online" } == true, "unmatched chat2 host must be excluded")
    }

    @Test
    fun `unknown service name returns null`() {
        assertNull(ServiceUrlResolver.resolve(prodData, "nonexistent"))
    }

    @Test
    fun `empty path service resolves with blank path`() {
        // avatar has path == "" — resolver returns it (caller decides default).
        val resolved = ServiceUrlResolver.resolve(prodData, "avatar")
        assertEquals(listOf("d272r1ud4wbyy4.cloudfront.net"), resolved?.hosts)
        assertEquals("", resolved?.path)
    }

    @Test
    fun `service with all blank-or-missing hosts returns null`() {
        // Labels match but the domains are blank → all dropped → empty → null.
        val data = Data(
            domains = listOf(Domain(label = "chat1", domain = "", certType = "self")),
            services = listOf(Service(name = "chat", path = "/chat", domains = listOf("chat1"))),
        )
        assertNull(ServiceUrlResolver.resolve(data, "chat"))
    }

    @Test
    fun `resolvePath returns the service path`() {
        assertEquals("/chat", ServiceUrlResolver.resolvePath(prodData, "chat"))
        assertEquals("/call", ServiceUrlResolver.resolvePath(prodData, "call"))
    }

    @Test
    fun `resolvePath returns path even when domain labels do not resolve`() {
        // path-only resolution must not depend on host-label resolution: a
        // server-configured path is honored even if its domains are missing.
        val data = Data(
            domains = null,
            services = listOf(Service(name = "chat", path = "/chatv2", domains = listOf("chat1"))),
        )
        assertEquals("/chatv2", ServiceUrlResolver.resolvePath(data, "chat"))
        // full resolve() (host-dependent) returns null in the same state
        assertNull(ServiceUrlResolver.resolve(data, "chat"))
    }

    @Test
    fun `resolvePath returns null for unknown service or null data`() {
        assertNull(ServiceUrlResolver.resolvePath(prodData, "nonexistent"))
        assertNull(ServiceUrlResolver.resolvePath(null, "chat"))
    }
}
