package com.jnyer27.noaaroute

import java.util.Locale

/** GPX and text output, matching noaa-route.py. */
object Gpx {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun legDesc(l: Leg, depthFt: Double): String {
        var d = String.format(Locale.US, "Next: %03.0f T, %.2f nm", l.bearing, l.distM / Planner.NM)
        shoalNote(l, depthFt)?.let { d += ", $it" }
        for (n in l.notes) d += "; $n"
        return d
    }

    /**
     * Length in water whose charted depth range reaches below the safe depth (NOAA's 0-6 ft
     * band for a 3 or 5 ft boat, the 6-12 ft band for an 8 ft one), so the label names the
     * safe depth rather than a fixed band.
     */
    private fun shallowLen(l: Leg, depthFt: Double) =
        String.format(Locale.US, "%.0f m where charted depth may be under %s ft", l.shoalM, num(depthFt))

    /** The shallowest nearby sounding, and whether the chart's whole-foot label is under the safe depth. */
    private fun sounding(lowM: Double, depthFt: Double): String {
        val ft = lowM / Pack.FT
        val under = Planner.soundingShallow(lowM, depthFt)
        return String.format(Locale.US, "%.1f ft", ft) + if (under) ", under your ${num(depthFt)} ft" else ""
    }

    /**
     * A fairway stored as a wide depth range (0 ft to about 12 ft) straddles a 2 ft safe
     * depth even when the soundings on it are 9 to 13 ft. Say that, instead of "under 2 ft".
     * A sounding the chart labels under the safe depth keeps the under-your-depth wording.
     */
    private fun depthAreaDeeper(lowM: Double?, depthFt: Double) =
        lowM != null && Planner.printedFeet(lowM) >= depthFt + 5.0 - 1e-6

    private fun shoalNote(l: Leg, depthFt: Double): String? {
        if (l.shoalM <= 0) return null
        if (depthAreaDeeper(l.lowM, depthFt))
            return String.format(Locale.US, "%.0f m where the depth area may include shallower water (shallowest sounding nearby %.1f ft, deeper than your %s ft)",
                l.shoalM, l.lowM!! / Pack.FT, num(depthFt))
        var d = shallowLen(l, depthFt)
        l.lowM?.let { d += " (shallowest sounding nearby ${sounding(it, depthFt)})" }
        return d
    }

    /**
     * Entry restricted or entry prohibited is a limit on the boat, so the leg is a check.
     * "entry may be prohibited" is a security zone: that note already ends in "check".
     * Anchoring, fishing and no-wake rules stay information.
     */
    fun navigationLimit(note: String) =
        (note.contains("entry restricted") || note.contains("entry prohibited")) && !note.contains("may be prohibited")

    /** A leg to look at: shoal water, a note asking for a check, or a navigation limit. */
    fun flagged(l: Leg) = l.shoalM > 0 || l.notes.any { it.contains("check") || navigationLimit(it) }

    /** One leg's sentence, the same text the GPX route point carries. */
    fun legText(r: Route, k: Int) = legDesc(r.legs[k], r.depthFt)

    const val MARK_SHOAL = 1
    const val MARK_BRIDGE = 2
    const val MARK_CABLE = 3
    const val MARK_RESTRICTED = 4
    const val MARK_CHECK = 5

    /** Which colour a leg row takes. 0 is ordinary text. Restricted water wins, then shoal water. */
    fun legMark(l: Leg): Int {
        val notes = l.notes
        if (notes.any { navigationLimit(it) }) return MARK_RESTRICTED
        if (l.shoalM > 0) return MARK_SHOAL
        if (notes.any { it.contains("cable") }) return MARK_CABLE
        if (notes.any { it.contains("bridge") || it.contains("span (vertical clearance") || it.contains("span (clearance") }) return MARK_BRIDGE
        if (flagged(l)) return MARK_CHECK
        return 0
    }

