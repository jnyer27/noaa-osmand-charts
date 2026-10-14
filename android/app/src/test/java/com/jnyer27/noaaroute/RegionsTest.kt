package com.jnyer27.noaaroute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionsTest {
    // A catalog as the route-pack workflow writes it: two NCDS regions and the old
    // Chesapeake area they replace
    private val catalogJson = """{"version":2,"areas":[
        {"id":"ncds_04","name":"Region 04: Upper Chesapeake and Delaware Bay","description":"upper","bbox":[-77.8,38.23,-73.4,39.73],
         "states":[],"file":"ncds_04.bin","size":7000000,"sha256":"a","created":"2026-10-17T12:00:00Z","ncds":"04","ncds_bbox":[-77.81,38.23,-64.95,39.73]},
        {"id":"ncds_05","name":"Region 05: Lower Chesapeake and Hampton Roads","description":"lower","bbox":[-77.8,35.5,-74.6,38.23],
         "states":[],"file":"ncds_05.bin","size":6000000,"sha256":"b","created":"2026-10-17T12:00:00Z","ncds":"05","ncds_bbox":[-77.81,35.5,-64.95,38.23]},
        {"id":"chesapeake","name":"Chesapeake Bay","description":"old","bbox":[-77.6,36.7,-75.0,39.7],"states":["MD","VA","DE"],
         "file":"chesapeake.bin","size":6800000,"sha256":"c","created":"2026-10-17T12:00:00Z","deprecated":true,"replaced_by":["ncds_04","ncds_05"]}]}"""
    private val catalog = Regions.parseCatalog(catalogJson)
    private fun byId(id: String) = catalog.first { it.id == id }
    private val marina = LatLon(39.32371, -76.39998)
    private val norfolk = LatLon(36.96, -76.33)

    @Test
    fun ncdsTable() {
        assertEquals(47, Regions.ALL.size)
        val r4 = Regions.byCode("04")!!
        assertEquals(38.23, r4.south, 1e-9)
        assertTrue(r4.contains(marina))
        assertFalse(Regions.byCode("05")!!.contains(marina))
    }

    @Test
    fun parsesOldAndNewCatalogs() {
        assertEquals("04", byId("ncds_04").ncds)
        assertEquals("Region 04", byId("ncds_04").shortName)
        assertTrue(byId("chesapeake").deprecated)
        assertEquals(listOf("ncds_04", "ncds_05"), byId("chesapeake").replacedBy)
        // Catalogs from before regions have none of the new fields
        val old = Regions.parseCatalog("""{"version":2,"areas":[{"id":"chesapeake","name":"Chesapeake Bay","bbox":[-77.6,36.7,-75.0,39.7],
            "states":["MD","VA"],"file":"chesapeake.bin","size":5500000,"sha256":"c","created":"2026-10-14T02:56:19Z"}]}""")
        assertNull(old[0].ncds)
        assertFalse(old[0].deprecated)
        assertEquals("Chesapeake Bay", old[0].shortName)
    }

    @Test
    fun tripAcrossTheSeamNeedsTheOtherRegion() {
        val have = setOf("ncds_04")
        val need = Regions.need(listOf(marina, norfolk), catalog, have) { byId("ncds_04").covers(it) }
        assertEquals(listOf("ncds_05"), need.download.map { it.id })
        assertFalse(need.outside)
    }

    @Test
    fun nothingNeededWhenInstalledPacksCoverTheTrip() {
        // The old Chesapeake pack still covers this trip: no nagging during the switch
        val need = Regions.need(listOf(marina, norfolk), catalog, setOf("chesapeake")) { byId("chesapeake").covers(it) }
        assertTrue(need.download.isEmpty())
        assertFalse(need.outside)
    }

    @Test
    fun neverOffersAReplacedPackAndFlagsTheOutside() {
        val cape = LatLon(41.5, -70.0) // Cape Cod: no pack yet
        val need = Regions.need(listOf(marina, cape), catalog, emptySet()) { false }
        assertTrue(need.outside)
        assertTrue(need.download.none { it.deprecated })
        assertEquals("ncds_04", need.download.first().id)
    }

    @Test
    fun samplesEveryFewKilometers() {
        val s = Regions.samples(listOf(marina, norfolk))
        assertEquals(marina, s.first())
        assertEquals(norfolk, s.last())
        assertTrue("about 2 km apart", s.size in 130..140) // ~264 km
    }

    @Test
    fun swapsTheOldAreaForItsRegions() {
        assertEquals(listOf("ncds_04", "ncds_05"), Regions.replacements(catalog, setOf("chesapeake"))["chesapeake"]!!.map { it.id })
        assertEquals(listOf("ncds_05"), Regions.replacements(catalog, setOf("chesapeake", "ncds_04"))["chesapeake"]!!.map { it.id })
        // All replacements installed: an empty list means the old pack can go
        assertTrue(Regions.replacements(catalog, setOf("chesapeake", "ncds_04", "ncds_05"))["chesapeake"]!!.isEmpty())
        assertTrue(Regions.replacements(catalog, setOf("ncds_04")).isEmpty())
    }

    @Test
    fun whereDoYouBoat() {
        assertEquals("ncds_04", Regions.areaAt(marina, catalog)!!.id)
        assertNull(Regions.areaAt(LatLon(41.5, -70.0), catalog))
    }
}
