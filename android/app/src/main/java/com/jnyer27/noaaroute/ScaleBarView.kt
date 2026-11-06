package com.jnyer27.noaaroute

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The chart's distance scale: a bar with end ticks and its length ("1 nm", "500 ft")
 * above it, on the chart panel color. [show] sets it from MapLibre's meters per pixel
 * at the map center (ChartTiles.scaleBar picks the round distance).
 */
class ScaleBarView(ctx: Context, private val chrome: ChartTiles.ChartChrome) : View(ctx) {
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = chrome.text
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.SQUARE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = chrome.text
        textSize = 13f * resources.displayMetrics.scaledDensity
    }
    private val bg = Paint().apply { color = chrome.panel }
    private var scale: ChartTiles.Scale? = null
    private val pad = 6f * density
    private val maxDp = 110.0

    fun show(metersPerDp: Double) {
        val s = ChartTiles.scaleBar(metersPerDp, maxDp)
        if (s?.label == scale?.label && s?.dp == scale?.dp) return
        scale = s
        visibility = if (s == null) GONE else VISIBLE
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (maxDp * density + 2 * pad).toInt()
        val h = (text.textSize + 10f * density + 2 * pad).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val s = scale ?: return
        val len = (s.dp * density).toFloat()
        val w = len + 2 * pad
        val labelW = text.measureText(s.label)
        canvas.drawRect(0f, 0f, maxOf(w, labelW + 2 * pad), height.toFloat(), bg)
        val y = height - pad - line.strokeWidth
        val tick = 6f * density
        canvas.drawLine(pad, y, pad + len, y, line)
        canvas.drawLine(pad, y - tick, pad, y, line)
        canvas.drawLine(pad + len, y - tick, pad + len, y, line)
        canvas.drawText(s.label, pad, pad + text.textSize - text.descent() / 2, text)
    }
}
