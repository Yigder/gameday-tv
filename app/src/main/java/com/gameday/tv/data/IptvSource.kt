package com.gameday.tv.data

import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

interface IptvSource {
    /** Validates credentials. Returns account details when the provider exposes them. */
    suspend fun login(): AccountInfo?

    suspend fun loadCatalog(): IptvCatalog

    /** Candidate URLs in the order they should be tried; later ones are fallbacks. */
    fun streamUrls(channel: Channel, preferred: StreamFormat): List<String>

    // ---- program guide ----

    /** This channel's guide around now (Xtream asks per channel; M3U uses [xmltvUrl] instead). */
    suspend fun epg(channel: Channel): List<Program> = emptyList()

    /** XMLTV guide for the whole lineup, when the provider publishes one. */
    val xmltvUrl: String? get() = null

    /** Replay URL for a past airing, when the channel keeps catch-up. */
    fun catchupUrl(channel: Channel, program: Program): String? = null

    // ---- movies & shows ----

    val hasVod: Boolean get() = false

    suspend fun loadMovies(): VodLibrary<Movie> = VodLibrary(emptyList(), emptyList())

    suspend fun loadSeries(): VodLibrary<Series> = VodLibrary(emptyList(), emptyList())

    suspend fun movieInfo(movie: Movie): MovieInfo? = null

    suspend fun seriesInfo(series: Series): SeriesInfo = SeriesInfo(series, emptyList(), emptyMap())

    fun movieUrl(movie: Movie): String? = movie.url

    fun episodeUrl(episode: Episode): String? = null