    /**
     * What to read before the leg table: how many legs to check, then shoal water,
     * bridges, cables and restricted areas. The closing line is here so it is not
     * only the last line of a long table.
     */
    fun warnings(r: Route): List<String> {
        val lines = ArrayList<String>()
        val runs = checkRuns(r)
        val n = runs.sumOf { it.last - it.first + 1 }
        if (n == 0) lines.add("No leg is flagged. Still verify every leg against a current chart before you go.")
        else lines.add("$n ${if (n == 1) "leg" else "legs"} to check, in ${runs.size} ${if (runs.size == 1) "stretch" else "stretches"}. Look at each one on a current chart before you go.")
        val shoal = r.legs.count { it.shoalM > 0 }
        if (shoal > 0) lines.add("$shoal ${if (shoal == 1) "leg is" else "legs are"} in water that may be under your ${num(r.depthFt)} ft.")
        fun named(pred: (String) -> Boolean) = r.legs.flatMap { it.notes }.filter(pred).distinct()
        val bridges = named { it.contains("bridge") || it.contains("span (vertical clearance") || it.contains("span (clearance") }
        if (bridges.isNotEmpty()) lines.add("${bridges.size} ${if (bridges.size == 1) "bridge" else "bridges"}: ${bridges.joinToString("; ")}")
        val cables = named { it.contains("cable") }
        if (cables.isNotEmpty()) lines.add("${cables.size} ${if (cables.size == 1) "cable" else "cables"}: ${cables.joinToString("; ")}")
        val areas = named { navigationLimit(it) || it.contains("restricted area") || it.contains("security zone") || it.contains("anchoring") || it.contains("fishing") || it.contains("no wake") }
        if (areas.isNotEmpty()) lines.add("${areas.size} restricted ${if (areas.size == 1) "area" else "areas"}: ${areas.joinToString("; ")}")
        lines.add("Not for navigation. Verify every leg against a current chart.")
        return lines
    }

