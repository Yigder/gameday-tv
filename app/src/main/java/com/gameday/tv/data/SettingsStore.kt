package com.gameday.tv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * App accounts, stored on this device only. Passwords are kept as salted PBKDF2 hashes.
 * Every account has its own IPTV provider and up to [MAX_PROFILES] profiles.
 */
class AccountStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("gameday_accounts", Context.MODE_PRIVATE)

    fun accounts(): List<AppAccount> = rawAccounts().map { it.first }

    var currentId: String?
        get() = prefs.getString(K_CURRENT, null)?.takeIf { id -> rawAccounts().any { it.first.id == id } }
        set(v) = prefs.edit().putString(K_CURRENT, v).apply()

    fun byId(id: String): AppAccount? = accounts().firstOrNull { it.id == id }

    /** @throws IllegalArgumentException with a message for the user. */
    suspend fun create(name: String, email: String, password: String): AppAccount {
        val n = name.trim()
        val e = email.trim().lowercase()
        require(n.isNotEmpty()) { "Enter your name." }
        require(EMAIL.matches(e)) { "Enter a valid email address." }
        require(password.length >= 8) { "Use at least 8 characters for your password." }
        require(rawAccounts().none { it.first.email == e }) { "An account with this email is already on this TV. Sign in instead." }
        val hash = withContext(Dispatchers.Default) { PasswordHasher.hash(password) }
        val account = AppAccount(UUID.randomUUID().toString(), n, e, System.currentTimeMillis())
        save(rawAccounts() + (account to hash))
        return account
    }

    suspend fun verify(email: String, password: String): AppAccount? {
        val e = email.trim().lowercase()
        val entry = rawAccounts().firstOrNull { it.first.email == e } ?: return null
        val ok = withContext(Dispatchers.Default) { PasswordHasher.verify(password, entry.second) }
        return if (ok) entry.first else null
    }

    fun hasEmail(email: String): Boolean = rawAccounts().any { it.first.email == email.trim().lowercase() }

    /** @throws IllegalArgumentException with a message for the user. */
    suspend fun changePassword(id: String, current: String, new: String) {
        val list = rawAccounts()
        val entry = list.firstOrNull { it.first.id == id } ?: throw IllegalArgumentException("Account not found.")
        val ok = withContext(Dispatchers.Default) { PasswordHasher.verify(current, entry.second) }
        require(ok) { "Your current password is incorrect." }
        require(new.length >= 8) { "Use at least 8 characters for your new password." }
        val hash = withContext(Dispatchers.Default) { PasswordHasher.hash(new) }
        save(list.map { if (it.first.id == id) it.first to hash else it })
    }

    fun rename(id: String, name: String) {
        save(rawAccounts().map { if (it.first.id == id) it.first.copy(name = name.trim()) to it.second else it })
    }

    /** Removes the account and everything stored for it (provider login, profiles, library). */
    fun delete(id: String) {
        val prefsNames = AccountPrefs(context, id).profiles.map { ProfilePrefs.prefsName(id, it.id) } + AccountPrefs.prefsName(id)
        prefsNames.forEach { context.deleteSharedPreferences(it) }
        save(rawAccounts().filter { it.first.id != id })
        if (prefs.getString(K_CURRENT, null) == id) currentId = null
    }

    private fun rawAccounts(): List<Pair<AppAccount, String>> = runCatching {
        val arr = JSONArray(prefs.getString(K_ACCOUNTS, "[]"))
        arr.objects().map { o ->
            AppAccount(o.getString("id"), o.optString("name"), o.optString("email"), o.optLong("created")) to o.optString("hash")
        }.toList()
    }.getOrDefault(emptyList())

    private fun save(list: List<Pair<AppAccount, String>>) {
        val arr = JSONArray()
        list.forEach { (a, hash) ->
            arr.put(JSONObject().put("id", a.id).put("name", a.name).put("email", a.email).put("created", a.createdAt).put("hash", hash))
        }
        prefs.edit().putString(K_ACCOUNTS, arr.toString()).apply()
    }

    companion object {
        const val MAX_PROFILES = 6
        private const val K_ACCOUNTS = "accounts"
        private const val K_CURRENT = "current"
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}

