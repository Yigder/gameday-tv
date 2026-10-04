package com.gameday.tv.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Trailers for On Demand titles. Add-ons list them as YouTube video ids; this asks YouTube's player
 * API (as its iOS and Quest apps do) for an address the app's own player can stream: an HLS
 * playlist when there is one, otherwise a single MP4 with sound.
 *
 * YouTube changes this API now and then. When it stops answering, cards simply keep their art.
 */
object Trailers {
    /** A playable trailer. [userAgent] must be sent with every request for it. */
    data class Source(val url: String, val hls: Boolean, val userAgent: String)

    private class Client(val id: String, val name: String, val version: String, val userAgent: String, val extra: Map<String, Any>)

    private val CLIENTS = listOf(
        Client(
            "5", "IOS", "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82"),
        ),
        Client(
            "28", "ANDROID_VR", "1.62.27",
            "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "osName" to "Android", "osVersion" to "12L", "androidSdkVersion" to 32),
        ),
    )

    private const val TTL_MS = 3 * 60 * 60_000L
    private val cache = object : LinkedHashMap<String, Pair<Long, Source?>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Source?>>?) = size > 60
    }
    @Volatile private var visitorData: String? = null

    /** A playable source for YouTube video [videoId], or null (unavailable, region-locked, API changed). */
    suspend fun resolve(videoId: String): Source? {
        synchronized(cache) { cache[videoId] }?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.let { return it.second }
        val found = resolveUncached(videoId)
        synchronized(cache) { cache[videoId] = System.currentTimeMillis() to found }
        return found
    }

    private suspend fun resolveUncached(videoId: String): Source? {
        var needsVisitor = false
        for (attempt in 0..1) {
            if (attempt == 1) {
                if (!needsVisitor) break
                visitorData = orNull { fetchVisitorData() } ?: break
            }
            for (c in CLIENTS) {
                val res = orNull { player(c, videoId) } ?: continue
                if (playabilityStatus(res.body) == "LOGIN_REQUIRED") needsVisitor = true
                if (!res.ok) continue
                parsePlayer(res.body, c.userAgent)?.let { return it }
            }
        }
        return null
    }

    /** [block]'s result, or null when it fails; cancellation still cancels (and nothing is cached). */
    private inline fun <T> orNull(block: () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun player(c: Client, videoId: String): HttpResult {
        val client = JSONObject().put("clientName", c.name).put("clientVersion", c.version).put("hl", "en").put("gl", "US")
        c.extra.forEach { (k, v) -> client.put(k, v) }
        visitorData?.let { client.put("visitorData", it) }
        val body = JSONObject()
            .put("videoId", videoId)
            .put("context", JSONObject().put("client", client))
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val headers = buildMap {
            put("User-Agent", c.userAgent)
            put("X-YouTube-Client-Name", c.id)
            put("X-YouTube-Client-Version", c.version)
            put("Origin", "https://www.youtube.com")
            visitorData?.let { put("X-Goog-Visitor-Id", it) }
        }
        return Http.postJson("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body.toString(), headers)
    }

    private val VISITOR = Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"")

    private suspend fun fetchVisitorData(): String? =
        VISITOR.find(Http.getString("https://www.youtube.com/?hl=en", mapOf("User-Agent" to BROWSER_UA)))?.groupValues?.get(1)

    private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    // ---- parsing (pure, unit tested) ----

    fun playabilityStatus(body: String): String? =
        runCatching { JSONObject(body).optJSONObject("playabilityStatus")?.optString("status")?.clean() }.getOrNull()

    /**
     * The best source in a player response: the HLS playlist (video and sound together, adaptive),
     * else the best MP4 that has both video and sound. Trailers need no more than 1080p.
     */
    fun parsePlayer(body: String, userAgent: String): Source? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val status = o.optJSONObject("playabilityStatus")?.optString("status")
        if (status != null && status != "OK") return null
        val data = o.optJSONObject("streamingData") ?: return null
        data.optString("hlsManifestUrl").clean()?.takeIf { it.startsWith("https://") }?.let { return Source(it, true, userAgent) }
        val muxed = (data.optJSONArray("formats") ?: JSONArray()).objects()
            .filter { f -> f.optString("mimeType").startsWith("video/mp4") && f.optString("url").startsWith("https://") }
            .filter { f -> f.optInt("height", 0) <= 1080 }
            .maxByOrNull { it.optInt("height", 0) }
        return muxed?.let { Source(it.optString("url"), false, userAgent) }
    }
}
