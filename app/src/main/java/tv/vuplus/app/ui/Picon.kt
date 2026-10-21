package tv.vuplus.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.vuplus.app.data.Channel
import tv.vuplus.app.data.PiconRepository
import tv.vuplus.app.data.Settings

/** Логотип канала. Пока грузится или если пикона нет, показываются первые буквы названия. */
@Composable
fun Picon(channel: Channel, settings: Settings, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val image by produceState<ImageBitmap?>(null, channel.ref, settings.host, settings.port) {
        value = PiconRepository.load(context, settings, channel)
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(PiconBg)
            .border(1.dp, Line, shape),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(3.dp),
            )
        } else {
            Text(
                text = channel.name.trim().take(2).uppercase(),
                color = Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