/** Settings shared by everyone on one app account. */
class AccountPrefs(context: Context, val accountId: String) {
    private val prefs = context.getSharedPreferences(prefsName(accountId), Context.MODE_PRIVATE)

    /** The single IPTV login from before multiple providers (2.0). Read once to migrate. */
    private val legacyIptv: IptvAccount?
        get() = Vault.open(prefs.getString(K_IPTV, null))?.let { decodeIptv(it) }

    /** IPTV logins and playlists, encrypted with a key from the Android Keystore. */
    var providers: List<ProviderEntry>
        get() {
            Vault.open(prefs.getString(K_PROVIDERS, null))?.let { return Providers.decode(it) }
            val old = legacyIptv ?: return emptyList()
            return listOf(ProviderEntry(UUID.randomUUID().toString(), ProviderEntry.defaultName(old), old, prefix = "")).also { providers = it }
        }
        set(v) {
            prefs.edit().putString(K_PROVIDERS, Vault.seal(Providers.encode(v))).remove(K_IPTV).apply()
        }

    /** Drops what On Demand saved (add-on links and the TorBox key) before it was removed. */
    fun forgetOnDemand() {
        if (prefs.contains(K_ADDONS) || prefs.contains(K_TORBOX)) prefs.edit().remove(K_ADDONS).remove(K_TORBOX).apply()
    }

    /** The user chose to watch scores without an IPTV provider for now. */
    var providerSkipped: Boolean by prefs.boolean(K_SKIPPED, false)
    var onboarded: Boolean by prefs.boolean(K_ONBOARDED, false)

    var streamFormat: StreamFormat
        get() = runCatching { StreamFormat.valueOf(prefs.getString(K_FORMAT, null) ?: "TS") }.getOrDefault(StreamFormat.TS)
        set(v) = prefs.edit().putString(K_FORMAT, v.name).apply()

    var userAgent: String
        get() = prefs.getString(K_UA, null)?.takeIf { it.isNotBlank() } ?: Http.DEFAULT_USER_AGENT
        set(v) = prefs.edit().putString(K_UA, v.trim()).apply()

