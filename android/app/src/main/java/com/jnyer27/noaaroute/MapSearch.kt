package com.jnyer27.noaaroute

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Search on the chart screen: named chart objects (buoys, beacons, lights, bridges,
 * channels, wrecks) from each region chart's `search` metadata, places, marinas and
 * roads from each base map's `search` metadata, and bridges and restricted areas from
 * the route packs. An index is a JSON array of [name, kind, lat, lon] or
 * [name, kind, lat, lon, detail]; tools/build_enc_tiles.py and tools/build_basemap.sh
 * write them.
 *
 * Plain Kotlin. The activity reads the metadata; nothing here touches Android.
 */
object MapSearch {
    /** One result. [source]: "chart", "base map" or "route data". */
    class Item(
        val name: String, val kind: String, val lat: Double, val lon: Double,
        val detail: String, val source: String,
    ) {
        /** The folded name, with a space in front so " w" finds a word that starts with w. */
        internal val key = " " + fold(name)
        /** "Buoy, Fl G 4s" or "Town". */
        val subtitle get() = KIND_NAMES[kind] ?: kind.replaceFirstChar { it.uppercase() }
        val line get() = if (detail.isEmpty()) subtitle else "$subtitle, $detail"
    }

    /** How the kinds read in the result list. Unknown kinds are capitalized as they are. */
    val KIND_NAMES = mapOf(
        "buoy" to "Buoy", "beacon" to "Daybeacon", "light" to "Light", "bridge" to "Bridge",
        "channel" to "Dredged channel", "fairway" to "Fairway", "wreck" to "Wreck",
        "city" to "City", "town" to "Town", "village" to "Village", "hamlet" to "Hamlet",
        "suburb" to "Neighborhood", "neighbourhood" to "Neighborhood", "quarter" to "Neighborhood",
        "road" to "Road", "marina" to "Marina", "beach" to "Beach", "ferry_terminal" to "Ferry terminal",
        "boat_rental" to "Boat rental", "boat_storage" to "Boat storage", "restricted" to "Restricted area",
    )

    /** Zoom to show a result at: a town wider than a buoy. */
    fun zoomFor(kind: String): Double = when (kind) {
        "city" -> 12.0
        "town", "restricted", "channel", "fairway" -> 13.0
        "village", "hamlet", "suburb", "neighbourhood", "quarter", "road" -> 14.0
        else -> 16.0
    }

    /** One index from a region file's metadata. Bad rows are skipped. */
    fun parse(json: String, source: String): List<Item> {
        val list = try { MiniJson.parse(json) as? List<*> } catch (_: Exception) { null } ?: return emptyList()
        return rows(list, source)
    }

    private fun rows(rows: List<*>, source: String): List<Item> {
        val out = ArrayList<Item>(rows.size)
        for (r in rows) {
            val a = r as? List<*> ?: continue
            if (a.size < 4) continue
            val name = (a[0] as? String)?.trim().orEmpty()
            val kind = a[1] as? String ?: continue
            val lat = (a[2] as? Number)?.toDouble() ?: continue
            val lon = (a[3] as? Number)?.toDouble() ?: continue
            if (name.isEmpty()) continue
            out.add(Item(name, kind, lat, lon, (a.getOrNull(4) as? String).orEmpty(), source))
        }
        return out
    }

    /** Bridges and restricted areas by name from the route packs, at the middle of each one's box. */
    fun fromNotes(notes: List<Note>): List<Item> {
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        for (n in notes) {
            if (n.name.isBlank() || n.name == "restricted area" || n.name == "unnamed") continue
            val kind = when (n.kind) { "resare" -> "restricted"; "bridge" -> "bridge"; else -> continue }
            val lat = (n.bbox[1] + n.bbox[3]) / 2
            val lon = (n.bbox[0] + n.bbox[2]) / 2
            val k = "$kind|${n.name}|${"%.2f".format(Locale.US, lat)}|${"%.2f".format(Locale.US, lon)}"
            if (!seen.add(k)) continue
            out.add(Item(n.name, kind, lat, lon, n.cfr.orEmpty(), "route data"))
        }
        return out
    }

