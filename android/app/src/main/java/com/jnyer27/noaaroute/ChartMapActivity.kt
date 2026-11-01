package com.jnyer27.noaaroute

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Offline NOAA ENC chart, with the planned route drawn on it.
 * MapLibre Native (BSD-2-Clause) renders an MBTiles file from tools/build_enc_tiles.py.
 * When this view is inside a downloaded region chart (Region 04 today), that file is the
 * source. Otherwise the bundled Middle River chart is shown, and the screen offers the
 * region download. There is no online base map: the picture is NOAA ENC only, so the
 * license is NOAA's public domain data plus MapLibre's own notice (the logo stays on the map).
 */
class ChartMapActivity : Activity() {
    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_ROUTE_JSON = "routeJson"
        const val EXTRA_DEPTH = "depthFt"
        const val EXTRA_NAME = "name"
    }

    private lateinit var mapView: MapView
    private lateinit var legend: TextView
    private lateinit var downloadButton: Button
    private val tiles by lazy { ChartTileStore(this) }
    private val uiHandler = Handler(Looper.getMainLooper())
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var destroyed = false
    private var depthFt = 3.0
    private var routeJson: String? = null
    /** "04:<sha>" for a region chart, or "bundled". Skip a redraw when it has not changed. */
    private var shownKey: String? = null
    private var loadError = false
    private var chartNote = ""
    private val worker = Executors.newSingleThreadExecutor()
    private val listener = LocationListener { loc -> showFix(loc) }
    private val poll = object : Runnable {
        override fun run() {
            if (destroyed) return
            present()
            uiHandler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        depthFt = intent.getDoubleExtra(EXTRA_DEPTH, 3.0)
        routeJson = intent.getStringExtra(EXTRA_ROUTE_JSON) ?: intent.getStringExtra(EXTRA_ROUTE)?.let { path ->
            try { File(path).takeIf { it.isFile }?.readText() } catch (_: Exception) { null }
        }
        title = "Chart"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        val frame = FrameLayout(this)
        frame.addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        legend = TextView(this).apply {
            text = "Opening the chart..."
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        root.addView(legend)
        downloadButton = Button(this).apply { visibility = View.GONE }
        root.addView(downloadButton)
        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        })
        setContentView(root)
        insetPadding(root)

        mapView.getMapAsync { loaded ->
            map = loaded
            loaded.uiSettings.isRotateGesturesEnabled = false
            loaded.uiSettings.isTiltGesturesEnabled = false
            loaded.uiSettings.isCompassEnabled = false
            mapView.addOnDidFailLoadingMapListener { message ->
                if (destroyed) return@addOnDidFailLoadingMapListener
                loadError = true
                legend.text = "Chart did not load: $message"
            }
            present()
            uiHandler.post(poll)
            worker.execute {
                tiles.reconcile()
                try { tiles.refreshCatalog() } catch (_: Exception) { }
                runOnUiThread { if (!destroyed) present() }
            }
        }
    }

    /** Choose the region chart or the bundled Middle River file, and the download button. */
    private fun present() {
        val loaded = map ?: return
        val pts = routePoints()
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
        val file = chosen?.let { tiles.file(it) }?.takeIf { it.isFile }
        if (chosen != null && file != null) {
            val key = chosen.ncds + ":" + chosen.sha256
            chartNote = status
            if (shownKey != key) {
                loadError = false
                openFile(loaded, file, chosen.bounds, chosen.shortName, region = true)
            } else if (!loadError) {
                legend.text = legendText(chosen.bounds[0], chosen.bounds[1], chosen.bounds[2], chosen.bounds[3], chosen.shortName, region = true)
            }
            shownKey = key
        } else {
            chartNote = status
            val key = "bundled"
            if (shownKey != key) {
                loadError = false
                val mb = bundled()
                val bounds = readBounds(mb) ?: doubleArrayOf(ChartTiles.WEST, ChartTiles.SOUTH, ChartTiles.EAST, ChartTiles.NORTH)
                openFile(loaded, mb, bounds, "Middle River", region = false)
            } else if (!loadError) {
                legend.text = legendText(ChartTiles.WEST, ChartTiles.SOUTH, ChartTiles.EAST, ChartTiles.NORTH, "Middle River", region = false)
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

    private fun openFile(loaded: MapLibreMap, mb: File, bounds: DoubleArray, heading: String, region: Boolean) {
        val glyphs = glyphDir()
        val json = ChartTiles.style(depthFt, "mbtiles://${mb.absolutePath}", "file://${glyphs.absolutePath}/{fontstack}/{range}.pbf")
        title = "$heading chart"
        val w = bounds[0]; val s = bounds[1]; val e = bounds[2]; val n = bounds[3]
        legend.text = legendText(w, s, e, n, heading, region)
        loaded.setStyle(Style.Builder().fromJson(json)) { st ->
            style = st
            routeJson?.let { (st.getSource("route") as? GeoJsonSource)?.setGeoJson(it) }
            loaded.setMinZoomPreference(8.0)
            loaded.setMaxZoomPreference(18.0)
            position(loaded, w, s, e, n)
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

    private fun legendText(w: Double, s: Double, e: Double, n: Double, heading: String, region: Boolean): String {
        val depth = ChartTiles.depthText(depthFt)
        val outside = routePoints().any { it.lon !in w..e || it.lat !in s..n }
        val sb = StringBuilder()
        sb.append("Proof of concept, not for navigation. NOAA ENC of $heading, depths in feet. ")
        sb.append("Safe operating depth $depth ft: darker blue is charted under it, pale blue is deeper, olive dries. ")
        sb.append("Green and red marks are port and starboard (IALA-B); a dark ring is a daybeacon. ")
        sb.append("The small dot beside a mark is its light, in that light's colour. ")
        if (routeJson != null) sb.append("Orange dashed legs are checks. ")
        sb.append("The blue dot is your GPS position.")
        if (outside) sb.append(if (region) " Charts cover this region; past that, the route is the line alone."
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
        val lm = getSystemService(LocationManager::class.java)
        try { lm.removeUpdates(listener) } catch (_: Exception) { }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!lm.isProviderEnabled(p)) continue
                lm.getLastKnownLocation(p)?.let { showFix(it) }
                lm.requestLocationUpdates(p, 2000L, 2f, listener, Looper.getMainLooper())
            } catch (_: Exception) { }
        }
    }

    private fun showFix(loc: Location) {
        val st = style ?: return
        if (destroyed) return
        (st.getSource("gps") as? GeoJsonSource)?.setGeoJson(ChartTiles.gpsGeoJson(loc.latitude, loc.longitude))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
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
    override fun onDestroy() {
        destroyed = true
        uiHandler.removeCallbacks(poll)
        worker.shutdownNow()
        mapView.onDestroy()
        super.onDestroy()
    }
}
