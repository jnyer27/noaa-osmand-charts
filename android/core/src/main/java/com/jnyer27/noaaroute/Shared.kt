package com.jnyer27.noaaroute

/** A named position found in shared text or a GPX file. */
data class SharedPoint(val name: String, val pos: LatLon)

/**
 * Points from a GPX file: named waypoints, route points, and each track's first and last point.
 * [trip] is the start, via points and destination when this app wrote them. [summary] is the
 * route description, which carries the same warning lines as the report.
 */
class SharedGpx(
    val waypoints: List<SharedPoint>, val route: List<SharedPoint>, val trackEnds: List<SharedPoint>,
    val trip: List<LatLon> = emptyList(), val summary: String = "",
) {
    val isEmpty get() = waypoints.isEmpty() && route.isEmpty() && trackEnds.isEmpty()
}

/**
 * Finds positions in what other apps share: OsmAnd "Share location" text,
 * geo: URIs, map links (lat=..&lon=.., q=lat,lon, Google's !3d..!4d.. pin and
 * /search/lat,lon, @lat,lon) and plain coordinate pairs, plus GPX files. Google
 * Maps short links hold no position: [resolveShort] follows them to the long link
 * with a fetch the caller supplies. Plain Kotlin so it is unit tested on the JVM.
 */