    var profiles: List<Profile>
        get() = runCatching {
            JSONArray(prefs.getString(K_PROFILES, "[]")).objects().map {
                Profile(it.getString("id"), it.optString("name"), it.optInt("color"))
            }.toList()
        }.getOrDefault(emptyList())
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) }
            prefs.edit().putString(K_PROFILES, arr.toString()).apply()
        }

    var lastProfileId: String? by prefs.string(K_LAST_PROFILE)

    /** Show "Who's watching?" when the app opens (only matters with 2+ profiles). */
    var askWhoIsWatching: Boolean by prefs.boolean(K_ASK, true)

    /** Provider categories hidden from the live guide's "All channels". */
    var hiddenGroups: Set<String>
        get() = prefs.getStringSet(K_HIDDEN_GROUPS, null)?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet(K_HIDDEN_GROUPS, v).apply()

    /** "number" or "name". */
    var guideSort: String by prefs.string(K_GUIDE_SORT, "number")

    /** DVR space limit in GB (0 = until the TV's storage runs low). */
    var dvrCapGb: Int
        get() = prefs.getInt(K_DVR_CAP, 20)
        set(v) = prefs.edit().putInt(K_DVR_CAP, v).apply()

    /** Delete recordings older than this many days (0 = keep). */
    var dvrKeepDays: Int
        get() = prefs.getInt(K_DVR_KEEP, 30)
        set(v) = prefs.edit().putInt(K_DVR_KEEP, v).apply()

    var dvrPaddingMin: Int
        get() = prefs.getInt(K_DVR_PAD, 2)
        set(v) = prefs.edit().putInt(K_DVR_PAD, v).apply()

    companion object {
        fun prefsName(accountId: String) = "acct_$accountId"
        private const val K_IPTV = "iptv"
        private const val K_PROVIDERS = "providers"
        private const val K_ADDONS = "addons"
        private const val K_TORBOX = "torbox"
        private const val K_SKIPPED = "provider_skipped"
        private const val K_ONBOARDED = "onboarded"
        private const val K_FORMAT = "stream_format"
        private const val K_UA = "user_agent"
        private const val K_PROFILES = "profiles"
        private const val K_LAST_PROFILE = "last_profile"
        private const val K_ASK = "ask_who"
        private const val K_HIDDEN_GROUPS = "hidden_groups"
        private const val K_GUIDE_SORT = "guide_sort"
        private const val K_DVR_CAP = "dvr_cap_gb"
        private const val K_DVR_KEEP = "dvr_keep_days"
        private const val K_DVR_PAD = "dvr_padding"

        fun encodeIptv(a: IptvAccount): String = when (a) {
            is IptvAccount.Xtream -> JSONObject().put("type", "xtream").put("server", a.server).put("user", a.username).put("pass", a.password)
            is IptvAccount.M3u -> JSONObject().put("type", "m3u").put("url", a.url).put("epg", a.epgUrl.orEmpty())
        }.toString()

        fun decodeIptv(s: String): IptvAccount? = runCatching {
            val o = JSONObject(s)
            when (o.optString("type")) {
                "xtream" -> IptvAccount.Xtream(o.optString("server"), o.optString("user"), o.optString("pass"))
                "m3u" -> IptvAccount.M3u(o.optString("url"), o.optString("epg").ifBlank { null })
                else -> null
            }
        }.getOrNull()
    }
}

/** One profile's own things: sports, teams, library, history, and viewing preferences. */
class ProfilePrefs(context: Context, accountId: String, profileId: String) {
    private val prefs = context.getSharedPreferences(prefsName(accountId, profileId), Context.MODE_PRIVATE)

    /** League keys followed. Defaults to every league. */
    var enabledLeagues: Set<String>
        get() = prefs.getStringSet(K_LEAGUES, null)?.toSet() ?: Leagues.everything.map { it.key }.toSet()
        set(v) = prefs.edit().putStringSet(K_LEAGUES, v).apply()

    var favoriteTeams: List<FavoriteTeam>
        get() = readList(K_TEAMS) { o ->
            FavoriteTeam(
                leagueKey = o.getString("league"),
                id = o.getString("id"),
                name = o.optString("name"),
                abbreviation = o.optString("abbr"),
                logo = o.optString("logo").ifBlank { null },
                record = o.optBoolean("record", false),
            )
        }
        set(v) = writeList(K_TEAMS, v) { t ->
            JSONObject().put("league", t.leagueKey).put("id", t.id).put("name", t.name)
                .put("abbr", t.abbreviation).put("logo", t.logo.orEmpty()).put("record", t.record)
        }

    /** Favorite channel ids, in the order they were added. */
    var favoriteChannels: List<String>
        get() = readStrings(K_FAV_CHANNELS)
        set(v) = writeStrings(K_FAV_CHANNELS, v)

    var recentChannels: List<String>
        get() = readStrings(K_RECENT_CHANNELS)
        set(v) = writeStrings(K_RECENT_CHANNELS, v.take(30))

    var recentSearches: List<String>
        get() = readStrings(K_RECENT_SEARCHES)
        set(v) = writeStrings(K_RECENT_SEARCHES, v.take(8))

