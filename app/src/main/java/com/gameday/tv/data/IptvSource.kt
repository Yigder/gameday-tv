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

interface IptvSource {
    /** Validates credentials. Returns account details when the provider exposes them. */
    suspend fun login(): AccountInfo?

    suspend fun loadCatalog(): IptvCatalog

    /** Candidate URLs in the order they should be tried; later ones are fallbacks. */
    fun streamUrls(channel: Channel, preferred: StreamFormat): List<String>

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

    private fun apiUrl(action: String? = null): String = buildString {
        append(base).append("/player_api.php?username=").append(enc(user))
        append("&password=").append(enc(pass))
        if (action != null) append("&action=").append(action)
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
        return AccountInfo(
            status = status,
            expiresAtMillis = ui.optString("exp_date").clean()?.toLongOrNull()?.times(1000),
            maxConnections = ui.optString("max_connections").clean(),
            activeConnections = ui.optString("active_cons").clean(),
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
        return listOf(preferred, preferred.other()).map { "$base/live/${Uri.encode(user)}/${Uri.encode(pass)}/$id.${it.ext}" }
    }

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
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "name" -> name = r.nextValueString()
                        "stream_id" -> streamId = r.nextValueString()
                        "stream_icon" -> icon = r.nextValueString()
                        "category_id" -> categoryId = r.nextValueString()
                        "num" -> num = r.nextValueString()
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
                    )
                }
            }
            r.endArray()
        }
        return out
    }

    companion object {
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

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}

// ---------------------------------------------------------------------------------------------
// Plain M3U / M3U8 playlist URL.
// ---------------------------------------------------------------------------------------------

class M3uSource(private val account: IptvAccount.M3u) : IptvSource {
    override suspend fun login(): AccountInfo? = null

    override suspend fun loadCatalog(): IptvCatalog {
        val raw = account.url.trim()
        val url = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "http://$raw"
        val catalog = Http.withStream(url) { M3uParser.parse(it) }
        if (catalog.channels.isEmpty()) throw IOException("That playlist has no live channels.")
        return catalog
    }

    override fun streamUrls(channel: Channel, preferred: StreamFormat): List<String> = listOfNotNull(channel.url)
}

object M3uParser {
    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val VOD_EXT = Regex("""\.(mp4|mkv|avi|mov|wmv|flv|webm)(\?.*)?$""", RegexOption.IGNORE_CASE)

    fun parse(input: InputStream): IptvCatalog {
        val channels = ArrayList<Channel>()
        val groups = LinkedHashSet<String>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var chno = 0

        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        val comma = titleComma(line)
                        val attrPart = if (comma >= 0) line.substring(0, comma) else line
                        val attrs = ATTR.findAll(attrPart).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                        val title = if (comma >= 0) line.substring(comma + 1).trim() else ""
                        name = title.ifBlank { attrs["tvg-name"]?.ifBlank { null } ?: "Channel" }
                        logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http", ignoreCase = true) }
                        group = attrs["group-title"]?.trim()?.ifBlank { null }
                        chno = attrs["tvg-chno"]?.toIntOrNull() ?: 0
                    }
                    line.startsWith("#EXTGRP:", ignoreCase = true) ->
                        group = line.substringAfter(':').trim().ifBlank { null }
                    line.startsWith("#") -> Unit
                    else -> {
                        val n = name
                        if (n != null && !isVod(line)) {
                            val g = group ?: "Uncategorized"
                            groups += g
                            channels += Channel(
                                id = "m${channels.size}",
                                name = n,
                                logo = logo,
                                group = g,
                                num = if (chno > 0) chno else channels.size + 1,
                                url = line,
                            )
                        }
                        name = null; logo = null; group = null; chno = 0
                    }
                }
            }
        }
        return IptvCatalog(groups.toList(), channels)
    }

    private fun isVod(url: String) = url.contains("/movie/") || url.contains("/series/") || VOD_EXT.containsMatchIn(url)

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