    // Check markers: a standard symbol and type for any app, OsmAnd's icon in its own extension.
    // The end of a stretch gets a green check mark instead of the orange warning.
    private const val CHECK_SYM = "<sym>Danger Area</sym><type>Check</type>"
    private const val CHECK_END_SYM = "<sym>Flag, Green</sym><type>Check</type>"
    private const val CHECK_END_OSMAND = "<extensions><osmand:icon>special_symbol_check_mark</osmand:icon>" +
        "<osmand:background>circle</osmand:background><osmand:color>#FF2E7D32</osmand:color></extensions>"
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
            "leg ${k + 1}: " + (listOfNotNull(shoalNote(l, r.depthFt)) + l.notes).joinToString("; ")
        }
        return notes.joinToString("; ") + ". Check the chart and the water."
    }

    /** The route as GPX; flagged route points are named "check", and [checks] adds the check markers and their symbols. [trip] is the start, via points and destination, written so opening the file can set them again. */
    fun write(r: Route, datum: String = "MLLW", checks: Boolean = true, trip: List<LatLon> = emptyList()): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"NOAA Route (Android)\" xmlns=\"http://www.topografix.com/GPX/1/1\" " +
            "xmlns:osmand=\"https://osmand.net\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
            "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        // Check markers where each run of flagged legs starts and ends (waypoints come before the route)
        fun marker(p: LatLon, name: String, text: String, end: Boolean = false) {
            val t = esc(text)
            sb.append(String.format(Locale.US, "<wpt lat=\"%.6f\" lon=\"%.6f\"><name>%s</name>", p.lat, p.lon, esc(name)))
            sb.append("<cmt>$t</cmt><desc>$t</desc>")
            sb.append(if (end) CHECK_END_SYM + CHECK_END_OSMAND else CHECK_SYM + CHECK_OSMAND).append("</wpt>\n")
        }
        if (checks) for (run in checkRuns(r)) {
            val legs = if (run.first == run.last) "leg ${run.first + 1}" else "legs ${run.first + 1}-${run.last + 1}"
            val nm = run.sumOf { r.legs[it].distM } / Planner.NM
            marker(r.points[run.first], String.format(Locale.US, "Check: %s (%.2f nm)", legs, nm), checkText(r, run))
            marker(r.points[run.last + 1], "Check ends: $legs", "End of the stretch to check ($legs).", end = true)
        }
        sb.append("<rte>\n<name>${esc(r.name)}</name>\n")
        val clearance = if (r.airFt == null) " Bridges and cables were not checked (no air draft)." else ""
        val head = String.format(Locale.US, "%.1f nm, planned for %s ft depth at %s from NOAA ENC.%s", r.totalNm, num(r.depthFt), datum, clearance)
        val warn = warnings(r).joinToString(" ")
        val tripLine = if (trip.size >= 2) " Trip points: " + trip.joinToString("; ") { String.format(Locale.US, "%.5f,%.5f", it.lat, it.lon) } else ""
        sb.append("<desc>${esc("$head $warn$tripLine")}</desc>\n")
        for ((k, p) in r.points.withIndex()) {
            var label = when (k) {
                0 -> "Start"
                r.points.size - 1 -> "End"
                else -> String.format(Locale.US, "WP%02d", k)
            }
            val leg = r.legs.getOrNull(k)
            // Named in any case: the one warning every chart app shows, and it adds nothing to
            // the map. The markers' checkbox only adds the symbol and comment.
            val flaggedLeg = leg != null && flagged(leg)
            if (flaggedLeg) label += " check"
            val check = checks && flaggedLeg
            sb.append(String.format(Locale.US, "<rtept lat=\"%.6f\" lon=\"%.6f\"><name>%s</name>", p.lat, p.lon, label))
            if (leg != null) {
                val d = esc(legDesc(leg, r.depthFt))
                if (check) sb.append("<cmt>$d</cmt>")
                sb.append("<desc>$d</desc>")
                if (check) sb.append(CHECK_SYM)
            }
            sb.append("</rtept>\n")
        }
        sb.append("</rte>\n</gpx>\n")
        return sb.toString()
    }

    /** A coloured stretch of [ShownReport.text]. [mark] is one of the MARK_ constants. */
    class ReportSpan(val start: Int, val end: Int, val mark: Int)

    /** The report text and the stretches to colour (warning lines and leg rows). */
    class ShownReport(val text: String, val spans: List<ReportSpan>)

    /** The report as plain text. [saved] is the file sentence, placed after the warning block. */
    fun report(r: Route, trip: List<String> = emptyList(), after: List<String> = emptyList(), bufM: Double? = null, datum: String = "MLLW", saved: String? = null) =
        shown(r, trip, after, bufM, datum, saved).text

    /**
     * The route summary shown under Plan route. The warning block comes first (after the
     * air-draft line, when bridges were not checked). [trip]: lines naming the start, via
     * points and destination; [after]: lines after the settings (charts used, when planned);
     * [bufM]: the hazard buffer, if it should be shown; [datum]: what depths are charted to;
     * [saved]: where the GPX file went, after the warnings and before the leg table.
     */
    fun shown(r: Route, trip: List<String> = emptyList(), after: List<String> = emptyList(), bufM: Double? = null, datum: String = "MLLW", saved: String? = null): ShownReport {
        val sb = StringBuilder()
        val spans = ArrayList<ReportSpan>()
        fun line(text: String, mark: Int = 0) {
            val start = sb.length
            sb.append(text).append('\n')
            if (mark != 0) spans.add(ReportSpan(start, sb.length, mark))
        }
        if (r.airFt == null) {
            line("Bridges and cables were not checked. No air draft was given, so a low bridge or overhead cable was not avoided.")
        }
        for (w in warnings(r)) {
            val mark = when {
                w.startsWith("No leg is flagged") || w.contains("legs to check") || w.contains("leg to check") -> MARK_CHECK
                w.contains("may be under your") -> MARK_SHOAL
                w.startsWith("1 bridge") || w.contains(" bridges:") -> MARK_BRIDGE
                w.startsWith("1 cable") || w.contains(" cables:") -> MARK_CABLE
                w.contains("restricted") -> MARK_RESTRICTED
                else -> 0
            }
            line(w, mark)
        }
        if (saved != null) line(saved)
        line("")
        val air = if (r.airFt != null) "air draft ${num(r.airFt)} ft" else "air draft not checked"
        line(String.format(Locale.US, "%s: %.1f nm, %d points", r.name, r.totalNm, r.points.size))
        for (t in trip) line(t)
        line(String.format(Locale.US, "depth %s ft (%s), %s%s, grid %.0f m%s",
            num(r.depthFt), datum, air, bufM?.let { String.format(Locale.US, ", hazard buffer %.0f m", it) } ?: "", r.resM,
            r.guideM?.let { String.format(Locale.US, " (long trip, guided by a %.0f m overview)", it) } ?: ""))
        for (t in after) line(t)
        for (m in r.messages) line(m)
        line("")
        line("Leg Course  Dist nm  Notes")
        for ((k, l) in r.legs.withIndex()) {
            val notes = ArrayList<String>()
            if (l.shoalM > 0) {
                val s = if (depthAreaDeeper(l.lowM, r.depthFt))
                    String.format(Locale.US, "%.0f m where the depth area may include shallower water, shallowest sounding within 100 m %.1f ft, deeper than your %s ft",
                        l.shoalM, l.lowM!! / Pack.FT, num(r.depthFt))
                else {
                    var t = shallowLen(l, r.depthFt)
                    t += l.lowM?.let { ", shallowest sounding within 100 m ${sounding(it, r.depthFt)}" } ?: ", no soundings within 100 m"
                    t
                }
                notes.add(s)
            }
            notes.addAll(l.notes)
            line(String.format(Locale.US, "%3d  %4.0fT  %6.2f  %s", k + 1, l.bearing, l.distM / Planner.NM, notes.joinToString("; ")), legMark(l))
        }
        line("")
        line("Not for navigation. Verify every leg against a current chart.")
        return ShownReport(sb.toString(), spans)
    }

    fun num(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
}
