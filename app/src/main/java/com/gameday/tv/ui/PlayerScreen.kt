package com.gameday.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.Channel
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.TeamScore
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

private enum class Panel { None, Scores, Channels }

internal val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** Remote buttons that show/hide the score bug (Info, Guide, and the red/green color keys). */
internal val SCORE_KEYS = setOf(Key.Info, Key.Guide, Key.ProgramRed, Key.ProgramGreen)

/** True on the first auto-repeat of a held key, i.e. a long press. */
internal fun KeyEvent.isLongPressRepeat(): Boolean = nativeKeyEvent.repeatCount == 1

@Composable
fun PlayerScreen(vm: AppViewModel) {
    val pb = vm.playback
    val channel = pb?.current
    if (pb == null || channel == null) {
        EmptyState("Nothing to play", modifier = Modifier.padding(top = 120.dp)) { ActionButton("Back", { vm.back() }, primary = true) }
        return
    }

    val stream = rememberStreamController()
    val candidates = remember(channel.id, vm.streamFormat) { vm.streamCandidates(channel) }
    LaunchedEffect(channel.id, candidates) {
        stream.load(channel.id, candidates)
        vm.noteRecent(channel)
    }

    // ---- overlays ----
    var panel by remember { mutableStateOf(Panel.None) }
    var infoVisible by remember { mutableStateOf(true) }
    var infoNonce by remember { mutableIntStateOf(0) }
    var okLongPressed by remember { mutableStateOf(false) }
    LaunchedEffect(infoNonce, channel.id) {
        infoVisible = true
        delay(5_000)
        infoVisible = false
    }

    // Score bug: the event we came from, or a live event this channel looks like it's showing.
    val detectedGame = remember(channel.id, vm.games) { vm.liveGameFor(channel) }
    val detectedTournament = remember(channel.id, vm.tournaments) { if (detectedGame == null) vm.liveTournamentFor(channel) else null }
    val linkedGame = pb.eventId?.let { vm.gameById(it) } ?: detectedGame.takeIf { pb.eventId == null }
    val linkedTournament = pb.eventId?.let { vm.tournamentById(it) } ?: detectedTournament.takeIf { pb.eventId == null }
    // ScoreBox-style: visible when the stream starts, then hidden until OK / Info, or a score change.
    val bug = rememberBugState(
        key = channel.id,
        scoreKey = scoreKeyOf(linkedGame, linkedTournament),
        mode = vm.scoreBugMode,
        popOnScore = vm.scoreAlerts,
    )

    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(panel) { if (panel == Panel.None) rootFocus.requestFocusSafely(30) }
    BackHandler(enabled = panel != Panel.None) { panel = Panel.None }

    fun onOk() {
        when {
            stream.error != null -> stream.retry()
            infoVisible -> infoVisible = false
            else -> {
                infoNonce++
                bug.show()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onKeyEvent { ev ->
                if (panel != Panel.None) return@onKeyEvent false
                // OK acts on release so that holding it can mean "add to Multiview" instead.
                if (ev.key in OK_KEYS) {
                    if (ev.type == KeyEventType.KeyDown && ev.isLongPressRepeat()) {
                        okLongPressed = true
                        vm.addToMultiview(channel, open = true)
                    } else if (ev.type == KeyEventType.KeyUp) {
                        if (!okLongPressed) onOk()
                        okLongPressed = false
                    }
                    return@onKeyEvent true
                }
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionUp, Key.ChannelUp -> { vm.zap(+1); true }
                    Key.DirectionDown, Key.ChannelDown -> { vm.zap(-1); true }
                    Key.DirectionLeft -> { panel = Panel.Scores; true }
                    Key.DirectionRight, Key.Menu -> { panel = Panel.Channels; true }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { stream.togglePause(); true }
                    in SCORE_KEYS -> { bug.toggle(); true }
                    else -> false
                }
            }
            .focusable()
            // Touch (phones/tablets): tap toggles the info banner, long-press adds to Multiview.
            .pointerInput(channel.id) {
                detectTapGestures(
                    onTap = { onOk() },
                    onLongPress = { vm.addToMultiview(channel, open = true) },
                )
            },
    ) {
        VideoSurface(stream, Modifier.fillMaxSize())

        if (stream.buffering && stream.error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner(48.dp)
                if (stream.attempt > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text("Trying alternate stream format…", fontSize = 13.sp, color = AppColors.TextDim)
                }
            }
        }

        stream.error?.let { msg ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .width(520.dp)
                    .background(Color(0xEE111824), RoundedCornerShape(16.dp))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Can't play ${channel.name}", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(msg, fontSize = 14.sp, color = AppColors.TextDim, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Text("OK  Retry     ▲▼  Other channel     ▶  Channel list", fontSize = 13.sp, color = AppColors.Accent)
            }
        }

        EventBug(
            linkedGame,
            linkedTournament,
            bug,
            Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 32.dp),
        )

        AnimatedVisibility(
            visible = infoVisible && panel == Panel.None,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val subtitle = linkedGame?.let { "${it.away.shortName} @ ${it.home.shortName}" } ?: linkedTournament?.name
            InfoBanner(channel, pb.index, pb.channels.size, subtitle)
        }

        AnimatedVisibility(
            visible = panel == Panel.Scores,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            ScoresPanel(vm) { eventId ->
                panel = Panel.None
                vm.openEventFromPlayer(eventId)
            }
        }

        AnimatedVisibility(
            visible = panel == Panel.Channels,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            ChannelsPanel(
                channels = pb.channels,
                current = pb.index,
                multiviewCount = vm.multiviewCount,
                onPick = { i ->
                    panel = Panel.None
                    vm.zapTo(i)
                },
                onMultiview = {
                    panel = Panel.None
                    vm.addToMultiview(channel, open = true)
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Overlays
// ---------------------------------------------------------------------------------------------

@Composable
private fun InfoBanner(channel: Channel, index: Int, total: Int, subtitle: String?) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000))))
            .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel, 64.dp)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (channel.num > 0) "${channel.num}   " else "") + channel.name,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(channel.group, subtitle).joinToString("  •  "),
                    fontSize = 14.sp,
                    color = AppColors.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "OK / Info  Score    ▲▼ Channel    ◀ Live scores    ▶ Channel list    Hold OK  Multiview    BACK Exit",
                    fontSize = 12.sp,
                    color = AppColors.TextDim,
                )
            }
            if (total > 1) Text("${index + 1} / $total", fontSize = 14.sp, color = AppColors.TextDim)
        }
    }
}