    /** Where recordings were left ("rec:<id>"; movie and episode points from before 2.4 are skipped). */
    var resume: List<ResumePoint>
        get() = readList(K_RESUME) { o ->
            ResumePoint(o.getString("key"), o.optString("title"), o.optString("subtitle"), o.optString("image").ifBlank { null },
                o.optLong("pos"), o.optLong("dur"), o.optLong("at"))
        }.filter { it.key.startsWith("rec:") }
        set(v) = writeList(K_RESUME, v.sortedByDescending { it.updatedAt }.take(40)) { r ->
            JSONObject().put("key", r.key).put("title", r.title).put("subtitle", r.subtitle).put("image", r.image.orEmpty())
                .put("pos", r.positionMs).put("dur", r.durationMs).put("at", r.updatedAt)
        }

    /** Spoiler protection: no scores on cards, pages or the score bug until asked. */
    var hideScores: Boolean by prefs.boolean(K_HIDE_SCORES, false)

    var scoreBugMode: ScoreBugMode
        get() = runCatching { ScoreBugMode.valueOf(prefs.getString(K_BUG_MODE, null) ?: "ON_PRESS") }.getOrDefault(ScoreBugMode.ON_PRESS)
        set(v) = prefs.edit().putString(K_BUG_MODE, v.name).apply()

    var scoreAlerts: Boolean by prefs.boolean(K_ALERTS, true)
    var captions: Boolean by prefs.boolean(K_CAPTIONS, false)

    /**
     * Seconds the score bug and score alerts lag behind live scores. IPTV streams run behind the
     * real broadcast, so live data would otherwise spoil plays before they're seen.
     */
    var scoreDelaySec: Int
        get() = prefs.getInt(K_SCORE_DELAY, 60)
        set(v) = prefs.edit().putInt(K_SCORE_DELAY, v).apply()

    /** Live video behind the menus, like YouTube TV: "sound", "muted" or "off". */
    var backgroundVideo: String by prefs.string(K_BG_VIDEO, "sound")

    /** Live guide filter: "all", "sports", "favorites", "recent" or "group:<name>". */
    var guideFilter: String by prefs.string(K_GUIDE_FILTER, "sports")

    /** How subtitles look ([SubtitleStyle.encode]). */
    var subtitleStyle: SubtitleStyle
        get() = SubtitleStyle.decode(prefs.getString(K_SUB_STYLE, null))
        set(v) = prefs.edit().putString(K_SUB_STYLE, v.encode()).apply()

    /** Caption language picked automatically (ISO 639-1); the TV's language until changed. */
    var subtitleLanguage: String
        get() = prefs.getString(K_SUB_LANG, null) ?: java.util.Locale.getDefault().language.ifBlank { "en" }
        set(v) = prefs.edit().putString(K_SUB_LANG, v).apply()

    /** Size of the player's round buttons: "small", "medium" or "large". */
    var playerButtons: String by prefs.string(K_PLAYER_BUTTONS, "medium")

    /** Drops what On Demand saved (saved titles, its searches and settings) before it was removed. */
    fun forgetOnDemand() {
        val old = listOf(K_SAVED, K_AUTOPLAY, K_TRAILERS, K_RECENT_OD_SEARCHES).filter { prefs.contains(it) }
        if (old.isNotEmpty()) prefs.edit().apply { old.forEach { remove(it) } }.apply()
    }

    private fun readStrings(key: String): List<String> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun writeStrings(key: String, v: List<String>) {
        prefs.edit().putString(key, JSONArray(v).toString()).apply()
    }

    private fun <T> readList(key: String, read: (JSONObject) -> T): List<T> = runCatching {
        JSONArray(prefs.getString(key, "[]")).objects().mapNotNull { runCatching { read(it) }.getOrNull() }.toList()
    }.getOrDefault(emptyList())

