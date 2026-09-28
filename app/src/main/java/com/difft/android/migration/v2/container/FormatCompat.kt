package com.difft.android.migration.v2.container

/**
 * Sole owner of the container format-version gate, so every caller applies the same rule.
 *
 * A container whose MAJOR differs from [SUPPORTED_FORMAT_VERSION] is rejected; the same MAJOR at any
 * MINOR is accepted, because MINOR is additive-only and an older importer passes unknown keys
 * through. Unparseable version strings are rejected as unsupported.
 */
internal object FormatCompat {

    /**
     * The container format version this app supports (`MAJOR.MINOR`). The MAJOR component gates
     * compatibility (cross-MAJOR reject). Mirrors the exporter's [ContainerFormat.FORMAT_VERSION].
     */
    const val SUPPORTED_FORMAT_VERSION = ContainerFormat.FORMAT_VERSION

    /**
     * @return true iff [manifestFormatVersion]'s MAJOR equals [SUPPORTED_FORMAT_VERSION]'s MAJOR.
     *   A malformed/unparseable version (either side) is treated as incompatible (returns false).
     */
    fun isCompatible(manifestFormatVersion: String): Boolean {
        val supportedMajor = majorOf(SUPPORTED_FORMAT_VERSION) ?: return false
        val manifestMajor = majorOf(manifestFormatVersion) ?: return false
        return manifestMajor == supportedMajor
    }

    /**
     * True when the container's MAJOR is newer than this build supports — the one rejection with a
     * user-actionable fix, so callers surface upgrade guidance instead of the generic message.
     * Malformed versions return false and fall to the generic reject via [isCompatible].
     */
    fun isNewerThanSupported(manifestFormatVersion: String): Boolean {
        val supportedMajor = majorOf(SUPPORTED_FORMAT_VERSION) ?: return false
        val manifestMajor = majorOf(manifestFormatVersion) ?: return false
        return manifestMajor > supportedMajor
    }

    /** Parse the MAJOR component of a `MAJOR.MINOR` version string, or null if malformed. */
    private fun majorOf(version: String): Int? =
        version.substringBefore('.').trim().toIntOrNull()
}
