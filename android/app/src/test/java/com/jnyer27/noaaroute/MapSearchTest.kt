package com.jnyer27.noaaroute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

class MapSearchTest {
    private val chart = MapSearch.parse(
        """[["Middle River Channel Light 2","light",39.2901,-76.4002,"Fl R 4s"],
            ["Middle River Channel Buoy 3","buoy",39.2950,-76.4050],
            ["Middle River Channel Buoy 3","buoy",39.2951,-76.4051],
            ["Hart-Miller Island","channel",39.25,-76.37],
            ["bad row","buoy"],
            ["","buoy",1.0,2.0]]""", "chart")
    private val base = MapSearch.parse(
        """[["Bowleys Marina","marina",39.305,-76.39],["Bay Drive","road",39.31,-76.38],
            ["Essex","town",39.31,-76.47],["Middle River","town",39.33,-76.44],
            ["Middle River Road","road",39.35,-76.45]]""", "base map")
    private val all = chart + base
    private val home = LatLon(39.32371, -76.39998)

    @Test
    fun rowsWithoutANameOrPositionAreSkipped() {
        assertEquals(4, chart.size)
        assertEquals("Light, Fl R 4s", chart[0].line)
        assertEquals("Buoy", chart[1].line)
        assertEquals("chart", chart[0].source)
    }

    @Test
    fun everyWordStartsAWordOfTheName() {
        val hits = MapSearch.find(all, "mid riv", home)
        assertTrue(hits.map { it.name }.toString(), hits.all { it.name.contains("Middle River") })
        // Names that start with the query come first, nearest first among them
        assertEquals("Middle River", MapSearch.find(all, "middle river", LatLon(39.33, -76.44)).first().name)
        assertEquals("Middle River Road", MapSearch.find(all, "river road", home).first().name)
        assertTrue(MapSearch.find(all, "buoy 3", home).any { it.name == "Middle River Channel Buoy 3" })
        // Inside a word only when the whole query appears: "arina" finds the marina
        assertEquals("Bowleys Marina", MapSearch.find(all, "arina", home).single().name)
        assertTrue(MapSearch.find(all, "zzz", home).isEmpty())
        assertTrue(MapSearch.find(all, "  ", home).isEmpty())
        // Punctuation and case do not matter
        assertEquals("Hart-Miller Island", MapSearch.find(all, "hart miller", home).single().name)
    }

    @Test
    fun theSameMarkFromTwoChartsIsListedOnceAndNearestComesFirst() {
        val buoys = MapSearch.find(all, "buoy", home)
        assertEquals(1, buoys.size)
        val roads = MapSearch.find(all, "road", home).filter { it.kind == "road" }
        assertEquals(listOf("Middle River Road"), roads.map { it.name })
        val towns = MapSearch.find(all, "e", LatLon(39.31, -76.47)).filter { it.kind == "town" }
        assertEquals("Essex", towns.first().name)
    }

    @Test
    fun routePackBridgesAndRestrictedAreasAreSearchable() {
        val box = doubleArrayOf(-76.5, 39.0, -76.3, 39.2)
        val notes = listOf(
            Note("bridge", 5, box, "Bbox", null, "Chesapeake Bay Bridge", emptyList(), null, 56.0, null, null),
            Note("bridge", 5, box, "Bbox", null, "Chesapeake Bay Bridge", emptyList(), null, 56.0, null, null),
            Note("resare", 5, box, "Bbox", null, "restricted area", listOf("entry restricted"), "33 CFR 334.1", null, null, null),
            Note("resare", 5, box, "Bbox", null, "Aberdeen Proving Ground", listOf("entry restricted"), "33 CFR 334.140", null, null, null),
            Note("cable", 5, box, "Bbox", null, "overhead cable", emptyList(), null, 20.0, null, null),
        )
        val items = MapSearch.fromNotes(notes)
        assertEquals(listOf("Chesapeake Bay Bridge", "Aberdeen Proving Ground"), items.map { it.name })
        assertEquals(39.1, items[0].lat, 1e-9)
        assertEquals("Restricted area, 33 CFR 334.140", items[1].line)
    }

    @Test
    fun theBaseMapIndexIsReadFromPmtilesMetadata() {
        val meta = """{"name":"Ncds_04 base map","search":[["Bowleys Marina","marina",39.305,-76.39]]}"""
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(meta.toByteArray()) } }.toByteArray()
        val header = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN)
        header.put("PMTiles".toByteArray())
        header.put(3.toByte())
        header.putLong(8, 127)              // root directory (unused here)
        header.putLong(16, 0)
        header.putLong(24, 127)             // metadata offset
        header.putLong(32, gz.size.toLong())
        header.put(97, 2.toByte())          // gzip
        val f = File.createTempFile("base", ".pmtiles")
        try {
            f.writeBytes(header.array() + gz)
            val json = MapSearch.pmtilesMetadata(f)!!
            val items = MapSearch.fromMetadata(json, "base map")
            assertEquals("Bowleys Marina", items.single().name)
            assertEquals("Marina", items.single().line)
            f.writeBytes(ByteArray(200))
            assertNull(MapSearch.pmtilesMetadata(f))
        } finally {
            f.delete()
        }
        assertTrue(MapSearch.fromMetadata("""{"name":"x"}""", "base map").isEmpty())
    }

    @Test
    fun aPinCarriesTheNameAndAZoomFitsTheKind() {
        val pin = MiniJson.parse(MapSearch.pinGeoJson(base[0])) as Map<*, *>
        val f = (pin["features"] as List<*>)[0] as Map<*, *>
        assertEquals("Bowleys Marina", (f["properties"] as Map<*, *>)["label"])
        assertTrue(MapSearch.zoomFor("city") < MapSearch.zoomFor("road"))
        assertTrue(MapSearch.zoomFor("road") < MapSearch.zoomFor("buoy"))
    }
}
