package com.jnyer27.noaaroute

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Offline NOAA ENC chart, with the planned route drawn on it.
 * MapLibre Native (BSD-2-Clause) renders an MBTiles file from tools/build_enc_tiles.py.
 * A downloaded chart from the vector catalog is the source when its bounds cover the
 * route, or the middle of the map once the camera has settled. Every region with a
 * tiles entry is eligible. Otherwise the bundled Middle River chart is shown, and the
 * screen offers the catalog chart for this view. There is no online base map: the picture is
 * NOAA ENC only, so the license is NOAA's public domain data plus MapLibre's own notice
 * (the logo stays on the map).
 * Route data, off until it is turned on, draws the planner's grid from downloaded route packs
 * on top of whichever chart is showing, and under the route line.
 */
class ChartMapActivity : Activity() {
    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_ROUTE_JSON = "routeJson"
        const val EXTRA_DEPTH = "depthFt"
        const val EXTRA_AIR = "airFt"
        const val EXTRA_NAME = "name"
        /** Result: a point picked on the chart, and what to do with it ([PICK_VIA] or [PICK_DEST]). */
        const val EXTRA_PICK_LAT = "pickLat"
        const val EXTRA_PICK_LON = "pickLon"
        const val EXTRA_PICK_ACTION = "pickAction"
        const val PICK_VIA = "via"
        const val PICK_DEST = "dest"
    }

    private lateinit var mapView: MapView
    private lateinit var legend: TextView
    /** The full key. The view shows [ChartTiles.keyLine] of this, or the load error. */
    private var fullKey = "Opening the chart..."
    /** A load failure stays in full even when the key is hidden. */
    private var keyError = false
    /** Remembered. True shows the paragraph. False is the one-line bar. */
    private var keyOpen = true
    private lateinit var downloadButton: Button
    private lateinit var routeToggle: CheckBox
    private lateinit var routeLegend: TextView
    private val tiles by lazy { ChartTileStore(this) }
    private val uiHandler = Handler(Looper.getMainLooper())
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var destroyed = false
    private var depthFt = 3.0
    private var airFt: Double? = null
    private var routeJson: String? = null
    /** Restricted areas from the downloaded route packs, built once per screen. */
    @Volatile private var restrictedJson: String? = null
    /** "04:<sha>" for a region chart, or "bundled". Skip a redraw when it has not changed. */
    private var shownKey: String? = null
    /** Region charts drawn now: every downloaded one, so panning across a seam shows both. */
    private var drawnSets: List<ChartTiles.TileSet> = emptyList()
    private var loadError = false
    private var chartNote = ""
    /** The first chart frames the route or Middle River. Later switches keep the camera. */
    private var framed = false
    private var overlayOn = false
    private var generation = 0
    private var openPacks: List<Pack> = emptyList()
    private var routeTiles: RouteTiles? = null
    private val shown = HashSet<String>()
    private val prefs by lazy { getSharedPreferences("noaaroute", Context.MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private val listener = LocationListener { loc -> showFix(loc) }
    private var followButton: Button? = null
    private var compassArrow: TextView? = null
    private var compassLabel: TextView? = null
    private var courseButton: Button? = null
    private var following = false
    private var courseUp = false
    private var heldCourse = 0.0
    private var lastFix: Location? = null
    private var wantFollow = false
    /** Our own camera moves must not turn Follow off. */
    private var movingCamera = false
    private val poll = object : Runnable {
        override fun run() {
            if (destroyed) return
            present()
            uiHandler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun systemNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Paint a control with the day or night pair. Hard-coded black on white goes black on black when the phone darkens a light theme. */
    private fun paintControl(v: TextView, chrome: ChartTiles.ChartChrome, bg: Int = chrome.panel) {
        v.setTextColor(chrome.text)
        v.setBackgroundColor(bg)
        if (v is CheckBox) v.buttonTintList = ColorStateList.valueOf(chrome.text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        depthFt = intent.getDoubleExtra(EXTRA_DEPTH, 3.0)
        airFt = if (intent.hasExtra(EXTRA_AIR)) {
            intent.getDoubleExtra(EXTRA_AIR, Double.NaN).takeIf { it.isFinite() && it > 0.0 && it < 300.0 }
        } else null
        routeJson = intent.getStringExtra(EXTRA_ROUTE_JSON) ?: intent.getStringExtra(EXTRA_ROUTE)?.let { path ->
            try { File(path).takeIf { it.isFile }?.readText() } catch (_: Exception) { null }
        }
        title = "Chart"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chrome = ChartTiles.chartChrome(systemNight())
        root.setBackgroundColor(chrome.keyBg)
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        val frame = FrameLayout(this)
        frame.addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        routeLegend = TextView(this).apply {
            visibility = View.GONE
            textSize = 11f
            setPadding(dp(8), dp(4), dp(8), dp(6))
            paintControl(this, chrome)
        }
        routeToggle = CheckBox(this).apply {
            text = RouteOverlay.TOGGLE
            textSize = 13f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            paintControl(this, chrome)
            isChecked = prefs.getBoolean(RouteOverlay.PREF, false)
            overlayOn = isChecked
            setOnCheckedChangeListener { _, on ->
                overlayOn = on
                prefs.edit().putBoolean(RouteOverlay.PREF, on).apply()
                if (style != null) refreshOverlay()
            }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(routeToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(routeLegend, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        frame.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            setMargins(dp(8), dp(8), dp(8), 0)
        })
        courseUp = prefs.getBoolean(ChartTiles.COURSE_PREF, false)
        followButton = Button(this).apply {
            text = "Follow"
            isAllCaps = false
            textSize = 14f
            paintControl(this, chrome)
            setOnClickListener { onFollowTapped() }
        }
        compassArrow = TextView(this).apply {
            text = "▲"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(chrome.text)
        }
        compassLabel = TextView(this).apply {
            text = ChartTiles.compassText(0.0)
            textSize = 14f
            setTextColor(chrome.text)
            setPadding(dp(4), 0, 0, 0)
        }
        val compass = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(chrome.panel)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            minimumHeight = dp(48)
            isClickable = true
            addView(compassArrow)
            addView(compassLabel)
            setOnClickListener { resetNorth() }
        }
        courseButton = Button(this).apply {
            isAllCaps = false
            textSize = 14f
            text = if (courseUp) "Course up" else "North up"
            paintControl(this, chrome)
            setOnClickListener { toggleCourse() }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(followButton)
            addView(compass)
            addView(courseButton)
        }
        frame.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, dp(8), dp(8))
        })
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        keyOpen = prefs.getBoolean(ChartTiles.KEY_PREF, true)
        legend = TextView(this).apply {
            textSize = 13f
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(4))
            paintControl(this, chrome, chrome.keyBg)
            isClickable = true
            setOnClickListener {
                keyOpen = !keyOpen
                prefs.edit().putBoolean(ChartTiles.KEY_PREF, keyOpen).apply()
                paintKey()
            }
        }
        paintKey()
        root.addView(legend, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        downloadButton = Button(this).apply {
            visibility = View.GONE
            paintControl(this, chrome, chrome.keyBg)
        }
        root.addView(downloadButton)
        root.addView(Button(this).apply {
            text = "Back"
            paintControl(this, chrome, chrome.keyBg)
            setOnClickListener { finish() }
        })
        setContentView(root)
        insetPadding(root)

        mapView.getMapAsync { loaded ->
            map = loaded
            loaded.uiSettings.isRotateGesturesEnabled = false
            loaded.uiSettings.isTiltGesturesEnabled = false
            loaded.uiSettings.isCompassEnabled = false
            loaded.addOnCameraMoveStartedListener { reason ->
                val gesture = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                if (gesture && !movingCamera) {
                    following = ChartTiles.stillFollowing(following, true)
                    paintFollow()
                }
            }
            loaded.addOnCameraMoveListener { paintCompass() }
            mapView.addOnDidFailLoadingMapListener { message ->
                if (destroyed) return@addOnDidFailLoadingMapListener
                loadError = true
                showKey("Chart did not load: $message", error = true)
            }
            loaded.addOnCameraIdleListener { if (overlayOn && !destroyed) refreshOverlay() }
            loaded.addOnMapClickListener { latLng -> showLeg(latLng.latitude, latLng.longitude) }
            loaded.addOnMapLongClickListener { latLng -> showPoint(latLng.latitude, latLng.longitude); true }
            present()
            uiHandler.post(poll)
            worker.execute {
                tiles.reconcile()
                try { tiles.refreshCatalog() } catch (_: Exception) { }
                runOnUiThread { if (!destroyed) present() }
            }
        }
    }

    /** Center of the map once it has fitted a real area, or null while it is still the world. */
    private fun viewCenter(): LatLon? {
        val loaded = map ?: return null
        val b = try { loaded.projection.visibleRegion.latLngBounds } catch (_: Exception) { return null }
        val west = b.longitudeWest
        val east = b.longitudeEast
        val south = b.latitudeSouth
        val north = b.latitudeNorth
        if (!(east > west && north > south) || east - west > 40.0 || north - south > 40.0) return null
        return LatLon((south + north) / 2, (west + east) / 2)
    }

    /** Choose a catalog chart for the route or the map, or the bundled Middle River file. */
    private fun present() {
        val loaded = map ?: return
        val pts = ChartTiles.focus(routePoints(), viewCenter())
        val ready = tiles.readySets()
        val catalog = tiles.catalogSets()
        val chosen = ChartTiles.pick(ready, pts)
        val offer = ChartTiles.offer(catalog, ready, pts)
        val remote = chosen?.let { c -> catalog.firstOrNull { it.ncds == c.ncds } }
        val update = remote?.takeIf { it.sha256 != chosen?.sha256 }
        val busyCode = update?.ncds ?: offer?.ncds
        val busy = tiles.busy(busyCode)
        val entry = busyCode?.let { tiles.get(it) }
        when {
            busy && entry != null -> {
                downloadButton.visibility = View.VISIBLE
                downloadButton.text = "Cancel download"
                downloadButton.setOnClickListener { tiles.cancel(entry.ncds); present() }
            }
            update != null -> {
                downloadButton.visibility = View.VISIBLE
                downloadButton.text = "Update ${update.shortName} chart (%.0f MB)".format(update.size / 1e6)
                downloadButton.setOnClickListener { confirmDownload(update) }
            }
            offer != null -> {
                downloadButton.visibility = View.VISIBLE
                downloadButton.text = "Download ${offer.shortName} chart (%.0f MB)".format(offer.size / 1e6)
                downloadButton.setOnClickListener { confirmDownload(offer) }
            }
            else -> downloadButton.visibility = View.GONE
        }
        val progress = if (busy && busyCode != null) tiles.progress(busyCode) else null
        val status = when {
            entry?.state == ChartTileStore.State.DOWNLOADING && progress != null ->
                " Downloading ${entry.name.substringBefore(':')}: %.0f of %.0f MB.".format(progress.first / 1e6, progress.second / 1e6)
            entry?.state == ChartTileStore.State.DOWNLOADING ->
                " Download queued (waits for Wi-Fi unless mobile data was allowed)."
            entry?.state == ChartTileStore.State.DOWNLOADED || entry?.state == ChartTileStore.State.SAVING ->
                " Checking the download..."
            entry?.error != null -> " ${entry.error}."
            else -> ""
        }
        // Every downloaded region chart is drawn at once, each its own source. Only the
        // one under the screen's center was, so the next region stayed blank while panning.
        val drawn = ready.filter { set -> tiles.file(set)?.isFile == true }.sortedBy { it.ncds }
        val fallback = when {
            chosen != null || offer == null || status.isNotEmpty() -> ""
            drawn.isEmpty() -> " This view is in ${offer.shortName}. The Middle River chart is shown until that chart is downloaded."
            else -> " This view is in ${offer.shortName}, which is not downloaded."
        }
        chartNote = status + fallback
        if (drawn.isNotEmpty()) {
            val key = drawn.joinToString(",") { it.ncds + ":" + it.sha256 }
            val w = drawn.minOf { it.bounds[0] }
            val so = drawn.minOf { it.bounds[1] }
            val e = drawn.maxOf { it.bounds[2] }
            val n = drawn.maxOf { it.bounds[3] }
            val heading = ChartTiles.regionsHeading(drawn)
            drawnSets = drawn
            if (shownKey != key) {
                loadError = false
                openFiles(loaded, drawn.map { tiles.file(it)!! }, doubleArrayOf(w, so, e, n), heading, region = true)
            } else if (!loadError) {
                publishLegend(w, so, e, n, heading, region = true)
            }
            shownKey = key
        } else {
            drawnSets = emptyList()
            val key = "bundled"
            if (shownKey != key) {
                loadError = false
                val mb = bundled()
                val bounds = readBounds(mb) ?: doubleArrayOf(ChartTiles.WEST, ChartTiles.SOUTH, ChartTiles.EAST, ChartTiles.NORTH)
                openFiles(loaded, listOf(mb), bounds, "Middle River", region = false)
            } else if (!loadError) {
                publishLegend(ChartTiles.WEST, ChartTiles.SOUTH, ChartTiles.EAST, ChartTiles.NORTH, "Middle River", region = false)
            }
            shownKey = key
        }
    }

    private fun confirmDownload(set: ChartTiles.TileSet) {
        if (tiles.freeBytes() < tiles.spaceNeeded(set)) {
            AlertDialog.Builder(this).setTitle("Not enough space")
                .setMessage("${set.shortName}'s chart is %.0f MB and the download needs about %.0f MB free. %.0f MB is free.".format(
                    set.size / 1e6, tiles.spaceNeeded(set) / 1e6, tiles.freeBytes() / 1e6))
                .setPositiveButton("OK", null).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        fun go(mobile: Boolean) {
            tiles.start(set, mobile)
            present()
        }
        AlertDialog.Builder(this).setTitle("${set.shortName} chart, %.0f MB".format(set.size / 1e6))
            .setMessage("NOAA's chart for the map in this app. Android downloads it in the background and resumes after interruptions. It stays in the app and works offline. It is not an OsmAnd map.")
            .setPositiveButton("On Wi-Fi only") { _, _ -> go(false) }
            .setNeutralButton("Use mobile data too") { _, _ -> go(true) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun openFiles(loaded: MapLibreMap, files: List<File>, bounds: DoubleArray, heading: String, region: Boolean) {
        val glyphs = glyphDir()
        val json = ChartTiles.style(depthFt, files.map { "mbtiles://${it.absolutePath}" }, "file://${glyphs.absolutePath}/{fontstack}/{range}.pbf")
        title = "$heading chart"
        val w = bounds[0]; val s = bounds[1]; val e = bounds[2]; val n = bounds[3]
        publishLegend(w, s, e, n, heading, region)
        loaded.setStyle(Style.Builder().fromJson(json)) { st ->
            style = st
            // A new style drops the previous image layers. Drop any build still aimed at the old one.
            generation++
            shown.clear()
            routeJson?.let { (st.getSource("route") as? GeoJsonSource)?.setGeoJson(it) }
            showRestricted(st)
            loaded.setMinZoomPreference(8.0)
            loaded.setMaxZoomPreference(18.0)
            if (!framed) {
                position(loaded, w, s, e, n)
                framed = true
            }
            if (overlayOn) refreshOverlay()
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                listen()
            } else {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            }
        }
    }

    /** Fit the part of the route that falls on this chart. With no route, open on Middle River when this chart covers it. */
    private fun position(loaded: MapLibreMap, w: Double, s: Double, e: Double, n: Double) {
        val pts = routePoints()
        val homeInside = ChartTiles.WEST >= w && ChartTiles.EAST <= e && ChartTiles.SOUTH >= s && ChartTiles.NORTH <= n
        if (pts.isEmpty() && homeInside) {
            fit(loaded, ChartTiles.WEST, ChartTiles.SOUTH, ChartTiles.EAST, ChartTiles.NORTH)
            return
        }
        val inside = pts.filter { it.lon in w..e && it.lat in s..n }
        val use = if (inside.isNotEmpty()) inside else listOf(LatLon((s + n) / 2, (w + e) / 2))
        fit(loaded, use.minOf { it.lon }, use.minOf { it.lat }, use.maxOf { it.lon }, use.maxOf { it.lat })
    }

    private fun fit(loaded: MapLibreMap, w: Double, s: Double, e: Double, n: Double) {
        val b = LatLngBounds.Builder()
        b.include(LatLng(s, w))
        b.include(LatLng(n, e))
        if (w == e || s == n) b.include(LatLng(s + 0.01, w + 0.01))
        mapView.post {
            if (destroyed) return@post
            try {
                loaded.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(36)))
            } catch (_: Exception) {
                loaded.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng((s + n) / 2, (w + e) / 2), 12.0))
            }
        }
    }

    private fun routePoints(): List<LatLon> {
        val json = routeJson ?: return emptyList()
        val out = ArrayList<LatLon>()
        // Points are the "kind":"point" features. A small scan keeps this off a JSON library path
        // that would duplicate ChartTiles; coordinates are written as [lon,lat].
        var i = 0
        while (true) {
            val k = json.indexOf("\"kind\":\"point\"", i)
            if (k < 0) break
            val c = json.indexOf("\"coordinates\":[", k)
            if (c < 0) break
            val end = json.indexOf(']', c)
            if (end < 0) break
            val parts = json.substring(c + "\"coordinates\":[".length, end).split(',')
            if (parts.size == 2) {
                val lon = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                if (lon != null && lat != null) out.add(LatLon(lat, lon))
            }
            i = end + 1
        }
        return out
    }

    /** About a fingertip (24 dp) on the map at this latitude, in meters. */
    private fun fingerM(lat: Double): Double {
        val mpp = try { map?.projection?.getMetersPerPixelAtLatitude(lat) } catch (_: Exception) { null } ?: return 300.0
        return (mpp * dp(24)).coerceIn(10.0, 2000.0)
    }

    /** Draw the yellow band under leg [n], or none when n is -1. */
    private fun highlightLeg(n: Int) {
        val layer = style?.getLayer("route-pick") as? LineLayer ?: return
        layer.setFilter(Expression.all(
            Expression.eq(Expression.get("kind"), Expression.literal("leg")),
            Expression.eq(Expression.get("leg"), Expression.literal(n))))
    }

    /**
     * A tap on a leg opens its details: the course and distance, then each note on its own
     * line, the check legs first in the title. The leg is highlighted while it is open.
     * A tap away from the route does nothing.
     */
    private fun showLeg(lat: Double, lon: Double): Boolean {
        val json = routeJson ?: return false
        val pick = ChartTiles.legPickAt(json, lat, lon, fingerM(lat)) ?: return false
        val (head, lines) = ChartTiles.legDetail(pick.note)
        val body = StringBuilder()
        if (head.isNotEmpty()) body.append(head)
        if (lines.isEmpty()) body.append(if (body.isEmpty()) "No notes on this leg." else "\n\nNo notes on this leg.")
        for (l in lines) { if (body.isNotEmpty()) body.append("\n\n"); body.append("\u2022 ").append(l) }
        if (pick.flagged) body.append("\n\nA check: look at this leg on a current chart before you go.")
        highlightLeg(pick.leg)
        AlertDialog.Builder(this)
            .setTitle(if (pick.flagged) "Leg ${pick.leg}: check" else "Leg ${pick.leg}")
            .setMessage(body.toString())
            .setPositiveButton("Close", null)
            .setNeutralButton("Add via here") { _, _ -> returnPick(PICK_VIA, lat, lon) }
            .setNegativeButton("Destination here") { _, _ -> returnPick(PICK_DEST, lat, lon) }
            .setOnDismissListener { if (!destroyed) highlightLeg(-1) }
            .show()
        return true
    }

    /** Press and hold: use that spot as a via point or the destination on the planning screen. */
    private fun showPoint(lat: Double, lon: Double) {
        val p = LatLon(lat, lon)
        AlertDialog.Builder(this)
            .setTitle(Coords.format(p))
            .setItems(arrayOf("Add as via point", "Set as destination")) { _, which ->
                returnPick(if (which == 0) PICK_VIA else PICK_DEST, lat, lon)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Back to the planning screen with the point. It fills the field there; Plan route plans it. */
    private fun returnPick(action: String, lat: Double, lon: Double) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PICK_ACTION, action)
            .putExtra(EXTRA_PICK_LAT, lat).putExtra(EXTRA_PICK_LON, lon))
        finish()
    }

    private fun publishLegend(w: Double, s: Double, e: Double, n: Double, heading: String, region: Boolean) {
        keyW = w
        keyS = s
        keyE = e
        keyN = n
        keyHeading = heading
        keyRegion = region
        keyReady = true
        showKey(legendText(w, s, e, n, heading, region))
    }

    /** Put the remembered key (or a load error) into the line under the map. */
    private fun showKey(text: String, error: Boolean = false) {
        fullKey = text
        keyError = error
        paintKey()
    }

    private fun paintKey() {
        val open = keyOpen || keyError
        legend.maxLines = if (open) Int.MAX_VALUE else 1
        legend.text = if (keyError) fullKey else ChartTiles.keyLine(fullKey, keyOpen)
    }

    private fun legendText(w: Double, s: Double, e: Double, n: Double, heading: String, region: Boolean): String {
        val sets = drawnSets
        val outside = routePoints().any { p -> if (sets.isEmpty()) p.lon !in w..e || p.lat !in s..n else sets.none { it.contains(p) } }
        val sb = StringBuilder()
        sb.append("Not for navigation. ")
        sb.append("NOAA ENC of $heading, depths in feet. ")
        sb.append(ChartTiles.orientSentence(courseUp))
        sb.append(ChartTiles.spanText(w, e, (s + n) / 2))
        sb.append(ChartTiles.depthLegend(depthFt))
        sb.append("Green and red marks are port and starboard (IALA-B); a dark ring is a daybeacon. ")
        sb.append("The small dot beside a mark is its light, in that light's colour. ")
        if (routeJson != null) sb.append("Orange dashed legs are checks. The number on a leg is its row in the report. Tap a leg for its details. A bridge or cable drawn for vertical clearance is not that orange. ")
        sb.append("A purple dashed outline is a restricted area from the route data, named along its edge: heavy where entry is restricted or prohibited or a security zone, light for anchoring, fishing or other rules. ")
        sb.append("The blue dot is your GPS position. Press and hold the chart to add a via point or set the destination there.")
        if (outside) sb.append(if (region) " Charts cover the downloaded regions; past them, the route is the line alone."
            else " Charts cover Middle River only; past that, the route is the line alone.")
        if (chartNote.isNotEmpty()) sb.append(chartNote)
        return sb.toString()
    }

    private fun bundled(): File {
        val mb = File(filesDir, ChartTiles.ASSET)
        copyAsset(ChartTiles.ASSET, mb)
        return mb
    }

    private fun glyphDir(): File {
        val glyphs = File(filesDir, "glyphs")
        copyAsset(ChartTiles.GLYPH_ASSET, File(glyphs, "${ChartTiles.FONT}/0-255.pbf"))
        return glyphs
    }

    /**
     * Copy a bundled chart file into private storage. MapLibre reads a real file, not an asset.
     * The previous check skipped the copy when the length matched. The light-colour tile was the
     * same number of bytes as the one already on the phone, so the new style looked up `col` in
     * the old file and every light fell through to the gray fallback. A checksum catches that.
     */
    private fun copyAsset(asset: String, dest: File) {
        dest.parentFile?.mkdirs()
        val stamp = File(dest.path + ".sha256")
        val digest = sha256(assets.open(asset))
        val saved = try { if (stamp.isFile) stamp.readText().trim() else null } catch (_: Exception) { null }
        if (dest.isFile && saved == digest) return
        val tmp = File(dest.path + ".part")
        assets.open(asset).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        stamp.writeText(digest)
    }

    private fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        input.use {
            val buf = ByteArray(8192)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun readBounds(mbtiles: File): DoubleArray? = try {
        SQLiteDatabase.openDatabase(mbtiles.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("select value from metadata where name='bounds'", null).use { c ->
                if (!c.moveToFirst()) return null
                val p = c.getString(0).split(',').mapNotNull { it.toDoubleOrNull() }
                if (p.size == 4) p.toDoubleArray() else null
            }
        }
    } catch (_: Exception) { null }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        try { lm.removeUpdates(listener) } catch (_: Exception) { }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!lm.isProviderEnabled(p)) continue
                lm.getLastKnownLocation(p)?.let { showFix(it) }
                lm.requestLocationUpdates(p, 2000L, 2f, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {
                // Permission was withdrawn between the check and the request.
            } catch (_: Exception) { }
        }
    }

    private fun showFix(loc: Location) {
        lastFix = loc
        val bearing = ChartTiles.mapBearing(courseUp && following, loc.bearing.takeIf { loc.hasBearing() }, loc.speed, heldCourse)
        if (courseUp && following && loc.hasBearing() && loc.speed >= ChartTiles.COURSE_SPEED_MPS)
            heldCourse = bearing
        val st = style
        if (st != null && !destroyed)
            (st.getSource("gps") as? GeoJsonSource)?.setGeoJson(ChartTiles.gpsGeoJson(loc.latitude, loc.longitude))
        if (following) moveToFix(loc, bearing)
        paintFollow()
    }

    private fun onFollowTapped() {
        if (!locationAllowed()) {
            wantFollow = true
            if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                AlertDialog.Builder(this).setTitle("Location")
                    .setMessage("Follow uses the phone's GPS to keep the chart on the boat. The chart works without it. Allow location to turn Follow on.")
                    .setPositiveButton("Allow") { _, _ ->
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
                    }
                    .setNegativeButton("Not now") { _, _ -> wantFollow = false }
                    .show()
            } else {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            }
            return
        }
        if (!locationEnabled()) {
            following = false
            paintFollow()
            AlertDialog.Builder(this).setTitle("GPS is off")
                .setMessage("The phone's location is turned off, so NOAA Route cannot follow the boat. Turn location on in the phone's settings. The chart still works.")
                .setPositiveButton("OK", null).show()
            return
        }
        following = true
        paintFollow()
        val fix = lastFix
        if (fix != null) moveToFix(fix, ChartTiles.mapBearing(courseUp, fix.bearing.takeIf { fix.hasBearing() }, fix.speed, heldCourse))
        else followButton?.text = "Waiting for GPS"
    }

    private fun locationAllowed() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun locationEnabled(): Boolean {
        val lm = getSystemService(LocationManager::class.java) ?: return false
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { false }
    }

    private fun moveToFix(loc: Location, bearing: Double) {
        val loaded = map ?: return
        if (destroyed) return
        val z = loaded.cameraPosition.zoom
        val zoom = if (z < 13.0) 15.0 else z
        val pos = CameraPosition.Builder()
            .target(LatLng(loc.latitude, loc.longitude))
            .zoom(zoom)
            .bearing(bearing)
            .tilt(0.0)
            .build()
        movingCamera = true
        loaded.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 400)
        mapView.postDelayed({ movingCamera = false }, 500)
    }

    private fun resetNorth() {
        courseUp = false
        heldCourse = 0.0
        prefs.edit().putBoolean(ChartTiles.COURSE_PREF, false).apply()
        courseButton?.text = "North up"
        val loaded = map ?: return
        val cam = loaded.cameraPosition
        movingCamera = true
        loaded.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(cam).bearing(0.0).tilt(0.0).build()), 300)
        mapView.postDelayed({ movingCamera = false }, 400)
        paintCompass()
        refreshKeySentence()
    }

    private fun toggleCourse() {
        courseUp = !courseUp
        prefs.edit().putBoolean(ChartTiles.COURSE_PREF, courseUp).apply()
        courseButton?.text = if (courseUp) "Course up" else "North up"
        if (!courseUp) heldCourse = 0.0
        refreshKeySentence()
        if (following) {
            val fix = lastFix
            if (fix != null) moveToFix(fix, ChartTiles.mapBearing(courseUp, fix.bearing.takeIf { fix.hasBearing() }, fix.speed, heldCourse))
            else if (!courseUp) resetNorth()
        } else if (!courseUp) {
            resetNorth()
        }
    }

    private fun paintFollow() {
        followButton?.text = when {
            following && lastFix == null -> "Waiting for GPS"
            following -> "Following"
            else -> "Follow"
        }
    }

    private fun paintCompass() {
        val b = map?.cameraPosition?.bearing ?: 0.0
        compassLabel?.text = ChartTiles.compassText(b)
        compassArrow?.rotation = (-b).toFloat()
    }

    private var keyW = 0.0
    private var keyS = 0.0
    private var keyE = 0.0
    private var keyN = 0.0
    private var keyHeading = ""
    private var keyRegion = false
    private var keyReady = false

    private fun refreshKeySentence() {
        if (!keyReady || keyError) return
        showKey(legendText(keyW, keyS, keyE, keyN, keyHeading, keyRegion))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        val askedFollow = wantFollow
        wantFollow = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            listen()
            if (askedFollow) onFollowTapped()
        } else if (askedFollow) {
            following = false
            paintFollow()
            if (!isFinishing) {
                AlertDialog.Builder(this).setTitle("Location not allowed")
                    .setMessage("NOAA Route cannot follow the boat without location. The chart still works. To turn Follow on later, allow location for NOAA Route in the phone's settings.")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    private fun insetPadding(v: View) {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        (v as? ViewGroup)?.clipToPadding = false
        v.setOnApplyWindowInsetsListener { view, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume(); if (style != null) listen() }
    override fun onPause() {
        try { getSystemService(LocationManager::class.java).removeUpdates(listener) } catch (_: Exception) {}
        mapView.onPause(); super.onPause()
    }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    /**
     * Draw or clear the route-data images for the current view. Off by default.
     * The checkbox text says when no downloaded pack covers the view, instead of failing.
     */
    private fun refreshOverlay() {
        val loaded = map ?: return
        if (style == null || destroyed) return
        val gen = ++generation
        if (!overlayOn) {
            clearOverlayLayers()
            routeLegend.visibility = View.GONE
            routeToggle.text = RouteOverlay.TOGGLE
            return
        }
        val bounds = try { loaded.projection.visibleRegion.latLngBounds } catch (_: Exception) { return }
        val west = bounds.longitudeWest
        val east = bounds.longitudeEast
        val south = bounds.latitudeSouth
        val north = bounds.latitudeNorth
        // The map opens on a world camera for a moment. Wait until it has fitted the chart.
        if (!(east > west && north > south) || east - west > 40.0 || north - south > 40.0) return
        routeLegend.visibility = View.VISIBLE
        routeLegend.text = RouteOverlay.DRAWING
        routeToggle.text = RouteOverlay.TOGGLE
        val depth = depthFt
        val air = airFt
        val have = HashSet(shown)
        try {
            worker.execute {
                if (gen != generation || destroyed) return@execute
                try {
                    val packs = ensurePacks()
                    if (gen != generation || destroyed) return@execute
                    if (packs.isEmpty()) {
                        runOnUiThread { if (gen == generation && !destroyed) showOverlayMessage(RouteOverlay.NO_PACK_NONE) }
                        return@execute
                    }
                    val tiles = routeTiles ?: RouteTiles(packs, File(filesDir, "route-overlay"), depth, air).also { routeTiles = it }
                    val result = tiles.build(west, south, east, north, have) { gen == generation && !destroyed }
                    runOnUiThread {
                        if (gen != generation || destroyed) {
                            result.placed.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                            return@runOnUiThread
                        }
                        when {
                            result.aborted -> { /* a newer view, or the camera was still settling */ }
                            !result.covered -> showOverlayMessage(RouteOverlay.NO_PACK)
                            result.ids.isEmpty() -> {
                                clearOverlayLayers()
                                routeLegend.visibility = View.VISIBLE
                                routeLegend.text = RouteOverlay.NOTHING
                                routeToggle.text = RouteOverlay.TOGGLE
                            }
                            else -> {
                                publish(result)
                                routeLegend.visibility = View.VISIBLE
                                routeLegend.text = RouteOverlay.legend(depth, air, result.images, result.seconds, result.stride)
                                routeToggle.text = RouteOverlay.TOGGLE
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("RouteOverlay", "route data failed", e)
                    runOnUiThread {
                        if (gen == generation && !destroyed) {
                            showOverlayMessage("Route data: ${e.message ?: "could not read the route pack"}")
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) { }
    }

    /**
     * Restricted areas named in leg notes come from the route packs, not the chart tiles,
     * so they are drawn from the packs' own outlines whether or not Route data is on.
     */
    private fun showRestricted(st: Style) {
        restrictedJson?.let { (st.getSource("restricted") as? GeoJsonSource)?.setGeoJson(it); return }
        try {
            worker.execute {
                if (destroyed) return@execute
                val json = try {
                    ChartTiles.restrictedGeoJson(ensurePacks().flatMap { it.notes })
                } catch (e: Exception) {
                    Log.w("ChartMap", "restricted areas failed", e); return@execute
                }
                restrictedJson = json
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    (style?.getSource("restricted") as? GeoJsonSource)?.setGeoJson(json)
                }
            }
        } catch (_: RejectedExecutionException) { }
    }

    private fun showOverlayMessage(text: String) {
        clearOverlayLayers()
        routeLegend.visibility = View.GONE
        routeToggle.text = text
    }

    /** Packs in the same order planning uses: file name, first tile with bytes wins. */
    private fun ensurePacks(): List<Pack> {
        if (openPacks.isNotEmpty()) return openPacks
        val dir = PackStore(this).dir
        val out = ArrayList<Pack>()
        for (f in dir.listFiles { file -> file.isFile && file.name.endsWith(".bin") }.orEmpty().sortedBy { it.name }) {
            try { out.add(Pack(f)) } catch (_: Exception) { }
        }
        openPacks = out
        return out
    }

    private fun publish(result: RouteTiles.Built) {
        val st = style ?: run {
            result.placed.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            return
        }
        val keep = result.ids.toSet()
        for (id in shown.filter { it !in keep }) removeOverlay(st, id)
        for (p in result.placed) {
            try {
                if (p.id !in shown) {
                    val quad = LatLngQuad(
                        LatLng(p.north, p.west), LatLng(p.north, p.east),
                        LatLng(p.south, p.east), LatLng(p.south, p.west),
                    )
                    st.addSource(ImageSource("src-${p.id}", quad, p.bitmap))
                    val layer = RasterLayer("lyr-${p.id}", "src-${p.id}").apply {
                        setProperties(
                            PropertyFactory.rasterOpacity(1f),
                            PropertyFactory.rasterFadeDuration(0f),
                            PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_NEAREST),
                        )
                    }
                    // Above every chart layer, under the route line (route-casing is the first route layer).
                    st.addLayerBelow(layer, "route-casing")
                    shown.add(p.id)
                }
            } catch (e: Exception) {
                Log.w("RouteOverlay", "could not add ${p.id}", e)
                try { st.removeLayer("lyr-${p.id}") } catch (_: Exception) {}
                try { st.removeSource("src-${p.id}") } catch (_: Exception) {}
                shown.remove(p.id)
            } finally {
                if (!p.bitmap.isRecycled) p.bitmap.recycle()
            }
        }
    }

    private fun clearOverlayLayers() {
        val st = style ?: return
        for (id in shown.toList()) removeOverlay(st, id)
    }

    private fun removeOverlay(st: Style, id: String) {
        try { st.removeLayer("lyr-$id") } catch (_: Exception) {}
        try { st.removeSource("src-$id") } catch (_: Exception) {}
        shown.remove(id)
    }

    override fun onDestroy() {
        destroyed = true
        generation++
        uiHandler.removeCallbacks(poll)
        val closing = openPacks
        openPacks = emptyList()
        routeTiles = null
        try {
            worker.execute { closing.forEach { try { it.close() } catch (_: Exception) {} } }
        } catch (_: RejectedExecutionException) {
            closing.forEach { try { it.close() } catch (_: Exception) {} }
        }
        worker.shutdown()
        mapView.onDestroy()
        super.onDestroy()
    }
}
