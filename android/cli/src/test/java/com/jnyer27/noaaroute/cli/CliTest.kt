package com.jnyer27.noaaroute.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.ServerSocket
import java.nio.file.Files

class CliTest {
    private val base = arrayOf("-f", "39.32371,-76.39998", "-t", "38.97731 N, 76.48559 W", "-d", "3", "-a", "10")

    private fun parse(vararg extra: String) = Cli.parse(base + extra)!!

    private fun usageError(vararg args: String): String =
        try { Cli.parse(arrayOf(*args)); "" } catch (e: UsageException) { e.message ?: "" }

    /** Runs the CLI, returning (exit code, stdout, stderr). */
    private fun run(vararg args: String, baseUrl: String? = null): Triple<Int, String, String> {
        val o = ByteArrayOutputStream()
        val e = ByteArrayOutputStream()
        val code = Cli(PrintStream(o, true), PrintStream(e, true), baseUrl).run(arrayOf(*args))
        return Triple(code, o.toString(), e.toString())
    }

    @Test
    fun parsesPointsAndOptions() {
        val o = parse("-v", "39 10.0 N, 76 20.0 W", "-v", "39.1,-76.3", "--inside", "--move-ends", "--resolve", "-b", "40", "-n", "trip")
        assertEquals(39.32371, o.from.lat, 1e-9)
        assertEquals(-76.48559, o.to.lon, 1e-9)
        assertEquals(2, o.vias.size)
        assertEquals(39 + 10.0 / 60, o.vias[0].lat, 1e-9)
        assertEquals(3.0, o.depthFt, 0.0)
        assertEquals("10", o.air)
        assertEquals(40.0, o.bufM, 0.0)
        assertTrue(o.inside && o.moveEnds && o.resolve && o.checks)
        assertEquals("trip", o.name)
    }

    @Test
    fun tideOptions() {
        assertTrue(!parse().tides)
        assertTrue(parse("--tides").tides && parse("--tides").tideDate == null)
        val o = parse("--tide-date", "2026-11-08")
        assertTrue(o.tides)
        assertEquals(java.time.LocalDate.of(2026, 11, 8), o.tideDate)
        assertTrue(usageError(*base, "--tide-date", "Nov 8").startsWith("--tide-date: not a date"))
    }

    @Test
    fun helpReturnsNull() {
        assertNull(Cli.parse(arrayOf("--help")))
        val (code, out, _) = run("-h")
        assertEquals(Exit.OK, code)
        assertTrue(out.contains("Usage: noaa-route"))
    }

    @Test
    fun badInput() {
        assertEquals("Give a start with -f", usageError("-t", "39,-76", "-d", "3"))
        assertEquals("Give a safe operating depth in feet with -d", usageError("-f", "39,-76", "-t", "39,-76"))
        assertEquals("Depth must be feet, 0-40", usageError("-f", "39,-76", "-t", "39,-76", "-d", "50"))
        assertEquals("-f: not a position: nowhere", usageError("-f", "nowhere"))
        assertEquals("-t needs a value", usageError("-f", "39,-76", "-t"))
        assertEquals("Unknown option: --fast", usageError("--fast"))
        val (code, _, err) = run("-f", "39,-76")
        assertEquals(Exit.USAGE, code)
        assertTrue(err.contains("Give a destination with -t"))
    }

    @Test
    fun airDraftIsRequiredUnlessBridgesAreSkipped() {
        val (code, _, err) = run("-f", "39.3,-76.4", "-t", "39.1,-76.2", "-d", "3")
        assertEquals(Exit.USAGE, code)
        assertTrue(err, err.contains("Give an air draft in feet with -a"))
        assertEquals(Exit.USAGE, run(*base.take(6).toTypedArray(), "-a", "abc").first)
        assertNull(Cli.parse(base.take(6).toTypedArray() + "--no-bridges")!!.air)
    }

    @Test
    fun offlineWithoutRouteDataSaysSo() {
        val dir = Files.createTempDirectory("cli").toFile()
        try {
            val (code, _, err) = run(*base, "--offline", "--packs", dir.path, "-o", File(dir, "r.gpx").path)
            assertEquals(Exit.NO_ROUTE, code)
            assertTrue(err, err.contains("No NOAA Route chart region covers these points"))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun unreachableReleaseIsADownloadError() {
        val dir = Files.createTempDirectory("cli").toFile()
        val port = ServerSocket(0).use { it.localPort } // closed again: nothing listens there
        try {
            val (code, _, err) = run(*base, "--packs", dir.path, "-o", File(dir, "r.gpx").path, baseUrl = "http://127.0.0.1:$port/")
            assertEquals(Exit.DOWNLOAD, code)
            assertTrue(err, err.contains("Could not download the route data catalog"))
        } finally { dir.deleteRecursively() }
    }
}
