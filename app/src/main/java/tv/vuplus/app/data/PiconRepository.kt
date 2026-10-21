package tv.vuplus.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Пиконы берутся с самого ресивера (OpenWebif отдаёт их по адресу /picon/...), интернет не нужен.
 * Скачанное лежит в кэше на диске приставки, поэтому повторно ресивер не нагружается.
 */
object PiconRepository {

    private sealed interface Fetch {
        class Ok(val bytes: ByteArray) : Fetch
        data object NotFound : Fetch
        data object Failed : Fetch
    }

    private const val NONE_TTL_MS = 24L * 60 * 60 * 1000

    private val memory = object : LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(4)

    /** 1:0:19:283D:3FB:1:C00000:0:0:0: превращается в 1_0_19_283D_3FB_1_C00000_0_0_0 */
    fun fileName(ref: String): String = ref.trim().replace(':', '_').trimEnd('_')

    suspend fun load(context: Context, settings: Settings, channel: Channel): ImageBitmap? {
        val name = fileName(channel.ref)
        val key = "${settings.host}/$name"
        memory.get(key)?.let { return it }
        if (key in missing) return null

        val bitmap = withContext(Dispatchers.IO) {
            gate.withPermit { loadFromDiskOrReceiver(context, settings, channel, name, key) }
        }
        if (bitmap != null) memory.put(key, bitmap)
        return bitmap
    }

    private fun loadFromDiskOrReceiver(
        context: Context,
        settings: Settings,
        channel: Channel,
        name: String,
        key: String,
    ): ImageBitmap? {
        val dirName = settings.host.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(context.cacheDir, "picons/$dirName").apply { mkdirs() }
        val file = File(dir, "$name.png")
        val none = File(dir, "$name.none")

        val cached = if (file.exists()) runCatching { file.readBytes() }.getOrNull() else null
        if (cached != null) return decode(cached)

        if (none.exists() && System.currentTimeMillis() - none.lastModified() < NONE_TTL_MS) {
            missing.add(key)
            return null
        }

        var result = fetch(settings, name)
        if (result is Fetch.NotFound) {
            // Запасной вариант: пиконы, названные по имени канала (набор SNP/SRP), только латиница.
            snpName(channel.name)?.let { result = fetch(settings, it) }
        }
        return when (val r = result) {
            is Fetch.Ok -> {
                runCatching { file.writeBytes(r.bytes) }
                decode(r.bytes)
            }
            Fetch.NotFound -> {
                runCatching { none.writeText("") }
                missing.add(key)
                null
            }
            Fetch.Failed -> null
        }
    }

    private fun fetch(settings: Settings, name: String): Fetch = try {
        val url = URL("http://${settings.host}:${settings.port}/picon/$name.png")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4_000
            conn.readTimeout = 6_000
            settings.authHeader?.let { conn.setRequestProperty("Authorization", it) }
            val code = conn.responseCode
            when {
                code == 404 -> Fetch.NotFound
                code !in 200..299 -> Fetch.Failed
                conn.contentType?.startsWith("image") != true -> Fetch.NotFound
                else -> Fetch.Ok(conn.inputStream.use { it.readBytes() })
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: IOException) {
        Fetch.Failed
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 320) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private fun snpName(name: String): String? {
        val ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).filter { it.code < 128 }
        val clean = ascii.replace("&", "and").replace("+", "plus").replace("*", "star")
            .lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }
        return clean.ifEmpty { null }
    }
}
