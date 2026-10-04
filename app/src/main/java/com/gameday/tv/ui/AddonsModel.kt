package com.gameday.tv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.gameday.tv.data.AccountPrefs
import com.gameday.tv.data.AddonCatalog
import com.gameday.tv.data.AddonStream
import com.gameday.tv.data.Addons
import com.gameday.tv.data.InstalledAddon
import com.gameday.tv.data.MetaDetail
import com.gameday.tv.data.MetaPreview
import com.gameday.tv.data.MetaVideo
import com.gameday.tv.data.Http
import com.gameday.tv.data.SubtitleCue
import com.gameday.tv.data.SubtitleRequest
import com.gameday.tv.data.SubtitleTrack
import com.gameday.tv.data.Subtitles
import com.gameday.tv.data.TorBoxClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A catalog row: an add-on's catalog (and, for "See all", which page). */
data class AddonRow(val addon: InstalledAddon, val catalog: AddonCatalog) {
    val key: String get() = "${addon.base}|${catalog.type}|${catalog.id}"
    val title: String
        get() {
            val kind = when (catalog.type) {
                "movie" -> "Movies"
                "series" -> "Shows"
                "tv" -> "Channels"
                else -> catalog.type.replaceFirstChar { it.uppercase() }
            }
            val n = catalog.name
            return if (n.contains(kind, true)) n else "$n $kind"
        }
}

/** Streams for a title, best first, with which torrents TorBox already has. */
data class StreamList(val streams: List<AddonStream>, val cached: Set<String>, val addonsAsked: Int)

/** What to play after an add-on episode ends. */
data class AddonNext(val type: String, val metaId: String, val video: MetaVideo, val title: String, val image: String?, val bingeGroup: String?)

/**
 * Streaming add-ons (the On Demand tab) and TorBox, per account. Owned by [AppViewModel].
 */
class AddonsModel(private val vm: AppViewModel) {
    val installed = mutableStateListOf<InstalledAddon>()
    var torboxConnected by mutableStateOf(false); private set
    /** "Pro · until 2026-12-01", or why the key didn't work. */
    var torboxStatus by mutableStateOf<String?>(null); private set
    /** A source is being prepared (TorBox resolving a torrent). */
    var resolving by mutableStateOf<String?>(null); private set

    private var prefs: AccountPrefs? = null
    private var torbox: TorBoxClient? = null
    private val catalogCache = HashMap<String, Pair<Long, List<MetaPreview>>>()
    private val metaCache = HashMap<String, MetaDetail>()

    /**
     * Titles of each catalog row, kept here (not in the screen) so coming back to On Demand
     * finds them immediately and focus returns to the same poster.
     */
    val rowItems = mutableStateMapOf<String, List<MetaPreview>>()
    val rowFailed = mutableStateMapOf<String, Boolean>()
    private val rowLoading = HashSet<String>()

    fun ensureRow(row: AddonRow) {
        if (row.key in rowItems || !rowLoading.add(row.key)) return
        vm.viewModelScope.launch {
            runCatching { catalog(row) }
                .onSuccess { rowItems[row.key] = it.take(30); rowFailed.remove(row.key) }
                .onFailure { rowFailed[row.key] = true }
            rowLoading.remove(row.key)
        }
    }

    val enabled: List<InstalledAddon> get() = installed.filter { it.enabled }
    val hasStreamAddons: Boolean get() = enabled.any { it.hasStreams }

    /** Rows for On Demand: every catalog that works without a search or genre. */
    val rows: List<AddonRow>
        get() = enabled.flatMap { a -> a.manifest.catalogs.filter { !it.needsExtra }.map { AddonRow(a, it) } }

    fun enter(p: AccountPrefs) {
        prefs = p
        installed.clear()
        val saved = p.addons
        if (saved == null) {
            // First time: start with Cinemeta (Stremio's catalog of movies and shows).
            vm.viewModelScope.launch {
                runCatching { Addons.install(Addons.CINEMETA) }.onSuccess {
                    if (prefs === p && installed.none { a -> a.base == it.base }) {
                        installed.add(0, it)
                        save()
                    }
                }
            }
        } else {
            installed.addAll(saved)
        }
        torbox = p.torboxKey?.let { TorBoxClient(it) }
        torboxConnected = torbox != null
        torboxStatus = null
        if (torbox != null) vm.viewModelScope.launch { checkTorBox() }
    }

    fun leave() {
        prefs = null
        installed.clear()
        torbox = null
        torboxConnected = false
        torboxStatus = null
        catalogCache.clear()
        metaCache.clear()
        subtitleFiles.clear()
        searchCache.clear()
        rowItems.clear()
        rowFailed.clear()
    }

    private fun save() {
        prefs?.addons = installed.toList()
        catalogCache.clear()
        searchCache.clear()
        // Rows of removed or turned-off add-ons go; the rest reload on demand.
        val keys = rows.map { it.key }.toSet()
        rowItems.keys.retainAll(keys)
        rowFailed.keys.retainAll(keys)
    }

