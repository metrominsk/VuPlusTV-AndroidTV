package tv.vuplus.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.xml.sax.SAXException
import tv.vuplus.app.data.Bouquet
import tv.vuplus.app.data.Channel
import tv.vuplus.app.data.Enigma2Api
import tv.vuplus.app.data.PlayerEngine
import tv.vuplus.app.data.Settings
import tv.vuplus.app.data.SettingsStore
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var api: Enigma2Api? = null
    private var connectJob: Job? = null
    private var epgJob: Job? = null
    private var infoJob: Job? = null
    private var digitsJob: Job? = null
    private var detailsJob: Job? = null
    private var signalJob: Job? = null
    private var zapJob: Job? = null

    init {
        val settings = store.load()
        _ui.update { it.copy(settings = settings, showSettings = !settings.configured) }
        if (settings.configured) connect(resume = true)
    }

    // ---- Подключение и загрузка ---------------------------------------------------------

    fun connect(resume: Boolean) {
        val settings = _ui.value.settings
        if (!settings.configured) {
            _ui.update { it.copy(showSettings = true) }
            return
        }
        val client = Enigma2Api(settings)
        api = client
        epgJob?.cancel()
        _ui.update { it.copy(message = "Подключаемся к ${settings.host}…", error = false) }
        launchLoading {
            val bouquets = client.bouquets()
            if (bouquets.isEmpty()) throw IOException("Букеты не найдены. Проверьте, что включён WebInterface")
            val bouquet = bouquets.firstOrNull { it.ref == store.lastBouquet } ?: bouquets.first()
            _ui.update { it.copy(bouquets = bouquets) }
            loadBouquet(client, bouquet)
            startEpgLoop(client)
            if (resume) {
                _ui.value.channels.firstOrNull { it.ref == store.lastRef }?.let { tune(it) }
            }
        }
    }

    fun selectBouquet(bouquet: Bouquet) {
        val client = api ?: return
        if (bouquet.ref == _ui.value.bouquet?.ref) return
        store.lastBouquet = bouquet.ref
        epgJob?.cancel()
        launchLoading {
            loadBouquet(client, bouquet)
            startEpgLoop(client)
        }
    }

    private fun launchLoading(block: suspend CoroutineScope.() -> Unit) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(message = e.userMessage(), error = true) }
            } finally {
                if (isActive) _ui.update { it.copy(loading = false) }
            }
        }
    }

    private suspend fun loadBouquet(client: Enigma2Api, bouquet: Bouquet) {
        val list = client.channels(bouquet)
        _ui.update {
            it.copy(
                bouquet = bouquet,
                channels = list,
                error = false,
                message = if (list.isEmpty()) "В букете «${bouquet.name}» нет каналов" else "Каналов: ${list.size}",
            )
        }
        if (list.isNotEmpty()) refreshEpg(client, bouquet)
    }

    /** Два запроса на весь букет вместо запроса на каждый канал. Ошибка EPG не ломает список. */
    private suspend fun refreshEpg(client: Enigma2Api, bouquet: Bouquet) {
        val (now, next) = try {
            coroutineScope {
                val n = async { client.epgNow(bouquet) }
                val x = async { client.epgNext(bouquet) }
                n.await() to x.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        _ui.update { st ->
            if (st.bouquet?.ref != bouquet.ref) {
                st
            } else {
                val channels = st.channels.map { it.copy(now = now[it.ref], next = next[it.ref]) }
                st.copy(
                    channels = channels,
                    current = channels.firstOrNull { it.ref == st.current?.ref } ?: st.current,
                )
            }
        }
    }

    private fun startEpgLoop(client: Enigma2Api) {
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                _ui.value.bouquet?.let { refreshEpg(client, it) }
            }
        }
    }

    // ---- Просмотр -------------------------------------------------------------------------

    fun tune(channel: Channel) {
        store.lastRef = channel.ref
        _ui.update { it.copy(current = channel, screen = Screen.Player, signal = null) }
        if (_ui.value.settings.showSignal) {
            // Чтобы уровень сигнала относился к смотримому каналу, переключаем и сам ресивер.
            val client = api
            zapJob?.cancel()
            zapJob = viewModelScope.launch {
                delay(400)
                try {
                    client?.zap(channel.ref)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Не критично: просто не будет данных о сигнале.
                }
            }
        }
        showInfo()
    }

    fun zap(delta: Int) {
        val st = _ui.value
        val list = st.channels
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.ref == st.current?.ref }
        val target = if (index < 0) {
            if (delta > 0) 0 else list.lastIndex
        } else {
            (index + delta).mod(list.size)
        }
        tune(list[target])
    }

    /** Прямой набор номера: ждём паузу и переключаемся. */
    fun typeDigit(digit: Int) {
        val typed = (_ui.value.digits + digit).takeLast(4)
        _ui.update { it.copy(digits = typed) }
        digitsJob?.cancel()
        digitsJob = viewModelScope.launch {
            delay(1_800)
            val number = typed.toIntOrNull()
            _ui.value.channels.firstOrNull { it.number == number }?.let { tune(it) }
            _ui.update { it.copy(digits = "") }
        }
    }

    fun showInfo() {
        infoJob?.cancel()
        _ui.update { it.copy(overlay = Overlay.Info) }
        infoJob = viewModelScope.launch {
            delay(8_000)
            _ui.update { if (it.overlay == Overlay.Info) it.copy(overlay = Overlay.None) else it }
        }
        pollSignal()
    }

    /** Пока видна панель информации, раз в пару секунд спрашиваем уровень сигнала (один лёгкий запрос). */
    private fun pollSignal() {
        signalJob?.cancel()
        val client = api ?: return
        if (!_ui.value.settings.showSignal) return
        signalJob = viewModelScope.launch {
            delay(1_500)
            while (isActive && _ui.value.overlay == Overlay.Info) {
                val signal = try {
                    client.signal()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                _ui.update { it.copy(signal = signal) }
                delay(2_500)
            }
        }
    }

    fun showChannels() {
        infoJob?.cancel()
        _ui.update { it.copy(overlay = Overlay.Channels) }
    }

    fun showDetails() {
        val channel = _ui.value.current ?: return
        infoJob?.cancel()
        detailsJob?.cancel()
        _ui.update { it.copy(overlay = Overlay.Details, upcoming = null) }
        detailsJob = viewModelScope.launch {
            val events = try {
                api?.epgService(channel.ref).orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _ui.update { it.copy(upcoming = events) }
        }
    }

    /** Меню плеера (долгое нажатие OK): на пультах без кнопки Menu это главный способ добраться до настроек. */
    fun showMenu() {
        infoJob?.cancel()
        _ui.update { it.copy(overlay = Overlay.Menu) }
    }

    /** Следующий плеер по кругу. Применяется сразу, без выхода из просмотра. */
    fun cycleEngine() {
        val settings = _ui.value.settings
        val engines = PlayerEngine.entries
        val updated = settings.copy(engine = engines[(settings.engine.ordinal + 1) % engines.size])
        store.save(updated)
        _ui.update { it.copy(settings = updated) }
    }

    fun closeOverlay() {
        infoJob?.cancel()
        _ui.update { it.copy(overlay = Overlay.None) }
    }

    // ---- Настройки и навигация ------------------------------------------------------------

    fun openSettings() {
        infoJob?.cancel()
        _ui.update { it.copy(showSettings = true, overlay = Overlay.None) }
    }

    fun saveSettings(settings: Settings) {
        val old = _ui.value.settings
        if (settings == old) {
            _ui.update { it.copy(showSettings = false) }
            return
        }
        store.save(settings)
        // Переподключаться к ресиверу нужно, только если изменился адрес или доступ.
        // Смена плеера или порта потока подхватывается на лету.
        val reconnect = settings.host != old.host || settings.port != old.port ||
            settings.user != old.user || settings.pass != old.pass
        if (!reconnect) {
            _ui.update { it.copy(settings = settings, showSettings = false) }
            return
        }
        _ui.update {
            it.copy(
                settings = settings,
                showSettings = false,
                screen = Screen.Home,
                overlay = Overlay.None,
                current = null,
                channels = emptyList(),
                bouquets = emptyList(),
                bouquet = null,
            )
        }
        connect(resume = false)
    }

    fun onBack() {
        val st = _ui.value
        when {
            st.showSettings -> if (st.settings.configured) _ui.update { it.copy(showSettings = false) }
            st.overlay == Overlay.Channels || st.overlay == Overlay.Details || st.overlay == Overlay.Menu -> closeOverlay()
            st.screen == Screen.Player -> {
                infoJob?.cancel()
                _ui.update { it.copy(screen = Screen.Home, overlay = Overlay.None) }
            }
        }
    }

    private fun Throwable.userMessage(): String = when (this) {
        is UnknownHostException -> "Не удаётся найти ресивер. Проверьте адрес"
        is SocketTimeoutException, is ConnectException -> "Ресивер не отвечает. Проверьте IP, порт и сеть"
        is SAXException -> "Ресивер ответил не XML. Проверьте HTTP-порт WebInterface"
        else -> message ?: "Нет связи с ресивером"
    }
}
