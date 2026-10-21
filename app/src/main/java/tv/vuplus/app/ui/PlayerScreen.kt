package tv.vuplus.app.ui

import android.view.KeyEvent as AKey
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import tv.vuplus.app.data.Channel
import tv.vuplus.app.data.EpgEvent
import tv.vuplus.app.data.Settings
import tv.vuplus.app.data.Signal
import tv.vuplus.app.data.StreamInfo
import tv.vuplus.app.data.parseServiceRef

/** Сколько раз тихо переоткрыть поток, прежде чем показать ошибку: ресиверу нужно время, чтобы настроить тюнер. */
private const val MAX_AUTO_RETRIES = 3

class PlayerActions(
    val zap: (Int) -> Unit,
    val digit: (Int) -> Unit,
    val info: () -> Unit,
    val channels: () -> Unit,
    val details: () -> Unit,
    val menu: () -> Unit,
    val settings: () -> Unit,
    val cycleEngine: () -> Unit,
    val closeOverlay: () -> Unit,
    val tune: (Channel) -> Unit,
)

@Composable
fun PlayerScreen(ui: UiState, channel: Channel, actions: PlayerActions) {
    val nowSec = rememberNowSec()
    val settings = ui.settings
    val focus = remember { FocusRequester() }
    var buffering by remember { mutableStateOf(false) }
    var rawFailed by remember { mutableStateOf(false) }
    var retryTick by remember { mutableIntStateOf(0) }
    var attempts by remember(channel.ref) { mutableIntStateOf(0) }
    var streamInfo by remember(channel.ref) { mutableStateOf(StreamInfo()) }

    // Ошибка показывается только после нескольких тихих повторов.
    val failed = rawFailed && attempts >= MAX_AUTO_RETRIES
    val retrying = rawFailed && attempts < MAX_AUTO_RETRIES

    // Пока идёт эфир, приставка не должна засыпать или уходить в скринсейвер.
    KeepScreenAwake()

    LaunchedEffect(rawFailed, attempts, channel.ref) {
        if (rawFailed && attempts < MAX_AUTO_RETRIES) {
            delay(1_000L * (attempts + 1))
            attempts += 1
            retryTick += 1
        }
    }
    // Если поток держится стабильно, счётчик повторов обнуляется.
    LaunchedEffect(rawFailed, buffering, channel.ref) {
        if (!rawFailed && !buffering) {
            delay(5_000)
            attempts = 0
        }
    }

    // Список каналов забирает фокус себе, во всех остальных случаях фокус у корневого блока.
    LaunchedEffect(ui.overlay) {
        if (ui.overlay != Overlay.Channels && ui.overlay != Overlay.Menu) runCatching { focus.requestFocus() }
    }

    fun handleKey(code: Int): Boolean {
        when (ui.overlay) {
            Overlay.Channels -> {
                if (code == AKey.KEYCODE_DPAD_RIGHT) {
                    actions.closeOverlay()
                    return true
                }
                return false
            }
            Overlay.Menu -> return false
            Overlay.Details -> {
                val closes = code == AKey.KEYCODE_DPAD_CENTER || code == AKey.KEYCODE_ENTER ||
                    code == AKey.KEYCODE_NUMPAD_ENTER || code == AKey.KEYCODE_DPAD_LEFT ||
                    code == AKey.KEYCODE_DPAD_RIGHT || code == AKey.KEYCODE_INFO
                if (closes) {
                    actions.closeOverlay()
                    return true
                }
            }
            else -> Unit
        }
        return when (code) {
            AKey.KEYCODE_DPAD_UP, AKey.KEYCODE_MEDIA_PREVIOUS, AKey.KEYCODE_CHANNEL_DOWN -> {
                actions.zap(-1); true
            }
            AKey.KEYCODE_DPAD_DOWN, AKey.KEYCODE_MEDIA_NEXT, AKey.KEYCODE_CHANNEL_UP -> {
                actions.zap(1); true
            }
            AKey.KEYCODE_DPAD_LEFT, AKey.KEYCODE_GUIDE -> {
                actions.channels(); true
            }
            AKey.KEYCODE_DPAD_RIGHT, AKey.KEYCODE_INFO -> {
                actions.details(); true
            }
            AKey.KEYCODE_DPAD_CENTER, AKey.KEYCODE_ENTER, AKey.KEYCODE_NUMPAD_ENTER -> {
                if (failed) {
                    attempts = 0
                    retryTick += 1
                } else {
                    actions.info()
                }
                true
            }
            AKey.KEYCODE_MENU -> {
                actions.menu(); true
            }
            in AKey.KEYCODE_0..AKey.KEYCODE_9 -> {
                actions.digit(code - AKey.KEYCODE_0); true
            }
            in AKey.KEYCODE_NUMPAD_0..AKey.KEYCODE_NUMPAD_9 -> {
                actions.digit(code - AKey.KEYCODE_NUMPAD_0); true
            }
            else -> false
        }
    }

    /** Удержание OK открывает меню: на пультах Xiaomi и подобных нет кнопки Menu. */
    fun handleLongPress(code: Int): Boolean {
        val confirm = code == AKey.KEYCODE_DPAD_CENTER || code == AKey.KEYCODE_ENTER ||
            code == AKey.KEYCODE_NUMPAD_ENTER
        if (confirm && (ui.overlay == Overlay.None || ui.overlay == Overlay.Info)) {
            actions.menu()
            return true
        }
        return false
    }

    val status = when {
        failed -> "нет потока"
        retrying -> "подключение"
        buffering -> "буферизация"
        else -> "в эфире"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) {
                    false
                } else {
                    val native = ev.nativeKeyEvent
                    when (native.repeatCount) {
                        0 -> handleKey(native.keyCode)
                        1 -> handleLongPress(native.keyCode)
                        else -> false
                    }
                }
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        VideoSurface(
            settings = settings,
            ref = channel.ref,
            retryTick = retryTick,
            onBuffering = { buffering = it },
            onFailed = { rawFailed = it },
            onInfo = { streamInfo = it },
        )

        if ((buffering || retrying) && !failed) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
        }

        if (failed) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.5f)
                    .background(Panel, RoundedCornerShape(18.dp))
                    .padding(26.dp),
            ) {
                Text("Не удалось открыть поток", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Плеер: ${settings.engine.title}. Попробуйте другой плеер в настройках " +
                        "или проверьте порт потока (обычно 8001).",
                    color = Muted,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "OK: повторить      Удерживайте OK: меню",
                    color = Accent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (ui.digits.isNotEmpty()) {
            Text(
                text = ui.digits,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(40.dp)
                    .background(Panel, RoundedCornerShape(14.dp))
                    .padding(horizontal = 26.dp, vertical = 10.dp),
            )
        }

        AnimatedVisibility(
            visible = ui.overlay == Overlay.Info,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            InfoBanner(
                channel = channel,
                settings = settings,
                bouquetName = ui.bouquet?.name,
                nowSec = nowSec,
                status = status,
                info = streamInfo,
                signal = ui.signal,
                modifier = Modifier,
            )
        }

        AnimatedVisibility(
            visible = ui.overlay == Overlay.Channels,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            ChannelPanel(ui.channels, channel, settings, nowSec, actions.tune)
        }

        AnimatedVisibility(
            visible = ui.overlay == Overlay.Menu,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            PlayerMenu(settings, actions)
        }

        AnimatedVisibility(
            visible = ui.overlay == Overlay.Details,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            DetailsPanel(channel, ui.upcoming, nowSec)
        }
    }
}

