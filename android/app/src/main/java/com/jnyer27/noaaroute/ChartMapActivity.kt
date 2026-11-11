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
import android.animation.ValueAnimator
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.WindowManager
import android.view.ViewGroup
import android.view.WindowInsets
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
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
import org.maplibre.android.style.layers.SymbolLayer
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
        private const val BASE_PREF = "baseMap"
        private const val LAYERS_PREF = "chartLayersOpen"
        const val EXTRA_DEPTH = "depthFt"
        const val EXTRA_AIR = "airFt"
        const val EXTRA_NAME = "name"
        /** Result: a point picked on the chart, and what to do with it ([PICK_START], [PICK_VIA] or [PICK_DEST]). */
        const val EXTRA_PICK_LAT = "pickLat"
        const val EXTRA_PICK_LON = "pickLon"
        const val EXTRA_PICK_ACTION = "pickAction"
        const val PICK_START = "start"
        const val PICK_VIA = "via"
        const val PICK_DEST = "dest"
    }

    private lateinit var mapView: MapView
    private lateinit var legend: TextView
    private var scaleBar: ScaleBarView? = null
    /** The full key. The view shows [ChartTiles.keyLine] of this, or the load error. */
    private var fullKey = "Opening the chart..."
    /** A load failure stays in full even when the key is hidden. */
    private var keyError = false
    /** Remembered. True shows the paragraph. False is the one-line bar. */
    private var keyOpen = true
    private lateinit var downloadButton: Button
    /** The OpenStreetMap base map offer, apart from the chart's so a chart update cannot hide it. */
    private lateinit var baseButton: Button
    private lateinit var routeToggle: CheckBox
    private lateinit var routeLegend: TextView
    private val tiles by lazy { ChartTileStore(this) }
    private val baseTiles by lazy { ChartTileStore(this, ChartTileStore.Kind.BASEMAP) }
    private lateinit var baseToggle: CheckBox
    /** OpenStreetMap base map files drawn now (empty when none is downloaded or it is off). */
    private var baseDrawn: List<File> = emptyList()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var destroyed = false
    private var depthFt = 3.0
    private var airFt: Double? = null
    private var routeJson: String? = null
    /** Restricted areas and overhead cables from the downloaded route packs, built once per screen. */
    @Volatile private var restrictedJson: String? = null
    @Volatile private var cablesJson: String? = null
    @Volatile private var packNotes: List<Note> = emptyList()
    @Volatile private var tidesJson: String? = null
    /** Predicted depth: soundings and contour labels show charted depth plus the tide now (off each time the chart opens). */
    private var predictedOn = false
    private var predictedNote = ""
    /** Sounding and contour label layers showing the tide; put back to the chart's own expressions when off. */
    private val predictedLayers = HashSet<String>()
    private var predictedSeries: TideSeries? = null
    private val predictedFetching = HashSet<String>()
    private val predictedFailed = HashSet<String>()
    private val predictedTick = object : Runnable {
        override fun run() {
            if (destroyed || !predictedOn) return
            refreshPredicted()
            uiHandler.postDelayed(this, 60_000)
        }
    }
    @Volatile private var tideStations: List<TideStation> = emptyList()
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
    /** When the last GPS (not network) fix came, for ignoring coarse network fixes in between. */
    private var lastGpsMs = 0L
    private var speedLabel: TextView? = null
    /** When a fix last showed the boat moving (elapsedRealtime), for keeping the screen on. */
    private var lastMovingMs = 0L
    /** The previous accepted fix's time, so the camera glides for about the gap between fixes. */
    private var prevFixMs = 0L
    /** Where the dot is drawn now: it glides from here to each new fix, in step with the camera. */
    private var shownLat = Double.NaN
    private var shownLon = Double.NaN
    private var dotGlide: ValueAnimator? = null
    private val awakeCheck = Runnable { paintAwake() }
    /** No fix for a while: the speed is no longer known. */
    private val speedStale = Runnable { speedLabel?.text = ChartTiles.speedText(null) }
    private var followButton: Button? = null
    /** Search items from the drawn charts, base maps and route packs; loaded on first search. */
    @Volatile private var searchItems: List<MapSearch.Item>? = null
    private var searchKey = ""
    private var compassArrow: TextView? = null
    private var compassLabel: TextView? = null
    private var courseButton: Button? = null
    private var following = false
    /** A pinch is in progress: GPS fixes do not move the camera until it ends. */
    private var scaling = false
    private var courseUp = false
    private var heldCourse = 0.0
    private var lastFix: Location? = null
    private var wantFollow = false
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
        baseToggle = CheckBox(this).apply {
            text = "Base map (OpenStreetMap)"
            textSize = 13f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            paintControl(this, chrome)
            isChecked = prefs.getBoolean(BASE_PREF, true)
            visibility = View.GONE
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(BASE_PREF, on).apply()
                present()
            }
        }
        val predictedToggle = CheckBox(this).apply {
            text = "Predicted depth (tide now)"
            textSize = 13f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            paintControl(this, chrome)
            isChecked = false
            setOnCheckedChangeListener { _, on ->
                predictedOn = on
                uiHandler.removeCallbacks(predictedTick)
                if (on) uiHandler.post(predictedTick) else refreshPredicted()
            }
        }
        // The layer checkboxes fold away behind a small Layers tab (remembered), to free the chart
        val layers = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(baseToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(routeToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(predictedToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(routeLegend, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val layersTab = TextView(this).apply {
            textSize = 14f
            minimumHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            paintControl(this, chrome)
            isClickable = true
            contentDescription = "Show or hide the chart layers"
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(layersTab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(layers, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val panelParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(dp(8), dp(8), dp(8), 0)
        }
        frame.addView(panel, panelParams)
        fun paintLayers(open: Boolean) {
            layers.visibility = if (open) View.VISIBLE else View.GONE
            layersTab.text = if (open) "Layers ▲" else "Layers ▼"
            panelParams.width = if (open) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            panel.layoutParams = panelParams
        }
        paintLayers(prefs.getBoolean(LAYERS_PREF, true))
        layersTab.setOnClickListener {
            val open = layers.visibility != View.VISIBLE
            prefs.edit().putBoolean(LAYERS_PREF, open).apply()
            paintLayers(open)
        }
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
        val searchButton = Button(this).apply {
            text = "Search"
            isAllCaps = false
            textSize = 14f
            paintControl(this, chrome)
            setOnClickListener { showSearch() }
        }
        speedLabel = TextView(this).apply {
            text = ChartTiles.speedText(null)
            textSize = 20f
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            paintControl(this, chrome)
            contentDescription = "Speed over ground"
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(speedLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(searchButton)
            addView(followButton)
            addView(compass)
            addView(courseButton)
        }
        frame.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, dp(8), dp(8))
        })
        scaleBar = ScaleBarView(this, chrome).apply { visibility = View.GONE }
        frame.addView(scaleBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(dp(8), 0, 0, dp(8))
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
        baseButton = Button(this).apply {
            visibility = View.GONE
            paintControl(this, chrome, chrome.keyBg)
        }
        root.addView(baseButton)
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
            // A one-finger pan ends Follow. A pinch or double-tap zoom keeps it, at your zoom.
            // (Camera-move reasons could not tell: with a fix every second the app's own
            // animation was running half the time, and gestures then went unnoticed.)
            loaded.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) {
                    if (detector.pointersCount == 1 && !scaling) {
                        following = ChartTiles.stillFollowing(following, true)
                        paintFollow()
                    }
                }
                override fun onMove(detector: MoveGestureDetector) {}
                override fun onMoveEnd(detector: MoveGestureDetector) {}
            })
            loaded.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                override fun onScaleBegin(detector: StandardScaleGestureDetector) { scaling = true }
                override fun onScale(detector: StandardScaleGestureDetector) {}
                override fun onScaleEnd(detector: StandardScaleGestureDetector) { scaling = false }
            })
            loaded.addOnCameraMoveListener { paintCompass(); paintScale() }
            mapView.addOnDidFailLoadingMapListener { message ->
                if (destroyed) return@addOnDidFailLoadingMapListener
                loadError = true
                showKey("Chart did not load: $message", error = true)
            }
            loaded.addOnCameraIdleListener {
                paintScale()
                if (following) paintGps()
                if (overlayOn && !destroyed) refreshOverlay()
                if (predictedOn && !destroyed) refreshPredicted()
            }
            loaded.addOnMapClickListener { latLng ->
                showLeg(latLng.latitude, latLng.longitude) || showTide(latLng.latitude, latLng.longitude) ||
                    showNotes(latLng.latitude, latLng.longitude)
            }
            loaded.addOnMapLongClickListener { latLng -> showPoint(latLng.latitude, latLng.longitude); true }
            present()
            uiHandler.post(poll)
            worker.execute {
                tiles.reconcile()
                baseTiles.reconcile()
                try { tiles.refreshCatalog() } catch (_: Exception) { }
                baseTiles.refreshCatalog()
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
        // The OpenStreetMap base map of the region on screen (or the route's), when not downloaded
        val baseCode = chosen?.ncds ?: offer?.ncds ?: update?.ncds
        val bset = baseCode?.let { c -> baseTiles.catalogSets().firstOrNull { it.ncds == c } }
        val be = bset?.let { baseTiles.get(it.ncds) }
        when {
            bset != null && baseTiles.busy(bset.ncds) -> {
                baseButton.visibility = View.VISIBLE
                val p = baseTiles.progress(bset.ncds)
                val why = baseTiles.waitReason(bset.ncds)
                baseButton.text = "Cancel base map download" +
                    (p?.let { " (%.0f of %.0f MB)".format(it.first / 1e6, it.second / 1e6) } ?: "") +
                    (why?.let { ", $it" } ?: "")
                baseButton.setOnClickListener { baseTiles.cancel(bset.ncds); present() }
            }
            bset != null && (be == null || be.state == ChartTileStore.State.FAILED || baseTiles.needsUpdate(be, bset)) -> {
                baseButton.visibility = View.VISIBLE
                baseButton.text = (if (be?.state == ChartTileStore.State.READY) "Update" else "Download") +
                    " ${bset.shortName} base map, roads and towns (%.0f MB)".format(bset.size / 1e6)
                baseButton.setOnClickListener { confirmDownload(bset, baseTiles) }
            }
            else -> baseButton.visibility = View.GONE
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
        // The OpenStreetMap base map: every downloaded region's file, under the chart's marks
        val baseReady = baseTiles.readySets().filter { baseTiles.file(it).isFile }.sortedBy { it.ncds }
        baseToggle.visibility = if (baseReady.isEmpty()) View.GONE else View.VISIBLE
        val baseSets = if (baseToggle.isChecked) baseReady else emptyList()
        baseDrawn = baseSets.map { baseTiles.file(it) }
        val baseKey = "|osm:" + baseSets.joinToString(",") { it.ncds + ":" + it.sha256 }
        if (drawn.isNotEmpty()) {
            val key = drawn.joinToString(",") { it.ncds + ":" + it.sha256 } + baseKey
            val w = drawn.minOf { it.bounds[0] }
            val so = drawn.minOf { it.bounds[1] }
            val e = drawn.maxOf { it.bounds[2] }
            val n = drawn.maxOf { it.bounds[3] }
            val heading = ChartTiles.regionsHeading(drawn)
            drawnSets = drawn
            if (shownKey != key) {
                loadError = false
                openFiles(loaded, drawn.map { tiles.file(it)!! }, doubleArrayOf(w, so, e, n), heading, region = true,
                    maxZooms = drawn.map { it.maxZoom })
            } else if (!loadError) {
                publishLegend(w, so, e, n, heading, region = true)
            }
            shownKey = key
        } else {
            drawnSets = emptyList()
            val key = "bundled$baseKey"
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

    private fun confirmDownload(set: ChartTiles.TileSet, store: ChartTileStore = tiles) {
        val tiles = store
        val what = store.kind.what
        if (tiles.freeBytes() < tiles.spaceNeeded(set)) {
            AlertDialog.Builder(this).setTitle("Not enough space")
                .setMessage("${set.shortName}'s $what is %.0f MB and the download needs about %.0f MB free. %.0f MB is free.".format(
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
        AlertDialog.Builder(this).setTitle("${set.shortName} $what, %.0f MB".format(set.size / 1e6))
            .setMessage(if (store.kind == ChartTileStore.Kind.BASEMAP)
                "Roads, towns, county lines and marinas from OpenStreetMap, drawn under the chart. Android downloads it in the background. It stays in the app and works offline. It has no depths."
                else "NOAA's chart for the map in this app. Android downloads it in the background and resumes after interruptions. It stays in the app and works offline. It is not an OsmAnd map.")
            .setPositiveButton("On Wi-Fi only") { _, _ -> go(false) }
            .setNeutralButton("Use mobile data too") { _, _ -> go(true) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun openFiles(
        loaded: MapLibreMap, files: List<File>, bounds: DoubleArray, heading: String, region: Boolean,
        maxZooms: List<Int>? = null,
    ) {
        val glyphs = glyphDir()
        val json = ChartTiles.style(depthFt, files.map { "mbtiles://${it.absolutePath}" }, "file://${glyphs.absolutePath}/{fontstack}/{range}.pbf",
            baseDrawn.map { "pmtiles://file://${it.absolutePath}" }, maxZooms)
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
            showTides(st)
            predictedLayers.clear() // the new style's sounding layers start charted
            if (predictedOn) refreshPredicted()
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
        // A check leg also gets the nearest station's tide, to judge when to be through it
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(this).apply { textSize = 15f; setPadding(dp(20), dp(12), dp(20), 0); text = body.toString() })
        val station = if (pick.flagged) Tides.nearest(tideStations, LatLon(lat, lon)).firstOrNull() else null
        if (station != null) {
            val km = Planner.distanceM(LatLon(lat, lon), station.point) / 1000.0
            content.addView(tidePanel(station, String.format(java.util.Locale.US, "\nTide at %s, the nearest station (%.1f km):", station.name, km), tideNeed(pick)))
        } else if (pick.flagged && pick.shoal) {
            content.addView(TextView(this).apply { textSize = 13f; setPadding(dp(20), dp(8), dp(20), 0)
                text = "No NOAA tide station within 30 km, or the station list is not downloaded yet." })
        }
        AlertDialog.Builder(this)
            .setTitle(if (pick.flagged) "Leg ${pick.leg}: check" else "Leg ${pick.leg}")
            .setView(android.widget.ScrollView(this).apply { addView(content) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Add via here") { _, _ -> returnPick(PICK_VIA, lat, lon) }
            .setNegativeButton("Destination here") { _, _ -> returnPick(PICK_DEST, lat, lon) }
            .setOnDismissListener { if (!destroyed) highlightLeg(-1) }
            .show()
        return true
    }

    /** Press and hold: use that spot as the start, a via point or the destination on the planning screen. */
    private fun showPoint(lat: Double, lon: Double) {
        val p = LatLon(lat, lon)
        AlertDialog.Builder(this)
            .setTitle(Coords.format(p))
            .setItems(arrayOf("Depth and tide here", "Set as start", "Add as via point", "Set as destination")) { _, which ->
                if (which == 0) showSpot(lat, lon)
                else returnPick(when (which) { 1 -> PICK_START; 2 -> PICK_VIA; else -> PICK_DEST }, lat, lon)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Depth and tide at a pressed spot: the chart's depth area, dredged depth and soundings
     * within 150 m (read from the downloaded chart tiles off the UI thread), then the nearest
     * station's tide with a red dotted line at the tide the shallowest of them needs for the
     * safe operating depth, and the times above it today.
     */
    private fun showSpot(lat: Double, lon: Double) {
        val p = LatLon(lat, lon)
        Thread {
            val spot = try { ChartDepthSource(this).spot(p) } catch (_: Exception) { null }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                val a = spot?.let { SpotReport.analyse(it, depthFt) }
                val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val text = a?.lines?.joinToString("\n") ?: "No downloaded chart covers this spot."
                content.addView(TextView(this).apply { textSize = 15f; setPadding(dp(20), dp(12), dp(20), 0)
                    this.text = text + "\n\nCharted depths are feet above chart datum (MLLW). Safe operating depth ${ChartTiles.depthText(depthFt)} ft." })
                val station = Tides.nearest(tideStations, p).firstOrNull()
                if (station != null) {
                    val km = Planner.distanceM(p, station.point) / 1000.0
                    content.addView(tidePanel(station, String.format(java.util.Locale.US, "\nTide at %s, the nearest station (%.1f km):", station.name, km),
                        spotNeed(a?.lowFt, a?.basis), a?.estimateFt))
                } else content.addView(TextView(this).apply { textSize = 13f; setPadding(dp(20), dp(8), dp(20), 0)
                    this.text = "No NOAA tide station within 30 km, or the station list is not downloaded yet." })
                AlertDialog.Builder(this)
                    .setTitle("Depth and tide: ${Coords.format(p)}")
                    .setView(android.widget.ScrollView(this).apply { addView(content) })
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Add via here") { _, _ -> returnPick(PICK_VIA, lat, lon) }
                    .setNegativeButton("Destination here") { _, _ -> returnPick(PICK_DEST, lat, lon) }
                    .show()
            }
        }.start()
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
        sb.append(predictedNote)
        sb.append("NOAA ENC of $heading, depths in feet. ")
        sb.append(ChartTiles.orientSentence(courseUp))
        sb.append(ChartTiles.spanText(w, e, (s + n) / 2))
        sb.append(ChartTiles.depthLegend(depthFt))
        sb.append("Green and red marks are port and starboard (IALA-B); a dark ring is a daybeacon. ")
        sb.append("The small dot beside a mark is its light, in that light's colour. ")
        if (routeJson != null) sb.append("Orange dashed legs are checks. The number on a leg is its row in the report. Tap a leg for its details; a check leg also shows the nearest station's tide, with a red dotted line at the tide its shallowest sounding needs for your depth. A bridge or cable drawn for vertical clearance is not that orange. ")
        if (routeJson != null) sb.append("An orange pin with ! is where a run of check legs starts, a green pin where it ends, like the GPX check markers. ")
        sb.append("A purple dashed outline is a restricted area from the route data, named along its edge: heavy where entry is restricted or prohibited or a security zone, light for anchoring, fishing or other rules. ")
        sb.append(if (airFt == null) "Overhead cables are dashed red: no air draft is set, so none was checked. "
            else "Overhead cables are dashed, red when the clearance is under your air draft or not charted, navy when it clears. ")
        sb.append("Tap a restricted area or a cable for its rule or clearance. ")
        sb.append("Teal dots (from zoom 9) are NOAA tide stations, filled when a year of predictions is saved on the phone: tap one for today's tides. ")
        sb.append("Search finds named buoys, lights, bridges, channels, towns, marinas and roads in your downloaded regions. ")
        if (baseDrawn.isNotEmpty()) sb.append("Roads, towns, county lines and marinas are from OpenStreetMap (\u00a9 OpenStreetMap contributors); it has no depths, and its shore may differ from NOAA's. ")
        sb.append("The blue dot is your GPS position; while Follow is on and the boat is moving, the blue line from it is your course over ground, with a ring where you will be in a minute at this speed. In Course up the boat sits low on the screen so most of the chart is ahead. The box above Search is your GPS speed over ground in mph. While Follow is on and the boat is moving, the screen stays on, until 5 minutes after it stops. Press and hold the chart for the depth and tide there, or to set the start or destination there or add a via point.")
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

    /**
     * Named marks, lights, bridges and channels from each drawn region chart, towns,
     * marinas and roads from each base map, and bridges and restricted areas from the
     * route packs. Read once per set of files, off the UI thread.
     */
    private fun loadSearch(): List<MapSearch.Item> {
        val charts = tiles.readySets().map { tiles.file(it) }.filter { it.isFile }
        val bases = baseTiles.readySets().map { baseTiles.file(it) }.filter { it.isFile }
        val key = (charts + bases).joinToString(",") { it.name + ":" + it.length() }
        searchItems?.let { if (key == searchKey) return it }
        val out = ArrayList<MapSearch.Item>()
        for (f in charts) try {
            readMetadata(f, "search")?.let { out.addAll(MapSearch.parse(it, "chart")) }
        } catch (e: Exception) { Log.w("ChartMap", "search index of ${f.name}", e) }
        for (f in bases) try {
            MapSearch.pmtilesMetadata(f)?.let { out.addAll(MapSearch.fromMetadata(it, "base map")) }
        } catch (e: Exception) { Log.w("ChartMap", "search index of ${f.name}", e) }
        try { out.addAll(MapSearch.fromNotes(ensurePacks().flatMap { it.notes })) } catch (_: Exception) { }
        searchItems = out
        searchKey = key
        return out
    }

    /** One metadata value of an MBTiles file, read in pieces (a cursor row holds about 2 MB). */
    private fun readMetadata(mbtiles: File, name: String): String? =
        SQLiteDatabase.openDatabase(mbtiles.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val len = db.rawQuery("select length(value) from metadata where name=?", arrayOf(name)).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            }
            if (len < 0) return null
            val sb = StringBuilder(len.toInt())
            val step = 512 * 1024
            var at = 1L
            while (at <= len) {
                db.rawQuery("select substr(value, ?, ?) from metadata where name=?", arrayOf(at.toString(), step.toString(), name)).use { c ->
                    if (c.moveToFirst()) sb.append(c.getString(0))
                }
                at += step
            }
            sb.toString()
        }

    /**
     * Search box: type part of a name ("mid riv", "bowleys", "light 2"). Results are
     * nearest the map's center first. Picking one moves the map there with a pin.
     */
    private fun showSearch() {
        val center = map?.cameraPosition?.target?.let { LatLon(it.latitude, it.longitude) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val input = EditText(this).apply {
            hint = "Buoy, light, bridge, town, marina, road"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val status = TextView(this).apply { setPadding(0, dp(4), 0, dp(4)); text = "Loading names..." }
        val list = ListView(this)
        box.addView(input)
        box.addView(status)
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        val d = AlertDialog.Builder(this).setTitle("Search the chart").setView(box)
            .setNegativeButton("Close", null).create()
        var shown: List<MapSearch.Item> = emptyList()
        fun refresh() {
            val items = searchItems ?: return
            val q = input.text.toString()
            shown = MapSearch.find(items, q, center)
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it ->
                val dist = center?.let { c -> " \u00b7 " + String.format(java.util.Locale.US, "%.1f nm", Planner.distanceM(c, LatLon(it.lat, it.lon)) / Planner.NM) } ?: ""
                "${it.name}\n${it.line}$dist"
            })
            status.text = when {
                q.isBlank() -> if (items.isEmpty()) "No names yet: download a region's chart or base map." else "${items.size} names from your downloaded charts, base maps and route data."
                shown.isEmpty() -> "Nothing matches \"$q\"."
                shown.size >= 60 -> "The 60 nearest matches. Type more to narrow them."
                else -> "${shown.size} ${if (shown.size == 1) "match" else "matches"}, nearest first."
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) { refresh() }
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val item = shown.getOrNull(pos) ?: return@setOnItemClickListener
            d.dismiss()
            goTo(item)
        }
        d.show()
        input.requestFocus()
        try {
            worker.execute {
                loadSearch()
                runOnUiThread { if (d.isShowing) refresh() }
            }
        } catch (_: RejectedExecutionException) { }
    }

    /** Center the map on a search result, with a pin and its name. Follow stops. */
    private fun goTo(item: MapSearch.Item) {
        val loaded = map ?: return
        if (following) { following = false; paintFollow() }
        (style?.getSource("search") as? GeoJsonSource)?.setGeoJson(MapSearch.pinGeoJson(item))
        val bearing = loaded.cameraPosition.bearing
        loaded.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(LatLng(item.lat, item.lon)).zoom(MapSearch.zoomFor(item.kind)).bearing(bearing).build()), 800)
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
                lm.requestLocationUpdates(p, ChartTiles.GPS_INTERVAL_MS, 0f, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {
                // Permission was withdrawn between the check and the request.
            } catch (_: Exception) { }
        }
    }

    private fun showFix(loc: Location) {
        val now = android.os.SystemClock.elapsedRealtime()
        val fixMs = loc.elapsedRealtimeNanos / 1_000_000
        if (loc.provider == LocationManager.GPS_PROVIDER) lastGpsMs = maxOf(lastGpsMs, fixMs)
        // A network fix between GPS fixes is coarse and has no speed: it made the dot jump
        else if (lastGpsMs > 0 && now - lastGpsMs < ChartTiles.GPS_FRESH_MS) return
        lastFix = loc
        if (loc.hasSpeed() && loc.speed >= ChartTiles.COURSE_SPEED_MPS && now - fixMs < ChartTiles.GPS_FRESH_MS)
            lastMovingMs = maxOf(lastMovingMs, fixMs)
        // The last known fix on opening can be old: its speed is not shown
        val left = ChartTiles.GPS_FRESH_MS - (now - fixMs)
        uiHandler.removeCallbacks(speedStale)
        if (left > 0) {
            speedLabel?.text = ChartTiles.speedText(loc.speed.takeIf { loc.hasSpeed() })
            uiHandler.postDelayed(speedStale, left)
        } else speedLabel?.text = ChartTiles.speedText(null)
        val bearing = ChartTiles.mapBearing(courseUp && following, loc.bearing.takeIf { loc.hasBearing() }, loc.speed, heldCourse)
        if (courseUp && following && loc.hasBearing() && loc.speed >= ChartTiles.COURSE_SPEED_MPS)
            heldCourse = bearing
        val glide = ChartTiles.glideMs(fixMs - prevFixMs)
        if (following) moveToFix(loc, bearing, glideMs = glide)
        prevFixMs = fixMs
        glideDot(loc.latitude, loc.longitude, glide)
        paintFollow()
    }

    /**
     * Slide the dot (with its heading line and 1 minute ring) from where it is drawn to the
     * new fix at a steady pace over [ms], like the camera, so it does not hop each second.
     * The first fix, or one more than [ChartTiles.GLIDE_MAX_M] away, jumps.
     */
    private fun glideDot(lat: Double, lon: Double, ms: Int) {
        dotGlide?.cancel()
        dotGlide = null
        val fromLat = shownLat
        val fromLon = shownLon
        val far = !fromLat.isFinite() || !fromLon.isFinite() ||
            LatLng(fromLat, fromLon).distanceTo(LatLng(lat, lon)) > ChartTiles.GLIDE_MAX_M
        if (far || destroyed) {
            shownLat = lat
            shownLon = lon
            return
        }
        dotGlide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val f = a.animatedFraction.toDouble()
                shownLat = fromLat + (lat - fromLat) * f
                shownLon = fromLon + (lon - fromLon) * f
                paintGps()
            }
            start()
        }
    }

    /**
     * The blue dot, and while following and moving a heading line along the GPS course,
     * twice the screen's diagonal long so it runs off the chart at any zoom.
     */
    private fun paintGps() {
        val loc = lastFix ?: return
        val st = style ?: return
        if (destroyed) return
        val lat = if (shownLat.isFinite()) shownLat else loc.latitude
        val lon = if (shownLon.isFinite()) shownLon else loc.longitude
        val course = ChartTiles.headingCourse(following, loc.bearing.takeIf { loc.hasBearing() }, loc.speed)
        var lengthM = 0.0
        if (course != null) {
            val b = try { map?.projection?.visibleRegion?.latLngBounds } catch (_: Exception) { null }
            lengthM = if (b != null) 2 * LatLng(b.latitudeNorth, b.longitudeWest).distanceTo(LatLng(b.latitudeSouth, b.longitudeEast))
                else 20000.0
            lengthM = lengthM.coerceIn(500.0, 200000.0)
        }
        (st.getSource("gps") as? GeoJsonSource)?.setGeoJson(
            ChartTiles.gpsGeoJson(lat, lon, course, lengthM, if (loc.hasSpeed()) loc.speed.toDouble() else 0.0))
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
        if (fix != null) moveToFix(fix, ChartTiles.mapBearing(courseUp, fix.bearing.takeIf { fix.hasBearing() }, fix.speed, heldCourse), snapZoom = true)
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

    /** Center on a fix. [snapZoom] (turning Follow on) zooms in to 15 from below 13; fixes keep your zoom. */
    /**
     * Center on a fix. [snapZoom] (turning Follow on) zooms in to 15 from below 13; fixes keep your zoom.
     * With [glideMs] the camera moves at a steady pace (no easing) for about the time to the next
     * fix, so the chart glides instead of hopping; a new fix mid-glide carries on from there.
     */
    private fun moveToFix(loc: Location, bearing: Double, snapZoom: Boolean = false, glideMs: Int = 0) {
        val loaded = map ?: return
        if (destroyed || scaling) return
        val z = loaded.cameraPosition.zoom
        val zoom = if (snapZoom && z < 13.0) 15.0 else z
        // Course up: the boat 3/4 of the way down, so most of the screen is ahead
        var target = LatLng(loc.latitude, loc.longitude)
        if (courseUp) {
            val mpd = loaded.projection.getMetersPerPixelAtLatitude(loc.latitude) * Math.pow(2.0, z - zoom)
            val heightDp = mapView.height / resources.displayMetrics.density.toDouble()
            val t = ChartTiles.lookAheadTarget(loc.latitude, loc.longitude, bearing, mpd, heightDp)
            target = LatLng(t.lat, t.lon)
        }
        val pos = CameraPosition.Builder()
            .target(target)
            .zoom(zoom)
            .bearing(bearing)
            .tilt(0.0)
            .build()
        if (glideMs > 0) loaded.easeCamera(CameraUpdateFactory.newCameraPosition(pos), glideMs, false)
        else loaded.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 400)
    }

    private fun resetNorth() {
        courseUp = false
        heldCourse = 0.0
        prefs.edit().putBoolean(ChartTiles.COURSE_PREF, false).apply()
        courseButton?.text = "North up"
        val loaded = map ?: return
        val cam = loaded.cameraPosition
        loaded.easeCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(cam).bearing(0.0).tilt(0.0).build()), 300)
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

    /**
     * Keep the screen on while following a moving boat, and for [ChartTiles.KEEP_AWAKE_MS]
     * after it stops; then the phone's own timeout applies again.
     */
    private fun paintAwake() {
        uiHandler.removeCallbacks(awakeCheck)
        val now = android.os.SystemClock.elapsedRealtime()
        if (!destroyed && ChartTiles.keepAwake(following, lastMovingMs, now)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            uiHandler.postDelayed(awakeCheck, lastMovingMs + ChartTiles.KEEP_AWAKE_MS - now + 50)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun paintFollow() {
        paintGps()
        paintAwake()
        followButton?.text = when {
            following && lastFix == null -> "Waiting for GPS"
            following -> "Following"
            else -> "Follow"
        }
    }

    /** The distance scale for the map center's latitude (MapLibre's pixels are dp). */
    private fun paintScale() {
        val m = map ?: return
        val lat = m.cameraPosition.target?.latitude ?: return
        scaleBar?.show(m.projection.getMetersPerPixelAtLatitude(lat))
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
        dotGlide?.cancel()
        dotGlide = null
        lastFix?.let { shownLat = it.latitude; shownLon = it.longitude }
        uiHandler.removeCallbacks(awakeCheck)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        uiHandler.removeCallbacks(speedStale)
        speedLabel?.text = ChartTiles.speedText(null)
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
     * Restricted areas and overhead cables named in leg notes come from the route packs,
     * not the chart tiles, so they are drawn from the packs' own outlines whether or not
     * Route data is on.
     */
    private fun showRestricted(st: Style) {
        fun apply(target: Style?) {
            restrictedJson?.let { (target?.getSource("restricted") as? GeoJsonSource)?.setGeoJson(it) }
            cablesJson?.let { (target?.getSource("cables") as? GeoJsonSource)?.setGeoJson(it) }
        }
        if (restrictedJson != null) { apply(st); return }
        val air = airFt
        try {
            worker.execute {
                if (destroyed) return@execute
                try {
                    val notes = ensurePacks().flatMap { it.notes }
                    cablesJson = ChartTiles.cablesGeoJson(notes, air)
                    restrictedJson = ChartTiles.restrictedGeoJson(notes)
                    packNotes = notes
                } catch (e: Exception) {
                    Log.w("ChartMap", "restricted areas failed", e); return@execute
                }
                runOnUiThread { if (!destroyed) apply(style) }
            }
        } catch (_: RejectedExecutionException) { }
    }

    /**
     * NOAA tide stations in the downloaded regions (and any with predictions saved), on
     * their own thread: the station list may have to be fetched first (about 2 MB, once).
     */
    private fun showTides(st: Style) {
        fun apply(target: Style?) {
            tidesJson?.let { (target?.getSource("tides") as? GeoJsonSource)?.setGeoJson(it) }
            if (predictedOn) refreshPredicted()
        }
        if (tidesJson != null) { apply(st); return }
        Thread {
            try {
                val cache = TideStore(this).cache
                val all = cache.stations().ifEmpty { try { cache.refreshStations() } catch (_: Exception) { emptyList() } }
                val saved = all.filter { cache.isSaved(it.id) }
                val shown = (TideStore(this).wanted(all) + saved).distinctBy { it.id }
                tideStations = shown
                tidesJson = Tides.stationsGeoJson(shown, saved.map { it.id }.toSet())
                runOnUiThread { if (!destroyed) apply(style) }
            } catch (e: Exception) {
                Log.w("ChartMap", "tide stations failed", e)
            }
        }.start()
    }

    /**
     * Predicted depth: the station nearest the map center (within 30 km), its predicted
     * height now added to every sounding and contour label (whole feet, rounded down), in
     * teal so they do not read as charted, red under the safe operating depth. Off, or with
     * no station or predictions, the labels are the chart's again. The key says which.
     */
    private fun refreshPredicted() {
        val st = style ?: return
        fun charted(note: String) {
            // The chart's own label and colors, as ChartTiles.STYLE writes them (MapLibre's
            // getters hand back plain values that do not set paint properties again)
            val field = Expression.raw("[\"to-string\",[\"get\",\"ft\"]]")
            val sounding = Expression.raw(String.format(java.util.Locale.US,
                "[\"case\",[\"<\",[\"get\",\"ft\"],%s],[\"to-color\",\"#9b1c1c\"],[\"to-color\",\"#1a1a1a\"]]", depthFt.toString()))
            for (id in predictedLayers) {
                val sym = st.getLayer(id) as? SymbolLayer ?: continue
                val contour = id.substringBefore('@').substringBefore('~') == "depcnt-label"
                sym.setProperties(PropertyFactory.textField(field),
                    if (contour) PropertyFactory.textColor("#2c5270") else PropertyFactory.textColor(sounding))
            }
            predictedLayers.clear()
            setPredictedNote(note)
        }
        if (!predictedOn) { charted(""); return }
        val center = map?.cameraPosition?.target ?: return
        if (tideStations.isEmpty()) { charted("Predicted depth is on, but the tide station list is not loaded yet: depths shown are charted. "); return }
        val station = Tides.nearest(tideStations, LatLon(center.latitude, center.longitude)).firstOrNull()
            ?: return charted("Predicted depth is on, but no NOAA tide station is within 30 km of the map center: depths shown are charted. ")
        val series = predictedSeries?.takeIf { it.station == station.id } ?: TideStore(this).cache.series(station.id)?.also { predictedSeries = it }
        val minute = Tides.minuteOf(java.time.Instant.now())
        val h = series?.height(minute)
        if (series == null || h == null) {
            if (predictedFetching.add(station.id)) Thread {
                val got = try { TideStore(this).cache.ensure(station.id) } catch (_: Exception) { null }
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    if (got != null) predictedSeries = got else predictedFailed.add(station.id)
                    refreshPredicted()
                }
            }.start()
            charted(when {
                station.id in predictedFailed -> "Predicted depth is on, but ${station.name}'s predictions are not saved and NOAA could not be reached: depths shown are charted. "
                series == null -> "Predicted depth is on: getting ${station.name}'s predictions from NOAA; until then depths shown are charted. "
                else -> "Predicted depth is on, but ${station.name} has no predictions saved for now: depths shown are charted. "
            })
            return
        }
        val sum = String.format(java.util.Locale.US, "[\"floor\",[\"+\",[\"get\",\"ft\"],%.3f]]", h)
        val field = Expression.raw("[\"to-string\",$sum]")
        val color = Expression.raw(String.format(java.util.Locale.US,
            "[\"case\",[\"<\",$sum,%.2f],[\"to-color\",\"#9b1c1c\"],[\"to-color\",\"#00707a\"]]", depthFt))
        for (layer in st.layers) {
            val base = layer.id.substringBefore('@').substringBefore('~')
            if (base != "soundg" && base != "depcnt-label") continue
            val sym = layer as? SymbolLayer ?: continue
            predictedLayers.add(layer.id)
            sym.setProperties(PropertyFactory.textField(field), PropertyFactory.textColor(color))
        }
        setPredictedNote(Tides.predictedKey(TideAt(station, series), minute, java.time.ZoneId.systemDefault()) ?: "")
    }

    private fun setPredictedNote(note: String) {
        if (note == predictedNote) return
        predictedNote = note
        if (keyReady) publishLegend(keyW, keyS, keyE, keyN, keyHeading, keyRegion)
    }

    /** What a check leg needs from the tide: the height to draw, and why (or why there is none). */
    private class TideNeed(val feet: Double?, val text: String)

    /** The tide line for a spot: [lowFt] charted, described by [basis]. */
    private fun spotNeed(lowFt: Double?, basis: String?): TideNeed {
        val d = ChartTiles.depthText(depthFt)
        if (lowFt == null || basis == null) return TideNeed(null, "No charted depth here to work out the tide needed.")
        val need = depthFt - lowFt
        if (need <= 0) return TideNeed(null, "Your $d ft is there at chart datum over $basis.")
        return TideNeed(need, String.format(java.util.Locale.US,
            "Red dotted line: %.1f ft of tide gives your %s ft over %s. Shallower water may be uncharted.", need, d, basis))
    }

    private fun tideNeed(pick: ChartTiles.LegPick): TideNeed? {
        if (!pick.shoal) return null
        val d = ChartTiles.depthText(depthFt)
        val low = pick.lowFt ?: return TideNeed(null,
            "No sounding is charted within 100 m of this leg, so the tide it needs cannot be worked out from the chart.")
        val need = depthFt - low
        if (need <= 0) return TideNeed(null, String.format(java.util.Locale.US,
            "The shallowest sounding near this leg, %.1f ft, already gives your %s ft at chart datum; the leg is flagged for its depth area.", low, d))
        return TideNeed(need, String.format(java.util.Locale.US,
            "Red dotted line: %.1f ft of tide gives your %s ft over the %.1f ft sounding, the shallowest charted within 100 m of this leg. " +
                "Shallower water may be uncharted.", need, d, low))
    }

    /**
     * A station's tide for today: highs and lows, the height now and the curve, with the
     * caveat. With [need], the red dotted line and the times the water is above it. With
     * [estimateFt] (Depth and tide here), the estimated depth at the spot with the tide now.
     * A station without saved predictions (or with too few) is fetched while the box is open.
     */
    private fun tidePanel(station: TideStation, heading: String?, need: TideNeed?, estimateFt: Double? = null): LinearLayout {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val info = TextView(this).apply { textSize = 15f; setPadding(dp(20), dp(8), dp(20), 0) }
        val curve = TideCurveView(this, systemNight()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { setMargins(dp(12), dp(8), dp(12), 0) }
        }
        val window = TextView(this).apply { textSize = 15f; setPadding(dp(20), dp(8), dp(20), 0) }
        val caveat = TextView(this).apply { textSize = 13f; setPadding(dp(20), dp(8), dp(20), dp(4)); text = Tides.CAVEAT }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(info); addView(curve); addView(window); addView(caveat) }
        fun fill(series: TideSeries?, failed: String?) {
            val now = Tides.minuteOf(java.time.Instant.now())
            val day = series?.let { Tides.dayText(it, today, zone) }
            val head = heading?.let { "$it\n" } ?: ""
            info.text = head + when {
                day != null -> "Today (${Tides.PREDICTED}):\n" + day.split(", ").joinToString("\n") +
                    (Tides.nowText(series!!, now)?.let { "\n\n" + it.replaceFirstChar { c -> c.uppercaseChar() } } ?: "")
                failed != null -> failed
                else -> "Getting this station's predictions from NOAA..."
            }
            curve.visibility = if (day != null) View.VISIBLE else View.GONE
            if (day != null) curve.show(series!!, today, zone, now, need?.feet)
            window.visibility = if (need != null) View.VISIBLE else View.GONE
            if (need != null) window.text = need.text + (need.feet?.takeIf { day != null }?.let { f ->
                " Above it today: " + (Tides.aboveText(Tides.above(series!!, today, zone, f), today, zone)
                    ?: "never; the predicted tide does not reach it.")
            } ?: "") + (estimateFt?.takeIf { day != null }?.let { e ->
                SpotReport.estimateWithTide(e, series!!, now, today, zone, depthFt)?.let { "\n\n$it" }
            } ?: "")
        }
        val cache = TideStore(this).cache
        val saved = cache.series(station.id)
        fill(saved, null)
        val stale = saved == null || saved.day(today, zone).isEmpty() || cache.needsFetch(station.id)
        if (stale) Thread {
            val got = try { cache.ensure(station.id) } catch (_: Exception) { null }
            val ok = got?.day(today, zone)?.isNotEmpty() == true
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (ok || saved == null) fill(got ?: saved, if (got == null) "Not downloaded yet, and NOAA could not be reached. " +
                    "It comes with the region's tides on Wi-Fi, or when a trip near it is planned." else "NOAA has no predictions for today here.")
                if (got != null && saved == null) { tidesJson = null; style?.let { showTides(it) } }
            }
        }.start()
        return box
    }

    /**
     * A tap on a tide station: today's predicted highs and lows, the height now and a curve
     * of the day, with the via and destination buttons.
     */
    private fun showTide(lat: Double, lon: Double): Boolean {
        if ((map?.cameraPosition?.zoom ?: 0.0) < 9.0) return false
        val station = Tides.nearest(tideStations, LatLon(lat, lon), maxM = fingerM(lat)).firstOrNull() ?: return false
        AlertDialog.Builder(this)
            .setTitle("Tides: ${station.name}" + if (station.state.isNotEmpty()) ", ${station.state}" else "")
            .setView(android.widget.ScrollView(this).apply { addView(tidePanel(station, null, null)) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Add via here") { _, _ -> returnPick(PICK_VIA, lat, lon) }
            .setNegativeButton("Destination here") { _, _ -> returnPick(PICK_DEST, lat, lon) }
            .show()
        return true
    }

    /**
     * A tap off the route on a restricted area or by an overhead cable: the report's
     * sentence for each, with the same via and destination buttons as a leg.
     */
    private fun showNotes(lat: Double, lon: Double): Boolean {
        val found = ChartTiles.notesAt(packNotes, lat, lon, fingerM(lat), airFt)
        if (found.isEmpty()) return false
        val cablesOnly = found.all { it.contains("cable") }
        val body = found.joinToString("\n\n") { "\u2022 $it" } +
            "\n\nFrom the route data. Read the rule on a current chart; the outline is simplified."
        AlertDialog.Builder(this)
            .setTitle(if (cablesOnly) "Overhead cable" else if (found.size == 1) "Restricted area" else "Restricted areas")
            .setMessage(body)
            .setPositiveButton("Close", null)
            .setNeutralButton("Add via here") { _, _ -> returnPick(PICK_VIA, lat, lon) }
            .setNegativeButton("Destination here") { _, _ -> returnPick(PICK_DEST, lat, lon) }
            .show()
        return true
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
        uiHandler.removeCallbacks(predictedTick)
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
