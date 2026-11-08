package com.jnyer27.noaaroute

import java.util.Locale

/**
 * Press and hold on View map, "Depth and tide here": what the chart says at the spot
 * ([ChartDepthRoute.spot]) in words, and the shallowest charted depth near it, which the tide
 * graph's red dotted line is drawn for.
 */
object SpotReport {
    /** [lines] to show; [lowFt] the shallowest charted depth used for the tide line, [basis] what it is. */
    class Analysis(val lines: List<String>, val lowFt: Double?, val basis: String?)

    fun analyse(spot: ChartDepthRoute.Spot, depthFt: Double, radiusM: Double = 150.0): Analysis {
        val lines = ArrayList<String>()
        val depare = spot.areas.filter { !it.dredged }.maxByOrNull { it.d1 }
        val dredged = spot.areas.filter { it.dredged }.minByOrNull { it.d1 }
        val d = ChartTiles.depthText(depthFt)
        when {
            depare == null && dredged == null -> lines.add("No charted depth area here (land, or outside the chart's depth areas).")
            depare != null && depare.d2 <= 0 -> lines.add("Depth area: dries (charted ${depare.d1} to ${depare.d2} ft).")
            depare != null -> lines.add("Depth area: ${depare.d1} to ${depare.d2} ft" + verdict(depare.d1, depare.d2, depthFt) + ".")
        }
        if (dredged != null) lines.add("Dredged area: charted ${dredged.d1} ft" +
            (if (dredged.d1 < depthFt) ", under your $d ft" else "") + ".")
        val r = String.format(Locale.US, "%.0f m", radiusM)
        val soundings = spot.soundings
        val shallow = soundings.minWithOrNull(compareBy<Pair<ChartDepth.Sounding, Double>> { it.first.ft }.thenBy { it.second })
        if (shallow == null) lines.add("No sounding charted within $r.")
        else {
            lines.add("Shallowest sounding within $r: ${shallow.first.ft} ft, ${dist(shallow.second)} away" +
                (if (shallow.first.ft < depthFt) ", under your $d ft" else "") + ".")
            val near = soundings.first()
            if (near !== shallow) lines.add("Nearest sounding: ${near.first.ft} ft, ${dist(near.second)} away.")
        }
        val (low, basis) = when {
            shallow != null -> shallow.first.ft.toDouble() to "the ${shallow.first.ft} ft sounding ${dist(shallow.second)} away"
            dredged != null -> dredged.d1.toDouble() to "the dredged area's charted ${dredged.d1} ft"
            depare != null -> depare.d1.toDouble() to "the depth area's shallowest charted limit, ${depare.d1} ft"
            else -> null to null
        }
        return Analysis(lines, low, basis)
    }

    private fun verdict(d1: Int, d2: Int, depthFt: Double) = when {
        d2 < depthFt -> ", all under your ${ChartTiles.depthText(depthFt)} ft"
        d1 < depthFt -> ", may be under your ${ChartTiles.depthText(depthFt)} ft"
        else -> ""
    }

    private fun dist(m: Double) = String.format(Locale.US, "%.0f m", m)
}
