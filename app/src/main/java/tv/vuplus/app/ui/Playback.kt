package tv.vuplus.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.MediaPlayer as AndroidMediaPlayer
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import android.widget.VideoView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import tv.vuplus.app.data.PlayerEngine
import tv.vuplus.app.data.Settings
import tv.vuplus.app.data.StreamInfo

/**
 * Видео выбранным движком. Все три движка сообщают наружу только два факта:
 * идёт ли буферизация и не случилась ли ошибка. [retryTick] увеличивают, чтобы переоткрыть поток.
 */
@Composable
fun VideoSurface(
    settings: Settings,
    ref: String,
    retryTick: Int,
    onBuffering: (Boolean) -> Unit,
    onFailed: (Boolean) -> Unit,
    onInfo: (StreamInfo) -> Unit,
) {
    when (settings.engine) {
        PlayerEngine.Exo -> ExoVideo(settings, ref, retryTick, onBuffering, onFailed, onInfo)
        PlayerEngine.Vlc -> VlcVideo(settings, ref, retryTick, onBuffering, onFailed)
        PlayerEngine.System -> SystemVideo(settings, ref, retryTick, onBuffering, onFailed, onInfo)
    }
}

// ---- Не даём приставке уснуть ------------------------------------------------------------

/**
 * Пока на экране видео: экран не гаснет и не включается скринсейвер, процессор и Wi-Fi не засыпают.
 * Флаг экрана ставится и на окно, и на корневой View, потому что у плеера он сбрасывается
 * при буферизации и смене состояния, из-за чего приставка и уходила в скринсейвер.
 */
@Composable
fun KeepScreenAwake() {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        view.keepScreenOn = true
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val wakeLock = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vuplus:stream")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.getOrNull()

        val wifiLock = runCatching {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "vuplus:stream")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.getOrNull()

        onDispose {
            view.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            runCatching { wakeLock?.release() }
            runCatching { wifiLock?.release() }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

// ---- Названия кодеков для панели информации ---------------------------------------------

private fun videoCodecName(mime: String?): String? = when (mime) {
    null -> null
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_H265 -> "HEVC"
    MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
    else -> mime.substringAfter('/').uppercase()
}

/** Название звукового кодека и, если это Dolby, его вариант. */
private fun audioInfo(mime: String?): Pair<String, String?>? = when (mime) {
    null -> null
    MimeTypes.AUDIO_AC3 -> "AC-3" to "Dolby Digital"
    MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3" to "Dolby Digital Plus"
    MimeTypes.AUDIO_AAC -> "AAC" to null
    MimeTypes.AUDIO_MPEG_L2 -> "MP2" to null
    MimeTypes.AUDIO_MPEG -> "MP3" to null
    MimeTypes.AUDIO_DTS -> "DTS" to null
    else -> mime.substringAfter('/').uppercase() to null
}

// ---- ExoPlayer ------------------------------------------------------------------------------

@OptIn(UnstableApi::class)
private fun createExoPlayer(context: Context, authHeader: String?): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(8_000)
        .setReadTimeoutMs(8_000)
    if (authHeader != null) http.setDefaultRequestProperties(mapOf("Authorization" to authHeader))

    // Потоки ресиверов часто без IDR-кадров и без AUD: эти флаги убирают «зависания» на старте и артефакты.
    val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
    )

    // Буфер больше стандартного: рывки на живом эфире чаще всего от нехватки запаса.
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 8_000,
            /* maxBufferMs = */ 30_000,
            /* bufferForPlaybackMs = */ 2_500,
            /* bufferForPlaybackAfterRebufferMs = */ 5_000,
        )
        .build()

    val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)

    return ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors).setDataSourceFactory(http))
        .setLoadControl(loadControl)
        .build()
}

