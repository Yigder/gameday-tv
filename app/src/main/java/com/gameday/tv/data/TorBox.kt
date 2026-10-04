package com.gameday.tv.data

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

data class TorBoxAccount(val email: String?, val plan: String, val expires: String?)

data class TorBoxFile(val id: Int, val name: String, val size: Long)

data class TorBoxTorrent(val files: List<TorBoxFile>, val ready: Boolean)

/**
 * TorBox (debrid) integration, like Nuvio's: torrents from stream add-ons are checked against
 * TorBox's cache and played from TorBox's servers instead of peer to peer.
 * API: https://api-docs.torbox.app (key from torbox.app › Settings).
 */
class TorBoxClient(private val apiKey: String, private val base: String = BASE) {
    private val auth = mapOf("Authorization" to "Bearer $apiKey")

    suspend fun account(): TorBoxAccount {
        val data = ok(Http.call("$base/user/me", auth)).optJSONObject("data") ?: throw IOException("TorBox didn't return your account.")
        return TorBoxAccount(
            email = data.optString("email").clean(),
            plan = when (data.optInt("plan", -1)) {
                0 -> "Free"
                1 -> "Essential"
                2 -> "Pro"
                3 -> "Standard"
                else -> "Active"
            },
            expires = data.optString("premium_expires_at").clean()?.take(10),
        )
    }

