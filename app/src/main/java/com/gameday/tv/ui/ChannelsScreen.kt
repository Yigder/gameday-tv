package com.gameday.tv.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.Channel
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

@Composable
fun ChannelsScreen(vm: AppViewModel, initialQuery: String) {
    val iptv = vm.iptv
    if (iptv !is IptvStatus.Ready) {
        when (iptv) {
            IptvStatus.Loading -> LoadingState("Loading your channels…", Modifier.padding(top = 120.dp))
            is IptvStatus.Failed -> EmptyState("Couldn't load channels", iptv.message, Modifier.padding(top = 100.dp)) {
                ActionButton("Retry", { vm.reloadChannels() }, primary = true)
            }
            else -> EmptyState("IPTV not connected", "Sign in to browse your channels.", Modifier.padding(top = 100.dp)) {
                ActionButton("Connect IPTV", { vm.navigate(Screen.Login) }, primary = true)
            }
        }
        return
    }
    val catalog = iptv.catalog

    var sportsOnly by rememberSaveable { mutableStateOf(catalog.sportsGroups.isNotEmpty()) }
    val groups = if (sportsOnly && catalog.sportsGroups.isNotEmpty()) catalog.sportsGroups else catalog.groups
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<Channel>?>(null) }

    LaunchedEffect(query, sportsOnly) {
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(250)
        results = vm.searchChannels(catalog, query, if (sportsOnly) catalog.sportsGroupSet else null)
    }

    val group = selected?.takeIf { it in groups } ?: groups.firstOrNull()
    val searching = results != null
    val shown: List<Channel> = results ?: group?.let { catalog.byGroup[it] }.orEmpty()
    val groupFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Channels", fontSize = 26.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(24.dp))
            TvTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search (e.g. ESPN, Chiefs, NFL)",
                imeAction = ImeAction.Search,
                onSubmit = {},
                modifier = Modifier.width(360.dp),
            )
            Spacer(Modifier.width(14.dp))
            if (catalog.sportsGroups.isNotEmpty()) {
                Chip(if (sportsOnly) "✓ Sports only" else "Sports only", sportsOnly, { sportsOnly = !sportsOnly })
            }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                ActionButton("Clear", { query = "" })
            }
            Spacer(Modifier.weight(1f))
            ActionButton(if (vm.multiviewCount > 0) "⊞ Multiview (${vm.multiviewCount})" else "⊞ Multiview", { vm.openMultiview() })
        }
        Text("Hold OK on a channel to add it to Multiview", fontSize = 12.sp, color = AppColors.TextDim)
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.width(250.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(4.dp, 4.dp, 4.dp, 32.dp),
            ) {
                itemsIndexed(groups, key = { _, g -> g }) { i, g ->
                    val isSelected = !searching && g == group
                    FocusSurface(
                        onClick = { selected = g; query = "" },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (i == 0) Modifier.focusRequester(groupFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused && query.isEmpty()) selected = g },
                        shape = RoundedCornerShape(10.dp),
                        focusedScale = 1.03f,
                        containerColor = if (isSelected) AppColors.CardFocused else AppColors.Card,
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                g,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) AppColors.Accent else AppColors.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${catalog.byGroup[g]?.size ?: 0}", fontSize = 12.sp, color = AppColors.TextDim)
                        }
                    }
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (searching) "Results for “$query” · ${shown.size}" else "${group.orEmpty()} · ${shown.size}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                if (shown.isEmpty()) {
                    EmptyState(if (searching) "No channels found" else "This category is empty")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(6.dp, 6.dp, 6.dp, 32.dp),
                    ) {
                        itemsIndexed(shown, key = { _, c -> c.id }) { i, ch ->
                            ChannelTile(
                                ch,
                                onClick = { vm.play(shown, i, null) },
                                onLongClick = { vm.addToMultiview(ch) },
                                modifier = if (i == 0) Modifier.focusRequester(gridFocus) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (initialQuery.isNotBlank()) {
            delay(400) // let the search results arrive
            if (!gridFocus.requestFocusSafely()) groupFocus.requestFocusSafely()
        } else {
            groupFocus.requestFocusSafely()
        }
    }
}

@Composable
private fun ChannelTile(channel: Channel, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(118.dp),
        focusedScale = 1.07f,
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ChannelLogo(channel, 52.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                channel.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
