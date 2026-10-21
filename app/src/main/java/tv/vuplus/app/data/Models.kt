package tv.vuplus.app.data

import java.net.URLEncoder
import java.util.Base64

/** Чем воспроизводить поток. Если один плеер рвёт картинку, другой часто справляется. */
enum class PlayerEngine(val title: String, val hint: String) {
    Exo("ExoPlayer", "Рекомендуется для начала. Аппаратное декодирование, настроенный буфер"),
    Vlc("VLC", "Свой декодер: справляется с MP2-звуком, проблемными потоками и рывками"),
    System("Системный", "Штатный плеер приставки (MediaPlayer), иногда лучше всего работает на «железе»"),
}

data class Settings(
    val host: String = "",
    val port: Int = 80,
    val streamPort: Int = 8001,
    val user: String = "",
    val pass: String = "",
    val engine: PlayerEngine = PlayerEngine.Exo,
    /** Показывать SNR/AGC/BER. Требует переключения ресивера на тот же канал, поэтому по умолчанию выключено. */
    val showSignal: Boolean = false,
) {
    val configured: Boolean get() = host.isNotBlank()

    /** Заголовок Basic-авторизации или null, если логин не задан. */
    val authHeader: String?
        get() = if (user.isBlank()) null
        else "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    /** Адрес MPEG-TS потока. У Enigma2 он отдаётся на отдельном порту (обычно 8001). */
    fun streamUrl(ref: String): String =
        "http://$host:$streamPort/${ref.trimStart('/').replace(" ", "%20")}"

    /** Для VLC логин и пароль передаются прямо в адресе. */
    fun streamUrlWithCredentials(ref: String): String {
        if (user.isBlank()) return streamUrl(ref)
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        return "http://${enc(user)}:${enc(pass)}@$host:$streamPort/${ref.trimStart('/').replace(" ", "%20")}"
    }
}

data class Bouquet(val name: String, val ref: String)

data class EpgEvent(
    val title: String,
    val startSec: Long,
    val durationSec: Long,
    val description: String = "",
) {
    val endSec: Long get() = startSec + durationSec

    fun progress(nowSec: Long): Float =
        if (durationSec <= 0) 0f
        else ((nowSec - startSec).toFloat() / durationSec).coerceIn(0f, 1f)
}

data class Channel(
    val number: Int,
    val name: String,
    val ref: String,
    val now: EpgEvent? = null,
    val next: EpgEvent? = null,
)

/** Что удалось узнать о потоке самого плеера, без дополнительных запросов к ресиверу. */
data class StreamInfo(
    val videoCodec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val audioCodec: String? = null,
    val audioChannels: Int = 0,
    /** «Dolby Digital» или «Dolby Digital Plus», если звук Dolby. */
    val dolby: String? = null,
) {
    val channelsLabel: String?
        get() = when (audioChannels) {
            0 -> null
            1 -> "моно"
            2 -> "стерео"
            6 -> "5.1"
            8 -> "7.1"
            else -> "$audioChannels кан."
        }
}

/** Уровень сигнала тюнера ресивера (проценты, дБ, ошибки). */
data class Signal(val snr: Int?, val agc: Int?, val ber: Long?, val snrDb: String?)
