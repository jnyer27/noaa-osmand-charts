package com.jnyer27.noaaroute

import java.util.Locale

/** GPX and text output, matching noaa-route.py. */
object Gpx {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun legDesc(l: Leg): String {
        var d = String.format(Locale.US, "Next: %03.0f T, %.2f nm", l.bearing, l.distM / Planner.NM)
        shoalNote(l)?.let { d += ", $it" }
        for (n in l.notes) d += "; $n"
        return d
    }

    private fun shoalNote(l: Leg): String? {
        if (l.shoalM <= 0) return null
        var d = String.format(Locale.US, "%.0f m in 0-6 ft charted water", l.shoalM)
        l.lowM?.let { d += String.format(Locale.US, " (shallowest sounding nearby %.1f ft)", it / Pack.FT) }
        return d
    }

    /** A leg to look at: 0-6 ft water, or a planner note asking for a check. */
    fun flagged(l: Leg) = l.shoalM > 0 || l.notes.any { it.contains("check") }

    // Check markers: a standard symbol and type for any app, OsmAnd's icon in its own extension
    private const val CHECK_SYM = "<sym>Danger Area</sym><type>Check</type>"
    /** A run longer than this also gets a marker where it ends. */
    const val CHECK_END_NM = 0.5
    private const val CHECK_OSMAND = "<extensions><osmand:icon>special_symbol_exclamation_mark</osmand:icon>" +
        "<osmand:background>triangle</osmand:background><osmand:color>#FFE65100</osmand:color></extensions>"

    /** Runs of consecutive flagged legs, as first and last leg index. */
    fun checkRuns(r: Route): List<IntRange> {
        val runs = ArrayList<IntRange>()
        var k = 0
        while (k < r.legs.size) {
            if (!flagged(r.legs[k])) { k++; continue }
            var j = k
            while (j + 1 < r.legs.size && flagged(r.legs[j + 1])) j++
            runs.add(k..j)
            k = j + 1
        }
        return runs
    }

    private fun checkText(r: Route, run: IntRange): String {
        val notes = run.map { k ->
            val l = r.legs[k]
            "leg ${k + 1}: " + (listOfNotNull(shoalNote(l)) + l.notes).joinToString("; ")
        }
        return notes.joinToString("; ") + ". Check the chart and the water."
    }

    fun write(r: Route, datum: String = "MLLW"): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"NOAA Route (Android)\" xmlns=\"http://www.topografix.com/GPX/1/1\" " +
            "xmlns:osmand=\"https://osmand.net\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
            "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        // A check marker where each run of flagged legs starts, and where a long one ends (waypoints come before the route)
        fun marker(p: LatLon, name: String, text: String) {
            val t = esc(text)
            sb.append(String.format(Locale.US, "<wpt lat=\"%.6f\" lon=\"%.6f\"><name>%s</name>", p.lat, p.lon, esc(name)))
            sb.append("<cmt>$t</cmt><desc>$t</desc>$CHECK_SYM$CHECK_OSMAND</wpt>\n")
        }
        for (run in checkRuns(r)) {
            val legs = if (run.first == run.last) "leg ${run.first + 1}" else "legs ${run.first + 1}-${run.last + 1}"
            val nm = run.sumOf { r.legs[it].distM } / Planner.NM
            marker(r.points[run.first], String.format(Locale.US, "Check: %s (%.2f nm)", legs, nm), checkText(r, run))
            if (nm > CHECK_END_NM) marker(r.points[run.last + 1], "Check ends: $legs", "End of the stretch to check ($legs).")
        }
        sb.append("<rte>\n<name>${esc(r.name)}</name>\n")
        sb.append("<desc>${esc(String.format(Locale.US, "%.1f nm, planned for %s ft depth at %s from NOAA ENC. Not for navigation.", r.totalNm, num(r.depthFt), datum))}</desc>\n")
        for ((k, p) in r.points.withIndex()) {
            var label = when (k) {
                0 -> "Start"
                r.points.size - 1 -> "End"
                else -> String.format(Locale.US, "WP%02d", k)
            }
            val leg = r.legs.getOrNull(k)
            val check = leg != null && flagged(leg)
            if (check) label += " check"
            sb.append(String.format(Locale.US, "<rtept lat=\"%.6f\" lon=\"%.6f\"><name>%s</name>", p.lat, p.lon, label))
            if (leg != null) {
                val d = esc(legDesc(leg))
                if (check) sb.append("<cmt>$d</cmt>")
                sb.append("<desc>$d</desc>")
                if (check) sb.append(CHECK_SYM)
            }
            sb.append("</rtept>\n")
        }
        sb.append("</rte>\n</gpx>\n")
        return sb.toString()
    }

    /**
     * The route summary shown under Plan route. [trip]: lines naming the start, via points
     * and destination; [after]: lines after the settings (charts used, when planned);
     * [bufM]: the hazard buffer, if it should be shown; [datum]: what depths are charted to.
     */
    fun report(r: Route, trip: List<String> = emptyList(), after: List<String> = emptyList(), bufM: Double? = null, datum: String = "MLLW"): String {
        val sb = StringBuilder()
        val air = if (r.airFt != null) "air draft ${num(r.airFt)} ft" else "air draft not checked"
        sb.append(String.format(Locale.US, "%s: %.1f nm, %d points\n", r.name, r.totalNm, r.points.size))
        for (t in trip) sb.append(t).append('\n')
        sb.append(String.format(Locale.US, "depth %s ft (%s), %s%s, grid %.0f m%s\n",
            num(r.depthFt), datum, air, bufM?.let { String.format(Locale.US, ", hazard buffer %.0f m", it) } ?: "", r.resM,
            r.guideM?.let { String.format(Locale.US, " (long trip, guided by a %.0f m overview)", it) } ?: ""))
        for (t in after) sb.append(t).append('\n')
        for (m in r.messages) sb.append(m).append('\n')
        sb.append("\nLeg Course  Dist nm  Notes\n")
        for ((k, l) in r.legs.withIndex()) {
            val notes = ArrayList<String>()
            if (l.shoalM > 0) {
                var s = String.format(Locale.US, "%.0f m in 0-6 ft", l.shoalM)
                s += l.lowM?.let { String.format(Locale.US, ", shallowest sounding within 100 m %.1f ft", it / Pack.FT) }
                    ?: ", no soundings within 100 m"
                notes.add(s)
            }
            notes.addAll(l.notes)
            sb.append(String.format(Locale.US, "%3d  %4.0fT  %6.2f  %s\n", k + 1, l.bearing, l.distM / Planner.NM, notes.joinToString("; ")))
        }
        sb.append("\nNot for navigation. Verify every leg against a current chart.\n")
        return sb.toString()
    }

    fun num(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
}
