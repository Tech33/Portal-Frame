package com.portalhacks.frame

import java.io.IOException

/** An album from any provider: its display title (may be empty) and its photos. */
class Album(
    @JvmField val title: String,
    @JvmField val slides: List<Slide>,
)

/**
 * A source of shared-album photos (Google Photos, iCloud, …). Each provider knows
 * which links it handles and how to turn one into an [Album].
 */
interface PhotoProvider {
    /** Human-facing name, e.g. "Google Photos" / "iCloud". */
    val displayName: String

    /** True if this provider handles [url]. */
    fun matches(url: String): Boolean

    /** Fetch the album. Throws on network/parse failure; may return an empty album. */
    @Throws(Exception::class)
    fun fetch(url: String): Album
}

/** Registry of the supported photo providers; routes a link to the right one. */
object PhotoSources {

    private val providers: List<PhotoProvider> = listOf(GooglePhotosSource, ApplePhotosSource)

    /** True if any provider recognises [url] as one of its shared-album links. */
    fun matches(url: String?): Boolean = url != null && providers.any { it.matches(url) }

    /** The provider that handles [url], or null. */
    fun providerFor(url: String): PhotoProvider? = providers.firstOrNull { it.matches(url) }

    /** Fetch [url] with whichever provider owns it. */
    @Throws(Exception::class)
    fun fetch(url: String): Album {
        val p = providerFor(url)
            ?: throw IOException("unsupported album link (not Google Photos or iCloud)")
        return p.fetch(url)
    }
}