    private fun <T> writeList(key: String, v: List<T>, write: (T) -> JSONObject) {
        val arr = JSONArray()
        v.forEach { arr.put(write(it)) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    companion object {
        fun prefsName(accountId: String, profileId: String) = "prof_${accountId}_$profileId"
        private const val K_LEAGUES = "enabled_leagues"
        private const val K_TEAMS = "favorite_teams"
        private const val K_FAV_CHANNELS = "favorite_channels"
        private const val K_RECENT_CHANNELS = "recent_channels"
        private const val K_RECENT_SEARCHES = "recent_searches"
        private const val K_SAVED = "saved"
        private const val K_RESUME = "resume"
        private const val K_HIDE_SCORES = "hide_scores"
        private const val K_BUG_MODE = "score_bug_mode"
        private const val K_ALERTS = "score_alerts"
        private const val K_CAPTIONS = "captions"
        private const val K_AUTOPLAY = "autoplay_next"
        private const val K_SCORE_DELAY = "score_delay_sec"
        private const val K_BG_VIDEO = "background_video"
        private const val K_TRAILERS = "trailer_previews"
        private const val K_GUIDE_FILTER = "guide_filter"
        private const val K_SUB_STYLE = "subtitle_style"
        private const val K_SUB_LANG = "subtitle_language"
        private const val K_PLAYER_BUTTONS = "player_buttons"
        private const val K_RECENT_OD_SEARCHES = "recent_od_searches"
    }
}

/**
 * Settings from GameDay TV 1.x (before app accounts). The first account created on the device can
 * take over the saved IPTV login and sports picks, so nobody has to type them in again.
 */
class LegacySettings(context: Context) {
    private val prefs = context.getSharedPreferences("gameday_settings", Context.MODE_PRIVATE)

    val provider: IptvAccount?
        get() = when (prefs.getString("acct_type", null)) {
            "xtream" -> IptvAccount.Xtream(
                prefs.getString("xt_server", "").orEmpty(),
                prefs.getString("xt_user", "").orEmpty(),
                prefs.getString("xt_pass", "").orEmpty(),
            )
            "m3u" -> IptvAccount.M3u(prefs.getString("m3u_url", "").orEmpty())
            else -> null
        }

    /** What to call the saved provider on screen, without showing any secrets. */
    val providerLabel: String?
        get() = when (val p = provider) {
            is IptvAccount.Xtream -> runCatching { android.net.Uri.parse(XtreamSource.normalizeServer(p.server)).host }.getOrNull() ?: "Xtream Codes"
            is IptvAccount.M3u -> "M3U playlist"
            null -> null
        }

    val enabledLeagues: Set<String>? get() = prefs.getStringSet("enabled_leagues", null)?.toSet()

    val favoriteTeams: List<FavoriteTeam>
        get() = runCatching {
            JSONArray(prefs.getString("favorite_teams", "[]")).objects().map { o ->
                FavoriteTeam(o.getString("league"), o.getString("id"), o.optString("name"), o.optString("abbr"), o.optString("logo").ifBlank { null })
            }.toList()
        }.getOrDefault(emptyList())

    val streamFormat: StreamFormat? get() = prefs.getString("stream_format", null)?.let { runCatching { StreamFormat.valueOf(it) }.getOrNull() }
    val userAgent: String? get() = prefs.getString("user_agent", null)?.takeIf { it.isNotBlank() }

    /** Removes the plain-text login once an account has stored it encrypted. */
    fun forgetProvider() {
        prefs.edit().remove("acct_type").remove("xt_server").remove("xt_user").remove("xt_pass").remove("m3u_url").apply()
    }
}

// ---- small SharedPreferences delegates ----

private fun SharedPreferences.boolean(key: String, default: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
    override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = getBoolean(key, default)
    override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) = edit().putBoolean(key, value).apply()
}

private fun SharedPreferences.string(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, String?> {
    override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = getString(key, null)
    override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String?) = edit().putString(key, value).apply()
}

private fun SharedPreferences.string(key: String, default: String) = object : kotlin.properties.ReadWriteProperty<Any?, String> {
    override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = getString(key, null) ?: default
    override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) = edit().putString(key, value).apply()
}
