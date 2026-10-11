package com.jnyer27.noaaroute

/** Parses "39.25,-76.48", "39.25 -76.48" or "39.25084° N, 76.48861° W" (as noaa-route.py). */
object Coords {
    private val re = Regex("""\s*([-+]?\d+(?:\.\d+)?)\s*([NS])?\s+([-+]?\d+(?:\.\d+)?)\s*([EW])?\s*""")

    fun parse(s: String): LatLon? {
        val t = s.trim().uppercase().replace("°", " ").replace(",", " ")
        val m = re.matchEntire(t) ?: return null
        var lat = m.groupValues[1].toDouble()
        var lon = m.groupValues[3].toDouble()
        if (m.groupValues[2] == "S") lat = -kotlin.math.abs(lat)
        if (m.groupValues[4] == "W") lon = -kotlin.math.abs(lon)
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return LatLon(lat, lon)
    }

    fun format(p: LatLon) = String.format(java.util.Locale.US, "%.5f, %.5f", p.lat, p.lon)
}
