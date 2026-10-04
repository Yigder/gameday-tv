package com.gameday.tv.data

/**
 * Remembers recent versions of live events so the score bug and alerts can show what the score
 * was a little while ago — IPTV streams run 30–90 seconds behind the live scoreboard.
 */
class DelayedHistory<T>(private val keepMs: Long = 15 * 60_000L) {
    private val history = HashMap<String, ArrayDeque<Pair<Long, T>>>()

    /** Adds [value] if it differs from the last version kept for [id]. */
    fun record(id: String, value: T, at: Long, same: (T, T) -> Boolean) {
        val list = history.getOrPut(id) { ArrayDeque() }
        val last = list.lastOrNull()
        if (last != null && same(last.second, value)) return
        list.addLast(at to value)
        // Keep the newest version older than the window too: it's still the one to show.
        while (list.size > 1 && list[1].first < at - keepMs) list.removeFirst()
    }

    /** The version that was current at [time]; the oldest one known when [time] is earlier than all. */
    fun at(id: String, time: Long): T? {
        val list = history[id] ?: return null
        var best: T? = null
        for ((t, v) in list) {
            if (t <= time) best = v else break
        }
        return best ?: list.firstOrNull()?.second
    }

    fun clear() = history.clear()
}

object ScoreSnapshots {
    fun sameGame(a: Game, b: Game): Boolean =
        a.state == b.state && a.away.score == b.away.score && a.home.score == b.home.score &&
            a.shortDetail == b.shortDetail && a.situation == b.situation && a.possessionTeamId == b.possessionTeamId

    fun sameTournament(a: Tournament, b: Tournament): Boolean =
        a.roundInProgress == b.roundInProgress && a.detail == b.detail &&
            a.leaders.take(5).map { it.id + it.toPar + it.thru } == b.leaders.take(5).map { it.id + it.toPar + it.thru }
}
