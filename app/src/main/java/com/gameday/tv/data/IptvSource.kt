package com.gameday.tv.data

import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
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
}

class M3uPlaylist(val catalog: IptvCatalog, val epgUrl: String?)

object M3uParser {
    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val VOD_EXT = Regex("""\.(mp4|mkv|avi|mov|wmv|flv|webm)(\?.*)?$""", RegexOption.IGNORE_CASE)

    fun parse(input: InputStream): M3uPlaylist {
        val channels = ArrayList<Channel>()
        val groups = LinkedHashSet<String>()
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
                                // Movies and episodes in the playlist aren't live channels.
                                line.contains("/series/") || isVod(line) -> Unit
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
        return M3uPlaylist(IptvCatalog(groups.toList(), channels), epgUrl)
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
