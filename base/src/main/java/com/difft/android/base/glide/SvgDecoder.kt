package com.difft.android.base.glide

import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.SimpleResource
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import com.caverock.androidsvg.SVG
import com.difft.android.base.log.lumberjack.L
import java.io.InputStream

class SvgDecoder : ResourceDecoder<InputStream, SVG> {
    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<SVG>? {
        return try {
            val svg = SVG.getFromInputStream(source)
            if (width != SIZE_ORIGINAL)
                svg.documentWidth = width.toFloat()
            if (height != SIZE_ORIGINAL)
                svg.documentHeight = height.toFloat()
            SimpleResource(svg)
        } catch (e: Exception) {
            L.w(e) { "[SvgDecoder] decode svg fail:" }
            null
        }
    }

    /**
     * Only claim streams that actually look like SVG. Glide tries every registered
     * `InputStream -> X` decoder; without this gate, every raster image loaded via an InputStream
     * model (e.g. the decrypting content uri for encrypted-at-rest attachments) would be fed to
     * [SVG.getFromInputStream], fail with "SVG document is empty", and log noise before Glide's
     * bitmap decoder takes over. Sniff the first bytes and defer non-SVG streams to other decoders.
     */
    override fun handles(source: InputStream, options: Options): Boolean {
        if (!source.markSupported()) return false // non-SVG: let the bitmap decoder handle it
        return try {
            source.mark(SNIFF_LIMIT)
            val buf = ByteArray(SNIFF_LIMIT)
            val n = source.read(buf)
            source.reset()
            if (n <= 0) return false
            val head = String(buf, 0, n, Charsets.US_ASCII).trimStart().lowercase()
            head.startsWith("<?xml") || head.startsWith("<svg")
        } catch (e: Exception) {
            runCatching { source.reset() }
            false
        }
    }

    companion object {
        private const val SNIFF_LIMIT = 64
    }
}