@OptIn(UnstableApi::class)
@Composable
private fun ExoVideo(
    settings: Settings,
    ref: String,
    retryTick: Int,
    onBuffering: (Boolean) -> Unit,
    onFailed: (Boolean) -> Unit,
    onInfo: (StreamInfo) -> Unit,
) {
    val context = LocalContext.current
    val buffering = rememberUpdatedState(onBuffering)
    val failed = rememberUpdatedState(onFailed)
    val info = rememberUpdatedState(onInfo)
    val player = remember(settings.authHeader) { createExoPlayer(context, settings.authHeader) }

    DisposableEffect(player) {
        // Сведения о потоке берутся из уже идущего воспроизведения, без запросов к ресиверу.
        fun publish() {
            val video = player.videoFormat
            val audio = player.audioFormat
            val audioKind = audioInfo(audio?.sampleMimeType)
            info.value(
                StreamInfo(
                    videoCodec = videoCodecName(video?.sampleMimeType),
                    width = video?.width?.takeIf { it > 0 } ?: 0,
                    height = video?.height?.takeIf { it > 0 } ?: 0,
                    frameRate = video?.frameRate?.takeIf { it > 0f } ?: 0f,
                    audioCodec = audioKind?.first,
                    audioChannels = audio?.channelCount?.takeIf { it > 0 } ?: 0,
                    dolby = audioKind?.second,
                ),
            )
        }

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering.value(state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) {
                    failed.value(false)
                    publish()
                }
            }

            override fun onTracksChanged(tracks: Tracks) = publish()

            override fun onVideoSizeChanged(videoSize: VideoSize) = publish()

            override fun onPlayerError(error: PlaybackException) {
                failed.value(true)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, ref, settings.host, settings.streamPort, retryTick) {
        failed.value(false)
        val item = MediaItem.Builder()
            .setUri(settings.streamUrl(ref))
            .setMimeType(MimeTypes.VIDEO_MP2T)
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    // Не играем в фоне, после возврата заново подключаемся к «живому» эфиру.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.stop() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (player.mediaItemCount > 0) {
            player.prepare()
            player.playWhenReady = true
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                isFocusable = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { it.player = player },
    )
}

// ---- Системный плеер приставки (MediaPlayer через VideoView) -------------------------------

@Composable
private fun SystemVideo(
    settings: Settings,
    ref: String,
    retryTick: Int,
    onBuffering: (Boolean) -> Unit,
    onFailed: (Boolean) -> Unit,
    onInfo: (StreamInfo) -> Unit,
) {
    val buffering = rememberUpdatedState(onBuffering)
    val failed = rememberUpdatedState(onFailed)
    val info = rememberUpdatedState(onInfo)
    val url = settings.streamUrl(ref)
    val headers = settings.authHeader?.let { mapOf("Authorization" to it) }.orEmpty()

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            VideoView(ctx).apply {
                isFocusable = false
                isFocusableInTouchMode = false
                setOnPreparedListener { mp ->
                    buffering.value(false)
                    failed.value(false)
                    info.value(StreamInfo(width = mp.videoWidth, height = mp.videoHeight))
                    start()
                }
                setOnErrorListener { _, _, _ ->
                    failed.value(true)
                    buffering.value(false)
                    true
                }
                setOnInfoListener { _, what, _ ->
                    when (what) {
                        AndroidMediaPlayer.MEDIA_INFO_BUFFERING_START -> buffering.value(true)
                        AndroidMediaPlayer.MEDIA_INFO_BUFFERING_END,
                        AndroidMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> buffering.value(false)
                        else -> Unit
                    }
                    false
                }
            }
        },
        update = { view ->
            val key = "$url#$retryTick"
            if (view.tag != key) {
                view.tag = key
                failed.value(false)
                buffering.value(true)
                view.setVideoURI(Uri.parse(url), headers)
                view.start()
            }
        },
        onRelease = { it.stopPlayback() },
    )
}

// ---- VLC ----------------------------------------------------------------------------------------

private class VlcHolder(context: Context) {
    val libVlc = LibVLC(context, arrayListOf("--network-caching=1500"))
    val player = VlcMediaPlayer(libVlc)
    val layout = VLCVideoLayout(context)
    var url: String? = null

    init {
        player.attachViews(layout, null, false, false)
    }

    fun play(url: String) {
        this.url = url
        val media = Media(libVlc, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        player.setMedia(media)
        media.release()
        player.play()
    }

    fun release() {
        player.setEventListener(null)
        player.stop()
        player.detachViews()
        player.release()
        libVlc.release()
    }
}

@Composable
private fun VlcVideo(
    settings: Settings,
    ref: String,
    retryTick: Int,
    onBuffering: (Boolean) -> Unit,
    onFailed: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val buffering = rememberUpdatedState(onBuffering)
    val failed = rememberUpdatedState(onFailed)
    val holder = remember { VlcHolder(context) }

    DisposableEffect(holder) {
        holder.player.setEventListener { event ->
            when (event.type) {
                VlcMediaPlayer.Event.Buffering -> buffering.value(event.buffering < 100f)
                VlcMediaPlayer.Event.Playing -> {
                    buffering.value(false)
                    failed.value(false)
                }
                VlcMediaPlayer.Event.EncounteredError -> {
                    failed.value(true)
                    buffering.value(false)
                }
                else -> Unit
            }
        }
        onDispose { holder.release() }
    }

    LaunchedEffect(holder, ref, settings.host, settings.streamPort, settings.user, settings.pass, retryTick) {
        failed.value(false)
        buffering.value(true)
        holder.play(settings.streamUrlWithCredentials(ref))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { holder.player.stop() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { holder.url?.let { holder.play(it) } }

    AndroidView(modifier = Modifier.fillMaxSize(), factory = { holder.layout })
}
