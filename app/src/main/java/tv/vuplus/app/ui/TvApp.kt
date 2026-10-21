package tv.vuplus.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun TvApp(vm: AppViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val actions = remember(vm) {
        PlayerActions(
            zap = vm::zap,
            digit = vm::typeDigit,
            info = vm::showInfo,
            channels = vm::showChannels,
            details = vm::showDetails,
            menu = vm::showMenu,
            settings = vm::openSettings,
            cycleEngine = vm::cycleEngine,
            closeOverlay = vm::closeOverlay,
            tune = vm::tune,
        )
    }

    // На главном экране Back закрывает приложение (обработчик выключен), в остальных случаях ведёт назад.
    BackHandler(enabled = if (ui.showSettings) ui.settings.configured else ui.screen == Screen.Player) {
        vm.onBack()
    }

    val current = ui.current
    when {
        ui.showSettings -> SettingsScreen(
            initial = ui.settings,
            canCancel = ui.settings.configured,
            onSave = vm::saveSettings,
            onCancel = vm::onBack,
        )
        ui.screen == Screen.Player && current != null -> PlayerScreen(ui, current, actions)
        else -> HomeScreen(
            ui = ui,
            onTune = vm::tune,
            onBouquet = vm::selectBouquet,
            onRefresh = { vm.connect(resume = false) },
            onSettings = vm::openSettings,
        )
    }
}