@Composable
private fun ScoresPanel(vm: AppViewModel, onPick: (eventId: String) -> Unit) {
    val now = System.currentTimeMillis()
    val golf = remember(vm.tournaments) { vm.tournaments.filter { it.state == GameState.LIVE } }
    val games = remember(vm.games) {
        vm.games.filter { it.state == GameState.LIVE }.sortedBy { it.startMillis } +
            vm.games.filter { it.state == GameState.PRE && it.startMillis - now < 12 * 60 * 60_000L }.sortedBy { it.startMillis }.take(20)
    }
    val firstId = golf.firstOrNull()?.id ?: games.firstOrNull()?.id
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSafely(120) }

    Column(
        Modifier
            .width(380.dp)
            .fillMaxHeight()
            .background(Color(0xF20B111B))
            .padding(start = 28.dp, end = 16.dp, top = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot(10.dp)
            Spacer(Modifier.width(8.dp))
            Text("Live Scores", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text("Select an event to find its channel", fontSize = 12.sp, color = AppColors.TextDim)
        Spacer(Modifier.height(12.dp))
        if (games.isEmpty() && golf.isEmpty()) {
            Text("No live or upcoming events right now.", fontSize = 14.sp, color = AppColors.TextDim)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp, 4.dp, 4.dp, 24.dp)) {
            items(golf, key = { it.id }) { t ->
                PanelCard(onClick = { onPick(t.id) }, focus = if (t.id == firstId) first else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.tour.label, fontSize = 11.sp, color = AppColors.TextDim, modifier = Modifier.weight(1f))
                        Text(t.detail.replace("Round ", "R"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (t.roundInProgress) AppColors.Live else AppColors.TextDim)
                    }
                    Text(t.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    t.leaders.take(2).forEach { p ->
                        Row {
                            Text("${p.position}  ${p.shortName}", fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(p.toPar, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = parColor(p.toPar))
                        }
                    }
                }
            }
            items(games, key = { it.id }) { g ->
                PanelCard(onClick = { onPick(g.id) }, focus = if (g.id == firstId) first else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.league.label, fontSize = 11.sp, color = AppColors.TextDim, modifier = Modifier.weight(1f))
                        StatusBadge(g)
                    }
                    Spacer(Modifier.height(4.dp))
                    CompactTeam(g.away, g)
                    CompactTeam(g.home, g)
                }
            }
        }
    }
}

@Composable
private fun PanelCard(onClick: () -> Unit, focus: FocusRequester?, content: @Composable () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
        focusedScale = 1.03f,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) { content() }
    }
}

@Composable
private fun CompactTeam(team: TeamScore, game: Game) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        TeamLogo(team.logo, team.abbreviation, 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(team.shortName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (game.state != GameState.PRE) Text(team.score, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ChannelsPanel(
    channels: List<Channel>,
    current: Int,
    multiviewCount: Int,
    onPick: (Int) -> Unit,
    onMultiview: () -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { currentFocus.requestFocusSafely(120) }

    Column(
        Modifier
            .width(440.dp)
            .fillMaxHeight()
            .background(Color(0xF20B111B))
            .padding(start = 16.dp, end = 28.dp, top = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Channels", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${channels.size} in this list", fontSize = 12.sp, color = AppColors.TextDim)
            }
            ActionButton(if (multiviewCount > 0) "⊞ Multiview ($multiviewCount)" else "⊞ Multiview", onMultiview)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(4.dp, 4.dp, 4.dp, 24.dp),
        ) {
            itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
                ChannelRow(
                    channel = ch,
                    subtitle = ch.group,
                    playing = i == current,
                    onClick = { onPick(i) },
                    modifier = if (i == current) Modifier.focusRequester(currentFocus) else Modifier,
                )
            }
        }
    }
}