    // ---- managing add-ons ----

    /** @return an error message, or null when the add-on was added. */
    suspend fun install(url: String): String? {
        if (url.isBlank()) return "Enter the add-on's address."
        return try {
            val addon = Addons.install(url)
            val i = installed.indexOfFirst { it.base == addon.base || it.manifest.id == addon.manifest.id }
            if (i >= 0) installed[i] = addon else installed += addon
            save()
            vm.showMessage("Added ${addon.name}")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            vm.friendly(e)
        }
    }

    /** One-tap install of a well-known add-on (errors show as a message). */
    fun installInBackground(url: String) {
        vm.viewModelScope.launch { install(url)?.let { vm.showMessage(it) } }
    }

    fun remove(addon: InstalledAddon) {
        installed.removeAll { it.url == addon.url }
        save()
        vm.showMessage("Removed ${addon.name}")
    }

    fun setEnabled(addon: InstalledAddon, on: Boolean) {
        val i = installed.indexOfFirst { it.url == addon.url }
        if (i < 0) return
        installed[i] = addon.copy(enabled = on)
        save()
    }

    fun moveUp(addon: InstalledAddon) {
        val i = installed.indexOfFirst { it.url == addon.url }
        if (i <= 0) return
        installed.add(i - 1, installed.removeAt(i))
        save()
    }

    // ---- TorBox ----

    /** @return an error message, or null when the key works. */
    suspend fun setTorBoxKey(key: String): String? {
        val k = key.trim()
        if (k.length < 10) return "Paste your TorBox API key (torbox.app › Settings › API key)."
        return try {
            val client = TorBoxClient(k)
            val acct = client.account()
            prefs?.torboxKey = k
            torbox = client
            torboxConnected = true
            torboxStatus = listOfNotNull(acct.plan, acct.expires?.let { "until $it" }).joinToString(" · ")
            vm.showMessage("TorBox connected")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            vm.friendly(e)
        }
    }

    fun removeTorBox() {
        prefs?.torboxKey = null
        torbox = null
        torboxConnected = false
        torboxStatus = null
        vm.showMessage("TorBox removed")
    }

    private suspend fun checkTorBox() {
        val client = torbox ?: return
        torboxStatus = try {
            val a = client.account()
            listOfNotNull(a.plan, a.expires?.let { "until $it" }).joinToString(" · ")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            vm.friendly(e)
        }
    }

    // ---- browsing ----

    suspend fun catalog(row: AddonRow, extra: Map<String, String> = emptyMap()): List<MetaPreview> {
        val url = Addons.catalogUrl(row.addon, row.catalog, extra)
        catalogCache[url]?.takeIf { System.currentTimeMillis() - it.first < 15 * 60_000L }?.let { return it.second }
        val list = Addons.catalog(row.addon, row.catalog, extra)
        catalogCache[url] = System.currentTimeMillis() to list
        return list
    }

