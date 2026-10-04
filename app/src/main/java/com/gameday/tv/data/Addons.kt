package com.gameday.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/*
 * Streaming add-ons that speak the Stremio add-on protocol (Cinemeta, Comet, Torrentio, MediaFusion,
 * AIOStreams…), the same ones Nuvio and Stremio use. An add-on is a base URL serving:
 *   manifest.json                        what it offers
 *   catalog/{type}/{id}[/{extra}].json   rows of titles
 *   meta/{type}/{id}.json                details, seasons and episodes
 *   stream/{type}/{id}.json              playable sources (URLs, or torrents for a debrid service)
 */

data class AddonResource(val name: String, val types: List<String>?, val idPrefixes: List<String>?)

data class CatalogExtra(val name: String, val isRequired: Boolean, val options: List<String>)

data class AddonCatalog(val type: String, val id: String, val name: String, val extras: List<CatalogExtra>) {
    val needsExtra: Boolean get() = extras.any { it.isRequired }
    val searchable: Boolean get() = extras.any { it.name == "search" }
    val genres: List<String> get() = extras.firstOrNull { it.name == "genre" }?.options.orEmpty()
}

data class AddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val logo: String?,
    val types: List<String>,
    val catalogs: List<AddonCatalog>,
    val resources: List<AddonResource>,
    val idPrefixes: List<String>?,
    /** The add-on must be configured on its website before it works. */
    val configurationRequired: Boolean,
) {
    fun supports(resource: String, type: String, id: String): Boolean {
        val r = resources.firstOrNull { it.name == resource } ?: return false
        val types = r.types ?: this.types
        if (types.isNotEmpty() && type !in types) return false
        val prefixes = r.idPrefixes ?: idPrefixes
        return prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) }
    }
}

/**
 * An installed add-on: the manifest URL it was added with (which can carry the add-on's settings,
 * including debrid keys, so it's stored encrypted) and what it said about itself.
 */
data class InstalledAddon(val url: String, val manifest: AddonManifest, val manifestJson: String, val enabled: Boolean = true) {
    val base: String get() = Addons.baseOf(url)
    val name: String get() = manifest.name
    val hasStreams: Boolean get() = manifest.resources.any { it.name == "stream" }
    val hasCatalogs: Boolean get() = manifest.catalogs.isNotEmpty()
    val hasSubtitles: Boolean get() = manifest.resources.any { it.name == "subtitles" }
}

/** A title in a catalog row or search result. */
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: String?,
    val genres: List<String>,
    val posterShape: String = "poster",
)

data class MetaVideo(
    /** Stream id for this episode, e.g. "tt0944947:1:2". */
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val released: Long?,
    val thumbnail: String?,
    val overview: String?,
)

data class MetaDetail(
    val preview: MetaPreview,
    val runtime: String?,
    val cast: List<String>,
    val director: List<String>,
    val videos: List<MetaVideo>,
) {
    val seasons: List<Int> get() = videos.map { it.season }.distinct().sorted().let { s -> s.filter { it > 0 } + s.filter { it <= 0 } }
}

/** A playable source from a stream add-on. */
data class AddonStream(
    val addon: String,
    val name: String,
    val description: String,
    val url: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val filename: String?,
    val bingeGroup: String?,
    val headers: Map<String, String>,
    val externalUrl: String?,
    val trackers: List<String>,
    val sizeBytes: Long?,
    /** Subtitles the source lists itself. */
    val subtitles: List<SubtitleTrack> = emptyList(),
) {
    /** "4K", "1080p"… parsed from the add-on's labels. */
    val quality: String? get() = Addons.quality("$name $description ${filename.orEmpty()}")
    val isTorrent: Boolean get() = url == null && infoHash != null
    val playable: Boolean get() = url != null || infoHash != null
}

object Addons {
    const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"

