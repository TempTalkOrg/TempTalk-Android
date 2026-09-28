package com.difft.android.migration.v2.container

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * I0 — [FormatCompat] single-owner version gate (Test Inventory T-FC1). The MAJOR-equality rule must
 * be identical everywhere it runs (file-path manifest validation, the streaming read path, and the
 * future P2P handshake pre-check). Pure JVM — no Robolectric needed.
 */
class FormatCompatTest {

    @Test
    fun `same MAJOR as supported is compatible (any MINOR, incl newer)`() {
        val supportedMajor = FormatCompat.SUPPORTED_FORMAT_VERSION.substringBefore('.')
        assertTrue(FormatCompat.isCompatible("$supportedMajor.0"), "same MAJOR.0 compatible")
        assertTrue(FormatCompat.isCompatible("$supportedMajor.9"), "same MAJOR, higher MINOR compatible (additive)")
        assertTrue(FormatCompat.isCompatible("$supportedMajor.99"), "MINOR is additive-only — newer MINOR accepted")
        assertTrue(FormatCompat.isCompatible(FormatCompat.SUPPORTED_FORMAT_VERSION), "exact supported version compatible")
    }

    @Test
    fun `a different MAJOR fails fast (cross-MAJOR is breaking)`() {
        val supportedMajor = FormatCompat.SUPPORTED_FORMAT_VERSION.substringBefore('.').toInt()
        assertFalse(FormatCompat.isCompatible("${supportedMajor + 1}.0"), "newer MAJOR rejected")
        if (supportedMajor > 0) {
            assertFalse(FormatCompat.isCompatible("${supportedMajor - 1}.0"), "older MAJOR rejected")
        }
        assertFalse(FormatCompat.isCompatible("999.0"), "far-future MAJOR rejected")
    }

    @Test
    fun `malformed version strings are treated as incompatible`() {
        assertFalse(FormatCompat.isCompatible(""), "empty rejected")
        assertFalse(FormatCompat.isCompatible("abc"), "non-numeric rejected")
        assertFalse(FormatCompat.isCompatible("x.y"), "non-numeric MAJOR rejected")
        assertFalse(FormatCompat.isCompatible(".5"), "missing MAJOR rejected")
    }

    // ── the version stays at the initial 1.0 until the feature ships ────────────────────────────

    @Test
    fun `this build supplies and accepts the initial 1 point 0`() {
        // Frozen deliberately: MAJOR is the only thing the gate compares, and nothing is released, so
        // additive schema growth does not move this. See ContainerFormat.FORMAT_VERSION.
        assertEquals("1.0", ContainerFormat.FORMAT_VERSION, "the version is frozen at the initial 1.0")
        assertTrue(FormatCompat.isCompatible("1.0"), "a 1.0 container is accepted")
    }

    @Test
    fun `other MINORs of the same MAJOR stay readable`() {
        // Containers stamped 1.1 exist — the version briefly read that during development, and another
        // end may still advertise it. MINOR is not gated, so they must import either way.
        assertTrue(FormatCompat.isCompatible("1.1"), "a 1.1 container is accepted")
        assertTrue(FormatCompat.isCompatible("1.9"), "any MINOR of MAJOR 1 is accepted")
    }

    @Test
    fun `a pre-identity 1 point 0 container is still accepted by this 1 point 1 build`() {
        // MINOR compatibility must hold in BOTH directions: an older container simply carries no
        // identity record, and the destination falls back to generating an identity at login.
        assertTrue(FormatCompat.isCompatible("1.0"), "1.0 containers must keep restoring after the bump")
    }

    @Test
    fun `the MAJOR gate is unchanged by the MINOR bump`() {
        assertFalse(FormatCompat.isCompatible("2.0"), "cross-MAJOR is still a hard reject")
        assertFalse(FormatCompat.isCompatible("0.9"), "older MAJOR is still a hard reject")
    }

    @Test
    fun `supported version is sourced from the container format constant`() {
        // Single source of truth: FormatCompat mirrors the exporter's ContainerFormat.FORMAT_VERSION,
        // so a writer-produced container always declares a MAJOR this gate accepts.
        assertTrue(
            FormatCompat.isCompatible(ContainerFormat.FORMAT_VERSION),
            "the exporter's own FORMAT_VERSION must always be self-compatible",
        )
    }
}
