package com.jnyer27.noaaroute

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        const val PACK_BASE = "https://github.com/jnyer27/noaa-osmand-charts/releases/download/route-pack/"
        const val PACK_NAME = "md-route-pack"
        const val ROUTES_DIR = "noaa-charts/routes"
        val DEFAULT_PLACES = listOf(
            "Maryland Marina" to "39.32371, -76.39998",
            "Hart-Miller Island beach" to "39.24700, -76.38800",
            "Bear Creek" to "39.25084, -76.48861",
            "Rock Hall Harbor" to "39.13276, -76.24449",
            "Annapolis, Ego Alley" to "38.97731, -76.48559",
            "Chesapeake City dock" to "39.52680, -75.81070",
        )
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("noaaroute", Context.MODE_PRIVATE) }
    private var pack: Pack? = null
    private var route: Route? = null
    private var gpxUri: Uri? = null

    private lateinit var packStatus: TextView
    private lateinit var packButton: Button
    private lateinit var start: EditText
    private lateinit var dest: EditText
    private lateinit var via: EditText
    private lateinit var depth: EditText
    private lateinit var air: EditText
    private lateinit var name: EditText
    private lateinit var planButton: Button
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var actions: LinearLayout

    // ------------------------------------------------------------ layout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, dp(12), 0, dp(2))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setSingleLine()
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((k, v) in views.withIndex()) addView(v, LinearLayout.LayoutParams(
            if (k == 0 && v is EditText) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, if (k == 0 && v is EditText) 1f else 0f))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "NOAA Route"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        packStatus = TextView(this)
        packButton = button("Download charts") { updatePack() }
        col.addView(label("Charts"))
        col.addView(packStatus)
        col.addView(packButton)

        start = field("LAT, LON or pick a place")
        col.addView(label("Start"))
        col.addView(row(start, button("Places") { pickPlace(start) }, button("Here") { useHere() }))
        dest = field("LAT, LON or pick a place")
        col.addView(label("Destination"))
        col.addView(row(dest, button("Places") { pickPlace(dest) }))
        via = field("optional, separate points with ;")
        col.addView(label("Via points"))
        col.addView(row(via, button("Add") { pickPlace(via, append = true) }))

        val num = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        depth = field("e.g. 3 (draft plus margin)", num)
        air = field("optional", num)
        col.addView(label("Safe operating depth (ft)"))
        col.addView(depth)
        col.addView(label("Air draft (ft)"))
        col.addView(air)
        name = field("route name")
        col.addView(label("Name"))
        col.addView(name)

        planButton = button("Plan route") { plan() }
        col.addView(planButton)
        status = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        col.addView(status)
        result = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(result)
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        actions.addView(button("Open in OsmAnd") { openInOsmAnd() })
        actions.addView(button("Show routes folder in Files") { showFolder() })
        actions.addView(button("Share GPX") { shareGpx() })
        col.addView(actions)
        col.addView(button("Manage places") { managePlaces() })

        setContentView(ScrollView(this).apply { addView(col) })

        depth.setText(prefs.getString("depth", "3"))
        air.setText(prefs.getString("air", ""))
        start.setText(prefs.getString("start", ""))
        dest.setText(prefs.getString("dest", ""))
        openPack()
        checkForUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
        pack?.close()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ route pack

    private fun packFile() = File(filesDir, "$PACK_NAME.bin")

    private fun openPack() {
        pack?.close()
        pack = null
        val f = packFile()
        if (!f.exists()) {
            packStatus.text = "No charts yet. Download them once on Wi-Fi (about 20-60 MB); planning then works offline."
            packButton.text = "Download charts"
            return
        }
        try {
            val p = Pack(f)
            pack = p
            packStatus.text = "NOAA ENC ${p.states.joinToString()} built ${p.created.take(10)}, %.0f MB, %.0f m grid"
                .format(f.length() / 1e6, p.res)
            packButton.text = "Check for chart update"
        } catch (e: Exception) {
            packStatus.text = "Chart file is damaged: ${e.message}. Download again."
            packButton.text = "Download charts"
        }
    }

    private fun httpGet(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.instanceFollowRedirects = true
        if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode} for $url")
        return c
    }

    private fun fetchMeta(): JSONObject =
        JSONObject(httpGet(PACK_BASE + "$PACK_NAME.json").inputStream.bufferedReader().readText())

    private fun checkForUpdate() {
        if (pack == null) return
        worker.execute {
            try {
                val meta = fetchMeta()
                val created = meta.getString("created")
                ui.post {
                    if (pack != null && created > pack!!.created) {
                        packStatus.append("\nNewer charts available (${created.take(10)}).")
                        packButton.text = "Download chart update"
                    }
                }
            } catch (_: Exception) { /* offline: keep what we have */ }
        }
    }

    private fun updatePack() {
        packButton.isEnabled = false
        status.text = "Checking for charts..."
        worker.execute {
            try {
                val meta = fetchMeta()
                val created = meta.getString("created")
                if (pack != null && created <= pack!!.created) {
                    ui.post { status.text = "Charts are up to date (${created.take(10)})."; packButton.isEnabled = true }
                    return@execute
                }
                val size = meta.getLong("size")
                val part = File(filesDir, "$PACK_NAME.part")
                val md = MessageDigest.getInstance("SHA-256")
                httpGet(PACK_BASE + "$PACK_NAME.bin").inputStream.use { inp ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var got = 0L
                        var last = 0L
                        while (true) {
                            val r = inp.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            md.update(buf, 0, r)
                            got += r
                            if (got - last > 1_000_000) {
                                last = got
                                ui.post { status.text = "Downloading charts: %.0f of %.0f MB".format(got / 1e6, size / 1e6) }
                            }
                        }
                    }
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (sha != meta.getString("sha256")) throw Exception("download check failed, try again")
                ui.post {
                    pack?.close()
                    pack = null
                    part.renameTo(packFile())
                    openPack()
                    status.text = "Charts updated (${created.take(10)})."
                    packButton.isEnabled = true
                }
            } catch (e: Exception) {
                ui.post { status.text = "Chart download failed: ${e.message}"; packButton.isEnabled = true }
            }
        }
    }

    // ------------------------------------------------------------ places

    private fun places(): MutableList<Pair<String, String>> {
        val s = prefs.getString("places", null) ?: return DEFAULT_PLACES.toMutableList()
        val a = JSONArray(s)
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("name") to o.getString("pos") } }.toMutableList()
    }

    private fun savePlaces(list: List<Pair<String, String>>) {
        val a = JSONArray()
        for ((n, p) in list) a.put(JSONObject().put("name", n).put("pos", p))
        prefs.edit().putString("places", a.toString()).apply()
    }

    private fun pickPlace(target: EditText, append: Boolean = false) {
        val list = places()
        AlertDialog.Builder(this).setTitle("Places")
            .setItems(list.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, k ->
                val v = list[k].second
                if (append && target.text.isNotBlank()) target.setText("${target.text}; $v") else target.setText(v)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun managePlaces() {
        val list = places()
        val items = list.map { "${it.first}\n${it.second}" }.toMutableList()
        items.add(0, "+ Add a place")
        AlertDialog.Builder(this).setTitle("Places (tap to edit or delete)")
            .setItems(items.toTypedArray()) { _, k ->
                if (k == 0) editPlace(list, -1) else editPlace(list, k - 1)
            }.setNegativeButton("Close", null).show()
    }

    private fun editPlace(list: MutableList<Pair<String, String>>, k: Int) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val n = field("name").apply { if (k >= 0) setText(list[k].first) }
        val p = field("LAT, LON").apply { setText(if (k >= 0) list[k].second else start.text.toString()) }
        box.addView(n)
        box.addView(p)
        val d = AlertDialog.Builder(this).setTitle(if (k >= 0) "Edit place" else "Add place").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val ll = Coords.parse(p.text.toString())
                if (n.text.isBlank() || ll == null) { toast("Need a name and LAT, LON"); return@setPositiveButton }
                val entry = n.text.toString().trim() to Coords.format(ll)
                if (k >= 0) list[k] = entry else list.add(entry)
                savePlaces(list)
            }.setNegativeButton("Cancel", null)
        if (k >= 0) d.setNeutralButton("Delete") { _, _ -> list.removeAt(k); savePlaces(list) }
        d.show()
    }

    // ------------------------------------------------------------ location

    private fun useHere() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        status.text = "Getting GPS fix..."
        val done = { loc: Location? ->
            if (loc == null) status.text = "No location fix. Turn on Location or type a position."
            else {
                start.setText(Coords.format(LatLon(loc.latitude, loc.longitude)))
                status.text = "Start set to current location (accuracy %.0f m).".format(loc.accuracy)
            }
        }
        val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, mainExecutor) { done(it ?: lm.getLastKnownLocation(provider)) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, { done(it) }, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            status.text = "Location permission missing."
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) useHere()
        else status.text = "Location permission denied; type the start position instead."
    }

    // ------------------------------------------------------------ planning

    private fun plan() {
        val p = pack ?: run { toast("Download the charts first."); return }
        val a = Coords.parse(start.text.toString()) ?: run { toast("Start must be LAT, LON"); return }
        val b = Coords.parse(dest.text.toString()) ?: run { toast("Destination must be LAT, LON"); return }
        val vias = via.text.toString().split(";").filter { it.isNotBlank() }.map {
            Coords.parse(it) ?: run { toast("Bad via point: $it"); return }
        }
        val d = depth.text.toString().toDoubleOrNull()?.takeIf { it > 0 && it < 40 } ?: run { toast("Depth must be feet, 0-40"); return }
        val airFt = air.text.toString().takeIf { it.isNotBlank() }?.let {
            it.toDoubleOrNull()?.takeIf { v -> v > 0 && v < 300 } ?: run { toast("Air draft must be feet"); return }
        }
        val routeName = name.text.toString().trim().ifEmpty {
            "route_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        }.replace(Regex("[^\\w.-]+"), "_")
        prefs.edit().putString("depth", depth.text.toString()).putString("air", air.text.toString())
            .putString("start", start.text.toString()).putString("dest", dest.text.toString()).apply()
        planButton.isEnabled = false
        actions.visibility = View.GONE
        result.text = ""
        route = null
        gpxUri = null
        worker.execute {
            val t0 = System.currentTimeMillis()
            try {
                val r = Planner(p).plan(routeName, listOf(a) + vias + listOf(b), d, airFt) { msg -> ui.post { status.text = msg } }
                val uri = saveGpx(r)
                ui.post {
                    route = r
                    gpxUri = uri
                    result.text = Gpx.report(r)
                    status.text = "Planned in %.1f s. Saved to Download/$ROUTES_DIR/${r.name}.gpx".format((System.currentTimeMillis() - t0) / 1000.0)
                    actions.visibility = View.VISIBLE
                    planButton.isEnabled = true
                }
            } catch (e: PlanException) {
                ui.post { status.text = ""; result.text = e.message; planButton.isEnabled = true }
            } catch (e: OutOfMemoryError) {
                ui.post { status.text = "Out of memory: try closer points or add via points."; planButton.isEnabled = true }
            } catch (e: Exception) {
                ui.post { status.text = "Error: ${e.message}"; planButton.isEnabled = true }
            }
        }
    }

    /** Write the GPX into Download/noaa-charts/routes via MediaStore (replacing our own older copy). */
    private fun saveGpx(r: Route): Uri {
        val fileName = "${r.name}.gpx"
        val rel = Environment.DIRECTORY_DOWNLOADS + "/" + ROUTES_DIR + "/"
        val coll = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var uri: Uri? = null
        contentResolver.query(coll, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(fileName, rel), null)?.use { c ->
            if (c.moveToFirst()) uri = Uri.withAppendedPath(coll, c.getLong(0).toString())
        }
        if (uri == null) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml")
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            }
            uri = contentResolver.insert(coll, v) ?: throw Exception("Could not create $fileName in Download")
        }
        contentResolver.openOutputStream(uri!!, "wt")!!.use { it.write(Gpx.write(r).toByteArray()) }
        return uri!!
    }

    private fun osmandPackage(): String? = listOf("net.osmand.plus", "net.osmand").firstOrNull {
        try { packageManager.getPackageInfo(it, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
    }

    private fun openInOsmAnd() {
        val uri = gpxUri ?: return
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/gpx+xml")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        osmandPackage()?.let { i.setPackage(it) }
        try {
            startActivity(i)
        } catch (_: Exception) {
            toast("OsmAnd could not open it; use 'Show routes folder in Files'.")
        }
    }

    private fun showFolder() {
        val doc = "primary:" + Environment.DIRECTORY_DOWNLOADS + "/" + ROUTES_DIR
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/" + Uri.encode(doc))
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.document/directory"))
        } catch (_: Exception) {
            toast("Open Files > Download > $ROUTES_DIR")
        }
    }

    private fun shareGpx() {
        val uri = gpxUri ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share route"))
    }
}
