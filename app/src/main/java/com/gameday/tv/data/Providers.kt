package com.gameday.tv.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One IPTV login or playlist on an account. An account can have several; their channels and
 * guides are combined.
 *
 * Ids from a provider are made unique by [prefix] ("p3k9~x1234"). The first provider keeps an empty
 * prefix so favorites, history and recordings saved before multiple providers still match.
 */
data class ProviderEntry(
    val id: String,
    val name: String,
    val account: IptvAccount,
    val prefix: String,
    val enabled: Boolean = true,
) {
    fun scope(raw: String): String = Providers.scoped(prefix, raw)

    companion object {
        fun defaultName(a: IptvAccount): String = when (a) {
            is IptvAccount.Xtream -> runCatching { java.net.URI(XtreamSource.normalizeServer(a.server)).host }.getOrNull() ?: "Xtream Codes"
            is IptvAccount.M3u -> runCatching { java.net.URI(a.url.trim()).host }.getOrNull()?.let { "$it playlist" } ?: "M3U playlist"
        }
    }
}

object Providers {
    private const val SEP = '~'

    fun scoped(prefix: String, raw: String): String = if (prefix.isEmpty()) raw else "$prefix$SEP$raw"

    fun prefixOf(id: String): String = if (SEP in id) id.substringBefore(SEP) else ""

    fun unscoped(id: String): String = if (SEP in id) id.substringAfter(SEP) else id

    fun newPrefix(taken: Collection<String>): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        while (true) {
            val p = "p" + (1..4).map { chars.random() }.joinToString("")
            if (p !in taken) return p
        }
    }

    fun encode(list: List<ProviderEntry>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().put("id", p.id).put("name", p.name).put("prefix", p.prefix).put("enabled", p.enabled)
                    .put("account", JSONObject(AccountPrefs.encodeIptv(p.account))),
            )
        }
        return arr.toString()
    }

    fun decode(s: String): List<ProviderEntry> = runCatching {
        JSONArray(s).objects().mapNotNull { o ->
            val account = AccountPrefs.decodeIptv(o.optJSONObject("account")?.toString() ?: return@mapNotNull null) ?: return@mapNotNull null
            ProviderEntry(
                id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                name = o.optString("name").ifBlank { ProviderEntry.defaultName(account) },
                account = account,
                prefix = o.optString("prefix"),
                enabled = o.optBoolean("enabled", true),
            )
        }.toList()
    }.getOrDefault(emptyList())

    /** Combines providers' lineups into one catalog (categories keep their first position). */
    fun merge(catalogs: List<IptvCatalog>): IptvCatalog? {
        if (catalogs.isEmpty()) return null
        if (catalogs.size == 1) return catalogs[0]
        val groups = LinkedHashSet<String>()
        catalogs.forEach { groups += it.groups }
        return IptvCatalog(groups.toList(), catalogs.flatMap { it.channels })
    }
}

/**
 * Wraps a provider's [IptvSource] so everything it returns carries provider-scoped ids, and
 * everything handed back to it is unscoped again.
 */
class ScopedSource(val entry: ProviderEntry, private val inner: IptvSource) {
    val prefix: String get() = entry.prefix
    private fun scope(raw: String) = Providers.scoped(prefix, raw)

    var info: AccountInfo? = null
        private set

    suspend fun login(): AccountInfo? = inner.login().also { info = it }

    suspend fun loadCatalog(): IptvCatalog {
        val c = inner.loadCatalog()
        return IptvCatalog(c.groups, c.channels.map { it.copy(id = scope(it.id), providerId = entry.id) })
    }

    fun streamUrls(channel: Channel, preferred: StreamFormat): List<String> = inner.streamUrls(channel, preferred)

    suspend fun epg(channel: Channel): List<Program> = inner.epg(channel)

    val xmltvUrl: String? get() = inner.xmltvUrl

    fun catchupUrl(channel: Channel, program: Program): String? = inner.catchupUrl(channel, program)
}