object Shared {
    private const val NUM = """[-+]?\d{1,3}(?:\.\d+)?"""
    private val geo = Regex("""geo:($NUM),($NUM)""")
    private val latLonParams = Regex("""[?&](?:lat|mlat)=($NUM)&(?:lon|lng|mlon)=($NUM)""")
    private val q = Regex("""[?&](?:q|query|ll|daddr|saddr)=($NUM)(?:,|%2C)(?:\s|\+|%20)*($NUM)""", RegexOption.IGNORE_CASE)
    private val at = Regex("""@($NUM),($NUM)""")
    // Google place links: !3d!4d is the place itself, @ only the map's center
    private val pin = Regex("""!3d($NUM)!4d($NUM)""")
    private val search = Regex("""/maps/search/($NUM)(?:,|%2C)(?:\s|\+|%20)*($NUM)""", RegexOption.IGNORE_CASE)
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
        val pins = pin.containsMatchIn(text)
        for (re in listOf(geo, latLonParams, q, pin, search, at)) {
            if (re === at && pins) continue
            for (m in re.findAll(text)) add(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
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
            ?.takeUnless { it.equals("Dropped pin", true) }
        return if (found.size == 1 && label != null && label.length <= 60)
            listOf(found.values.first().copy(name = label.removeSuffix(":")))
        else found.values.toList()
    }

    // Google Maps short links (the Share button), resolved by following redirects
    private val short = Regex("""https?://(?:maps\.app\.goo\.gl|goo\.gl/maps|g\.co/kgs|share\.google)/[\w\-./?=&%]+""", RegexOption.IGNORE_CASE)
    private val googleHost = Regex("""(?:[\w-]+\.)*(?:google\.[a-z]{2,3}(?:\.[a-z]{2})?|goo\.gl|g\.co|share\.google)""", RegexOption.IGNORE_CASE)
    private const val MAX_HOPS = 6

    /** Google Maps short links in shared text. */
    fun shortLinks(text: String): List<String> = short.findAll(text).map { it.value.trimEnd('.', ',', ')') }.distinct().toList()

    /**
     * The long link a short link leads to, following redirects with [location]
     * (one request: the Location header, or null if there is none). Only Google
     * hosts are followed; a consent page's continue= link is taken as is.
     */
    fun resolveShort(link: String, location: (String) -> String?): String? {
        var url = link
        repeat(MAX_HOPS) {
            val host = Regex("""^https://([^/?#:]+)""").find(url)?.groupValues?.get(1) ?: return null
            if (!googleHost.matches(host)) return null
            if (host.startsWith("consent.", true)) {
                val cont = Regex("""[?&]continue=([^&]+)""").find(url)?.groupValues?.get(1) ?: return null
                return java.net.URLDecoder.decode(cont, "UTF-8")
            }
            val next = location(url) ?: return if (url == link) null else url
            url = if (next.startsWith("/")) "https://$host$next" else next
        }
        return url
    }

    /** The place a Google link names when it holds no position (q=Name, or /maps/place/Name/). */
    fun placeName(url: String): String? {
        val raw = Regex("""[?&]q=([^&]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/maps/place/([^/@?]+)""").find(url)?.groupValues?.get(1) ?: return null
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private val tag = Regex("""<(wpt|rtept|trkpt)\b([^>]*?)(/>|>(.*?)</\1>)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val attLat = Regex("""\blat\s*=\s*["']($NUM)["']""")
    private val attLon = Regex("""\blon\s*=\s*["']($NUM)["']""")
    private val nameTag = Regex("""<name>\s*(?:<!\[CDATA\[)?(.*?)(?:]]>)?\s*</name>""", RegexOption.DOT_MATCHES_ALL)
    private val trkseg = Regex("""<trk\b.*?</trk>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&").trim()

    /**
     * Up to [limit] bytes of a stream as UTF-8 text. A gzipped stream (i-Boating's .gpxgz,
     * or .gpx.gz) is unzipped first, by its magic bytes, whatever the file is called.
     */
    fun readText(input: java.io.InputStream, limit: Int = 20_000_000): String {
        val b = java.io.BufferedInputStream(input)
        b.mark(2)
        val gz = b.read() == 0x1f && b.read() == 0x8b
        b.reset()
        val s = if (gz) java.util.zip.GZIPInputStream(b) else b
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (out.size() < limit) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    /**
     * The line a GPX file draws, for Import GPX: the first route with 2 or more points, else
     * every track point in file order (all segments), else the waypoints in order. The name
     * is the route's or track's own when it has one. Null when there is no line.
     */
    fun gpxLine(xml: String): Pair<String?, List<LatLon>>? {
        if (!xml.contains("<gpx", ignoreCase = true)) return null
        fun pts(body: String, kind: String) = tag.findAll(body).filter { it.groupValues[1].equals(kind, true) }.mapNotNull { m ->
            val lat = attLat.find(m.groupValues[2])?.groupValues?.get(1)?.toDouble()
            val lon = attLon.find(m.groupValues[2])?.groupValues?.get(1)?.toDouble()
            if (lat != null && lon != null && valid(lat, lon)) LatLon(lat, lon) else null
        }.toList()
        fun nameOf(block: String, child: String) = nameTag.find(block.substringBefore("<$child"))?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.isNotEmpty() }
        val noTracks = trkseg.replace(xml, "")
        for (r in rteBlock.findAll(noTracks)) {
            val line = pts(r.value, "rtept")
            if (line.size >= 2) return nameOf(r.value, "rtept") to line
        }
        val tracks = trkseg.findAll(xml).toList()
        val line = tracks.flatMap { pts(it.value, "trkpt") }
        if (line.size >= 2) return tracks.firstOrNull()?.let { nameOf(it.value, "trkseg") } to line
        val w = pts(noTracks.replace(rteBlock, ""), "wpt")
        return if (w.size >= 2) null to w else null
    }

    private val rteBlock = Regex("""<rte\b.*?</rte>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

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
        val rteDesc = Regex("""<rte\b[^>]*>.*?<desc>\s*(?:<!\[CDATA\[)?(.*?)(?:]]>)?\s*</desc>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(xml)?.groupValues?.get(1)?.let(::unescape).orEmpty()
        val trip = Regex("""Trip points:\s*([0-9.,;\s\-]+)""").find(rteDesc)?.groupValues?.get(1)?.split(';')?.mapNotNull { part ->
            val bits = part.trim().split(',').map { it.trim() }
            if (bits.size != 2) null else {
                val lat = bits[0].toDoubleOrNull()
                val lon = bits[1].toDoubleOrNull()
                if (lat != null && lon != null && valid(lat, lon)) LatLon(lat, lon) else null
            }
        }.orEmpty()
        val summary = rteDesc.substringBefore("Trip points:").trim()
        return SharedGpx(wpts, rte, ends, trip, summary)
    }
}
