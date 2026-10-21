package tv.vuplus.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceRefTest {

    @Test
    fun satelliteAndIdsAreReadFromRef() {
        val info = parseServiceRef("1:0:19:283D:3FB:1:820000:0:0:0:")
        assertNotNull(info)
        assertEquals(0x283D, info!!.sid)
        assertEquals(0x3FB, info.tsid)
        assertEquals(1, info.onid)
        assertEquals("13.0°E Hot Bird 13B/13C/13D", info.source)
        assertEquals("SID 283D   TSID 03FB   ONID 0001", info.ids)
    }

    @Test
    fun unknownSatelliteShowsOnlyPosition() {
        assertEquals("19.2°E Astra 1", parseServiceRef("1:0:1:1:1:1:C00000:0:0:0:")!!.source)
        assertEquals("30.0°E", parseServiceRef("1:0:1:1:1:1:12C0000:0:0:0:")!!.source)
    }

    @Test
    fun westPositionAndCableAndBrokenRef() {
        assertEquals("1.0°W", parseServiceRef("1:0:1:1:1:1:E060000:0:0:0:")!!.source)
        assertEquals("Кабель", parseServiceRef("1:0:1:1:1:1:FFFF0000:0:0:0:")!!.source)
        assertNull(parseServiceRef("garbage"))
    }

    @Test
    fun signalIsParsed() {
        val xml = "<e2frontendstatus><e2snrdb>13.40 dB</e2snrdb><e2snr>90 %</e2snr><e2ber>0</e2ber><e2acg>64 %</e2acg></e2frontendstatus>"
        val s = Enigma2Parser.signal(xml)!!
        assertEquals(90, s.snr)
        assertEquals(64, s.agc)
        assertEquals(0L, s.ber)
        assertEquals("13.40 dB", s.snrDb)
        assertNull(Enigma2Parser.signal("<e2frontendstatus></e2frontendstatus>"))
    }
}
