package com.gameday.tv.ui

import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val DAY = DateTimeFormatter.ofPattern("EEE M/d", Locale.getDefault())
private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
private val DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

fun formatStart(millis: Long): String {
    if (millis <= 0) return "TBD"
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = zoned.format(TIME)
    return when (zoned.toLocalDate()) {
        today -> time
        today.plusDays(1) -> "Tomorrow $time"
        today.minusDays(1) -> "Yesterday $time"
        else -> "${zoned.format(DAY)} $time"
    }
}

/** "Today", "Tomorrow", "Saturday", "Oct 12, 2026". */
fun formatDay(millis: Long): String {
    val d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        d == today -> "Today"
        d == today.plusDays(1) -> "Tomorrow"
        d == today.minusDays(1) -> "Yesterday"
        d.isAfter(today) && d.isBefore(today.plusDays(7)) -> d.format(WEEKDAY)
        else -> d.format(DATE)
    }
}

fun formatTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TIME)

fun formatDate(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DATE)

fun formatRange(start: Long, end: Long): String = "${formatTime(start)} – ${formatTime(end)}"

/** "1 hr 25 min", "45 min". */
fun durationText(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "$h hr $m min"
        h > 0 -> "$h hr"
        else -> "$m min"
    }
}

/** "12:05" or "1:02:05" for player positions. */
fun clockText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun minutesLeft(end: Long, now: Long = System.currentTimeMillis()): String {
    val left = ((end - now) / 60_000L).coerceAtLeast(0)
    return if (left >= 60) "${durationText(left * 60_000L)} left" else "$left min left"
}

fun bytesText(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1e6)
    else -> "${bytes / 1000} KB"
}

fun statusText(game: Game): String = when (game.state) {
    GameState.LIVE, GameState.FINAL -> game.shortDetail
    GameState.PRE -> formatStart(game.startMillis)
}

fun stateOrder(game: Game): Int = when (game.state) {
    GameState.LIVE -> 0
    GameState.PRE -> 1
    GameState.FINAL -> 2
}
