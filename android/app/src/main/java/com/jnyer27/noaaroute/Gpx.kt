package com.jnyer27.noaaroute

import java.util.Locale

/** GPX and text output, matching noaa-route.py. */
object Gpx {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun legDesc(l: Leg): String {
        var d = String.format(Locale.US, "Next: %03.0f T, %.2f nm", l.bearing, l.distM / Planner.NM)
        if (l.shoalM > 0) {
            d += String.format(Locale.US, ", %.0f m in 0-6 ft charted water", l.shoalM)
            l.lowM?.let { d += String.format(Locale.US, " (shallowest sounding nearby %.1f ft)", it / Pack.FT) }
        }
        return d
    }

    fun write(r: Route): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"NOAA Route (Android)\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("<rte>\n<name>${esc(r.name)}</name>\n")
        sb.append("<desc>${esc(String.format(Locale.US, "%.1f nm, planned for %s ft depth at MLLW from NOAA ENC. Not for navigation.", r.totalNm, num(r.depthFt)))}</desc>\n")
        for ((k, p) in r.points.withIndex()) {
            val label = when (k) {
                0 -> "Start"
                r.points.size - 1 -> "End"
                else -> String.format(Locale.US, "WP%02d", k)
            }
            sb.append(String.format(Locale.US, "<rtept lat=\"%.6f\" lon=\"%.6f\"><name>%s</name>", p.lat, p.lon, label))
            if (k < r.legs.size) sb.append("<desc>${esc(legDesc(r.legs[k]))}</desc>")
            sb.append("</rtept>\n")
        }
        sb.append("</rte>\n</gpx>\n")
        return sb.toString()
    }

    fun report(r: Route): String {
        val sb = StringBuilder()
        val air = if (r.airFt != null) "air draft ${num(r.airFt)} ft" else "air draft not checked"
        sb.append(String.format(Locale.US, "%s: %.1f nm, %d points\ndepth %s ft (MLLW), %s, grid %.0f m%s\n",
            r.name, r.totalNm, r.points.size, num(r.depthFt), air, r.resM,
            r.guideM?.let { String.format(Locale.US, " (long trip, guided by a %.0f m overview)", it) } ?: ""))
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