private fun fpsText(fps: Float): String =
    if (fps % 1f < 0.05f || fps % 1f > 0.95f) fps.toInt().toString() else "%.2f".format(fps)

private val clockFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, d MMMM yyyy", Locale.forLanguageTag("ru")).withZone(ZoneId.systemDefault())

/** Широкая нижняя панель в стиле инфобара ресивера. */
@Composable
private fun InfoBanner(
    channel: Channel,
    settings: Settings,
    bouquetName: String?,
    nowSec: Long,
    status: String,
    info: StreamInfo,
    signal: Signal?,
    modifier: Modifier,
) {
    val now = channel.now
    val refInfo = remember(channel.ref) { parseServiceRef(channel.ref) }
    val clock by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis() / 1000
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000))))
            .padding(horizontal = 48.dp)
            .padding(top = 44.dp, bottom = 24.dp),
    ) {
        // Номер, название, дата и время
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("${channel.number}", fontSize = 38.sp, fontWeight = FontWeight.Bold)
                refInfo?.source?.let {
                    Text(it, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                channel.name,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(2f),
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(dateFormatter.format(Instant.ofEpochSecond(clock)), color = Muted, fontSize = 14.sp)
                Text(
                    clockFormatter.format(Instant.ofEpochSecond(clock)),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                bouquetName?.let {
                    Text(it, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Accent.copy(alpha = 0.45f)))
        Spacer(Modifier.height(12.dp))

        // Пикон, текущая и следующая передачи
        Row(verticalAlignment = Alignment.CenterVertically) {
            Picon(channel, settings, Modifier.width(92.dp).height(56.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                if (now != null) {
                    val left = ((now.endSec - nowSec) / 60).coerceAtLeast(0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${now.range()}  ${now.title}",
                            fontSize = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("+$left мин", color = Accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(now.progress(nowSec))
                    Spacer(Modifier.height(6.dp))
                } else {
                    Text("Нет данных EPG", color = Muted, fontSize = 18.sp)
                }
                channel.next?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${it.range()}  ${it.title}",
                            color = Muted,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("${it.durationSec / 60} мин", color = Muted, fontSize = 16.sp)
                    }
                }
            }
        }
        if (now != null && now.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                now.description,
                color = Muted,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Accent.copy(alpha = 0.45f)))
        Spacer(Modifier.height(8.dp))

        // Идентификаторы канала (из референса, без запросов)
        refInfo?.let {
            Text(
                it.ids,
                color = InfoGreen,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
        }

        // Поток: жёлтым статус и видео, зелёным источник и звук
        Row(verticalAlignment = Alignment.CenterVertically) {
            val video = buildList {
                add("Поток: $status")
                info.videoCodec?.let { add(it) }
                if (info.width > 0 && info.height > 0) add("${info.width}×${info.height}")
                if (info.frameRate > 0f) add("${fpsText(info.frameRate)} кадр/с")
            }
            Text(
                video.joinToString("   "),
                color = InfoYellow,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.3f),
            )
            Text(
                refInfo?.source.orEmpty(),
                color = InfoGreen,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.2f),
            )
            val audio = buildList {
                info.audioCodec?.let { add(it) }
                info.channelsLabel?.let { add(it) }
                info.dolby?.let { add(it) }
            }
            Text(
                audio.joinToString("   "),
                color = InfoGreen,
                fontSize = 15.sp,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.3f),
            )
        }

        // Уровень сигнала (только если включён в настройках и ресивер ответил)
        if (signal != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                signal.snr?.let { SignalMeter("SNR", it, signal.snrDb?.let { db -> "  $db" }.orEmpty()) }
                signal.agc?.let { SignalMeter("AGC", it, "") }
                signal.ber?.let {
                    Text("BER $it", color = if (it > 0) Danger else InfoGreen, fontSize = 15.sp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "← каналы   → описание   ↑ ↓ переключить   удерживайте OK: меню",
            color = Muted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun PlayerMenu(settings: Settings, actions: PlayerActions) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    Column(
        Modifier
            .fillMaxWidth(0.42f)
            .background(Panel, RoundedCornerShape(20.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Меню", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        MenuItem("Плеер: ${settings.engine.title}   (OK: сменить)", Modifier.focusRequester(requester), actions.cycleEngine)
        MenuItem("Список каналов", Modifier, actions.channels)
        MenuItem("Описание передачи", Modifier, actions.details)
        MenuItem("Настройки подключения", Modifier, actions.settings)
        MenuItem("Закрыть", Modifier, actions.closeOverlay)
    }
}

@Composable
private fun MenuItem(text: String, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(text, fontSize = 18.sp)
    }
}

@Composable
private fun SignalMeter(label: String, percent: Int, extra: String) {
    val color = when {
        percent >= 60 -> InfoGreen
        percent >= 30 -> InfoYellow
        else -> Danger
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .width(150.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Line),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                    .background(color),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("$percent %$extra", fontSize = 15.sp)
    }
}

@Composable
private fun ChannelPanel(
    channels: List<Channel>,
    current: Channel,
    settings: Settings,
    nowSec: Long,
    onTune: (Channel) -> Unit,
) {
    val currentIndex = channels.indexOfFirst { it.ref == current.ref }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    val requester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.44f)
            .background(Panel)
            .padding(horizontal = 18.dp, vertical = 24.dp),
    ) {
        Text("Каналы", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(channels, key = { _, channel -> channel.ref }) { index, channel ->
                FocusCard(
                    onClick = { onTune(channel) },
                    modifier = (if (index == currentIndex) Modifier.focusRequester(requester) else Modifier)
                        .fillMaxWidth(),
                    selected = index == currentIndex,
                    padding = PaddingValues(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Picon(channel, settings, Modifier.width(56.dp).height(34.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${channel.number}. ${channel.name}",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            channel.now?.let {
                                Text(
                                    "${it.range()}  ${it.title}",
                                    fontSize = 14.sp,
                                    color = Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(6.dp))
                                ProgressLine(it.progress(nowSec))
                            }
                            channel.next?.let {
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    "Далее ${it.range()}  ${it.title}",
                                    fontSize = 12.sp,
                                    color = Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsPanel(channel: Channel, upcoming: List<EpgEvent>?, nowSec: Long) {
    val now = channel.now
    Column(
        Modifier
            .fillMaxWidth(0.72f)
            .background(Panel, RoundedCornerShape(18.dp))
            .padding(28.dp),
    ) {
        Text(channel.name, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (now != null) {
            Spacer(Modifier.height(4.dp))
            Text("${now.range()}  ${now.title}", color = Accent, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                now.description.ifBlank { "Подробного описания в EPG нет." },
                color = Muted,
                fontSize = 16.sp,
                maxLines = 9,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text("Для этого канала нет данных EPG.", color = Muted, fontSize = 16.sp)
        }

        val after = now?.endSec ?: nowSec
        val later = upcoming.orEmpty().filter { it.startSec >= after }.take(3)
        Spacer(Modifier.height(16.dp))
        when {
            upcoming == null -> Text("Загружаем программу…", color = Muted, fontSize = 15.sp)
            later.isNotEmpty() -> {
                Text("Далее в эфире", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                later.forEach {
                    Text(
                        "${it.range()}  ${it.title}",
                        color = Muted,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("OK, ← или →: закрыть", color = Muted, fontSize = 13.sp)
    }
}
