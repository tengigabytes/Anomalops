// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.telemetry.divelog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDateTime

/** FR-44: the two supported export formats (requirements section 8), in the shapes Subsurface writes them. */
class DiveLogImporterTest {
    private fun read(xml: String) = DiveLogImporter.read(xml.trimIndent().byteInputStream())

    @Test
    fun fr44_readsUddfWithKelvinAndSeconds() {
        val dives = read(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <uddf xmlns="http://www.streit.cc/uddf/3.2/" version="3.2.0">
              <generator><name>x</name><datetime>2026-01-01T00:00:00</datetime></generator>
              <profiledata><repetitiongroup id="rg1"><dive id="d1">
                <informationbeforedive><datetime>2026-09-28T10:00:00</datetime></informationbeforedive>
                <samples>
                  <waypoint><depth>0.0</depth><divetime>0</divetime><temperature>300.15</temperature></waypoint>
                  <waypoint><depth>5.5</depth><divetime>30</divetime></waypoint>
                  <waypoint><divetime>60</divetime><depth>10</depth><temperature>299.15</temperature></waypoint>
                </samples>
              </dive></repetitiongroup></profiledata>
            </uddf>
            """,
        )
        assertEquals(1, dives.size)
        assertEquals(LocalDateTime.of(2026, 9, 28, 10, 0), dives[0].start)
        val points = dives[0].points
        assertEquals(listOf(0.0, 30.0, 60.0), points.map { it.offsetS })
        assertEquals(listOf(0.0, 5.5, 10.0), points.map { it.depthM })
        assertEquals(27.0, points[0].tempC!!, 1e-9)
        assertNull(points[1].tempC)
        assertEquals(26.0, points[2].tempC!!, 1e-9)
    }

    @Test
    fun fr44_uddfDateTimeDropsTheZone() {
        assertEquals(LocalDateTime.of(2026, 9, 28, 10, 0, 5), parseLogDateTime("2026-09-28T10:00:05+08:00"))
        assertEquals(LocalDateTime.of(2026, 9, 28, 10, 0, 5), parseLogDateTime("2026-09-28T10:00:05Z"))
        assertEquals(LocalDateTime.of(2026, 9, 28, 10, 0), parseLogDateTime("2026-09-28T10:00:00"))
    }

    @Test
    fun fr44_readsSubsurfaceFromTheFirstDiveComputer() {
        val dives = read(
            """
            <divelog program='subsurface' version='2'>
            <settings><divecomputerid model='X' deviceid='1'/></settings>
            <dives>
            <trip date='2026-09-28' time='09:00:00'>
            <dive number='1' date='2026-09-28' time='09:10:00' duration='2:00 min'>
              <divecomputer model='A'>
              <sample time='0:30 min' depth='3.0 m' temp='27.5 C' />
              <sample time='1:00 min' depth='6.0 m' />
              <sample time='112:30 min' depth='1.0 m' />
              </divecomputer>
              <divecomputer model='B'>
              <sample time='0:30 min' depth='99.0 m' />
              </divecomputer>
            </dive>
            </trip>
            <dive number='2' date='2026-09-28' time='14:00:00'>
              <sample time='0:10 min' depth='2.0 m' />
            </dive>
            </dives>
            </divelog>
            """,
        )
        assertEquals(2, dives.size)
        assertEquals(LocalDateTime.of(2026, 9, 28, 9, 10), dives[0].start)
        assertEquals(listOf(30.0, 60.0, 6750.0), dives[0].points.map { it.offsetS })
        assertEquals(listOf(3.0, 6.0, 1.0), dives[0].points.map { it.depthM })
        assertEquals(listOf(27.5, null, null), dives[0].points.map { it.tempC })
        assertEquals(listOf(2.0), dives[1].points.map { it.depthM })
    }

    @Test
    fun fr44_skipsAnEmptyUddfPlaceholderDive() {
        val dives = read(
            """
            <uddf version="3.3.0"><profiledata><repetitiongroup>
              <dive id="previous_dive"><informationbeforedive><surfaceintervalbeforedive/></informationbeforedive></dive>
              <dive id="dive"><informationbeforedive><datetime>2022-04-24T10:30:58</datetime></informationbeforedive>
                <samples><waypoint><depth>1.0</depth><divetime>0.0</divetime></waypoint></samples></dive>
            </repetitiongroup></profiledata></uddf>
            """,
        )
        assertEquals(listOf(LocalDateTime.of(2022, 4, 24, 10, 30, 58)), dives.map { it.start })
    }

    @Test
    fun fr44_acceptsUnpaddedSubsurfaceDatesAndHours() {
        val dives = read(
            "<dives><dive date='2014-4-1' time='6:00:00'></dive><dive date='2011-12-02' time='10:05'/></dives>",
        )
        assertEquals(
            listOf(LocalDateTime.of(2014, 4, 1, 6, 0), LocalDateTime.of(2011, 12, 2, 10, 5)),
            dives.map { it.start },
        )
    }

    @Test
    fun fr44_refusesUnitsItDoesNotKnow() {
        assertThrows(DiveLogFormatException::class.java) {
            read(
                "<dives><dive date='2026-09-28' time='09:10:00'><sample time='0:30 min' depth='10 ft'/></dive></dives>",
            )
        }
    }

    @Test
    fun fr44_refusesOtherFormatsAndBrokenXml() {
        assertThrows(DiveLogFormatException::class.java) { read("<gpx><trk/></gpx>") }
        assertThrows(DiveLogFormatException::class.java) { read("<uddf><profiledata>") }
        assertThrows(DiveLogFormatException::class.java) { read("") }
    }

    @Test
    fun fr44_refusesDoctypes() {
        assertThrows(DiveLogFormatException::class.java) {
            read(
                """
                <?xml version="1.0"?>
                <!DOCTYPE dives [<!ENTITY x SYSTEM "file:///etc/hostname">]>
                <dives><dive date='2026-09-28' time='09:10:00'><notes>&x;</notes></dive></dives>
                """,
            )
        }
    }
}
