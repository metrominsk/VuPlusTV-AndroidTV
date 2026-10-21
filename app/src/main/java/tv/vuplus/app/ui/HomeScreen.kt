package tv.vuplus.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.vuplus.app.data.Bouquet
import tv.vuplus.app.data.Channel
import tv.vuplus.app.data.Settings

@Composable
fun HomeScreen(
    ui: UiState,
    onTune: (Channel) -> Unit,
    onBouquet: (Bouquet) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    val nowSec = rememberNowSec()
    val gridState = rememberLazyGridState()
    val requester = remember { FocusRequester() }
    val hasChannels = ui.channels.isNotEmpty()
    // Фокус сразу на последнем канале, чтобы пульт работал с первого нажатия.
    val focusIndex = ui.channels.indexOfFirst { it.ref == ui.current?.ref }.coerceAtLeast(0)

    LaunchedEffect(hasChannels) {
        if (hasChannels) {
            gridState.scrollToItem(focusIndex)
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Vu+ TV", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(20.dp))
            Text(
                text = ui.message,
                color = if (ui.error) Danger else Muted,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TvButton(if (ui.loading) "Загрузка…" else "Обновить", onRefresh)
            Spacer(Modifier.width(12.dp))
            TvButton("Настройки", onSettings)
        }

        if (ui.bouquets.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(ui.bouquets, key = { it.ref }) { bouquet ->
                    TvButton(
                        text = bouquet.name,
                        onClick = { onBouquet(bouquet) },
                        selected = bouquet.ref == ui.bouquet?.ref,
                    )
                }
            }
        } else {
            Spacer(Modifier.height(14.dp))
        }

        if (!hasChannels) {
            Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = when {
                        ui.loading -> "Загружаем список каналов…"
                        ui.error -> "Откройте «Настройки» и проверьте адрес ресивера."
                        else -> "Каналов нет."
                    },
                    color = Muted,
                    fontSize = 20.sp,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(260.dp),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                itemsIndexed(ui.channels, key = { _, channel -> channel.ref }) { index, channel ->
                    ChannelTile(
                        channel = channel,
                        settings = ui.settings,
                        nowSec = nowSec,
                        modifier = if (index == focusIndex) Modifier.focusRequester(requester) else Modifier,
                        onClick = { onTune(channel) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelTile(
    channel: Channel,
    settings: Settings,
    nowSec: Long,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FocusCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(172.dp),
        padding = PaddingValues(14.dp),
    ) {
        val now = channel.now
        Row(verticalAlignment = Alignment.CenterVertically) {
            Picon(channel, settings, Modifier.width(64.dp).height(38.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row {
                    Text(
                        text = channel.number.toString().padStart(2, '0'),
                        color = Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (now != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(now.range(), fontSize = 13.sp, color = Muted)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (now != null) {
            Text(now.title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            ProgressLine(now.progress(nowSec))
        } else {
            Text("Нет данных EPG", fontSize = 14.sp, color = Muted)
            Spacer(Modifier.weight(1f))
        }
        channel.next?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Далее: ${it.title}",
                fontSize = 13.sp,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
