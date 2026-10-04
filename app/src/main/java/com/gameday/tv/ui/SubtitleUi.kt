package com.gameday.tv.ui

import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.text.Cue
import androidx.tv.material3.Text
import com.gameday.tv.data.SubtitleStyle
import com.gameday.tv.data.Subtitles

/** Captions for what's playing. */
@Stable
class PlayerSubtitles {
    /** The automatic language choice was made (the viewer's own choice is never overridden). */
    var decided = false
}

/**
 * Draws captions in the viewer's style: the stream's text captions ([lines]) and its picture
 * captions ([bitmaps], e.g. DVB and PGS).
 */
@Composable
fun SubtitleOverlay(lines: List<String>, bitmaps: List<Cue>, style: SubtitleStyle, modifier: Modifier = Modifier, scale: Float = 1f, sidePadding: androidx.compose.ui.unit.Dp = 64.dp) {
    if (lines.isEmpty() && bitmaps.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val bottom = maxHeight * SubtitleStyle.POSITIONS[style.position].second
        if (bitmaps.isNotEmpty()) {
            // Picture captions are positioned by the stream.
            bitmaps.forEach { cue ->
                val bmp = cue.bitmap ?: return@forEach
                val w = if (cue.size != Cue.DIMEN_UNSET) maxWidth * cue.size else maxWidth * 0.6f
                val x = if (cue.position != Cue.DIMEN_UNSET) maxWidth * cue.position else (maxWidth - w) / 2
                val y = if (cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_FRACTION) maxHeight * cue.line else maxHeight * 0.8f
                Image(
                    bmp.asImageBitmap(), null,
                    Modifier.padding(start = x.coerceAtLeast(0.dp), top = y.coerceAtLeast(0.dp)).width(w),
                )
            }
        }
        if (lines.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = sidePadding, end = sidePadding, bottom = bottom),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                lines.forEach { SubtitleLine(it, style, scale) }
            }
        }
    }
}

@Composable
private fun SubtitleLine(text: String, style: SubtitleStyle, scale: Float) {
    val size = (SubtitleStyle.SIZES[style.size].second * scale).sp
    val color = Color(SubtitleStyle.COLORS[style.color].second)
    val box = Color(SubtitleStyle.BACKGROUNDS[style.background].second.toInt())
    val family = remember(style.font) { subtitleFont(style.font) }
    val base = TextStyle(
        fontSize = size,
        lineHeight = size * 1.2f,
        fontFamily = family,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    val edge = SubtitleStyle.EDGES[style.edge]
    Box(
        Modifier
            .then(if (box.alpha > 0f) Modifier.background(box, RoundedCornerShape(4.dp)) else Modifier)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (edge) {
            "Outline" -> {
                // A black stroke under the fill keeps text readable on any picture.
                Text(text, style = base.copy(color = Color.Black, drawStyle = Stroke(width = size.value * 0.18f * 1.6f)))
                Text(text, style = base.copy(color = color))
            }
            "Drop shadow" -> Text(text, style = base.copy(color = color, shadow = Shadow(Color(0xE6000000), Offset(2.5f, 2.5f), 5f)))
            else -> Text(text, style = base.copy(color = color))
        }
    }
}

private fun subtitleFont(i: Int): FontFamily = when (SubtitleStyle.FONTS[i]) {
    "Condensed" -> FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
    "Serif" -> FontFamily.Serif
    "Monospace" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

/** Text of the stream's own captions (picture captions are drawn separately). */
fun textOfCues(cues: List<Cue>): List<String> = cues.mapNotNull { c -> c.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } }

/** A sample of the current style over a picture-like background. */
@Composable
fun SubtitlePreview(style: SubtitleStyle, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(Brush.linearGradient(listOf(Color(0xFF5D7A99), Color(0xFFB9A27E), Color(0xFF2F3B2B))), RoundedCornerShape(8.dp)),
    ) {
        SubtitleOverlay(listOf("This is how subtitles will look.", "Change the size, color and more below."), emptyList(), style, scale = 0.75f, sidePadding = 12.dp)
    }
}

private fun <T> cycle(list: List<T>, i: Int) = (i + 1) % list.size

/** Rows for every style option, for Settings › Playback and the player's subtitle panel. */
fun LazyListScope.subtitleStyleItems(vm: AppViewModel, prefix: String) {
    item(key = "$prefix-lang") {
        val langs = Subtitles.LANGUAGES.let { if (vm.subtitleLanguage in it) it else listOf(vm.subtitleLanguage) + it }
        SettingRow(
            "Language", { vm.updateSubtitleLanguage(langs[cycle(langs, langs.indexOf(vm.subtitleLanguage))]) },
            subtitle = "Picked automatically from the stream when captions are on",
            value = Subtitles.languageName(vm.subtitleLanguage),
        )
    }
    item(key = "$prefix-size") {
        val s = vm.subtitleStyle
        SettingRow("Text size", { vm.updateSubtitleStyle(s.copy(size = cycle(SubtitleStyle.SIZES, s.size))) }, value = SubtitleStyle.SIZES[s.size].first)
    }
    item(key = "$prefix-color") {
        val s = vm.subtitleStyle
        SettingRow("Text color", { vm.updateSubtitleStyle(s.copy(color = cycle(SubtitleStyle.COLORS, s.color))) }, value = SubtitleStyle.COLORS[s.color].first)
    }
    item(key = "$prefix-edge") {
        val s = vm.subtitleStyle
        SettingRow("Text edge", { vm.updateSubtitleStyle(s.copy(edge = cycle(SubtitleStyle.EDGES, s.edge))) }, value = SubtitleStyle.EDGES[s.edge])
    }
    item(key = "$prefix-bg") {
        val s = vm.subtitleStyle
        SettingRow("Background", { vm.updateSubtitleStyle(s.copy(background = cycle(SubtitleStyle.BACKGROUNDS, s.background))) },
            value = SubtitleStyle.BACKGROUNDS[s.background].first)
    }
    item(key = "$prefix-font") {
        val s = vm.subtitleStyle
        SettingRow("Font", { vm.updateSubtitleStyle(s.copy(font = cycle(SubtitleStyle.FONTS, s.font))) }, value = SubtitleStyle.FONTS[s.font])
    }
    item(key = "$prefix-bold") {
        val s = vm.subtitleStyle
        SettingRow("Bold", { vm.updateSubtitleStyle(s.copy(bold = !s.bold)) }, checked = s.bold)
    }
    item(key = "$prefix-pos") {
        val s = vm.subtitleStyle
        SettingRow("Position", { vm.updateSubtitleStyle(s.copy(position = cycle(SubtitleStyle.POSITIONS, s.position))) },
            value = SubtitleStyle.POSITIONS[s.position].first)
    }
    item(key = "$prefix-reset") {
        SettingRow("Reset to default", { vm.updateSubtitleStyle(SubtitleStyle()) })
    }
}