    companion object {
        fun create(account: IptvAccount): IptvSource = when (account) {
            is IptvAccount.Xtream -> XtreamSource(account)
            is IptvAccount.M3u -> M3uSource(account)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Xtream Codes API (player_api.php) — the login most IPTV providers hand out.
// ---------------------------------------------------------------------------------------------

class XtreamSource(account: IptvAccount.Xtream) : IptvSource {
    private val base = normalizeServer(account.server)
    private val user = account.username.trim()
    private val pass = account.password.trim()
    private val userPath = Uri.encode(user)
    private val passPath = Uri.encode(pass)

    /** Catch-up start times are in the server's local time. */
    private var serverZone: ZoneId = ZoneId.of("UTC")

    private fun apiUrl(action: String? = null, extra: String = ""): String = buildString {
        append(base).append("/player_api.php?username=").append(enc(user))
        append("&password=").append(enc(pass))
        if (action != null) append("&action=").append(action)
        append(extra)
    }

    override suspend fun login(): AccountInfo {
        val body = Http.getString(apiUrl())
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw IOException("The server didn't respond like an Xtream Codes panel. Check the URL, including the port (e.g. http://host:8080).")
        }
        val ui = root.optJSONObject("user_info")
            ?: throw IOException("Unexpected server response (no user info).")
        if (ui.optInt("auth", 0) != 1) throw IOException("Login rejected. Check your username and password.")
        val status = ui.optString("status").clean()
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw IOException("Your IPTV account status is \"$status\".")
        }
        val tz = root.optJSONObject("server_info")?.optString("timezone").clean()
        tz?.let { runCatching { serverZone = ZoneId.of(it) } }
        return AccountInfo(
            status = status,
            expiresAtMillis = ui.optString("exp_date").clean()?.toLongOrNull()?.times(1000),
            maxConnections = ui.optString("max_connections").clean(),
            activeConnections = ui.optString("active_cons").clean(),
            timezone = tz,
        )
    }

    override suspend fun loadCatalog(): IptvCatalog {
        val categories = Http.withStream(apiUrl("get_live_categories")) { readCategories(it) }
        val channels = Http.withStream(apiUrl("get_live_streams")) { readStreams(it, categories) }
        if (channels.isEmpty()) throw IOException("No live channels were found on this account.")
        val present = channels.mapTo(HashSet()) { it.group }
        val groups = LinkedHashSet<String>()
        categories.values.filterTo(groups) { it in present }
        channels.mapTo(groups) { it.group } // any groups not listed in categories
        return IptvCatalog(groups.toList(), channels)
    }

    override fun streamUrls(channel: Channel, preferred: StreamFormat): List<String> {
        val id = channel.streamId ?: return listOfNotNull(channel.url)
        return listOf(preferred, preferred.other()).map { "$base/live/$userPath/$passPath/$id.${it.ext}" }
    }

    // ---- guide ----

    override suspend fun epg(channel: Channel): List<Program> {
        val id = channel.streamId ?: return emptyList()
        // The full table also says which airings have a replay; the short list is lighter.
        val url = if (channel.archiveDays > 0) apiUrl("get_simple_data_table", "&stream_id=$id")
        else apiUrl("get_short_epg", "&stream_id=$id&limit=24")
        val body = Http.getString(url)
        return parseEpgListings(body, channel.id)
    }

    override fun catchupUrl(channel: Channel, program: Program): String? {
        val id = channel.streamId ?: return null
        if (channel.archiveDays <= 0) return null
        val now = System.currentTimeMillis()
        if (program.startMillis > now) return null
        if (program.startMillis < now - channel.archiveDays * 86_400_000L) return null
        val minutes = ((program.endMillis - program.startMillis) / 60_000L).coerceAtLeast(1)
        val start = Instant.ofEpochMilli(program.startMillis).atZone(serverZone).format(CATCHUP_TIME)
        return "$base/timeshift/$userPath/$passPath/$minutes/$start/$id.ts"
    }

    // ---- movies & shows ----

    override val hasVod: Boolean get() = true

    override suspend fun loadMovies(): VodLibrary<Movie> {
        val categories = Http.withStream(apiUrl("get_vod_categories")) { readCategories(it) }
        val movies = Http.withStream(apiUrl("get_vod_streams")) { readMovies(it) }
        return VodLibrary(categories.map { VodCategory(it.key, it.value) }, movies)
    }

    override suspend fun loadSeries(): VodLibrary<Series> {
        val categories = Http.withStream(apiUrl("get_series_categories")) { readCategories(it) }
        val series = Http.withStream(apiUrl("get_series")) { readSeries(it) }
        return VodLibrary(categories.map { VodCategory(it.key, it.value) }, series)
    }

    override suspend fun movieInfo(movie: Movie): MovieInfo? {
        val root = runCatching { JSONObject(Http.getString(apiUrl("get_vod_info", "&vod_id=${movie.id}"))) }.getOrNull() ?: return null
        val info = root.optJSONObject("info") ?: JSONObject()
        val data = root.optJSONObject("movie_data") ?: JSONObject()
        return MovieInfo(
            plot = info.optString("plot").clean() ?: info.optString("description").clean(),
            cast = info.optString("cast").clean() ?: info.optString("actors").clean(),
            director = info.optString("director").clean(),
            genre = info.optString("genre").clean(),
            releaseDate = info.optString("releasedate").clean() ?: info.optString("release_date").clean(),
            durationSecs = info.optString("duration_secs").clean()?.toIntOrNull(),
            backdrop = firstImage(info.opt("backdrop_path")) ?: info.optString("movie_image").clean(),
            rating = info.optString("rating").clean(),
            ext = data.optString("container_extension").clean(),
        )
    }

    override suspend fun seriesInfo(series: Series): SeriesInfo {
        val root = JSONObject(Http.getString(apiUrl("get_series_info", "&series_id=${series.id}")))
        val info = root.optJSONObject("info")
        val merged = if (info == null) series else series.copy(
            plot = series.plot ?: info.optString("plot").clean(),
            genre = series.genre ?: info.optString("genre").clean(),
            backdrop = series.backdrop ?: firstImage(info.opt("backdrop_path")),
            poster = series.poster ?: info.optString("cover").clean(),
        )
        val bySeason = sortedMapOf<Int, MutableList<Episode>>()
        fun addEpisode(o: JSONObject, seasonHint: Int) {
            val id = o.optString("id").clean() ?: return
            val season = o.optString("season").clean()?.toIntOrNull() ?: seasonHint
            val ei = o.optJSONObject("info") ?: JSONObject()
            bySeason.getOrPut(season) { ArrayList() } += Episode(
                id = id,
                seriesId = series.id,
                season = season,
                number = o.optString("episode_num").clean()?.toIntOrNull() ?: (bySeason[season]?.size ?: 0) + 1,
                title = o.optString("title").clean() ?: "Episode",
                plot = ei.optString("plot").clean(),
                image = ei.optString("movie_image").clean(),
                durationSecs = ei.optString("duration_secs").clean()?.toIntOrNull(),
                ext = o.optString("container_extension").clean() ?: "mp4",
            )
        }
        when (val eps = root.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k ->
                val arr = eps.optJSONArray(k) ?: return@forEach
                arr.objects().forEach { addEpisode(it, k.toIntOrNull() ?: 1) }
            }
            is JSONArray -> eps.objects().forEach { addEpisode(it, 1) }
        }
        bySeason.values.forEach { list -> list.sortBy { it.number } }
        return SeriesInfo(merged, bySeason.keys.toList(), bySeason)
    }