    /** Which of [hashes] TorBox already has, so they play instantly. */
    suspend fun cached(hashes: Collection<String>): Set<String> {
        if (hashes.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (chunk in hashes.distinct().chunked(50)) {
            val res = Http.call("$base/torrents/checkcached?hash=${chunk.joinToString(",")}&format=list&list_files=false", auth)
            if (!res.ok) continue
            out += parseCached(res.body)
        }
        return out
    }

    /**
     * Adds the torrent to TorBox (instant when it's cached), picks the video file and returns a
     * direct link to stream it.
     */
    suspend fun resolve(stream: AddonStream, season: Int?, episode: Int?): String {
        val hash = stream.infoHash ?: throw IOException("This source isn't a torrent.")
        val torrentId = addTorrent(hash, stream)
        var torrent = TorBoxTorrent(emptyList(), false)
        // Cached torrents are ready within a second or two.
        for (attempt in 0 until 8) {
            torrent = torrentInfo(torrentId)
            if (torrent.ready && torrent.files.isNotEmpty()) break
            delay(if (attempt == 0) 500L else 1_000L)
        }
        if (!torrent.ready || torrent.files.isEmpty()) {
            throw IOException("TorBox doesn't have this one cached yet. It's downloading now — try again in a few minutes, or pick a ⚡ source.")
        }
        val file = pickFile(torrent.files, stream, season, episode) ?: throw IOException("No video file found in this torrent.")
        val res = Http.call("$base/torrents/requestdl?token=${enc(apiKey)}&torrent_id=$torrentId&file_id=${file.id}&zip_link=false")
        val link = ok(res).optString("data").clean()
        return link?.takeIf { it.startsWith("http") } ?: throw IOException("TorBox didn't return a link for this file.")
    }

    private suspend fun addTorrent(hash: String, stream: AddonStream): Long {
        val magnet = buildString {
            append("magnet:?xt=urn:btih:").append(hash)
            (stream.filename ?: stream.description.lineSequence().firstOrNull())?.let { append("&dn=").append(enc(it)) }
            stream.trackers.take(10).forEach { append("&tr=").append(enc(it)) }
        }
        val res = Http.call("$base/torrents/createtorrent", auth, mapOf("magnet" to magnet, "seed" to "3", "allow_zip" to "false"))
        val json = runCatching { JSONObject(res.body) }.getOrNull()
        json?.optJSONObject("data")?.let { d ->
            (d.optLong("torrent_id", -1).takeIf { it >= 0 } ?: d.optLong("id", -1).takeIf { it >= 0 })?.let { return it }
        }
        // Already in the account: find it in the list.
        findInList(hash)?.let { return it }
        throw IOException(json?.let(::errorText) ?: Http.describeHttpError(res.code))
    }

    private suspend fun findInList(hash: String): Long? {
        val res = Http.call("$base/torrents/mylist?bypass_cache=true", auth)
        if (!res.ok) return null
        val arr = runCatching { JSONObject(res.body).optJSONArray("data") }.getOrNull() ?: return null
        return arr.objects().firstOrNull { it.optString("hash").equals(hash, true) }?.optLong("id")
    }

    private suspend fun torrentInfo(id: Long): TorBoxTorrent {
        val res = Http.call("$base/torrents/mylist?id=$id&bypass_cache=true", auth)
        if (!res.ok) return TorBoxTorrent(emptyList(), false)
        return parseTorrent(res.body)
    }

    private fun ok(res: HttpResult): JSONObject {
        val json = runCatching { JSONObject(res.body) }.getOrNull()
        if (res.code == 401 || res.code == 403) throw IOException("TorBox rejected the API key. Check it in Settings › Add-ons.")
        if (!res.ok || json == null || !json.optBoolean("success", true)) {
            throw IOException(json?.let(::errorText) ?: Http.describeHttpError(res.code))
        }
        return json
    }

    private fun errorText(json: JSONObject): String =
        json.optString("detail").clean() ?: json.optString("error").clean()?.let { "TorBox: $it" } ?: "TorBox request failed."

    companion object {
        const val BASE = "https://api.torbox.app/v1/api"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private val VIDEO = Regex("\\.(mkv|mp4|avi|mov|m4v|ts|webm|wmv|m2ts)$", RegexOption.IGNORE_CASE)

        // ---- parsing and file choice (pure, unit tested) ----

        fun parseCached(body: String): Set<String> {
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptySet()
            return when (val data = root.opt("data")) {
                is JSONArray -> data.objects().mapNotNull { it.optString("hash").clean()?.lowercase() }.toSet()
                is JSONObject -> data.keys().asSequence().map { k -> data.optJSONObject(k)?.optString("hash").clean() ?: k }.map { it.lowercase() }.toSet()
                else -> emptySet()
            }
        }

        fun parseTorrent(body: String): TorBoxTorrent {
            val data = runCatching { JSONObject(body).opt("data") }.getOrNull()
            val torrent = when (data) {
                is JSONObject -> data
                is JSONArray -> data.optJSONObject(0)
                else -> null
            } ?: return TorBoxTorrent(emptyList(), false)
            val files = torrent.optJSONArray("files")?.objects()?.mapNotNull { f ->
                val name = f.optString("short_name").clean() ?: f.optString("name").clean() ?: return@mapNotNull null
                TorBoxFile(f.optInt("id"), name.substringAfterLast('/'), f.optLong("size"))
            }?.toList().orEmpty()
            val ready = torrent.optBoolean("download_present", false) || torrent.optBoolean("download_finished", false) ||
                torrent.optString("download_state").let { it == "cached" || it == "completed" || it == "uploading" }
            return TorBoxTorrent(files, ready)
        }

        /** The add-on's file name, then the episode, then the biggest video (skipping samples). */
        fun pickFile(files: List<TorBoxFile>, stream: AddonStream, season: Int?, episode: Int?): TorBoxFile? {
            val videos = files.filter { VIDEO.containsMatchIn(it.name) && !it.name.contains("sample", true) }.ifEmpty { files }
            stream.filename?.let { fn -> videos.firstOrNull { it.name.equals(fn, true) }?.let { return it } }
            if (season != null && episode != null) {
                val patterns = listOf(
                    Regex("s0*${season}[ ._-]?e0*${episode}(?!\\d)", RegexOption.IGNORE_CASE),
                    Regex("(?<!\\d)${season}x0*${episode}(?!\\d)", RegexOption.IGNORE_CASE),
                )
                videos.filter { f -> patterns.any { it.containsMatchIn(f.name) } }.maxByOrNull { it.size }?.let { return it }
                if (videos.size > 1) {
                    // A season pack whose names only carry the episode number.
                    videos.filter { Regex("(?<!\\d)e?0*${episode}(?!\\d)", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }.singleOrNull()?.let { return it }
                }
            }
            stream.fileIdx?.let { idx -> videos.firstOrNull { it.id == idx }?.let { if (videos.size == 1 || season == null) return it } }
            return videos.maxByOrNull { it.size }
        }
    }
}
