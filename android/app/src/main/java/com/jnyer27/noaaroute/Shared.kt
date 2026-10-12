package com.jnyer27.noaaroute

/** A named position found in shared text or a GPX file. */
data class SharedPoint(val name: String, val pos: LatLon)

/** Points from a GPX file: named waypoints, route points, and each track's first and last point. */
class SharedGpx(val waypoints: List<SharedPoint>, val route: List<SharedPoint>, val trackEnds: List<SharedPoint>) {
    val isEmpty get() = waypoints.isEmpty() && route.isEmpty() && trackEnds.isEmpty()
}

/**
 * Finds positions in what other apps share: OsmAnd "Share location" text,
 * geo: URIs, map links (lat=..&lon=.., q=lat,lon, @lat,lon) and plain
 * coordinate pairs, plus GPX files. Plain Kotlin so it is unit tested on the JVM.
 */
object Shared {
    private const val NUM = """[-+]?\d{1,3}(?:\.\d+)?"""
    private val geo = Regex("""geo:($NUM),($NUM)""")
    private val latLonParams = Regex("""[?&](?:lat|mlat)=($NUM)&(?:lon|lng|mlon)=($NUM)""")
    private val q = Regex("""[?&](?:q|query|ll|daddr|saddr)=($NUM)(?:,|%2C)\s*($NUM)""", RegexOption.IGNORE_CASE)
    private val at = Regex("""@($NUM),($NUM)""")
    private val pair = Regex("""(?<![\w.])([-+]?\d{1,2}\.\d{2,})\s*°?\s*([NS])?\s*[,;\s]\s*([-+]?\d{1,3}\.\d{2,})\s*°?\s*([EW])?(?![\w.])""",
        RegexOption.IGNORE_CASE)

    private fun valid(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    /** Positions in shared text, in order of appearance, duplicates removed. */
    fun parseText(text: String): List<SharedPoint> {
        val found = LinkedHashMap<String, SharedPoint>()
        fun add(lat: Double, lon: Double) {
            if (!valid(lat, lon)) return
            val key = "%.5f,%.5f".format(java.util.Locale.US, lat, lon)
            found.putIfAbsent(key, SharedPoint(Coords.format(LatLon(lat, lon)), LatLon(lat, lon)))
        }
        for (re in listOf(geo, latLonParams, q, at)) for (m in re.findAll(text)) {
            add(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        }
        if (found.isEmpty()) for (m in pair.findAll(text)) {
            var lat = m.groupValues[1].toDouble()
            var lon = m.groupValues[3].toDouble()
            if (m.groupValues[2].equals("S", true)) lat = -kotlin.math.abs(lat)
            if (m.groupValues[4].equals("W", true)) lon = -kotlin.math.abs(lon)
            add(lat, lon)
        }
        // A short label before the position (OsmAnd shares "Name\nLocation: ...") names a single point
        val label = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.contains("://") && !it.contains("geo:") && pair.find(it) == null }
        return if (found.size == 1 && label != null && label.length <= 60)
            listOf(found.values.first().copy(name = label.removeSuffix(":")))
        else found.values.toList()
    }

    private val tag = Regex("""<(wpt|rtept|trkpt)\b([^>]*?)(/>|>(.*?)</\1>)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val attLat = Regex("""\blat\s*=\s*["']($NUM)["']""")
    private val attLon = Regex("""\blon\s*=\s*["']($NUM)["']""")
    private val nameTag = Regex("""<name>\s*(?:<!\[CDATA\[)?(.*?)(?:]]>)?\s*</name>""", RegexOption.DOT_MATCHES_ALL)
    private val trkseg = Regex("""<trk\b.*?</trk>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&").trim()

    /** Points in a GPX document, or null if it is not GPX. */
    fun parseGpx(xml: String): SharedGpx? {
        if (!xml.contains("<gpx", ignoreCase = true)) return null
        val wpts = ArrayList<SharedPoint>()
        val rte = ArrayList<SharedPoint>()
        fun point(attrs: String, body: String?, fallback: String): SharedPoint? {
            val lat = attLat.find(attrs)?.groupValues?.get(1)?.toDouble() ?: return null
            val lon = attLon.find(attrs)?.groupValues?.get(1)?.toDouble() ?: return null
            if (!valid(lat, lon)) return null
            val name = body?.let { nameTag.find(it)?.groupValues?.get(1) }?.let(::unescape)?.takeIf { it.isNotEmpty() } ?: fallback
            return SharedPoint(name, LatLon(lat, lon))
        }
        // Waypoints and route points (track points are handled per track below)
        val noTracks = trkseg.replace(xml, "")
        for (m in tag.findAll(noTracks)) {
            val kind = m.groupValues[1].lowercase()
            val body = m.groupValues[4].ifEmpty { null }
            when (kind) {
                "wpt" -> point(m.groupValues[2], body, "Waypoint ${wpts.size + 1}")?.let { wpts.add(it) }
                "rtept" -> point(m.groupValues[2], body, "Route point ${rte.size + 1}")?.let { rte.add(it) }
            }
        }
        val ends = ArrayList<SharedPoint>()
        for ((k, t) in trkseg.findAll(xml).withIndex()) {
            val pts = tag.findAll(t.value).filter { it.groupValues[1].equals("trkpt", true) }.toList()
            if (pts.isEmpty()) continue
            val trackName = nameTag.find(t.value.substringBefore("<trkseg"))?.groupValues?.get(1)?.let(::unescape) ?: "Track ${k + 1}"
            point(pts.first().groupValues[2], null, "$trackName (start)")?.let { ends.add(it) }
            if (pts.size > 1) point(pts.last().groupValues[2], null, "$trackName (end)")?.let { ends.add(it) }
        }
        return SharedGpx(wpts, rte, ends)
    }
}
