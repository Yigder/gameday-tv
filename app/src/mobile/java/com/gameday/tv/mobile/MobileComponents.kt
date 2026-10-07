package com.gameday.tv.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameday.tv.data.Channel
import com.gameday.tv.data.FavoriteTeam
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.data.Leagues
import com.gameday.tv.data.Program
import com.gameday.tv.data.RecStatus
import com.gameday.tv.data.Recording
import com.gameday.tv.data.ResumePoint
import com.gameday.tv.data.Tournament
import com.gameday.tv.ui.AppDialog
import com.gameday.tv.ui.AppViewModel
import com.gameday.tv.ui.ChannelLogo
import com.gameday.tv.ui.ChannelThumb
import com.gameday.tv.ui.DialogAction
import com.gameday.tv.ui.GameArt
import com.gameday.tv.ui.GameThumb
import com.gameday.tv.ui.Icons
import com.gameday.tv.ui.IptvStatus
import com.gameday.tv.ui.LiveBadge
import com.gameday.tv.ui.MultiviewPreset
import com.gameday.tv.ui.PosterThumb
import com.gameday.tv.ui.ProgressLine
import com.gameday.tv.ui.Screen
import com.gameday.tv.ui.Tag
import com.gameday.tv.ui.TeamLogo
import com.gameday.tv.ui.TournamentThumb
import com.gameday.tv.ui.channelMenu
import com.gameday.tv.ui.cleanChannelName
import com.gameday.tv.ui.durationText
import com.gameday.tv.ui.formatDay
import com.gameday.tv.ui.formatStart
import com.gameday.tv.ui.gameMenu
import com.gameday.tv.ui.minutesLeft
import com.gameday.tv.ui.recordingMenu
import com.gameday.tv.ui.statusLine
import com.gameday.tv.ui.theme.AppColors
import com.gameday.tv.ui.tournamentMenu

/** Width of a 16:9 card in a horizontal row. */
val TILE_WIDTH = 248.dp

/** Side margin of the phone screens. */
val GUTTER = 16.dp

@Composable
fun AppIcon(icon: ImageVector, description: String?, modifier: Modifier = Modifier, tint: Color = AppColors.Text, size: Dp = 24.dp) {
    Icon(icon, description, modifier.size(size), tint = tint)
}

// ---------------------------------------------------------------------------------------------
// Menus: the view model's dialogs as a bottom sheet
// ---------------------------------------------------------------------------------------------

