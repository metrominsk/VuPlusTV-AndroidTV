package tv.vuplus.app.data

import java.util.Locale

/** Данные, которые можно получить из референса канала без единого запроса. */
data class RefInfo(val sid: Int, val tsid: Int, val onid: Int, val source: String?) {
    val ids: String
        get() = String.format(Locale.ROOT, "SID %04X   TSID %04X   ONID %04X", sid, tsid, onid)
}

/** Референс вида 1:0:19:283D:3FB:1:C00000:0:0:0: (SID, TSID, ONID и пространство имён с позицией спутника). */
fun parseServiceRef(ref: String): RefInfo? {
    val parts = ref.trim().split(':')
    if (parts.size < 7) return null
    val sid = parts[3].toIntOrNull(16) ?: return null
    val tsid = parts[4].toIntOrNull(16) ?: return null
    val onid = parts[5].toIntOrNull(16) ?: return null
    val namespace = parts[6].toLongOrNull(16) ?: return null
    return RefInfo(sid, tsid, onid, sourceLabel(namespace))
}

// Позиция спутника в десятых долях градуса восточной долготы. Названия приблизительные: поправьте под свои.
private val satellites = mapOf(
    48 to "Astra 4A / SES-5",
    90 to "Eutelsat 9B",
    130 to "Hot Bird 13B/13C/13D",
    160 to "Eutelsat 16A",
    192 to "Astra 1",
    235 to "Astra 3",
    282 to "Astra 2",
    360 to "Eutelsat 36B/36C",
    390 to "Hellas Sat",
    420 to "Türksat",
    530 to "Express-AM6",
    560 to "Express-AT1/AT2",
    800 to "Express-80",
    900 to "Yamal 401",
    1400 to "Express-AM5",
)

private fun sourceLabel(namespace: Long): String? {
    val high = ((namespace shr 16) and 0xFFFF).toInt()
    return when {
        high == 0xFFFF -> "Кабель"
        high == 0xEEEE -> "Эфир"
        high == 0 || high > 3600 -> null
        else -> {
            val west = high > 1800
            val position = if (west) 3600 - high else high
            val base = String.format(Locale.ROOT, "%d.%d°%s", position / 10, position % 10, if (west) "W" else "E")
            satellites[high]?.let { "$base $it" } ?: base
        }
    }
}
