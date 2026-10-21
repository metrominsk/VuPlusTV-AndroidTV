package tv.vuplus.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import tv.vuplus.app.ui.TvApp
import tv.vuplus.app.ui.VuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VuTheme { TvApp() } }
    }
}