    override fun movieUrl(movie: Movie): String = "$base/movie/$userPath/$passPath/${movie.id}.${movie.ext}"

    override fun episodeUrl(episode: Episode): String = "$base/series/$userPath/$passPath/${episode.id}.${episode.ext}"

    // ---- parsing ----

    private fun readCategories(input: InputStream): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.isLenient = true
            if (r.peek() != JsonToken.BEGIN_ARRAY) return out
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                var id: String? = null
                var name: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "category_id" -> id = r.nextValueString()
                        "category_name" -> name = r.nextValueString()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (id != null) out[id] = name?.trim()?.ifBlank { null } ?: "Category $id"
            }
            r.endArray()
        }
        return out
    }

    private fun readStreams(input: InputStream, categories: Map<String, String>): List<Channel> {
        val out = ArrayList<Channel>()
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.isLenient = true
            if (r.peek() != JsonToken.BEGIN_ARRAY) throw IOException("Unexpected channel list from the server.")
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                var name: String? = null
                var streamId: String? = null
                var icon: String? = null
                var categoryId: String? = null
                var num: String? = null
                var epgId: String? = null
                var archive: String? = null
                var archiveDays: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "name" -> name = r.nextValueString()
                        "stream_id" -> streamId = r.nextValueString()
                        "stream_icon" -> icon = r.nextValueString()
                        "category_id" -> categoryId = r.nextValueString()
                        "num" -> num = r.nextValueString()
                        "epg_channel_id" -> epgId = r.nextValueString()
                        "tv_archive" -> archive = r.nextValueString()
                        "tv_archive_duration" -> archiveDays = r.nextValueString()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                val cleanName = name?.trim()
                if (!cleanName.isNullOrEmpty() && streamId != null) {
                    out += Channel(
                        id = "x$streamId",
                        name = cleanName,
                        logo = icon?.takeIf { it.startsWith("http", ignoreCase = true) },
                        group = categories[categoryId] ?: "Uncategorized",
                        num = num?.toIntOrNull() ?: (out.size + 1),
                        streamId = streamId,
                        epgId = epgId.clean(),
                        archiveDays = if (archive == "1") archiveDays?.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0,
                    )
                }
            }
            r.endArray()
        }
        return out
    }

    private fun readMovies(input: InputStream): List<Movie> {
        val out = ArrayList<Movie>()
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.isLenient = true
            if (r.peek() != JsonToken.BEGIN_ARRAY) return out
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                var name: String? = null
                var id: String? = null
                var icon: String? = null
                var cat: String? = null
                var rating: String? = null
                var added: String? = null
                var ext: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "name" -> name = r.nextValueString()
                        "stream_id" -> id = r.nextValueString()
                        "stream_icon" -> icon = r.nextValueString()
                        "category_id" -> cat = r.nextValueString()
                        "rating" -> rating = r.nextValueString()
                        "added" -> added = r.nextValueString()
                        "container_extension" -> ext = r.nextValueString()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                val n = name?.trim()
                if (!n.isNullOrEmpty() && id != null) {
                    out += Movie(
                        id = id,
                        name = n,
                        poster = icon?.takeIf { it.startsWith("http", true) },
                        categoryId = cat,
                        rating = rating?.toDoubleOrNull()?.takeIf { it > 0 },
                        added = added?.toLongOrNull() ?: 0,
                        ext = ext.clean() ?: "mp4",
                    )
                }
            }
            r.endArray()
        }
        return out
    }

    private fun readSeries(input: InputStream): List<Series> {
        val out = ArrayList<Series>()
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.isLenient = true
            if (r.peek() != JsonToken.BEGIN_ARRAY) return out
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                var name: String? = null
                var id: String? = null
                var cover: String? = null
                var cat: String? = null
                var plot: String? = null
                var genre: String? = null
                var rating: String? = null
                var release: String? = null
                var backdrop: String? = null
                var modified: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "name" -> name = r.nextValueString()
                        "series_id" -> id = r.nextValueString()
                        "cover" -> cover = r.nextValueString()
                        "category_id" -> cat = r.nextValueString()
                        "plot" -> plot = r.nextValueString()
                        "genre" -> genre = r.nextValueString()
                        "rating" -> rating = r.nextValueString()
                        "releaseDate", "release_date" -> release = r.nextValueString()
                        "last_modified" -> modified = r.nextValueString()
                        "backdrop_path" -> backdrop = r.nextFirstString()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                val n = name?.trim()
                if (!n.isNullOrEmpty() && id != null) {
                    out += Series(
                        id = id,
                        name = n,
                        poster = cover?.takeIf { it.startsWith("http", true) },
                        categoryId = cat,
                        plot = plot.clean(),
                        genre = genre.clean(),
                        rating = rating?.toDoubleOrNull()?.takeIf { it > 0 },
                        releaseDate = release.clean(),
                        backdrop = backdrop?.takeIf { it.startsWith("http", true) },
                        lastModified = modified?.toLongOrNull() ?: 0,
                    )
                }
            }
            r.endArray()
        }
        return out
    }

    companion object {
        private val CATCHUP_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm", Locale.US)

        /** Accepts "host:port", "http://host:port/", or a pasted player_api.php / get.php link. */
        fun normalizeServer(raw: String): String {
            var s = raw.trim()
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            s = s.substringBefore('?').trimEnd('/')
            for (suffix in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")) {
                if (s.endsWith(suffix, ignoreCase = true)) s = s.dropLast(suffix.length)
            }
            return s.trimEnd('/')
        }

        /** Turns an Xtream "get.php?username=..&password=.." playlist link into an Xtream login (faster, has categories). */
        fun fromPlaylistUrl(url: String): IptvAccount.Xtream? {
            val trimmed = url.trim()
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
            if (uri.path?.endsWith("get.php") != true) return null
            val user = uri.getQueryParameter("username")?.takeIf { it.isNotBlank() } ?: return null
            val pass = uri.getQueryParameter("password")?.takeIf { it.isNotBlank() } ?: return null
            return IptvAccount.Xtream(trimmed.substringBefore("/get.php"), user, pass)
        }

        /**
         * Parses get_short_epg / get_simple_data_table. Titles and descriptions are usually base64.
         * Pure (no Android APIs) so it can be unit tested.
         */
        fun parseEpgListings(body: String, channelId: String): List<Program> {
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
            val list = root.optJSONArray("epg_listings") ?: return emptyList()
            val out = ArrayList<Program>(list.length())
            for (o in list.objects()) {
                val start = o.optString("start_timestamp").clean()?.toLongOrNull()?.times(1000) ?: continue
                val end = (o.optString("stop_timestamp").clean() ?: o.optString("end_timestamp").clean())
                    ?.toLongOrNull()?.times(1000) ?: continue
                if (end <= start) continue
                out += Program(
                    channelId = channelId,
                    title = decodeMaybeBase64(o.optString("title")).ifBlank { "Untitled" },
                    description = decodeMaybeBase64(o.optString("description")),
                    startMillis = start,
                    endMillis = end,
                    hasArchive = o.optInt("has_archive", 0) == 1,
                )
            }
            return out.distinctBy { it.startMillis }.sortedBy { it.startMillis }
        }

        private val BASE64 = Regex("^[A-Za-z0-9+/=\\s]+$")

        fun decodeMaybeBase64(s: String?): String {
            val t = s.clean() ?: return ""
            if (!BASE64.matches(t) || t.length % 4 != 0) return t
            return try {
                val text = String(Base64.getDecoder().decode(t.replace("\\s".toRegex(), "")), Charsets.UTF_8)
                if (text.any { it == '�' || (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') }) t else text.trim()
            } catch (_: IllegalArgumentException) {
                t
            }
        }

        private fun firstImage(v: Any?): String? = when (v) {
            is JSONArray -> (0 until v.length()).asSequence().map { v.optString(it) }.firstOrNull { it.startsWith("http", true) }
            is String -> v.takeIf { it.startsWith("http", true) }
            else -> null
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}

// ---------------------------------------------------------------------------------------------
// Plain M3U / M3U8 playlist URL.
// ---------------------------------------------------------------------------------------------

class M3uSource(private val account: IptvAccount.M3u) : IptvSource {
    private var playlist: M3uPlaylist? = null

    override suspend fun login(): AccountInfo? = null

    override suspend fun loadCatalog(): IptvCatalog {
        val raw = account.url.trim()
        val url = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "http://$raw"
        val parsed = Http.withStream(url) { M3uParser.parse(it) }
        if (parsed.catalog.channels.isEmpty()) throw IOException("That playlist has no live channels.")
        playlist = parsed
        return parsed.catalog
    }

    override fun streamUrls(channel: Channel, preferred: StreamFormat): List<String> = listOfNotNull(channel.url)

    override val xmltvUrl: String? get() = account.epgUrl?.takeIf { it.isNotBlank() } ?: playlist?.epgUrl

    override val hasVod: Boolean get() = playlist?.movies?.items?.isNotEmpty() == true

    override suspend fun loadMovies(): VodLibrary<Movie> = playlist?.movies ?: VodLibrary(emptyList(), emptyList())
}

class M3uPlaylist(val catalog: IptvCatalog, val movies: VodLibrary<Movie>, val epgUrl: String?)

object M3uParser {
    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val VOD_EXT = Regex("""\.(mp4|mkv|avi|mov|wmv|flv|webm)(\?.*)?$""", RegexOption.IGNORE_CASE)

    fun parse(input: InputStream): M3uPlaylist {
        val channels = ArrayList<Channel>()
        val groups = LinkedHashSet<String>()
        val movies = ArrayList<Movie>()
        val movieGroups = LinkedHashMap<String, String>()
        val usedIds = HashSet<String>()
        var epgUrl: String? = null
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var tvgId: String? = null
        var chno = 0

        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#EXTM3U", ignoreCase = true) -> {
                        val attrs = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                        epgUrl = (attrs["url-tvg"] ?: attrs["x-tvg-url"])?.split(',')?.firstOrNull()?.trim()?.ifBlank { null }
                    }
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        val comma = titleComma(line)
                        val attrPart = if (comma >= 0) line.substring(0, comma) else line
                        val attrs = ATTR.findAll(attrPart).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                        val title = if (comma >= 0) line.substring(comma + 1).trim() else ""
                        name = title.ifBlank { attrs["tvg-name"]?.ifBlank { null } ?: "Channel" }
                        logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http", ignoreCase = true) }
                        group = attrs["group-title"]?.trim()?.ifBlank { null }
                        tvgId = attrs["tvg-id"]?.trim()?.ifBlank { null }
                        chno = attrs["tvg-chno"]?.toIntOrNull() ?: 0
                    }
                    line.startsWith("#EXTGRP:", ignoreCase = true) ->
                        group = line.substringAfter(':').trim().ifBlank { null }
                    line.startsWith("#") -> Unit
                    else -> {
                        val n = name
                        if (n != null) {
                            val g = group ?: "Uncategorized"
                            when {
                                line.contains("/series/") -> Unit // episodes without show info aren't browsable
                                isVod(line) -> {
                                    val catId = movieGroups.getOrPut(g) { "m${movieGroups.size}" }
                                    movies += Movie(
                                        id = stableId("v", line, usedIds),
                                        name = n,
                                        poster = logo,
                                        categoryId = catId,
                                        rating = null,
                                        added = 0,
                                        ext = line.substringAfterLast('.').substringBefore('?').lowercase(),
                                        url = line,
                                    )
                                }
                                else -> {
                                    groups += g
                                    channels += Channel(
                                        id = stableId("m", line, usedIds),
                                        name = n,
                                        logo = logo,
                                        group = g,
                                        num = if (chno > 0) chno else channels.size + 1,
                                        url = line,
                                        epgId = tvgId,
                                    )
                                }
                            }
                        }
                        name = null; logo = null; group = null; tvgId = null; chno = 0
                    }
                }
            }
        }
        val movieLibrary = VodLibrary(movieGroups.map { VodCategory(it.value, it.key) }, movies)
        return M3uPlaylist(IptvCatalog(groups.toList(), channels), movieLibrary, epgUrl)
    }

    /** Ids derived from the URL, so favorites and history survive playlist reorders. */
    private fun stableId(prefix: String, url: String, used: MutableSet<String>): String {
        val base = prefix + Integer.toUnsignedString(url.hashCode(), 36)
        var id = base
        var i = 1
        while (!used.add(id)) id = "$base-${i++}"
        return id
    }

    private fun isVod(url: String) = url.contains("/movie/") || VOD_EXT.containsMatchIn(url)

    /** First comma outside of quoted attribute values separates attributes from the channel title. */
    private fun titleComma(line: String): Int {
        var inQuotes = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }
}

// ---------------------------------------------------------------------------------------------

internal fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

internal fun JsonReader.nextValueString(): String? = when (peek()) {
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    JsonToken.BOOLEAN -> nextBoolean().toString()
    JsonToken.NULL -> { nextNull(); null }
    else -> { skipValue(); null }
}

/** A string, or the first string of an array (Xtream sends backdrop_path either way). */
internal fun JsonReader.nextFirstString(): String? = when (peek()) {
    JsonToken.BEGIN_ARRAY -> {
        beginArray()
        var first: String? = null
        while (hasNext()) {
            val v = nextValueString()
            if (first == null && !v.isNullOrBlank()) first = v
        }
        endArray()
        first
    }
    else -> nextValueString()
}
