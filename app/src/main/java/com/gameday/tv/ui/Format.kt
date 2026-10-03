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
private val DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

fun formatStart(millis: Long): String {
    if (millis <= 0) return "TBD"
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = zoned.format(TIME)
    return when (zoned.toLocalDate()) {
        today -> time
        today.plusDays(1) -> "Tomorrow $time"
        today.minusDays(1) -> "Yesterday"
        else -> "${zoned.format(DAY)} $time"
    }
}

fun formatTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TIME)

fun formatDate(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DATE)

fun statusText(game: Game): String = when (game.state) {
    GameState.LIVE, GameState.FINAL -> game.shortDetail
    GameState.PRE -> formatStart(game.startMillis)
}

fun stateOrder(game: Game): Int = when (game.state) {
    GameState.LIVE -> 0
    GameState.PRE -> 1
    GameState.FINAL -> 2
}
