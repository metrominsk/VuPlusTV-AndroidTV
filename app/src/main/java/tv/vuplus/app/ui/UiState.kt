package tv.vuplus.app.ui

import tv.vuplus.app.data.Bouquet
import tv.vuplus.app.data.Channel
import tv.vuplus.app.data.EpgEvent
import tv.vuplus.app.data.Settings
import tv.vuplus.app.data.Signal

enum class Screen { Home, Player }

/** Что показано поверх видео. */
enum class Overlay { None, Info, Channels, Details, Menu }

data class UiState(
    val settings: Settings = Settings(),
    val showSettings: Boolean = false,
    val bouquets: List<Bouquet> = emptyList(),
    val bouquet: Bouquet? = null,
    val channels: List<Channel> = emptyList(),
    val current: Channel? = null,
    val screen: Screen = Screen.Home,
    val overlay: Overlay = Overlay.None,
    val loading: Boolean = false,
    val message: String = "",
    val error: Boolean = false,
    /** Набираемый с пульта номер канала. */
    val digits: String = "",
    /** Ближайшие передачи текущего канала; null — ещё загружаются. */
    val upcoming: List<EpgEvent>? = null,
    /** Уровень сигнала, если включён в настройках. */
    val signal: Signal? = null,
)
