package tv.vuplus.app.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vuplus", Context.MODE_PRIVATE)

    fun load() = Settings(
        host = prefs.getString("host", "").orEmpty(),
        port = prefs.getInt("port", 80),
        streamPort = prefs.getInt("streamPort", 8001),
        user = prefs.getString("user", "").orEmpty(),
        pass = prefs.getString("pass", "").orEmpty(),
        engine = runCatching { PlayerEngine.valueOf(prefs.getString("engine", "").orEmpty()) }
            .getOrDefault(PlayerEngine.Exo),
        showSignal = prefs.getBoolean("showSignal", false),
    )

    fun save(s: Settings) {
        prefs.edit()
            .putString("host", s.host)
            .putInt("port", s.port)
            .putInt("streamPort", s.streamPort)
            .putString("user", s.user)
            .putString("pass", s.pass)
            .putString("engine", s.engine.name)
            .putBoolean("showSignal", s.showSignal)
            .apply()
    }

    var lastRef: String
        get() = prefs.getString("lastRef", "").orEmpty()
        set(value) = prefs.edit().putString("lastRef", value).apply()

    var lastBouquet: String
        get() = prefs.getString("lastBouquet", "").orEmpty()
        set(value) = prefs.edit().putString("lastBouquet", value).apply()
}