    /** Accepts "https://host/…/manifest.json", the add-on's page URL, or stremio:// links. */
    fun normalizeUrl(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("stremio://", true)) s = "https://" + s.substring("stremio://".length)
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        s = s.substringBefore('#').trimEnd('/')
        if (s.endsWith("/configure", true)) s = s.dropLast("/configure".length)
        if (!s.endsWith("/manifest.json", true)) s = "$s/manifest.json"
        return s
    }

    fun baseOf(manifestUrl: String): String = manifestUrl.substringBefore('?').removeSuffix("/manifest.json").trimEnd('/')

    /** encodeURIComponent, which is how Stremio puts ids in paths ("tt1:1:2" → "tt1%3A1%3A2"). */
    fun encode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 128 || ch in "-_.!~*'()") append(ch) else append('%').append("%02X".format(c))
        }
    }

    suspend fun install(rawUrl: String): InstalledAddon {
        val url = normalizeUrl(rawUrl)
        val body = Http.getString(url)
        val manifest = parseManifest(body) ?: throw IOException("That address isn't a Stremio-compatible add-on.")
        if (manifest.configurationRequired) throw IOException("${manifest.name} needs to be set up first. Configure it on its website, then add the link it gives you.")
        return InstalledAddon(url, manifest, body)
    }

    fun encodeInstalled(list: List<InstalledAddon>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("url", it.url).put("enabled", it.enabled).put("manifest", it.manifestJson)) }
        return arr.toString()
    }

    fun decodeInstalled(s: String): List<InstalledAddon> = runCatching {
        JSONArray(s).objects().mapNotNull { o ->
            val url = o.optString("url").clean() ?: return@mapNotNull null
            val json = o.optString("manifest")
            val manifest = parseManifest(json) ?: return@mapNotNull null
            InstalledAddon(url, manifest, json, o.optBoolean("enabled", true))
        }.toList()
    }.getOrDefault(emptyList())

    fun catalogUrl(addon: InstalledAddon, catalog: AddonCatalog, extra: Map<String, String> = emptyMap()): String {
        val ex = extra.filterValues { it.isNotEmpty() }.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val path = "${encode(catalog.type)}/${encode(catalog.id)}"
        return "${addon.base}/catalog/$path${if (ex.isEmpty()) "" else "/$ex"}.json"
    }

    suspend fun catalog(addon: InstalledAddon, catalog: AddonCatalog, extra: Map<String, String> = emptyMap()): List<MetaPreview> =
        parseMetas(Http.getString(catalogUrl(addon, catalog, extra)), catalog.type)

    /** Details from the first add-on that has them. */
    suspend fun meta(addons: List<InstalledAddon>, type: String, id: String): MetaDetail? {
        for (a in addons.filter { it.enabled && it.manifest.supports("meta", type, id) }) {
            val body = runCatching { Http.getString("${a.base}/meta/${encode(type)}/${encode(id)}.json") }.getOrNull() ?: continue
            parseMeta(body, type)?.let { return it }
        }
        return null
    }

    /** Streams from every add-on that has them, fetched in parallel; slow add-ons are skipped. */
    suspend fun streams(addons: List<InstalledAddon>, type: String, id: String, timeoutMs: Long = 20_000): List<AddonStream> = coroutineScope {
        addons.filter { it.enabled && it.manifest.supports("stream", type, id) }.map { a ->
            async {
                withTimeoutOrNull(timeoutMs) {
                    runCatching { parseStreams(Http.getString("${a.base}/stream/${encode(type)}/${encode(id)}.json"), a.name) }.getOrNull()
                }.orEmpty()
            }
        }.awaitAll().flatten()
    }

    fun subtitlesUrl(addon: InstalledAddon, type: String, id: String, filename: String?, videoSize: Long?): String {
        val extra = listOfNotNull(
            filename?.let { "filename=${encode(it)}" },
            videoSize?.takeIf { it > 0 }?.let { "videoSize=$it" },
        ).joinToString("&")
        return "${addon.base}/subtitles/${encode(type)}/${encode(id)}${if (extra.isEmpty()) "" else "/$extra"}.json"
    }

    /** Subtitles from every subtitle add-on, in parallel; slow ones are skipped. */
    suspend fun subtitles(addons: List<InstalledAddon>, type: String, id: String, filename: String?, videoSize: Long?, timeoutMs: Long = 12_000): List<SubtitleTrack> =
        coroutineScope {
            addons.filter { it.enabled && it.manifest.supports("subtitles", type, id) }.map { a ->
                async {
                    withTimeoutOrNull(timeoutMs) {
                        runCatching { Subtitles.parseList(Http.getString(subtitlesUrl(a, type, id, filename, videoSize)), a.name) }.getOrNull()
                    }.orEmpty()
                }
            }.awaitAll().flatten().distinctBy { it.url }
        }

    // ---- parsing (pure, unit tested) ----

    fun parseManifest(body: String): AddonManifest? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val id = o.optString("id").clean() ?: return null
        val name = o.optString("name").clean() ?: id
        val resources = ArrayList<AddonResource>()
        o.optJSONArray("resources")?.let { arr ->
            for (i in 0 until arr.length()) {
                when (val r = arr.opt(i)) {
                    is String -> resources += AddonResource(r, null, null)
                    is JSONObject -> r.optString("name").clean()?.let { n ->
                        resources += AddonResource(n, r.optJSONArray("types")?.strings(), r.optJSONArray("idPrefixes")?.strings())
                    }
                }
            }
        }
        val catalogs = o.optJSONArray("catalogs")?.objects()?.mapNotNull { c ->
            val type = c.optString("type").clean() ?: return@mapNotNull null
            val cid = c.optString("id").clean() ?: return@mapNotNull null
            val extras = ArrayList<CatalogExtra>()
            c.optJSONArray("extra")?.objects()?.forEach { e ->
                e.optString("name").clean()?.let { extras += CatalogExtra(it, e.optBoolean("isRequired"), e.optJSONArray("options")?.strings().orEmpty()) }
            }
            // Older manifests list extras by name only.
            c.optJSONArray("extraSupported")?.strings()?.forEach { n ->
                if (extras.none { it.name == n }) extras += CatalogExtra(n, c.optJSONArray("extraRequired")?.strings()?.contains(n) == true, emptyList())
            }
            AddonCatalog(type, cid, c.optString("name").clean() ?: cid, extras)
        }?.toList().orEmpty()
        if (resources.isEmpty() && catalogs.isNotEmpty()) resources += AddonResource("catalog", null, null)
        val hints = o.optJSONObject("behaviorHints")
        return AddonManifest(
            id = id,
            name = name,
            version = o.optString("version").clean() ?: "",
            description = o.optString("description").clean(),
            logo = o.optString("logo").clean(),
            types = o.optJSONArray("types")?.strings().orEmpty(),
            catalogs = catalogs,
            resources = resources,
            idPrefixes = o.optJSONArray("idPrefixes")?.strings(),
            configurationRequired = hints?.optBoolean("configurationRequired") == true,
        )
    }

    fun parseMetas(body: String, fallbackType: String): List<MetaPreview> {
        val arr = runCatching { JSONObject(body).optJSONArray("metas") }.getOrNull() ?: return emptyList()
        return arr.objects().mapNotNull { parsePreview(it, fallbackType) }.toList()
    }

    private fun parsePreview(o: JSONObject, fallbackType: String): MetaPreview? {
        val id = o.optString("id").clean() ?: o.optString("imdb_id").clean() ?: return null
        return MetaPreview(
            id = id,
            type = o.optString("type").clean() ?: fallbackType,
            name = o.optString("name").clean() ?: return null,
            poster = o.optString("poster").clean()?.takeIf { it.startsWith("http") },
            background = o.optString("background").clean()?.takeIf { it.startsWith("http") },
            logo = o.optString("logo").clean()?.takeIf { it.startsWith("http") },
            description = o.optString("description").clean(),
            releaseInfo = o.optString("releaseInfo").clean() ?: o.optString("year").clean(),
            imdbRating = o.optString("imdbRating").clean(),
            genres = (o.optJSONArray("genres") ?: o.optJSONArray("genre"))?.strings().orEmpty(),
            posterShape = o.optString("posterShape").clean() ?: "poster",
        )
    }

    fun parseMeta(body: String, fallbackType: String): MetaDetail? {
        val o = runCatching { JSONObject(body).optJSONObject("meta") }.getOrNull() ?: return null
        val preview = parsePreview(o, fallbackType) ?: return null
        val videos = o.optJSONArray("videos")?.objects()?.mapNotNull { v ->
            val vid = v.optString("id").clean() ?: return@mapNotNull null
            val season = if (v.has("season")) v.optInt("season") else 1
            val episode = if (v.has("episode")) v.optInt("episode") else v.optInt("number", 0)
            MetaVideo(
                id = vid,
                title = v.optString("name").clean() ?: v.optString("title").clean() ?: "Episode $episode",
                season = season,
                episode = episode,
                released = v.optString("released").clean()?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                thumbnail = v.optString("thumbnail").clean()?.takeIf { it.startsWith("http") },
                overview = v.optString("overview").clean() ?: v.optString("description").clean(),
            )
        }?.sortedWith(compareBy({ if (it.season <= 0) Int.MAX_VALUE else it.season }, { it.episode }))?.toList().orEmpty()
        return MetaDetail(
            preview = preview,
            runtime = o.optString("runtime").clean(),
            cast = o.optJSONArray("cast")?.strings().orEmpty(),
            director = o.optJSONArray("director")?.strings().orEmpty(),
            videos = videos,
        )
    }

    fun parseStreams(body: String, addonName: String): List<AddonStream> {
        val arr = runCatching { JSONObject(body).optJSONArray("streams") }.getOrNull() ?: return emptyList()
        return arr.objects().mapNotNull { s ->
            val hints = s.optJSONObject("behaviorHints")
            val headers = HashMap<String, String>()
            hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { h -> h.keys().forEach { k -> h.optString(k).clean()?.let { headers[k] = it } } }
            val url = s.optString("url").clean()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            val hash = s.optString("infoHash").clean()?.lowercase()?.takeIf { it.length == 40 || it.length == 32 }
            val external = s.optString("externalUrl").clean()
            if (url == null && hash == null && external == null) return@mapNotNull null
            val title = s.optString("title").clean() ?: s.optString("description").clean() ?: ""
            AddonStream(
                addon = addonName,
                name = s.optString("name").clean() ?: addonName,
                description = title,
                url = url,
                infoHash = hash,
                fileIdx = if (s.has("fileIdx") && !s.isNull("fileIdx")) s.optInt("fileIdx") else null,
                filename = hints?.optString("filename").clean(),
                bingeGroup = hints?.optString("bingeGroup").clean(),
                headers = headers,
                externalUrl = external,
                trackers = s.optJSONArray("sources")?.strings().orEmpty().filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") },
                sizeBytes = hints?.optLong("videoSize")?.takeIf { it > 0 } ?: sizeFromText(title),
                subtitles = s.optJSONArray("subtitles")?.let { Subtitles.parseArray(it, addonName) }.orEmpty(),
            )
        }.toList()
    }

    private val QUALITY = listOf(
        Regex("\\b(2160p|4k|uhd)\\b", RegexOption.IGNORE_CASE) to "4K",
        Regex("\\b1440p\\b", RegexOption.IGNORE_CASE) to "1440p",
        Regex("\\b1080p\\b", RegexOption.IGNORE_CASE) to "1080p",
        Regex("\\b720p\\b", RegexOption.IGNORE_CASE) to "720p",
        Regex("\\b(480p|576p|sd)\\b", RegexOption.IGNORE_CASE) to "SD",
    )

    fun quality(text: String): String? = QUALITY.firstOrNull { it.first.containsMatchIn(text) }?.second

    fun qualityRank(q: String?): Int = when (q) {
        "4K" -> 5
        "1440p" -> 4
        "1080p" -> 3
        "720p" -> 2
        "SD" -> 1
        else -> 0
    }

    private val SIZE = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(GB|MB)", RegexOption.IGNORE_CASE)

    fun sizeFromText(text: String): Long? {
        val m = SIZE.find(text) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        return (n * if (m.groupValues[2].equals("GB", true)) 1e9 else 1e6).toLong()
    }

    /**
     * Best first: ready-to-play links (and torrents TorBox already has), higher quality, then larger
     * files; torrents TorBox would still have to download go last.
     */
    fun sortStreams(streams: List<AddonStream>, cached: Set<String>): List<AddonStream> =
        streams.sortedWith(
            compareBy<AddonStream> {
                when {
                    it.url != null -> 0
                    it.infoHash != null && it.infoHash in cached -> 0
                    it.infoHash != null -> 2
                    else -> 3
                }
            }.thenByDescending { qualityRank(it.quality) }.thenByDescending { it.sizeBytes ?: 0L },
        )
}

private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it)?.toString()?.clean() }
