package com.jnyer27.noaaroute

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.time.LocalDate
import java.time.ZoneId

/**
 * One day of predicted tide at a station, midnight to midnight on the phone's clock: the
 * curve, each high and low with its height, the 0 ft line (chart datum) when the water goes
 * near it, a line at the current time, and on a check leg a red dotted line at the tide
 * the boat needs there ([needFt]).
 */
class TideCurveView(ctx: Context, night: Boolean) : View(ctx) {
    private var series: TideSeries? = null
    private var from = 0L
    private var to = 0L
    private var now = 0L
    private var zone: ZoneId = ZoneId.systemDefault()
    private var needFt: Double? = null

    private val density = ctx.resources.displayMetrics.density
    private val ink = if (night) 0xFFEEEEEE.toInt() else 0xFF212121.toInt()
    private val faint = if (night) 0x66FFFFFF else 0x33000000
    private val teal = if (night) 0xFF4DD0E1.toInt() else 0xFF00838F.toInt()
    private val fillColor = if (night) 0x334DD0E1 else 0x2200838F
    private val nowColor = if (night) 0xFFFFAB91.toInt() else 0xFFBF360C.toInt()
    private val needColor = if (night) 0xFFEF5350.toInt() else 0xFFD32F2F.toInt()

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = teal }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fillColor }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f * density; color = faint }
    private val nowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * density; color = nowColor }
    private val needLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = needColor
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }
    private val needLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = needColor; textSize = 11f * density; textAlign = Paint.Align.RIGHT; isFakeBoldText = true }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = teal }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 11f * density; textAlign = Paint.Align.CENTER }

    fun show(s: TideSeries, date: LocalDate, zone: ZoneId, nowMinute: Long, need: Double? = null) {
        series = s
        needFt = need
        this.zone = zone
        from = date.atStartOfDay(zone).toEpochSecond() / 60
        to = date.plusDays(1).atStartOfDay(zone).toEpochSecond() / 60
        now = nowMinute
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = series ?: return
        val step = 10L
        val samples = (from..to step step).mapNotNull { m -> s.height(m)?.let { m to it } }
        if (samples.size < 2) return
        val day = s.extremes.filter { it.minute in from until to }
        var lo = minOf(samples.minOf { it.second }, 0.0)
        var hi = samples.maxOf { it.second }
        needFt?.let { lo = minOf(lo, it); hi = maxOf(hi, it) }
        if (hi - lo < 0.5) hi = lo + 0.5
        val pad = (hi - lo) * 0.08
        lo -= pad; hi += pad
        val left = 4f * density
        val right = width - 4f * density
        val top = 18f * density // room for the high labels
        val bottom = height - 30f * density // low labels, then hours
        fun x(m: Long) = left + (right - left) * (m - from).toFloat() / (to - from)
        fun y(f: Double) = (bottom - (bottom - top) * ((f - lo) / (hi - lo))).toFloat()

        // Hours: 6 am, noon, 6 pm
        label.textAlign = Paint.Align.CENTER
        for ((h, name) in listOf(6 to "6 am", 12 to "noon", 18 to "6 pm")) {
            val m = from + h * 60
            c.drawLine(x(m), top, x(m), bottom, grid)
            c.drawText(name, x(m), height - 4f * density, label)
        }
        // Chart datum
        if (lo < 0.0) {
            c.drawLine(left, y(0.0), right, y(0.0), grid)
            label.textAlign = Paint.Align.LEFT
            c.drawText("0 ft", left, y(0.0) - 3f * density, label)
            label.textAlign = Paint.Align.CENTER
        }
        val path = Path()
        samples.forEachIndexed { k, (m, f) -> if (k == 0) path.moveTo(x(m), y(f)) else path.lineTo(x(m), y(f)) }
        val under = Path(path).apply {
            lineTo(x(samples.last().first), bottom); lineTo(x(samples.first().first), bottom); close()
        }
        c.drawPath(under, area)
        c.drawPath(path, line)
        for (e in day) {
            val ex = x(e.minute)
            val ey = y(e.feet)
            c.drawCircle(ex, ey, 3.5f * density, dot)
            val text = String.format(java.util.Locale.US, "%.1f ft %s", e.feet, Tides.clock(e.minute, zone))
            val w = label.measureText(text) / 2
            val cx = ex.coerceIn(left + w, right - w)
            c.drawText(text, cx, if (e.high) ey - 7f * density else ey + 15f * density, label)
        }
        needFt?.let { n ->
            val ny = y(n)
            c.drawPath(Path().apply { moveTo(left, ny); lineTo(right, ny) }, needLine)
            c.drawText(String.format(java.util.Locale.US, "need %.1f ft", n), right, ny - 4f * density, needLabel)
        }
        if (now in from..to) c.drawLine(x(now), top - 6f * density, x(now), bottom, nowLine)
    }
}
