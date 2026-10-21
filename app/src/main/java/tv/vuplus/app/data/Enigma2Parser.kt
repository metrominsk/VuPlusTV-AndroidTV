package tv.vuplus.app.data

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Разбор XML-ответов WebInterface Enigma2/OpenWebif. Не зависит от Android — легко тестируется. */
object Enigma2Parser {

    data class Service(val name: String, val ref: String)
    data class EpgItem(val serviceRef: String, val event: EpgEvent)

    private const val FLAG_DIRECTORY = 1
    private const val FLAG_MARKER = 64

    fun services(xml: String): List<Service> =
        elements(xml, "e2service").mapNotNull { e ->
            val name = e.text("e2servicename")
            val ref = e.text("e2servicereference")
            if (name.isBlank() || ref.isBlank()) null else Service(name, ref)
        }

    /** Вторая часть референса — флаги. Папки (букеты) и разделители-маркеры не воспроизводятся. */
    fun isPlayable(ref: String): Boolean {
        val flags = ref.split(':').getOrNull(1)?.toIntOrNull() ?: return true
        return (flags and (FLAG_DIRECTORY or FLAG_MARKER)) == 0
    }

    fun events(xml: String): List<EpgItem> =
        elements(xml, "e2event").mapNotNull { e ->
            val ref = e.text("e2eventservicereference")
            val start = e.text("e2eventstart").toLongOrNull()
            val title = e.text("e2eventtitle")
            if (ref.isBlank() || start == null || title.isBlank() || title == "None") {
                null
            } else {
                val description = e.text("e2eventdescriptionextended")
                    .ifBlank { e.text("e2eventdescription") }
                EpgItem(
                    serviceRef = ref,
                    event = EpgEvent(
                        title = title,
                        startSec = start,
                        durationSec = e.text("e2eventduration").toLongOrNull() ?: 0L,
                        description = description,
                    ),
                )
            }
        }

    /** Ответ /web/signal: уровень сигнала тюнера. */
    fun signal(xml: String): Signal? {
        val e = elements(xml, "e2frontendstatus").firstOrNull() ?: return null
        fun number(vararg tags: String): Long? =
            tags.firstNotNullOfOrNull { tag -> e.text(tag).filter { it.isDigit() }.toLongOrNull() }
        val snr = number("e2snr")?.toInt()
        val agc = number("e2acg", "e2agc")?.toInt()
        if (snr == null && agc == null) return null
        return Signal(
            snr = snr,
            agc = agc,
            ber = number("e2ber"),
            snrDb = e.text("e2snrdb").ifBlank { null },
        )
    }

    private fun parse(xml: String): Document {
        // Некоторые образы отдают в описаниях управляющие символы, на которых падает XML-парсер.
        val clean = xml.filter { it.code >= 0x20 || it == '\n' || it == '\r' || it == '\t' }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(clean)))
    }

    private fun elements(xml: String, tag: String): List<Element> {
        val nodes = parse(xml).getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.text(tag: String): String =
        getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
}