    /**
     * Results for [query]: every word must start a word of the name ("mid riv" finds
     * "Middle River"), or the whole query must appear in it. A name that starts with the
     * query comes first, then word matches, then the rest; within each, nearest to [near].
     * The same name and kind within about 200 m is listed once (charts overlap at seams).
     */
    fun find(items: List<Item>, query: String, near: LatLon?, limit: Int = 60): List<Item> {
        val q = fold(query)
        val qWords = q.split(' ').filter { it.isNotEmpty() }
        if (qWords.isEmpty()) return emptyList()
        val needles = qWords.map { " $it" }
        class Hit(val item: Item, val rank: Int, val dist: Double)
        val hits = ArrayList<Hit>()
        for (it in items) {
            // Base maps list a couple of hundred thousand streets: no lists or copies per item
            val rank = when {
                it.key.startsWith(q, 1) -> 0
                needles.all { w -> it.key.contains(w) } -> 1
                it.key.contains(q) -> 2
                else -> continue
            }
            val d = if (near == null) 0.0 else Planner.distanceM(near, LatLon(it.lat, it.lon))
            hits.add(Hit(it, rank, d))
        }
        hits.sortWith(compareBy<Hit> { it.rank }.thenBy { it.dist }.thenBy { it.item.name })
        val out = ArrayList<Item>()
        for (h in hits) {
            if (out.any { o -> o.kind == h.item.kind && o.key == h.item.key && Planner.distanceM(LatLon(o.lat, o.lon), LatLon(h.item.lat, h.item.lon)) < 200.0 }) continue
            out.add(h.item)
            if (out.size >= limit) break
        }
        return out
    }

    /** Lower case, letters and digits only, single spaces: "Rd." and "rd" match. */
    internal fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        var space = true
        for (c in s.lowercase(Locale.US)) {
            if (c.isLetterOrDigit()) { sb.append(c); space = false }
            else if (!space) { sb.append(' '); space = true }
        }
        return sb.toString().trim()
    }

    /**
     * The JSON metadata of a PMTiles v3 file (the base map): header bytes 24-39 give its
     * offset and length, byte 97 its compression (1 none, 2 gzip). Null when the file is
     * not PMTiles v3 or the metadata is compressed some other way.
     */
    fun pmtilesMetadata(file: File): String? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 127) return null
            val h = ByteArray(127)
            raf.readFully(h)
            if (String(h, 0, 7, Charsets.US_ASCII) != "PMTiles" || h[7].toInt() != 3) return null
            val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val off = bb.getLong(24)
            val len = bb.getLong(32)
            if (off < 0 || len <= 0 || len > 64L shl 20 || off + len > raf.length()) return null
            val raw = ByteArray(len.toInt())
            raf.seek(off)
            raf.readFully(raw)
            return when (h[97].toInt()) {
                1 -> String(raw, Charsets.UTF_8)
                2 -> GZIPInputStream(raw.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
                else -> null
            }
        }
    }

    /** The `search` index inside a base map's metadata (a list, or a JSON string of one). */
    fun fromMetadata(metadataJson: String, source: String): List<Item> {
        val m = try { MiniJson.parse(metadataJson) as? Map<*, *> } catch (_: Exception) { null } ?: return emptyList()
        return when (val v = m["search"]) {
            is String -> parse(v, source)
            is List<*> -> rows(v, source)
            else -> emptyList()
        }
    }

    /** GeoJSON for the pin on a picked result. */
    fun pinGeoJson(item: Item): String = String.format(Locale.US,
        "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"label\":%s}," +
            "\"geometry\":{\"type\":\"Point\",\"coordinates\":[%.6f,%.6f]}}]}",
        quote(item.name), item.lon, item.lat)

    private fun quote(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> if (c < ' ') append(' ') else append(c)
        }
        append('"')
    }
}
