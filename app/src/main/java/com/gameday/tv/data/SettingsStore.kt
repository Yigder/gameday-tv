package com.gameday.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local, on-device settings. Credentials never leave the device except to the user's own IPTV server. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("gameday_settings", Context.MODE_PRIVATE)

    var account: IptvAccount?
        get() = when (prefs.getString(K_TYPE, null)) {
            "xtream" -> IptvAccount.Xtream(
                prefs.getString(K_SERVER, "").orEmpty(),
                prefs.getString(K_USER, "").orEmpty(),
                prefs.getString(K_PASS, "").orEmpty(),
            )
            "m3u" -> IptvAccount.M3u(prefs.getString(K_M3U, "").orEmpty())
            else -> null
        }
        set(value) {
            val e = prefs.edit()
            when (value) {
                is IptvAccount.Xtream -> e.putString(K_TYPE, "xtream")
                    .putString(K_SERVER, value.server)
                    .putString(K_USER, value.username)
                    .putString(K_PASS, value.password)
                    .remove(K_M3U)
                is IptvAccount.M3u -> e.putString(K_TYPE, "m3u")
                    .putString(K_M3U, value.url)
                    .remove(K_SERVER).remove(K_USER).remove(K_PASS)
                null -> e.remove(K_TYPE).remove(K_SERVER).remove(K_USER).remove(K_PASS).remove(K_M3U)
            }
            e.apply()
        }

    /** User chose to use the app for scores without signing in to IPTV. */
    var scoresOnly: Boolean
        get() = prefs.getBoolean(K_SCORES_ONLY, false)
        set(v) = prefs.edit().putBoolean(K_SCORES_ONLY, v).apply()

    var streamFormat: StreamFormat
        get() = runCatching { StreamFormat.valueOf(prefs.getString(K_FORMAT, null) ?: "TS") }.getOrDefault(StreamFormat.TS)
        set(v) = prefs.edit().putString(K_FORMAT, v.name).apply()

    var userAgent: String
        get() = prefs.getString(K_UA, null)?.takeIf { it.isNotBlank() } ?: Http.DEFAULT_USER_AGENT
        set(v) = prefs.edit().putString(K_UA, v.trim()).apply()

    /** League keys the user wants to follow. Defaults to every league. */
    var enabledLeagues: Set<String>
        get() = prefs.getStringSet(K_LEAGUES, null)?.toSet() ?: Leagues.everything.map { it.key }.toSet()
        set(v) = prefs.edit().putStringSet(K_LEAGUES, v).apply()

    var favoriteTeams: List<FavoriteTeam>
        get() = runCatching {
            val arr = JSONArray(prefs.getString(K_FAVORITES, "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FavoriteTeam(
                    leagueKey = o.getString("league"),
                    id = o.getString("id"),
                    name = o.optString("name"),
                    abbreviation = o.optString("abbr"),
                    logo = o.optString("logo").takeIf { it.isNotBlank() },
                )
            }
        }.getOrDefault(emptyList())
        set(v) {
            val arr = JSONArray()
            v.forEach { t ->
                arr.put(
                    JSONObject().put("league", t.leagueKey).put("id", t.id).put("name", t.name)
                        .put("abbr", t.abbreviation).put("logo", t.logo.orEmpty()),
                )
            }
            prefs.edit().putString(K_FAVORITES, arr.toString()).apply()
        }

    var scoreBugMode: ScoreBugMode
        get() = runCatching { ScoreBugMode.valueOf(prefs.getString(K_BUG_MODE, null) ?: "ON_PRESS") }.getOrDefault(ScoreBugMode.ON_PRESS)
        set(v) = prefs.edit().putString(K_BUG_MODE, v.name).apply()

    /** Pop the score bug up (and alert for favorite teams) when a score changes. */
    var scoreAlerts: Boolean
        get() = prefs.getBoolean(K_ALERTS, true)
        set(v) = prefs.edit().putBoolean(K_ALERTS, v).apply()

    private companion object {
        const val K_LEAGUES = "enabled_leagues"
        const val K_FAVORITES = "favorite_teams"
        const val K_BUG_MODE = "score_bug_mode"
        const val K_ALERTS = "score_alerts"
        const val K_TYPE = "acct_type"
        const val K_SERVER = "xt_server"
        const val K_USER = "xt_user"
        const val K_PASS = "xt_pass"
        const val K_M3U = "m3u_url"
        const val K_SCORES_ONLY = "scores_only"
        const val K_FORMAT = "stream_format"
        const val K_UA = "user_agent"
    }
}
