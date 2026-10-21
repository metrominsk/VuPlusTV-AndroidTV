package tv.vuplus.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Enigma2ParserTest {

    private val services = """<?xml version="1.0" encoding="UTF-8"?>
<e2servicelist>
  <e2service><e2servicereference>1:64:1:0:0:0:0:0:0:0:</e2servicereference><e2servicename>--- HD ---</e2servicename></e2service>
  <e2service><e2servicereference>1:0:19:283D:3FB:1:C00000:0:0:0:</e2servicereference><e2servicename>News &amp; Sport HD</e2servicename></e2service>
  <e2service><e2servicereference>1:7:1:0:0:0:0:0:0:0:FROM BOUQUET "sub.tv" ORDER BY bouquet</e2servicereference><e2servicename>Sub</e2servicename></e2service>
</e2servicelist>"""

    @Test
    fun serviceNamesAreDecoded() {
        val list = Enigma2Parser.services(services)
        assertEquals(3, list.size)
        assertEquals("News & Sport HD", list[1].name)
    }

    @Test
    fun markersAndFoldersAreNotPlayable() {
        val list = Enigma2Parser.services(services)
        assertFalse(Enigma2Parser.isPlayable(list[0].ref))
        assertTrue(Enigma2Parser.isPlayable(list[1].ref))
        assertFalse(Enigma2Parser.isPlayable(list[2].ref))
    }

    @Test
    fun epgEventsAreParsedAndEmptyOnesSkipped() {
        val xml = """<e2eventlist>
  <e2event>
    <e2eventstart>1760265600</e2eventstart>
    <e2eventduration>3600</e2eventduration>
    <e2eventtitle>Вечерние новости</e2eventtitle>
    <e2eventdescription>Коротко</e2eventdescription>
    <e2eventdescriptionextended>Подробное описание</e2eventdescriptionextended>
    <e2eventservicereference>1:0:19:283D:3FB:1:C00000:0:0:0:</e2eventservicereference>
  </e2event>
  <e2event>
    <e2eventstart>None</e2eventstart>
    <e2eventtitle>None</e2eventtitle>
    <e2eventservicereference>1:0:19:1:1:1:C00000:0:0:0:</e2eventservicereference>
  </e2event>
</e2eventlist>"""
        val items = Enigma2Parser.events(xml)
        assertEquals(1, items.size)
        val e = items[0].event
        assertEquals("Вечерние новости", e.title)
        assertEquals("Подробное описание", e.description)
        assertEquals(1760269200L, e.endSec)
        assertEquals(0.5f, e.progress(1760267400L), 0.001f)
    }

    @Test
    fun controlCharactersDoNotBreakParsing() {
        val xml = "<e2eventlist><e2event><e2eventstart>10</e2eventstart><e2eventduration>5</e2eventduration>" +
            "<e2eventtitle>A\u0001B</e2eventtitle><e2eventservicereference>1:0:1:</e2eventservicereference></e2event></e2eventlist>"
        assertEquals("AB", Enigma2Parser.events(xml).single().event.title)
    }
}
