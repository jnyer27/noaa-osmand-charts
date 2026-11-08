package com.jnyer27.noaaroute

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

/** PackCache against a local server standing in for the route-pack release. */
class PackCacheTest {
    private val files = HashMap<String, ByteArray>()
    private lateinit var server: HttpServer
    private lateinit var root: File
    private lateinit var cache: PackCache

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = files[ex.requestURI.path.removePrefix("/rel/")]
            if (body == null) ex.sendResponseHeaders(404, -1)
            else { ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) } }
            ex.close()
        }
        server.start()
        root = Files.createTempDirectory("packcache").toFile()
        cache = PackCache(File(root, "areas"), File(root, "catalog.json"), "http://127.0.0.1:${server.address.port}/rel/")
    }

    @After
    fun stop() {
        server.stop(0)
        root.deleteRecursively()
    }

    /** A valid NRPK2 pack with no tiles: enough for its header to be read. */
    private fun pack(id: String, created: String): ByteArray {
        val header = """{"format":"NRPK","version":2,"created":"$created","states":["TEST"],
            "area":{"id":"$id","name":"Test $id","bbox":[-76.5,39.0,-76.4,39.1]},"tx0":0,"ty0":0,
            "west":-76.5,"north":39.1,"dlat":0.0002,"dlon":0.0002,"res":20.0,"width":256,"height":256,
            "tile":256,"planes":${Pack.NPLANES},"depth_table":[],"notes":[]}""".replace("\n", "")
        val hb = header.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("NRPK2\n".toByteArray())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(hb.size).array())
        out.write(hb)
        out.write(ByteArray(12)) // one empty tile
        return out.toByteArray()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Publish packs and a catalog listing them; [extra] adds raw JSON fields per id. */
    private fun publish(packs: Map<String, ByteArray>, created: Map<String, String>, extra: Map<String, String> = emptyMap(),
                        badSha: Set<String> = emptySet()) {
        val entries = packs.map { (id, b) ->
            files["$id.bin"] = b
            val s = if (id in badSha) "0".repeat(64) else sha(b)
            """{"id":"$id","name":"Region $id","file":"$id.bin","size":${b.size},"sha256":"$s",
               "created":"${created.getValue(id)}","bbox":[-76.5,39.0,-76.4,39.1]${extra[id] ?: ""}}"""
        }
        files["catalog.json"] = """{"areas":[${entries.joinToString(",")}]}""".toByteArray()
    }

    private fun leftovers() = cache.dir.listFiles().orEmpty().filter { it.name.endsWith(".part") }

    @Test
    fun catalogIsCachedForOffline() {
        assertTrue(cache.cachedCatalog().isEmpty())
        publish(mapOf("a" to pack("a", "2026-11-01T00:00:00Z")), mapOf("a" to "2026-11-01T00:00:00Z"))
        assertEquals(listOf("a"), cache.fetchCatalog().map { it.id })
        server.stop(0)
        assertEquals(listOf("a"), cache.cachedCatalog().map { it.id })
    }

    @Test
    fun downloadChecksShaAndInstalls() {
        publish(mapOf("a" to pack("a", "2026-11-01T00:00:00Z")), mapOf("a" to "2026-11-01T00:00:00Z"))
        val before = cache.signature()
        cache.download(cache.fetchCatalog().single())
        assertEquals(mapOf("a" to "2026-11-01T00:00:00Z"), cache.installed())
        assertNotEquals(before, cache.signature())
        assertTrue(leftovers().isEmpty())
        cache.delete("a")
        assertTrue(cache.installed().isEmpty())
    }

    @Test
    fun badShaIsRefusedAndLeavesNothing() {
        publish(mapOf("a" to pack("a", "2026-11-01T00:00:00Z")), mapOf("a" to "2026-11-01T00:00:00Z"), badSha = setOf("a"))
        val e = runCatching { cache.download(cache.fetchCatalog().single()) }.exceptionOrNull()
        assertEquals("download check failed, try again", e?.message)
        assertFalse(File(cache.dir, "a.bin").exists())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun missingFileFails() {
        publish(mapOf("a" to pack("a", "2026-11-01T00:00:00Z")), mapOf("a" to "2026-11-01T00:00:00Z"))
        val a = cache.fetchCatalog().single()
        files.remove("a.bin")
        val e = runCatching { cache.download(a) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().startsWith("HTTP 404"))
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun updateAllRefreshesAndSwapsReplacedPacks() {
        // Installed: "a" (old build) and "old"; not installed: "c"
        val t1 = "2026-11-01T00:00:00Z"
        val t2 = "2026-11-08T00:00:00Z"
        publish(mapOf("a" to pack("a", t1), "old" to pack("old", t1)), mapOf("a" to t1, "old" to t1))
        cache.fetchCatalog().forEach { cache.download(it) }
        // New release: "a" rebuilt, "old" replaced by "b", "c" new but not installed
        publish(
            mapOf("a" to pack("a", t2), "old" to pack("old", t1), "b" to pack("b", t2), "c" to pack("c", t2)),
            mapOf("a" to t2, "old" to t1, "b" to t2, "c" to t2),
            extra = mapOf("old" to ""","deprecated":true,"replaced_by":["b"]"""),
        )
        assertEquals(2, cache.updateAll())
        assertEquals(mapOf("a" to t2, "b" to t2), cache.installed())
        // Nothing newer: no downloads
        assertEquals(0, cache.updateAll())
    }
}
