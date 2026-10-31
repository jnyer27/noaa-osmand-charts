package com.jnyer27.noaaroute

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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
 * Offline chart of Middle River, MD, with the planned route drawn on it.
 * MapLibre Native (BSD-2-Clause) renders the MBTiles file produced by tools/build_enc_tiles.py.
 * There is no online base map: the picture is NOAA ENC only, so the license is NOAA's public
 * domain data plus MapLibre's own notice (the logo stays on the map).
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
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var destroyed = false
    private var depthFt = 3.0
    private var routeJson: String? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val listener = LocationListener { loc -> showFix(loc) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        depthFt = intent.getDoubleExtra(EXTRA_DEPTH, 3.0)
        routeJson = intent.getStringExtra(EXTRA_ROUTE_JSON) ?: intent.getStringExtra(EXTRA_ROUTE)?.let { path ->
            try { File(path).takeIf { it.isFile }?.readText() } catch (_: Exception) { null }
        }
        title = "Middle River chart"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        val frame = FrameLayout(this)
        frame.addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        legend = TextView(this).apply {
            text = "Opening the Middle River chart..."
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        root.addView(legend)
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
                if (!destroyed) legend.text = "Chart did not load: $message"
            }
            worker.execute {
                try {
                    val chart = prepare()
                    val bounds = readBounds(chart.mbtiles) ?: doubleArrayOf(
                        ChartTiles.WEST, ChartTiles.SOUTH, ChartTiles.EAST, ChartTiles.NORTH)
                    val json = ChartTiles.style(
                        depthFt,
                        "mbtiles://${chart.mbtiles.absolutePath}",
                        "file://${chart.glyphs.absolutePath}/{fontstack}/{range}.pbf")
                    runOnUiThread {
                        if (destroyed) return@runOnUiThread
                        show(loaded, json, bounds)
                    }
                } catch (e: Exception) {
                    runOnUiThread { if (!destroyed) legend.text = "Chart file failed: ${e.message}" }
                }
            }
        }
    }

    private fun show(loaded: MapLibreMap, json: String, bounds: DoubleArray) {
        val w = bounds[0]; val s = bounds[1]; val e = bounds[2]; val n = bounds[3]
        legend.text = legendText(w, s, e, n)
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

    /** Fit the part of the route that falls on this chart, or the chart itself. */
    private fun position(loaded: MapLibreMap, w: Double, s: Double, e: Double, n: Double) {
        val pts = routePoints()
        val inside = pts.filter { it.lon in w..e && it.lat in s..n }
        val b = LatLngBounds.Builder()
        val use = if (inside.isNotEmpty()) inside else listOf(LatLon((s + n) / 2, (w + e) / 2))
        for (p in use) b.include(LatLng(p.lat, p.lon))
        if (use.size == 1) b.include(LatLng(use[0].lat + 0.01, use[0].lon + 0.01))
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

    private fun legendText(w: Double, s: Double, e: Double, n: Double): String {
        val depth = ChartTiles.depthText(depthFt)
        val outside = routePoints().any { it.lon !in w..e || it.lat !in s..n }
        val sb = StringBuilder()
        sb.append("Proof of concept, not for navigation. NOAA ENC of Middle River, depths in feet. ")
        sb.append("Safe operating depth $depth ft: darker blue is charted under it, pale blue is deeper, olive dries. ")
        sb.append("Green and red marks are port and starboard (IALA-B); a dark ring is a daybeacon. ")
        sb.append("The small dot beside a mark is its light, in that light's colour. ")
        if (routeJson != null) sb.append("Orange dashed legs are checks. ")
        sb.append("The blue dot is your GPS position.")
        if (outside) sb.append(" Charts cover Middle River only; past that, the route is the line alone.")
        return sb.toString()
    }

    private fun prepare(): LocalChart {
        val mb = File(filesDir, ChartTiles.ASSET)
        copyAsset(ChartTiles.ASSET, mb)
        val glyphs = File(filesDir, "glyphs")
        copyAsset(ChartTiles.GLYPH_ASSET, File(glyphs, "${ChartTiles.FONT}/0-255.pbf"))
        return LocalChart(mb, glyphs)
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
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
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
        worker.shutdownNow()
        mapView.onDestroy()
        super.onDestroy()
    }

    private class LocalChart(val mbtiles: File, val glyphs: File)
}
