package com.jnyer27.noaaroute

import java.util.Locale

/**
 * Press and hold on View map, "Depth and tide here": what the chart says at the spot
 * ([ChartDepthRoute.spot]) in words, and the shallowest charted depth near it, which the tide
 * graph's red dotted line is drawn for.
 */
object SpotReport {
    /**
     * [lines] to show; [lowFt] the shallowest charted depth used for the tide line, [basis] what
     * it is; [estimateFt] the charted depth at the spot estimated from the soundings around it
     * (null when there are too few).
     */
    class Analysis(val lines: List<String>, val lowFt: Double?, val basis: String?, val estimateFt: Double? = null)

    /** Soundings used for the estimate: the nearest this many, within the search radius. */
    const val ESTIMATE_SOUNDINGS = 8
    /** At least this many soundings, and the nearest within [ESTIMATE_NEAR_M], to estimate at all. */
    const val ESTIMATE_MIN = 3
    const val ESTIMATE_NEAR_M = 75.0
    /** A sounding this close is the depth there. */
    private const val ON_SOUNDING_M = 5.0

    /**
     * Charted depth at the spot by inverse distance weighting (weight 1/d squared) of the
     * nearest [ESTIMATE_SOUNDINGS] soundings, clamped to the range of the depth area the spot
     * is in. Null with fewer than [ESTIMATE_MIN] soundings or none within [ESTIMATE_NEAR_M].
     * Soundings are sparse and the bottom between them is unknown: an estimate, not a depth.
     */
    fun estimate(spot: ChartDepthRoute.Spot): Double? {
        val near = spot.soundings.sortedBy { it.second }.take(ESTIMATE_SOUNDINGS)
        if (near.isEmpty()) return null
        if (near[0].second <= ON_SOUNDING_M) return clamp(near[0].first.ft.toDouble(), spot)
        if (near.size < ESTIMATE_MIN || near[0].second > ESTIMATE_NEAR_M) return null
        var w = 0.0
        var sum = 0.0
        for ((s, d) in near) {
            val k = 1.0 / (d * d)
            w += k
            sum += k * s.ft
        }
        return clamp(sum / w, spot)
    }

    /**
     * The estimate with the tide: the predicted depth at the spot now, and the times today the
     * estimate plus the tide reaches [depthFt]. Null when the predictions do not cover now.
     */
    fun estimateWithTide(est: Double, s: TideSeries, minute: Long, date: java.time.LocalDate, zone: java.time.ZoneId, depthFt: Double): String? {
        val h = s.height(minute) ?: return null
        val d = ChartTiles.depthText(depthFt)
        val now = String.format(Locale.US, "Predicted depth here now: about %.1f ft (the estimated %.1f ft %s %.1f ft of tide).",
            est + h, est, if (h < 0) "minus" else "plus", kotlin.math.abs(h))
        val need = depthFt - est
        val times = Tides.aboveText(Tides.above(s, date, zone, need), date, zone)
        return now + " " + when {
            times == "all day" -> "By the estimate, your $d ft is there all day."
            times != null -> "By the estimate, your $d ft is there today $times."
            else -> "By the estimate, the predicted tide does not give your $d ft here today."
        }
    }

    private fun clamp(ft: Double, spot: ChartDepthRoute.Spot): Double {
        val area = spot.areas.filter { !it.dredged }.maxByOrNull { it.d1 } ?: return ft
        return ft.coerceIn(area.d1.toDouble(), maxOf(area.d1, area.d2).toDouble())
    }

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
        val est = estimate(spot)
        if (est != null) {
            val n = minOf(spot.soundings.size, ESTIMATE_SOUNDINGS)
            lines.add(String.format(Locale.US, "Estimated charted depth here: about %.1f ft, from the %d nearest soundings " +
                "(closer ones count more%s). The bottom between soundings is not charted.", est, n,
                if (depare != null) ", kept within the ${depare.d1} to ${depare.d2} ft depth area" else ""))
        } else if (shallow != null) {
            lines.add("Too few soundings close by to estimate the depth here.")
        }
        return Analysis(lines, low, basis, est)
    }

    private fun verdict(d1: Int, d2: Int, depthFt: Double) = when {
        d2 < depthFt -> ", all under your ${ChartTiles.depthText(depthFt)} ft"
        d1 < depthFt -> ", may be under your ${ChartTiles.depthText(depthFt)} ft"
        else -> ""
    }

    private fun dist(m: Double) = String.format(Locale.US, "%.0f m", m)
}
