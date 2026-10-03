package com.gameday.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.gameday.tv.data.Channel
import com.gameday.tv.data.Game
import com.gameday.tv.data.GameState
import com.gameday.tv.ui.theme.AppColors

/** A channel in a list (multiview picker, side panels). */
@Composable
fun ChannelRow(
    channel: Channel,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    playing: Boolean = false,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(60.dp),
        focusedScale = 1.02f,
        shape = RoundedCornerShape(8.dp),
        containerColor = if (playing) Color(0x40FFFFFF) else Color(0x14FFFFFF),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(cleanChannelName(channel.name), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, color = AppColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (badge != null) {
                Spacer(Modifier.width(8.dp))
                Tag(badge, color = Color(0x33FFFFFF))
            }
            if (playing) {
                Spacer(Modifier.width(8.dp))
                LiveDot()
            }
        }
    }
}

/** "LIVE · Q4 2:31", a start time, or "Final". */
@Composable
fun StatusBadge(game: Game, fontSize: TextUnit = 12.sp) {
    when (game.state) {
        GameState.LIVE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "LIVE",
                fontSize = fontSize * 0.85f,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.background(AppColors.LiveBadge, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(game.shortDetail, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        GameState.PRE -> Text(formatStart(game.startMillis), fontSize = fontSize, color = AppColors.TextDim, maxLines = 1)
        GameState.FINAL -> Text(game.shortDetail, fontSize = fontSize, fontWeight = FontWeight.Bold, color = AppColors.TextDim, maxLines = 1)
    }
}