/** [AppDialog]s (the TV's side menus) as a bottom sheet of actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(vm: AppViewModel, dialog: AppDialog) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { vm.dismissDialog() },
        sheetState = state,
        containerColor = AppColors.Raised,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(dialog.title, fontSize = 19.sp, fontWeight = FontWeight.Medium, maxLines = 3, modifier = Modifier.padding(horizontal = 24.dp))
            dialog.subtitle?.let {
                Text(it, fontSize = 13.sp, color = AppColors.TextDim, maxLines = 2, modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp))
            }
            dialog.message?.let {
                Text(it, fontSize = 14.sp, color = Color(0xFFCCCCCC), lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            dialog.actions.forEach { a -> SheetAction(a) }
        }
    }
}

@Composable
private fun SheetAction(a: DialogAction) {
    ListItem(
        headlineContent = { Text(a.label, fontSize = 15.sp) },
        leadingContent = a.icon?.let { { AppIcon(it, null) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.combinedClickable(onClick = a.onClick).padding(horizontal = 8.dp),
    )
}

// ---------------------------------------------------------------------------------------------
// Bars and headers
// ---------------------------------------------------------------------------------------------

/** A screen's top bar with Back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(vm: AppViewModel, title: String = "", actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 18.sp) },
        navigationIcon = { IconButton(onClick = { vm.back() }) { AppIcon(Icons.Back, "Back") } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Background),
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = GUTTER, end = 4.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
        if (action != null && onAction != null) {
            androidx.compose.material3.TextButton(onClick = onAction) { Text(action, color = AppColors.TextDim) }
        }
    }
}

/** A titled horizontal row of cards in a [androidx.compose.foundation.lazy.LazyColumn]. */
fun LazyListScope.rowSection(key: String, title: String, content: LazyListScope.() -> Unit) {
    item(key = key) {
        Column {
            SectionHeader(title)
            LazyRow(
                contentPadding = PaddingValues(horizontal = GUTTER),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------------

/** Artwork with a title and subtitle under it; long-press opens the item's menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaTile(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    width: Dp? = TILE_WIDTH,
    aspect: Float = 16f / 9f,
    art: @Composable BoxScope.() -> Unit,
) {
    Column(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(10.dp)).background(AppColors.Card)) { art() }
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, fontSize = 12.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun GameTile(vm: AppViewModel, game: Game, width: Dp = TILE_WIDTH) {
    MediaTile(
        title = game.title,
        subtitle = "${game.league.label} · ${statusLine(game, vm.hideScores)}",
        // Like the TV: a live game plays; anything else opens its page.
        onClick = { if (game.state == GameState.LIVE) vm.watchGame(game) else vm.openGame(game) },
        onLongClick = { gameMenu(vm, game) },
        width = width,
    ) { GameThumb(game, vm.hideScores, vm.recordingForEvent(game.id) != null) }
}

@Composable
fun TournamentTile(vm: AppViewModel, t: Tournament, width: Dp = TILE_WIDTH) {
    MediaTile(
        title = t.name,
        subtitle = "${t.tour.label} · ${t.detail}",
        onClick = { if (t.roundInProgress) vm.watchTournament(t) else vm.openTournament(t) },
        onLongClick = { tournamentMenu(vm, t) },
        width = width,
    ) { TournamentThumb(t, vm.hideScores) }
}

@Composable
fun ChannelTile(vm: AppViewModel, channel: Channel, list: List<Channel> = listOf(channel), width: Dp = TILE_WIDTH) {
    LaunchedEffect(channel.id) { vm.requestEpg(channel) }
    val now = System.currentTimeMillis()
    val program = vm.nowPlaying(channel.id, now)
    MediaTile(
        title = program?.title ?: cleanChannelName(channel.name),
        subtitle = if (program != null) "${cleanChannelName(channel.name)} · ${minutesLeft(program.endMillis, now)}" else channel.group,
        onClick = { vm.playChannel(channel, list) },
        onLongClick = { channelMenu(vm, channel, list) },
        width = width,
    ) { ChannelThumb(channel, program, now) }
}

@Composable
fun RedZoneTile(vm: AppViewModel, channels: List<Channel>, liveGames: Int) {
    val channel = channels.first()
    MediaTile(
        title = "NFL RedZone",
        subtitle = "NFL · $liveGames ${if (liveGames == 1) "game" else "games"} live",
        onClick = { vm.playChannel(channel, channels) },
        onLongClick = { channelMenu(vm, channel, channels) },
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFB3001B), Color(0xFF4A0010)))), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("RED ZONE", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = 1.sp)
                Text("$liveGames LIVE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xCCFFFFFF))
            }
        }
    }
}

@Composable
fun ResumeTile(vm: AppViewModel, point: ResumePoint, width: Dp? = TILE_WIDTH) {
    MediaTile(
        title = point.title,
        subtitle = "${point.subtitle} · ${durationText(point.durationMs - point.positionMs)} left",
        onClick = { vm.playResume(point) },
        width = width,
        onLongClick = {
            vm.showDialog(AppDialog(point.title, point.subtitle, actions = listOf(
                DialogAction("Resume", Icons.Play) { vm.dismissDialog(); vm.playResume(point) },
                DialogAction("Remove from Continue watching", Icons.Close) { vm.dismissDialog(); vm.removeResume(point.key) },
            )))
        },
    ) {
        PosterThumb(point.image, point.title)
        ProgressLine(point.progress, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun RecordingTile(vm: AppViewModel, r: Recording, width: Dp? = TILE_WIDTH) {
    val status = when (r.status) {
        RecStatus.SCHEDULED -> formatStart(r.startMillis)
        RecStatus.RECORDING -> "Recording now"
        RecStatus.DONE -> "${formatDay(r.startMillis)} · ${durationText(r.durationMs)}"
        else -> r.status.label
    }
    val resumeAt = vm.resumeFor("rec:${r.id}")
    MediaTile(
        title = r.title,
        subtitle = "${cleanChannelName(r.channelName)} · $status",
        onClick = { if (r.status == RecStatus.DONE || r.status == RecStatus.RECORDING) vm.playRecording(r) else recordingMenu(vm, r) },
        onLongClick = { recordingMenu(vm, r) },
        width = width,
    ) {
        val game = r.eventId?.let { vm.gameById(it) }
        if (game != null) GameArt(game, showScore = false)
        else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2B2B2B), Color(0xFF151515)))), contentAlignment = Alignment.Center) {
            Text(r.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 3, modifier = Modifier.padding(10.dp))
        }
        Box(Modifier.fillMaxSize().padding(6.dp)) {
            when (r.status) {
                RecStatus.RECORDING -> Tag("● REC", Modifier.align(Alignment.TopStart), color = AppColors.LiveBadge)
                RecStatus.SCHEDULED -> Tag("Scheduled", Modifier.align(Alignment.TopStart))
                RecStatus.FAILED -> Tag("Failed", Modifier.align(Alignment.TopStart), color = Color(0xCC5F2120))
                else -> Unit
            }
        }
        resumeAt?.let { ProgressLine(it.progress, Modifier.align(Alignment.BottomCenter)) }
    }
}

@Composable
fun TeamTile(vm: AppViewModel, team: FavoriteTeam, width: Dp? = 120.dp) {
    MediaTile(
        title = team.name,
        subtitle = if (team.record) "Recording all games" else Leagues.byKey(team.leagueKey)?.label,
        onClick = { vm.openTeam(team.leagueKey, team.id) },
        width = width,
        aspect = 1f,
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF242424)), contentAlignment = Alignment.Center) {
            TeamLogo(team.logo, team.abbreviation, 64.dp)
        }
        if (team.record) Box(Modifier.fillMaxSize().padding(6.dp)) { Tag("REC", Modifier.align(Alignment.TopEnd), color = AppColors.LiveBadge) }
    }
}

@Composable
fun PresetTile(vm: AppViewModel, preset: MultiviewPreset) {
    MediaTile(title = preset.title, subtitle = preset.subtitle, onClick = { vm.startMultiview(preset.channels) }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val tiles: List<@Composable () -> Unit> = if (preset.games.isNotEmpty()) {
                preset.games.take(4).map { g -> @Composable { GameArt(g, logoFraction = 0.55f, showScore = false) } }
            } else {
                preset.channels.take(4).map { c ->
                    @Composable {
                        Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2A)), contentAlignment = Alignment.Center) {
                            ChannelLogo(c, 30.dp, background = Color.Transparent)
                        }
                    }
                }
            }
            tiles.chunked(2).forEach { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { tile -> Box(Modifier.weight(1f).fillMaxSize()) { tile() } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Box(Modifier.size(40.dp).align(Alignment.Center).background(Color(0xCC000000), CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(Icons.Multiview, null, size = 22.dp)
        }
        Box(Modifier.fillMaxSize().padding(6.dp)) { LiveBadge(Modifier.align(Alignment.BottomStart), small = true) }
    }
}

// ---------------------------------------------------------------------------------------------
// List rows
// ---------------------------------------------------------------------------------------------

/** A channel in a list: logo, what's on, and how far through it is. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelListRow(
    vm: AppViewModel,
    channel: Channel,
    list: List<Channel>,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
    selected: Boolean = false,
    onClick: () -> Unit = { vm.playChannel(channel, list) },
) {
    LaunchedEffect(channel.id) { vm.requestEpg(channel) }
    val program: Program? = vm.nowPlaying(channel.id, now)
    val next = vm.nextProgram(channel.id, now)
    Row(
        modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFF2A2A2A) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = { channelMenu(vm, channel, list) })
            .padding(horizontal = GUTTER, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(channel, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cleanChannelName(channel.name), fontSize = 12.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (vm.isFavoriteChannel(channel.id)) {
                    Spacer(Modifier.width(4.dp))
                    AppIcon(Icons.Star, null, tint = AppColors.Warn, size = 12.dp)
                }
            }
            Text(program?.title ?: "No guide information", fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (program == null) AppColors.TextDim else AppColors.Text)
            if (program != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressLine(program.progress(now), Modifier.width(64.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        listOfNotNull(minutesLeft(program.endMillis, now), next?.let { "Next: ${it.title}" }).joinToString(" · "),
                        fontSize = 11.sp, color = AppColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A settings-style row: icon, title, subtitle, and a value or a switch. */
@Composable
fun SettingItem(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    checked: Boolean? = null,
    chevron: Boolean = false,
) {
    ListItem(
        headlineContent = { Text(title, fontSize = 15.sp) },
        supportingContent = subtitle?.let { { Text(it, fontSize = 12.sp, color = AppColors.TextDim, lineHeight = 16.sp) } },
        leadingContent = icon?.let { { AppIcon(it, null, tint = AppColors.TextDim) } },
        trailingContent = when {
            checked != null -> ({ Switch(checked = checked, onCheckedChange = { onClick() }) })
            value != null -> ({ Text(value, fontSize = 13.sp, color = AppColors.TextDim, maxLines = 1) })
            chevron -> ({ AppIcon(Icons.ChevronRight, null, tint = AppColors.TextDim) })
            else -> null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.combinedClickableCompat(onClick),
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

/** Shown at the top of Sports and Live until a provider is connected. */
@Composable
fun ProviderBanner(vm: AppViewModel) {
    Column(
        Modifier
            .padding(GUTTER)
            .fillMaxWidth()
            .background(AppColors.Raised, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text("Connect your TV provider to watch", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            when (val s = vm.iptv) {
                is IptvStatus.Failed -> s.message
                IptvStatus.Loading -> "Connecting to your provider…"
                else -> "Add your IPTV login to watch games, browse the guide, record, and use Multiview."
            },
            fontSize = 13.sp,
            color = AppColors.TextDim,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.iptv is IptvStatus.Failed) androidx.compose.material3.OutlinedButton(onClick = { vm.reloadChannels() }) { Text("Retry") }
            androidx.compose.material3.Button(onClick = { vm.navigate(Screen.Provider()) }) { Text("Set up provider") }
        }
    }
}

/** A centered message with optional buttons. */
@Composable
fun MessageBlock(title: String, message: String? = null, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Text(message, fontSize = 14.sp, color = AppColors.TextDim, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
    }
}

/** Phone-sized detail header: wide artwork, then the title, details and actions under it. */
@Composable
fun DetailHero(
    title: String,
    meta: List<String>,
    live: Boolean = false,
    description: String? = null,
    art: @Composable BoxScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            art()
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to AppColors.Background)))
        }
        Column(Modifier.padding(horizontal = GUTTER)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 27.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    LiveBadge()
                    Spacer(Modifier.width(8.dp))
                }
                Text(meta.joinToString("  •  "), fontSize = 13.sp, color = AppColors.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(description, fontSize = 14.sp, color = Color(0xFFCCCCCC), lineHeight = 19.sp)
            }
            Spacer(Modifier.height(14.dp))
            actions()
        }
    }
}
