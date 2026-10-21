package tv.vuplus.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Клиент WebInterface Enigma2 (OpenWebif). Маршруты могут отличаться в разных образах,
 * проверено только по документации и семантике API, на реальном ресивере не запускалось.
 */
class Enigma2Api(private val settings: Settings) {

    suspend fun bouquets(): List<Bouquet> =
        Enigma2Parser.services(get("/web/getservices")).map { Bouquet(it.name, it.ref) }

    suspend fun channels(bouquet: Bouquet): List<Channel> =
        Enigma2Parser.services(get("/web/getservices?sRef=${enc(bouquet.ref)}"))
            .filter { Enigma2Parser.isPlayable(it.ref) }
            .distinctBy { it.ref }
            .mapIndexed { i, s -> Channel(number = i + 1, name = s.name, ref = s.ref) }

    /** «Сейчас в эфире» для всех каналов букета одним запросом. */
    suspend fun epgNow(bouquet: Bouquet): Map<String, EpgEvent> =
        epgMap("/web/epgnow?bRef=${enc(bouquet.ref)}")

    /** «Далее» для всех каналов букета одним запросом. */
    suspend fun epgNext(bouquet: Bouquet): Map<String, EpgEvent> =
        epgMap("/web/epgnext?bRef=${enc(bouquet.ref)}")

    /** Ближайшие передачи одного канала (для экрана описания). */
    suspend fun epgService(ref: String): List<EpgEvent> =
        Enigma2Parser.events(get("/web/epgservice?sRef=${enc(ref)}"))
            .map { it.event }
            .sortedBy { it.startSec }

    /** Уровень сигнала главного тюнера ресивера. Относится к тому каналу, на котором сейчас сам ресивер. */
    suspend fun signal(): Signal? = Enigma2Parser.signal(get("/web/signal"))

    /** Переключить сам ресивер на канал (нужно, чтобы уровень сигнала относился к смотримому каналу). */
    suspend fun zap(ref: String) {
        get("/web/zap?sRef=${enc(ref)}")
    }

    private suspend fun epgMap(path: String): Map<String, EpgEvent> =
        Enigma2Parser.events(get(path)).associate { it.serviceRef to it.event }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val conn = URL("http://${settings.host}:${settings.port}$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            settings.authHeader?.let { conn.setRequestProperty("Authorization", it) }
            val code = conn.responseCode
            if (code == 401) throw IOException("Неверный логин или пароль (HTTP 401)")
            if (code !in 200..299) throw IOException("Ресивер ответил HTTP $code")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
