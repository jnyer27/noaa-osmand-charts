package com.jnyer27.noaaroute

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The NCDS region map: a built-in coastline (assets/coastline.bin, from
 * tools/build_coastline.py), NOAA's region rectangles colored by pack status,
 * and the trip's points. Pinch to zoom, drag to pan, double-tap to zoom in,
 * tap a region to act on it. Works offline.
 */
class RegionMapView(ctx: Context) : View(ctx) {
    enum class Status { NOT_BUILT, AVAILABLE, INSTALLED, UPDATE }

    /** Status of a region code ("04"). */
    var status: (String) -> Status = { Status.NOT_BUILT }
    /** Where to print a region's code: the middle of its pack's charted box, else of its rectangle. */
    var labelAt: (String) -> LatLon? = { null }
    var markers: List<Pair<LatLon, String>> = emptyList()
    /** Whether a region code has a chart map ready for OsmAnd (labelled under its code). */
    var hasChart: (String) -> Boolean = { false }
    var onRegionTap: (NcdsRegion) -> Unit = {}

    companion object {
        // Map units: x = longitude (western Pacific shifted west of -180) * cos(38 N), y = -latitude
        private val K = cos(Math.toRadians(38.0)).toFloat()
        private fun wx(lon: Double) = ((if (lon > 0) lon - 360 else lon) * K).toFloat()
        private fun wy(lat: Double) = (-lat).toFloat()

        @Volatile private var coast: Pair<Path, Path>? = null

        /** Land and water paths in map units, read once per process. */
        private fun coastline(ctx: Context): Pair<Path, Path> = coast ?: synchronized(this) {
            coast ?: run {
                val land = Path()
                val water = Path()
                val b = ByteBuffer.wrap(ctx.assets.open("coastline.bin").use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(6).also { b.get(it) }
                require(String(magic) == "NRCL1\n") { "Bad coastline asset" }
                repeat(b.int) {
                    val path = if (b.get().toInt() == 0) land else water
                    val n = b.int
                    for (k in 0 until n) {
                        val x = wx(b.int / 1e5)
                        val y = wy(b.int / 1e5)
                        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                }
                (land to water).also { coast = it }
            }
        }
    }

    private val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val density = resources.displayMetrics.density
    private val seaColor = if (night) Color.rgb(18, 32, 46) else Color.rgb(205, 226, 243)
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) Color.rgb(48, 48, 44) else Color.rgb(236, 230, 214) }
    private val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = seaColor }
    private val green = Color.rgb(46, 160, 67)
    private val amber = Color.rgb(230, 160, 20)
    private val blue = if (night) Color.rgb(120, 170, 255) else Color.rgb(40, 100, 200)
    private val faint = if (night) Color.argb(90, 200, 200, 200) else Color.argb(90, 90, 90, 90)
    private val text = if (night) Color.rgb(235, 235, 235) else Color.rgb(25, 25, 25)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dash = DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val coastPaths = coastline(ctx)

    // screen = map * scale + offset
    private var scale = 1f
    private var ox = 0f
    private var oy = 0f
    private var fitted = false
    private var fitBox: DoubleArray? = null

    /** Show this lon/lat box (W, S, E, N) once the view has a size. */
    fun fit(box: DoubleArray) {
        fitBox = box
        fitted = false
        invalidate()
    }

    private fun doFit() {
        val b = fitBox ?: doubleArrayOf(-98.0, 24.0, -64.0, 50.0)
        val x0 = wx(b[0]); val x1 = wx(b[2]); val y0 = wy(b[3]); val y1 = wy(b[1])
        scale = min(width / (x1 - x0), height / (y1 - y0)) * 0.9f
        ox = width / 2f - (x0 + x1) / 2 * scale
        oy = height / 2f - (y0 + y1) / 2 * scale
        fitted = true
    }

    /** v kept inside a..b, or the middle when the visible part is narrower than the margins. */
    private fun clamp(v: Float, a: Float, b: Float) = if (a > b) (a + b) / 2 else v.coerceIn(a, b)

    private fun sx(lon: Double) = wx(lon) * scale + ox
    private fun sy(lat: Double) = wy(lat) * scale + oy

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            ox -= dx; oy -= dy
            invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { zoom(2f, e.x, e.y); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val lon = ((e.x - ox) / scale / K).toDouble().let { if (it < -180) it + 360 else it }
            val p = LatLon(-((e.y - oy) / scale).toDouble(), lon)
            // The smallest region under the finger (some sit inside wider ones' rectangles at the edges)
            Regions.ALL.filter { it.contains(p) }.minByOrNull { (it.east - it.west) * (it.north - it.south) }?.let(onRegionTap)
            return true
        }
    })

    private fun zoom(f: Float, fx: Float, fy: Float) {
        val s = (scale * f).coerceIn(2f, 4000f)
        val k = s / scale
        ox = fx - (fx - ox) * k
        oy = fy - (fy - oy) * k
        scale = s
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (ow == 0 || !fitted) doFit()
    }

    override fun onDraw(c: Canvas) {
        if (!fitted && width > 0) doFit()
        c.drawColor(seaColor)
        c.save()
        c.translate(ox, oy)
        c.scale(scale, scale)
        c.drawPath(coastPaths.first, landPaint)
        c.drawPath(coastPaths.second, waterPaint)
        c.restore()

        val view = RectF(0f, 0f, width.toFloat(), height.toFloat())
        // Regions without a pack first, faint, then the ones that have one on top
        val order = Regions.ALL.sortedBy { if (status(it.code) == Status.NOT_BUILT) 0 else 1 }
        for (r in order) {
            val box = RectF(sx(r.west), sy(r.north), sx(r.east), sy(r.south))
            if (!RectF.intersects(box, view)) continue
            val st = status(r.code)
            val col = when (st) { Status.INSTALLED -> green; Status.UPDATE -> amber; Status.AVAILABLE -> blue; Status.NOT_BUILT -> faint }
            stroke.pathEffect = if (st == Status.AVAILABLE) dash else null
            stroke.strokeWidth = (if (st == Status.NOT_BUILT) 1f else 2.5f) * density
            stroke.color = col
            if (st == Status.INSTALLED || st == Status.UPDATE) {
                fill.color = col
                fill.alpha = 50
                c.drawRect(box, fill)
            }
            c.drawRect(box, stroke)
            // Code label at its pack's charted box when there is one (04 runs far offshore)
            val at = labelAt(r.code)
            val lx = if (at != null) sx(at.lon) else box.centerX()
            val ly = if (at != null) sy(at.lat) else box.centerY()
            val size = min(box.width(), box.height())
            if (size > 28 * density) {
                label.textSize = (if (st == Status.NOT_BUILT) 11f else 15f) * density
                label.color = if (st == Status.NOT_BUILT) faint else text
                val tx = clamp(lx, max(box.left, 0f) + 16 * density, min(box.right, width.toFloat()) - 16 * density)
                val ty = clamp(ly, max(box.top, 0f) + 20 * density, min(box.bottom, height.toFloat()) - 6 * density)
                c.drawText(r.code, tx, ty, label)
                if (hasChart(r.code) && size > 48 * density) {
                    label.textSize = 10f * density
                    label.color = text
                    c.drawText("chart map", tx, ty + 13 * density, label)
                }
            }
        }
        for ((p, name) in markers) {
            val x = sx(p.lon)
            val y = sy(p.lat)
            fill.color = Color.rgb(220, 50, 47)
            fill.alpha = 255
            c.drawCircle(x, y, 5 * density, fill)
            label.textSize = 12f * density
            label.color = text
            c.drawText(name, x, y - 9 * density, label)
        }
    }
}
