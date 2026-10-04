package com.gameday.tv.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.gameday.tv.data.MetaPreview
import com.gameday.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * Search for On Demand titles only: the keyboard on the left with title suggestions that update as
 * you type, results on the right, and popular titles before anything is typed.
 */
@Composable
fun OnDemandSearchScreen(vm: AppViewModel) {
    val screenKey = Screen.OnDemandSearch.key
    val addons = vm.addons
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MetaPreview>?>(null) }
    var resultsFor by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var submitted by remember { mutableIntStateOf(0) }
    val firstKey = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val nav = rememberRowNav(listState)
    InitialFocus(vm, screenKey, firstKey)

    val q = query.trim()
    LaunchedEffect(q) {
        if (q.length < 2) {
            results = null
            resultsFor = ""
            searching = false
            return@LaunchedEffect
        }
        searching = true
        // Short pause so each letter doesn't start a search; earlier results stay up meanwhile.
        delay(280)
        // A newer letter cancels this search; only failures of this one count as "nothing found".
        val found = try {
            addons.search(q)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        results = rankResults(found, q)
        resultsFor = q
        searching = false
    }
    LaunchedEffect(submitted, searching, results) {
        if (submitted == 0 || searching || results == null) return@LaunchedEffect
        if (results?.isNotEmpty() == true) resultsFocus.requestFocusSafely(60)
        submitted = 0
    }

    Row(Modifier.fillMaxSize().padding(top = 32.dp)) {
        Column(
            Modifier.width(340.dp).fillMaxHeight().padding(start = 48.dp, end = 10.dp).verticalScroll(rememberScrollState()),
        ) {
            Text("Search On Demand", fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            Row(
                Modifier.fillMaxWidth().height(46.dp).background(Color(0x1FFFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Search, null, Modifier.size(22.dp), tint = AppColors.TextDim)
                Spacer(Modifier.width(10.dp))
                Text(
                    query.ifEmpty { "Movies and shows" },
                    fontSize = 18.sp,
                    color = if (query.isEmpty()) AppColors.TextDim else AppColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (searching) Spinner(18.dp)
            }
            Spacer(Modifier.height(14.dp))
            OnScreenKeyboard(
                onKey = { if (query.length < 40) query += it },
                onBackspace = { query = query.dropLast(1) },
                onClear = { query = "" },
                firstKey = firstKey,
                onEnter = {
                    if (q.length < 2) vm.showMessage("Type at least 2 letters")
                    else {
                        vm.addRecentOnDemandSearch(q)
                        submitted++
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
            val list = results
            when {
                q.length >= 2 && !list.isNullOrEmpty() -> {
                    // Suggestions as you type: open a title straight from here.
                    Text("Suggestions", fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                    list.distinctBy { it.name.lowercase() }.take(6).forEach { m ->
                        SettingRow(
                            m.name, { vm.addRecentOnDemandSearch(q); vm.navigate(Screen.AddonDetail(m.type, m.id)) },
                            value = m.releaseInfo,
                            icon = if (m.type == "movie") Icons.Movie else Icons.Tv,
                        )
                    }
                }
                q.isEmpty() && vm.recentOnDemandSearches.isNotEmpty() -> {
                    Text("Recent searches", fontSize = 13.sp, color = AppColors.TextDim, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                    vm.recentOnDemandSearches.take(5).forEach { s -> SettingRow(s, { query = s }, icon = Icons.Clock) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        Box(Modifier.weight(1f).fillMaxHeight()) {
            val list = results
            when {
                addons.installed.isEmpty() -> EmptyState("No add-ons yet", "Add a catalog add-on like Cinemeta in Settings › Add-ons to search movies and shows.",
                    Modifier.padding(top = 80.dp)) { PillButton("Add-ons settings", { vm.openSettings(SettingsSection.ADDONS) }) }
                q.length < 2 -> Recommendations(vm, screenKey, nav, listState)
                list == null -> LoadingState("Searching…", Modifier.padding(top = 80.dp))
                list.isEmpty() && !searching -> EmptyState("No titles found for \"$resultsFor\"", "Check the spelling, or try another title, actor or year.", Modifier.padding(top = 80.dp))
                // Fresh rows for each search, so they start at the best match instead of keeping an
                // old scroll position as results change while typing.
                else -> key(resultsFor) {
                    val rowsState = rememberLazyListState()
                    val rowsNav = rememberRowNav(rowsState)
                    val movies = list.filter { it.type == "movie" }
                    val shows = list.filter { it.type != "movie" }
                    PivotScroll(offset = ROW_TITLE) {
                    LazyColumn(Modifier.focusRequester(resultsFocus), state = rowsState, contentPadding = PaddingValues(bottom = 200.dp)) {
                        cardRow("top", "Top results", rowsNav) { items(list.take(12), key = { "t:" + it.type + it.id }) { AddonCard(vm, it, screenKey, keyPrefix = "t:") } }
                        if (movies.isNotEmpty()) cardRow("movies", "Movies", rowsNav) { items(movies, key = { "m:" + it.id }) { AddonCard(vm, it, screenKey, keyPrefix = "m:") } }
                        if (shows.isNotEmpty()) cardRow("shows", "Shows", rowsNav) { items(shows, key = { "s:" + it.type + it.id }) { AddonCard(vm, it, screenKey, keyPrefix = "s:") } }
                    }
                    }
                }
            }
        }
    }
}

/** Before typing: popular titles from the add-on catalogs, and what's being watched. */
@Composable
private fun Recommendations(vm: AppViewModel, screenKey: String, nav: RowNav, listState: androidx.compose.foundation.lazy.LazyListState) {
    val addons = vm.addons
    val rows = addons.rows.take(4)
    LaunchedEffect(rows.map { it.key }) { rows.forEach { addons.ensureRow(it) } }
    val resume = vm.resume.filter { it.key.startsWith("addon:") }
    PivotScroll(offset = ROW_TITLE) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 200.dp)) {
            item(key = "rec-title") { Text("Recommended", fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp)) }
            if (resume.isNotEmpty()) {
                cardRow("resume", "Continue watching", nav) { items(resume, key = { "r:" + it.key }) { ResumeCard(vm, it, screenKey) } }
            }
            rows.forEach { row ->
                val list = addons.rowItems[row.key]
                if (!list.isNullOrEmpty()) {
                    cardRow(row.key, row.title, nav) { items(list, key = { "c:" + it.type + it.id }) { AddonCard(vm, it, screenKey, keyPrefix = row.key) } }
                }
            }
        }
    }
}

/** Closest names first (exact, starts with, a word starts with, contains); otherwise the add-on's order. */
fun rankResults(list: List<MetaPreview>, query: String): List<MetaPreview> {
    val q = query.trim().lowercase()
    fun score(name: String): Int {
        val n = name.lowercase()
        return when {
            n == q -> 0
            n.startsWith(q) -> 1
            n.split(' ', '-', ':').any { it.startsWith(q) } -> 2
            n.contains(q) -> 3
            else -> 4
        }
    }
    return list.withIndex().sortedWith(compareBy({ score(it.value.name) }, { it.index })).map { it.value }
}
