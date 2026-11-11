package com.difft.android.migration.v2.container








internal object FormatCompat {

    



    const val SUPPORTED_FORMAT_VERSION = ContainerFormat.FORMAT_VERSION

    



    fun isCompatible(manifestFormatVersion: String): Boolean {
        val supportedMajor = majorOf(SUPPORTED_FORMAT_VERSION) ?: return false
        val manifestMajor = majorOf(manifestFormatVersion) ?: return false
        return manifestMajor == supportedMajor
    }

    




    fun isNewerThanSupported(manifestFormatVersion: String): Boolean {
        val supportedMajor = majorOf(SUPPORTED_FORMAT_VERSION) ?: return false
        val manifestMajor = majorOf(manifestFormatVersion) ?: return false
        return manifestMajor > supportedMajor
    }

    
    private fun majorOf(version: String): Int? =
        version.substringBefore('.').trim().toIntOrNull()
}
