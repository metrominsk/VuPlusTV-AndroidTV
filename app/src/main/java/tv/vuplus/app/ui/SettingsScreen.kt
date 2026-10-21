package tv.vuplus.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.vuplus.app.data.PlayerEngine
import tv.vuplus.app.data.Settings

@Composable
fun SettingsScreen(
    initial: Settings,
    canCancel: Boolean,
    onSave: (Settings) -> Unit,
    onCancel: () -> Unit,
) {
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var streamPort by remember { mutableStateOf(initial.streamPort.toString()) }
    var user by remember { mutableStateOf(initial.user) }
    var pass by remember { mutableStateOf(initial.pass) }
    var engine by remember { mutableStateOf(initial.engine) }
    var showSignal by remember { mutableStateOf(initial.showSignal) }

    // Допускаем вставку адреса вместе с http:// и путём.
    val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
    val valid = cleanHost.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 64.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Подключение к Vu+", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            "Включите WebInterface (OpenWebif) на ресивере. Телевизор и ресивер должны быть в одной сети.",
            color = Muted,
            fontSize = 16.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Field(host, { host = it }, "IP-адрес или имя ресивера", Modifier.weight(2f))
            Field(port, { port = it.filter(Char::isDigit).take(5) }, "HTTP-порт (обычно 80)", Modifier.weight(1f), KeyboardType.Number)
            Field(streamPort, { streamPort = it.filter(Char::isDigit).take(5) }, "Порт потока (обычно 8001)", Modifier.weight(1f), KeyboardType.Number)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Field(user, { user = it }, "Логин, если нужен", Modifier.weight(1f))
            Field(pass, { pass = it }, "Пароль, если нужен", Modifier.weight(1f), KeyboardType.Password, password = true)
        }
        Text("Плеер", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerEngine.entries.forEach { option ->
                TvButton(text = option.title, selected = option == engine, onClick = { engine = option })
            }
        }
        Text(engine.hint, color = Muted, fontSize = 15.sp)
        Text("Уровень сигнала (SNR, AGC, BER)", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = if (showSignal) "Показывать: вкл" else "Показывать: выкл",
                selected = showSignal,
                onClick = { showSignal = !showSignal },
            )
        }
        Text(
            "Данные берутся с тюнера ресивера, поэтому ресивер при переключении канала в приложении " +
                "переключается на тот же канал. Включайте, только если смотрите ресивер через это приложение.",
            color = Muted,
            fontSize = 15.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = "Сохранить",
                enabled = valid,
                onClick = {
                    onSave(
                        Settings(
                            host = cleanHost,
                            port = port.toIntOrNull()?.coerceIn(1, 65535) ?: 80,
                            streamPort = streamPort.toIntOrNull()?.coerceIn(1, 65535) ?: 8001,
                            user = user.trim(),
                            pass = pass,
                            engine = engine,
                            showSignal = showSignal,
                        ),
                    )
                },
            )
            if (canCancel) TvButton("Отмена", onCancel)
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    labelText: String,
    modifier: Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        label = { Text(labelText) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
    )
}