    /** Searches every add-on catalog that supports it; movies and shows are kept apart. */
    suspend fun search(query: String): List<MetaPreview> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val key = q.lowercase()
        searchCache[key]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.let { return it.second }
        return searchUncached(q).also { if (it.isNotEmpty()) searchCache[key] = System.currentTimeMillis() to it }
    }

    /** Recent searches, so typing back and forth (or Enter after a pause) answers at once. */
    private val searchCache = object : LinkedHashMap<String, Pair<Long, List<MetaPreview>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<MetaPreview>>>?) = size > 30
    }

    private suspend fun searchUncached(q: String): List<MetaPreview> {
        val searchable = enabled.flatMap { a -> a.manifest.catalogs.filter { it.searchable && it.extras.count { e -> e.isRequired } <= 1 }.map { AddonRow(a, it) } }
        val results = coroutineScope {
            searchable.map { r ->
                async {
                    try {
                        Addons.catalog(r.addon, r.catalog, mapOf("search" to q))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        return results.flatten().distinctBy { it.type + it.id }
    }

    suspend fun meta(type: String, id: String): MetaDetail? {
        val key = "$type|$id"
        metaCache[key]?.let { return it }
        return Addons.meta(enabled, type, id)?.also { metaCache[key] = it }
    }

    suspend fun streams(type: String, id: String): StreamList {
        val addons = enabled
        val asked = addons.count { it.manifest.supports("stream", type, id) }
        val all = Addons.streams(addons, type, id)
        val hashes = all.filter { it.isTorrent }.mapNotNull { it.infoHash }
        val cached = torbox?.let { tb -> runCatching { tb.cached(hashes) }.getOrDefault(emptySet()) } ?: emptySet()
        return StreamList(Addons.sortStreams(all, cached), cached, asked)
    }

    // ---- subtitles ----

    val hasSubtitleAddons: Boolean get() = enabled.any { it.hasSubtitles }
    private val subtitleFiles = object : LinkedHashMap<String, List<SubtitleCue>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SubtitleCue>>?) = size > 6
    }

    /** Subtitles for an add-on title: the source's own, then every subtitle add-on's. */
    suspend fun subtitles(req: SubtitleRequest): List<SubtitleTrack> {
        val fromAddons = runCatching { Addons.subtitles(enabled, req.type, req.id, req.filename, req.videoSize) }.getOrDefault(emptyList())
        return (req.fromSource + fromAddons).distinctBy { it.url }
    }

    /** Downloads and parses a subtitle file (the last few are kept). */
    suspend fun loadSubtitle(track: SubtitleTrack): List<SubtitleCue> {
        subtitleFiles[track.url]?.let { return it }
        val cues = withContext(Dispatchers.Default) { Subtitles.parse(Subtitles.decodeBytes(Http.getBytes(track.url))) }
        if (cues.isEmpty()) throw java.io.IOException("That subtitle file is empty or in a format GameDay TV can't read.")
        subtitleFiles[track.url] = cues
        return cues
    }

    // ---- playing ----

    /**
     * Plays [stream] for [meta] (and [video] for an episode). Torrents go through TorBox; the
     * position saved for this title resumes.
     */
    fun play(stream: AddonStream, meta: MetaDetail, video: MetaVideo?, next: AddonNext? = null) {
        if (stream.url == null && stream.infoHash == null) {
            vm.showMessage("This source opens in another app, which GameDay TV can't do.")
            return
        }
        vm.viewModelScope.launch {
            val url = stream.url ?: run {
                val tb = torbox ?: run {
                    vm.showDialog(
                        AppDialog(
                            title = "This source needs TorBox",
                            message = "Torrent sources play through a debrid service. Add your TorBox API key in Settings › Add-ons, " +
                                "or choose a source that plays directly.",
                            actions = listOf(
                                DialogAction("Open Add-ons settings", Icons.Settings) { vm.dismissDialog(); vm.openSettings(SettingsSection.ADDONS) },
                                DialogAction("Back") { vm.dismissDialog() },
                            ),
                        ),
                    )
                    return@launch
                }
                resolving = stream.name
                try {
                    tb.resolve(stream, video?.season, video?.episode)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    vm.showMessage(vm.friendly(e))
                    null
                } finally {
                    resolving = null
                }
            } ?: return@launch
            val p = meta.preview
            val item = VodItem(
                key = resumeKey(p.type, p.id, video?.id ?: p.id),
                title = p.name,
                subtitle = video?.let { "S${it.season} E${it.episode} · ${it.title}" } ?: listOfNotNull(p.releaseInfo, stream.quality).joinToString(" · ").ifBlank { "Movie" },
                image = video?.thumbnail ?: p.background ?: p.poster,
                url = url,
                headers = stream.headers,
                next = next ?: video?.let { nextAfter(meta, it, stream.bingeGroup) },
                subtitles = SubtitleRequest(p.type, video?.id ?: p.id, stream.filename, stream.sizeBytes, stream.subtitles),
            )
            vm.playVodItems(listOf(item), 0)
        }
    }

    private fun nextAfter(meta: MetaDetail, video: MetaVideo, bingeGroup: String?): AddonNext? {
        val list = meta.videos.filter { it.season > 0 }
        val i = list.indexOfFirst { it.id == video.id }
        val n = list.getOrNull(i + 1) ?: return null
        if (n.released != null && n.released > System.currentTimeMillis()) return null
        val p = meta.preview
        return AddonNext(p.type, p.id, n, p.name, p.background ?: p.poster, bingeGroup)
    }

    /** Autoplay: the next episode from the same add-on release group when possible. */
    fun playNext(next: AddonNext) {
        vm.viewModelScope.launch {
            vm.showMessage("Up next: S${next.video.season} E${next.video.episode} · ${next.video.title}")
            val meta = meta(next.type, next.metaId) ?: return@launch vm.back()
            val found = streams(next.type, next.video.id)
            fun ready(s: AddonStream) = s.url != null || (s.isTorrent && s.infoHash in found.cached)
            val list = found.streams
            val pick = list.firstOrNull { next.bingeGroup != null && it.bingeGroup == next.bingeGroup && ready(it) }
                ?: list.firstOrNull { ready(it) }
            if (pick == null) {
                vm.showMessage("Couldn't find a source for the next episode")
                vm.back()
                return@launch
            }
            play(pick, meta, next.video)
        }
    }

    companion object {
        /** "addon:{type}:{metaId}|{videoId}" — kept in Continue watching. */
        fun resumeKey(type: String, metaId: String, videoId: String) = "addon:$type:$metaId|$videoId"

        /** (type, metaId, videoId) from a [resumeKey]. */
        fun parseResumeKey(key: String): Triple<String, String, String>? {
            if (!key.startsWith("addon:")) return null
            val rest = key.removePrefix("addon:")
            val type = rest.substringBefore(':', "").ifEmpty { return null }
            val ids = rest.substringAfter(':')
            return Triple(type, ids.substringBefore('|'), ids.substringAfter('|', ids.substringBefore('|')))
        }
    }
}
