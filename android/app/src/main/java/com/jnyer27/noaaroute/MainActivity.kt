package com.jnyer27.noaaroute

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil

class MainActivity : Activity() {
    companion object {
        const val ROUTES_DIR = "noaa-charts/routes"
        const val BUF_M = 60.0 // hazard buffer around shallow soundings and obstructions
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
    private val store by lazy { PackStore(this) }
    private val maps by lazy { ChartMaps(this) }
    private var charts: ChartSet? = null
    private var chartsSig = ""
    private var catalog: List<AreaInfo> = emptyList()
    private val downloading = HashSet<String>()
    private val failed = HashSet<String>() // automatic downloads skip these until a manual retry
    private var regionsChanged: (() -> Unit)? = null
    private var pendingFix: ((Location?) -> Unit)? = null
    private var route: Route? = null
    private var gpxUri: Uri? = null

    private lateinit var packStatus: TextView
    private lateinit var packButton: Button
    private lateinit var notice: LinearLayout
    private lateinit var noticeText: TextView
    private lateinit var noticeButton: Button
    private lateinit var needBox: LinearLayout
    private lateinit var needText: TextView
    private lateinit var needButton: Button
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
        packButton = button("Regions") { showRegions() }
        col.addView(label("Charts"))
        col.addView(packStatus)
        col.addView(packButton)
        noticeText = TextView(this)
        noticeButton = button("Switch") {}
        notice = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(4), 0, 0) }
        notice.addView(noticeText)
        notice.addView(noticeButton)
        col.addView(notice)

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

        needText = TextView(this)
        needButton = button("Download") {}
        needBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(12), 0, 0) }
        needBox.addView(needText)
        needBox.addView(needButton)
        col.addView(needBox)
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

        setContentView(ScrollView(this).apply { addView(col); insetPadding(this) })

        depth.setText(prefs.getString("depth", "3"))
        air.setText(prefs.getString("air", ""))
        start.setText(prefs.getString("start", ""))
        dest.setText(prefs.getString("dest", ""))
        File(filesDir, "md-route-pack.bin").delete() // single-pack format from the first build
        // The trip banner follows what is typed (a short pause first)
        val tripCheck = Runnable { checkTrip() }
        for (f in listOf(start, dest, via)) f.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) { ui.removeCallbacks(tripCheck); ui.postDelayed(tripCheck, 500) }
        })
        PackStore.schedule(this, store.auto == true) // keeps the daily update job after app updates
        catalog = store.cachedCatalog()
        openCharts()
        refreshCatalog(quiet = true)
        if (savedInstanceState == null) handleShared(intent)
    }

    /**
     * Android 15+ draws apps targeting SDK 35 edge to edge: keep a screen's content out
     * from under the status bar, camera cutout (it moves with orientation on foldables),
     * navigation bar and keyboard (adjustResize no longer does it).
     */
    private fun insetPadding(v: View) {
        if (Build.VERSION.SDK_INT < 35) return
        (v as? ViewGroup)?.clipToPadding = false
        v.setOnApplyWindowInsetsListener { view, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            view.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        // The background job may have updated or swapped packs
        if (store.signature() != chartsSig) { openCharts(); checkTrip() }
        // Chart map downloads may have finished, or a map was deleted in Files
        worker.execute { maps.reconcile(); ui.post { regionsChanged?.invoke() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShared(intent)
    }

    // ------------------------------------------------------------ shared positions

    /** Positions shared or opened from other apps: ask where each one goes. */
    private fun handleShared(i: Intent?) {
        if (i == null) return
        val action = i.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return
        worker.execute {
            val found: Pair<List<SharedPoint>, SharedGpx?> = try {
                val uri: Uri? = if (action == Intent.ACTION_SEND) {
                    @Suppress("DEPRECATION") (i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
                } else i.data
                val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty() + " " + i.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
                when {
                    uri != null && uri.scheme == "geo" -> Shared.parseText(uri.toString()) to null
                    uri != null -> {
                        val body = contentResolver.openInputStream(uri)?.use { s ->
                            val out = java.io.ByteArrayOutputStream()
                            val buf = ByteArray(1 shl 16)
                            while (out.size() < 20_000_000) {
                                val n = s.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                            out.toString("UTF-8")
                        }.orEmpty()
                        val gpx = Shared.parseGpx(body)
                        if (gpx != null) emptyList<SharedPoint>() to gpx else Shared.parseText(body) to null
                    }
                    else -> Shared.parseText(text) to null
                }
            } catch (e: Exception) {
                ui.post { toast("Could not read what was shared: ${e.message}") }
                return@execute
            }
            ui.post { offerShared(found.first, found.second) }
        }
    }

    private fun offerShared(points: List<SharedPoint>, gpx: SharedGpx?) {
        if (gpx != null) {
            if (gpx.isEmpty) { toast("No waypoints, routes or tracks in that GPX file."); return }
            val items = ArrayList<String>()
            val actions = ArrayList<() -> Unit>()
            val line = gpx.route.ifEmpty { gpx.trackEnds }
            if (line.size >= 2) {
                items.add("Use as trip: ${line.first().name} \u2192 ${line.last().name}")
                actions.add {
                    start.setText(Coords.format(line.first().pos))
                    dest.setText(Coords.format(line.last().pos))
                    status.text = "Start and destination set from the GPX file."
                }
            }
            if (gpx.waypoints.isNotEmpty()) {
                items.add("Save all ${gpx.waypoints.size} waypoint${if (gpx.waypoints.size > 1) "s" else ""} as places")
                actions.add {
                    upsertPlaces(places(), gpx.waypoints.map { it.name to Coords.format(it.pos) })
                    toast("Saved ${gpx.waypoints.size} place(s) from the GPX file")
                }
            }
            for (p in (gpx.waypoints + gpx.route + gpx.trackEnds).take(300)) {
                items.add("${p.name}\n${Coords.format(p.pos)}")
                actions.add { assignPoint(p) }
            }
            AlertDialog.Builder(this).setTitle("Points in the GPX file")
                .setItems(items.toTypedArray()) { _, k -> actions[k]() }
                .setNegativeButton("Cancel", null).show()
            return
        }
        when (points.size) {
            0 -> toast("No coordinates found in what was shared.")
            1 -> assignPoint(points[0])
            else -> AlertDialog.Builder(this).setTitle("Which position?")
                .setItems(points.map { "${it.name}\n${Coords.format(it.pos)}" }.toTypedArray()) { _, k -> assignPoint(points[k]) }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun assignPoint(p: SharedPoint) {
        val pos = Coords.format(p.pos)
        val named = p.name != pos
        val title = if (named) "${p.name}\n$pos" else pos
        val choices = arrayOf("Start", "Destination", "Via point", "Save as place\u2026")
        AlertDialog.Builder(this).setTitle(title)
            .setItems(choices) { _, k ->
                when (k) {
                    0 -> { start.setText(pos); status.text = "Start set: $title" }
                    1 -> { dest.setText(pos); status.text = "Destination set: $title" }
                    2 -> {
                        via.setText(if (via.text.isBlank()) pos else "${via.text}; $pos")
                        status.text = "Via point added: $title"
                    }
                    else -> editPlace(places(), -1, if (named) p.name else "", pos)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    /** Add or update places by name (same name = same place, updated). */
    private fun upsertPlaces(list: MutableList<Pair<String, String>>, entries: List<Pair<String, String>>) {
        for (e in entries) {
            val i = list.indexOfFirst { it.first.equals(e.first, ignoreCase = true) }
            if (i >= 0) list[i] = e else list.add(e)
        }
        savePlaces(list)
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
        charts?.packs?.forEach { it.close() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ chart regions

    private fun openCharts() {
        charts?.packs?.forEach { it.close() }
        charts = null
        val packs = ArrayList<Pack>()
        val bad = ArrayList<String>()
        for (f in store.dir.listFiles { f -> f.name.endsWith(".bin") }.orEmpty().sortedBy { it.name }) {
            try { packs.add(Pack(f)) } catch (e: Exception) { bad.add(f.name); f.delete() }
        }
        chartsSig = store.signature()
        charts = if (packs.isEmpty()) null else ChartSet(packs)
        packStatus.text = when {
            packs.isEmpty() -> "No chart regions yet. Tap Regions and download yours (a few MB, once); planning then works offline."
            else -> "Charts: " + packs.joinToString { it.areaName.substringBefore(':') } +
                " · NOAA data from " + packs.minOf { it.created }.take(10)
        } + (if (bad.isNotEmpty()) "\nRemoved damaged file(s): ${bad.joinToString()}" else "")
        updateBadge()
        regionsChanged?.invoke()
    }

    private fun installed(id: String) = charts?.packs?.firstOrNull { it.areaId == id }
    private fun installedIds() = charts?.packs.orEmpty().map { it.areaId }.toSet()
    /** The regions replacing an area: from the catalog while it lists the area, then built in. */
    private fun replacedBy(id: String): List<String>? =
        catalog.firstOrNull { it.id == id }?.let { a -> a.replacedBy.takeIf { a.deprecated && it.isNotEmpty() } }
            ?: Regions.RETIRED[id]?.takeIf { catalog.none { it.id == id } }

    private fun hasUpdate(a: AreaInfo) = !a.deprecated && installed(a.id)?.let { a.created > it.created } == true

    private fun updateBadge() {
        val updates = catalog.count { hasUpdate(it) }
        packButton.text = if (updates > 0) "Regions ($updates update${if (updates > 1) "s" else ""})" else "Regions"
    }

    /** Fetch catalog.json (the last copy works offline); quiet = no messages when offline. */
    private fun refreshCatalog(quiet: Boolean, then: (() -> Unit)? = null) {
        worker.execute {
            val list = try { store.fetchCatalog() } catch (e: Exception) {
                if (!quiet) ui.post { status.text = "Could not reach the chart catalog (${e.message}); showing the last one." }
                store.cachedCatalog()
            }
            ui.post {
                catalog = list
                if (store.dropReplaced(list)) openCharts()
                updateBadge()
                afterCatalog()
                then?.invoke()
            }
        }
    }

    /** Ask once whether to download and update regions automatically on Wi-Fi, then go on. */
    private fun ensureAuto(then: () -> Unit) {
        if (store.auto != null) { then(); return }
        AlertDialog.Builder(this).setTitle("Automatic chart downloads")
            .setMessage("Download and update chart regions automatically on Wi-Fi? NOAA publishes new data every week, " +
                "and a trip that needs another region gets it without asking. You can change this on the Regions screen.")
            .setPositiveButton("Yes") { _, _ -> store.auto = true; then() }
            .setNegativeButton("No, ask me") { _, _ -> store.auto = false; then() }
            .setCancelable(false).show()
    }

    /** Download packs one after another in the background, then reopen the charts. */
    private fun downloadAreas(list: List<AreaInfo>, then: (() -> Unit)? = null) {
        val todo = list.filter { it.id !in downloading }
        if (todo.isEmpty()) return
        downloading.addAll(todo.map { it.id })
        packButton.isEnabled = false
        worker.execute {
            val errors = ArrayList<String>()
            for (a in todo) {
                try {
                    store.download(a) { got, size ->
                        ui.post { status.text = "Downloading ${a.shortName}: %.1f of %.1f MB".format(got / 1e6, size / 1e6) }
                    }
                    synchronized(failed) { failed.remove(a.id) }
                } catch (e: Exception) {
                    errors.add("${a.shortName}: ${e.message}")
                    synchronized(failed) { failed.add(a.id) }
                }
            }
            ui.post {
                downloading.removeAll(todo.map { it.id }.toSet())
                store.dropReplaced(catalog)
                openCharts()
                packButton.isEnabled = true
                status.text = if (errors.isEmpty()) todo.joinToString { it.shortName } + " ready." else "Chart download failed: ${errors.joinToString("; ")}"
                afterCatalog() // the switch notice and the trip banner
                then?.invoke()
            }
        }
    }

    /** After each catalog fetch: the switch from a replaced area, and the first-launch question. */
    private fun afterCatalog() {
        val swap = Regions.replacements(catalog, installedIds()).entries.firstOrNull { it.value.isNotEmpty() }
        if (swap == null) {
            notice.visibility = View.GONE
        } else {
            val oldName = catalog.firstOrNull { it.id == swap.key }?.name ?: installed(swap.key)?.areaName ?: swap.key
            val names = swap.value.joinToString(" and ") { it.shortName }
            val mb = swap.value.sumOf { it.size } / 1e6
            if (store.auto == true && store.unmetered() && swap.value.none { it.id in failed }) {
                status.text = "$oldName is now $names: downloading them."
                downloadAreas(swap.value)
            } else {
                noticeText.text = "$oldName is now $names (NOAA's chart regions, about %.0f MB). The old area is removed once they are in.".format(mb)
                noticeButton.text = "Switch"
                noticeButton.setOnClickListener { ensureAuto { downloadAreas(swap.value) } }
                notice.visibility = View.VISIBLE
            }
        }
        if (charts == null && !prefs.getBoolean("welcomed", false) && catalog.any { !it.deprecated }) {
            prefs.edit().putBoolean("welcomed", true).apply()
            welcome()
        }
        checkTrip()
    }

    /** First launch: suggest the region where the phone is. */
    private fun welcome() {
        AlertDialog.Builder(this).setTitle("Where do you boat?")
            .setMessage("Route planning needs NOAA chart data for your waters, a few MB per region. Find your region from your location, or pick it on the map.")
            .setPositiveButton("Use my location") { _, _ ->
                locate { loc ->
                    if (loc == null) { status.text = "No location fix; pick your region on the map."; showRegions(); return@locate }
                    val here = LatLon(loc.latitude, loc.longitude)
                    val a = Regions.areaAt(here, catalog)
                    if (a == null) {
                        AlertDialog.Builder(this).setTitle("No region here yet")
                            .setMessage("None of the published chart regions covers your position. Pick one on the map.")
                            .setPositiveButton("Open map") { _, _ -> showRegions() }.setNegativeButton("Later", null).show()
                    } else {
                        AlertDialog.Builder(this).setTitle("You're in ${a.shortName}")
                            .setMessage("${a.name}\n\n${a.description}\n\nAbout %.0f MB. Download it now?".format(a.size / 1e6))
                            .setPositiveButton("Download") { _, _ -> ensureAuto { downloadAreas(listOf(a)) } }
                            .setNeutralButton("Map") { _, _ -> showRegions() }
                            .setNegativeButton("Later", null).show()
                    }
                }
            }
            .setNeutralButton("Pick on map") { _, _ -> showRegions() }
            .setNegativeButton("Later", null).show()
    }

    /** Trip points typed so far (bad or empty fields are skipped). */
    private fun tripPoints(): List<LatLon> {
        val pts = ArrayList<LatLon>()
        Coords.parse(start.text.toString())?.let { pts.add(it) }
        via.text.toString().split(";").mapNotNullTo(pts) { Coords.parse(it) }
        Coords.parse(dest.text.toString())?.let { pts.add(it) }
        return pts
    }

    /** Banner above Plan route when the trip runs into regions not downloaded (or none at all). */
    private fun checkTrip() {
        val pts = tripPoints()
        if (pts.size < 2 || catalog.isEmpty()) { needBox.visibility = View.GONE; return }
        val have = installedIds()
        val need = Regions.need(pts, catalog, have) { p -> charts?.covers(p.lat, p.lon) == true }
        val todo = need.download.filter { it.id !in downloading }
        when {
            todo.isNotEmpty() && store.auto == true && store.unmetered() && todo.none { it.id in failed } -> {
                needBox.visibility = View.GONE
                status.text = "This trip needs " + todo.joinToString { it.shortName } + ": downloading."
                downloadAreas(todo)
            }
            todo.isNotEmpty() -> {
                val mb = todo.sumOf { it.size } / 1e6
                needText.text = "This trip also needs " + todo.joinToString(" and ") { it.name } + " (about %.0f MB).".format(mb) +
                    if (need.outside) " Part of it is outside every chart region, so planning will fail there." else ""
                needButton.visibility = View.VISIBLE
                needButton.setOnClickListener { ensureAuto { downloadAreas(todo) } }
                needBox.visibility = View.VISIBLE
            }
            need.outside -> {
                needText.text = "Part of this trip is outside every chart region this app has, so planning will fail there."
                needButton.visibility = View.GONE
                needBox.visibility = View.VISIBLE
            }
            else -> needBox.visibility = View.GONE
        }
    }

    /** The Regions screen: NCDS region map, automatic downloads, and the list of packs. */
    private fun showRegions() {
        val d = Dialog(this, android.R.style.Theme_DeviceDefault_DayNight)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(8), 0) }
        top.addView(TextView(this).apply {
            text = "Regions"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(button("Close") { d.dismiss() })
        root.addView(top)
        root.addView(TextView(this).apply {
            text = "NOAA chart regions. Tap one to download, update or delete it."
            setPadding(dp(16), 0, dp(16), dp(4))
        })

        val map = RegionMapView(this)
        fun areaFor(code: String) = catalog.firstOrNull { it.ncds == code && !it.deprecated }
        map.status = { code ->
            val a = areaFor(code)
            when {
                a == null -> RegionMapView.Status.NOT_BUILT
                installed(a.id) == null -> RegionMapView.Status.AVAILABLE
                hasUpdate(a) -> RegionMapView.Status.UPDATE
                else -> RegionMapView.Status.INSTALLED
            }
        }
        map.labelAt = { code -> areaFor(code)?.bbox?.let { LatLon((it[1] + it[3]) / 2, (it[0] + it[2]) / 2) } }
        val pts = tripPoints()
        map.markers = listOfNotNull(
            Coords.parse(start.text.toString())?.let { it to "Start" },
            Coords.parse(dest.text.toString())?.let { it to "Destination" },
        ) + listOfNotNull(lastFix()?.let { it to "You" })
        // Fit the trip, else the downloaded regions, else what is published
        val boxes = (pts.map { doubleArrayOf(it.lon - 0.5, it.lat - 0.5, it.lon + 0.5, it.lat + 0.5) }.ifEmpty {
            catalog.filter { !it.deprecated && installed(it.id) != null }.map { it.bbox }
        }).ifEmpty { catalog.filter { !it.deprecated }.map { it.bbox } }
        if (boxes.isNotEmpty()) map.fit(doubleArrayOf(boxes.minOf { it[0] }, boxes.minOf { it[1] }, boxes.maxOf { it[2] }, boxes.maxOf { it[3] }))
        map.onRegionTap = { r -> regionCard(r.code) }
        map.hasChart = { code -> maps.get(code)?.state == ChartMaps.State.READY }
        root.addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.4f))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        val auto = CheckBox(this).apply {
            text = "Download and update regions automatically on Wi-Fi"
            isChecked = store.auto == true
            setOnCheckedChangeListener { _, on -> store.auto = on }
        }
        list.addView(auto)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(rows)
        fun fill() {
            rows.removeAllViews()
            map.invalidate()
            val ids = (catalog.sortedBy { it.deprecated }.map { it.id } + charts?.packs.orEmpty().map { it.areaId }).distinct()
            if (ids.isEmpty()) rows.addView(TextView(this).apply { text = "No chart regions available (offline?)" })
            for (id in ids) {
                val c = catalog.firstOrNull { it.id == id }
                val have = installed(id)
                val state = when {
                    replacedBy(id) != null -> "replaced by " + replacedBy(id)!!.joinToString(" and ") { r -> catalog.firstOrNull { it.id == r }?.shortName ?: r } +
                        if (have != null) " (removed once they are downloaded)" else ""
                    have == null -> "not downloaded, about %.0f MB".format((c?.size ?: 0) / 1e6)
                    c != null && hasUpdate(c) -> "UPDATE available (${c.created.take(10)})"
                    else -> "up to date, NOAA data from ${have.created.take(10)}"
                }
                if (replacedBy(id) != null && have == null) continue
                rows.addView(TextView(this).apply {
                    text = "${c?.name ?: have?.areaName ?: id}\n$state"
                    setPadding(0, dp(10), 0, dp(10))
                    setOnClickListener { c?.ncds?.let { regionCard(it) } ?: areaAction(id) }
                })
            }
            // Chart maps for OsmAnd
            rows.addView(label("Chart maps for OsmAnd"))
            val entries = maps.all().values.sortedBy { it.key }
            if (entries.isEmpty()) rows.addView(TextView(this).apply {
                text = "NOAA's paper-chart style maps (NCDS), converted for OsmAnd: tap a region on the map to download one."
            })
            for (e in entries) rows.addView(TextView(this).apply {
                text = (if (e.merged) "Combined regions " + e.codes.joinToString(" + ") else "Region ${e.key}") + "\n" + chartStatus(e)
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { if (e.merged) chartCard(e.key) else regionCard(e.key) }
            })
            val ready = entries.filter { !it.merged && !it.mbtilesOnly && it.state == ChartMaps.State.READY }
            if (ready.size >= 2) rows.addView(button("Combine chart maps for OsmAnd") { combineCharts(ready.map { it.key }) })
        }
        fill()
        regionsChanged = { fill() }
        // Live progress while chart maps download or convert, and the change to ready or
        // failed (the background job makes it, so redraw whenever any status changed: the
        // last change of a merge left the list at "adding regions..." before)
        val tick = object : Runnable {
            var last = ""
            override fun run() {
                if (!d.isShowing) return
                val all = maps.all().values
                val now = all.joinToString { "${it.key}:${it.state}" }
                if (now != last || all.any { it.state == ChartMaps.State.DOWNLOADING }) fill()
                last = now
                ui.postDelayed(this, 2000)
            }
        }
        ui.postDelayed(tick, 2000)
        d.setOnDismissListener { regionsChanged = null; ui.removeCallbacks(tick) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        insetPadding(root)
        d.setContentView(root)
        d.show()
        refreshCatalog(quiet = false) // statuses may be stale; fill() runs again via openCharts
    }

    private fun areaAction(id: String) {
        val c = catalog.firstOrNull { it.id == id }
        val have = installed(id)
        val b = AlertDialog.Builder(this).setTitle(c?.name ?: have?.areaName ?: id)
        if (c != null && !c.deprecated && (have == null || hasUpdate(c)))
            b.setPositiveButton(if (have == null) "Download" else "Update") { _, _ -> ensureAuto { downloadAreas(listOf(c)) } }
        if (have != null) b.setNeutralButton("Delete") { _, _ ->
            charts?.packs?.forEach { it.close() }
            charts = null
            store.delete(id)
            openCharts()
            checkTrip()
        }
        b.setMessage(when {
            replacedBy(id) != null -> "Replaced by NOAA's chart regions " + replacedBy(id)!!.joinToString(" and ") { r -> catalog.firstOrNull { it.id == r }?.shortName ?: r } + "."
            have != null && (c == null || !hasUpdate(c)) -> "Up to date: NOAA data from ${have.created.take(10)}." + (c?.let { "\n\n${it.description}" } ?: "")
            have != null -> "Installed: ${have.created.take(10)}. New: ${c!!.created.take(10)}."
            else -> "About %.0f MB.\n\n%s".format(c!!.size / 1e6, c.description)
        }).setNegativeButton("Cancel", null).show()
    }

    /** Chart map status line for the list and the card. */
    private fun chartStatus(e: ChartMaps.Entry): String = when (e.state) {
        ChartMaps.State.DOWNLOADING -> maps.progress(e)?.let { (got, total) ->
            if (total > 0) "downloading: %.0f of %.0f MB (waits for Wi-Fi unless mobile data was allowed)".format(got / 1e6, total / 1e6)
            else "download queued (waits for Wi-Fi unless mobile data was allowed)"
        } ?: "downloading"
        ChartMaps.State.DOWNLOADED, ChartMaps.State.CONVERTING -> "converting for OsmAnd..."
        ChartMaps.State.MERGING -> if (e.base != null) "adding regions to the combined map..." else "combining..."
        ChartMaps.State.READY -> "ready: Download/${ChartMaps.SUBDIR}/${e.file} (%.0f MB)".format(e.size / 1e6) +
            if (e.mbtilesOnly) " (.mbtiles, not converted)" else ""
        ChartMaps.State.FAILED -> "failed: ${e.error}"
    }

    /** One NCDS region: its route planning data and its chart map for OsmAnd. */
    private fun regionCard(code: String) {
        val area = catalog.firstOrNull { it.ncds == code && !it.deprecated }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val d = AlertDialog.Builder(this).setTitle(area?.name ?: "NCDS region $code").setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Close", null).create()
        fun act(text: String, f: () -> Unit) = button(text) { d.dismiss(); f() }

        box.addView(label("Route planning data"))
        val have = area?.let { installed(it.id) }
        box.addView(TextView(this).apply {
            text = when {
                area == null -> "Not available for this region yet."
                have == null -> "Not downloaded. About %.0f MB.\n%s".format(area.size / 1e6, area.description)
                hasUpdate(area) -> "Update available: NOAA data from ${area.created.take(10)} (you have ${have.created.take(10)})."
                else -> "Up to date: NOAA data from ${have.created.take(10)}.\n${area.description}"
            }
        })
        if (area != null && (have == null || hasUpdate(area)))
            box.addView(act(if (have == null) "Download route data" else "Update route data") { ensureAuto { downloadAreas(listOf(area)) } })
        if (have != null) box.addView(act("Delete route data") {
            charts?.packs?.forEach { it.close() }
            charts = null
            store.delete(area!!.id)
            openCharts()
            checkTrip()
        })

        box.addView(label("Chart map for OsmAnd"))
        val e = maps.get(code)
        val status = TextView(this)
        box.addView(status)
        when (e?.state) {
            null -> {
                status.text = "NOAA's paper-chart style map of the region (NCDS), converted for OsmAnd. Checking the size..."
                worker.execute {
                    val size = try { maps.remoteSize(code) } catch (_: Exception) { -1L }
                    ui.post {
                        status.text = if (size > 0) "NOAA's paper-chart style map of the region (NCDS), converted for OsmAnd: " +
                            "%.0f MB download, about %.0f MB for OsmAnd, %.0f MB free space needed while converting.".format(
                                size / 1e6, 1.7 * size / 1e6, ChartMaps.spaceNeeded(size, false) / 1e6)
                        else "NOAA's paper-chart style map of the region (NCDS), converted for OsmAnd. Could not reach NOAA to check the size."
                    }
                }
                box.addView(act("Download for OsmAnd") { startChart(code, mbtilesOnly = false) })
                box.addView(act("Download the .mbtiles only") { startChart(code, mbtilesOnly = true) })
            }
            ChartMaps.State.READY -> {
                status.text = chartStatus(e)
                if (!e.mbtilesOnly) box.addView(act("Open in OsmAnd") { openChart(e) })
                box.addView(act("Show in Files") { showFolder(ChartMaps.SUBDIR) })
                // Add this region to a combined map that does not have it yet
                if (!e.mbtilesOnly) for (m in maps.all().values.filter { it.merged && it.state == ChartMaps.State.READY && code !in it.codes })
                    box.addView(act("Add to combined map " + m.codes.joinToString(" + ")) { addToCombined(m.key, listOf(code)) })
                box.addView(act("Delete chart map") { maps.delete(code); regionsChanged?.invoke() })
            }
            ChartMaps.State.FAILED -> {
                status.text = chartStatus(e)
                box.addView(act("Try again") { maps.cancel(code); startChart(code, e.mbtilesOnly) })
                box.addView(act("Remove") { maps.cancel(code); regionsChanged?.invoke() })
            }
            else -> {
                status.text = chartStatus(e)
                if (e.state == ChartMaps.State.DOWNLOADING) box.addView(act("Cancel download") { maps.cancel(code); regionsChanged?.invoke() })
            }
        }
        d.show()
    }

    /** A combined chart map's card: open it, add regions to it, or delete it. */
    private fun chartCard(key: String) {
        val e = maps.get(key) ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val d = AlertDialog.Builder(this).setTitle("Combined chart map: regions " + e.codes.joinToString(" + "))
            .setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Close", null).create()
        fun act(text: String, f: () -> Unit) = button(text) { d.dismiss(); f() }
        box.addView(TextView(this).apply { text = chartStatus(e) })
        if (e.state == ChartMaps.State.READY) {
            box.addView(act("Open in OsmAnd") { openChart(e) })
            box.addView(act("Show in Files") { showFolder(ChartMaps.SUBDIR) })
            val more = maps.all().values.filter { !it.merged && !it.mbtilesOnly && it.state == ChartMaps.State.READY && it.key !in e.codes }.map { it.key }
            if (more.isNotEmpty()) box.addView(act("Add regions\u2026") { addToCombined(key, more, ask = true) })
        }
        box.addView(act("Delete") { maps.delete(key); regionsChanged?.invoke() })
        d.show()
    }

    /**
     * Add ready region maps to a combined map (in place, then renamed to list all its
     * regions); with [ask], pick which of [codes] first.
     */
    private fun addToCombined(key: String, codes: List<String>, ask: Boolean = false) {
        val e = maps.get(key) ?: return
        fun go(sel: List<String>) {
            maps.requestAdd(key, sel)
            status.text = "Adding region ${sel.joinToString(" + ")} to the combined map " + e.codes.joinToString(" + ") +
                ". When it is done, delete the old combined map in OsmAnd and import the new file."
            regionsChanged?.invoke()
        }
        if (!ask) { go(codes); return }
        val pick = BooleanArray(codes.size) { true }
        AlertDialog.Builder(this).setTitle("Add to combined map " + e.codes.joinToString(" + "))
            .setMultiChoiceItems(codes.map { "Region $it" }.toTypedArray(), pick) { _, k, on -> pick[k] = on }
            .setPositiveButton("Add") { _, _ ->
                val sel = codes.filterIndexed { k, _ -> pick[k] }
                if (sel.isEmpty()) toast("Pick at least one region.") else go(sel)
            }.setNegativeButton("Cancel", null).show()
    }

    /** Check the size and space, ask Wi-Fi or mobile data, then hand the download to Android. */
    private fun startChart(code: String, mbtilesOnly: Boolean) {
        status.text = "Checking region $code's chart map..."
        worker.execute {
            val size = try { maps.remoteSize(code) } catch (e: Exception) {
                ui.post { status.text = "Could not reach NOAA: ${e.message}" }
                return@execute
            }
            val need = ChartMaps.spaceNeeded(size, mbtilesOnly)
            val free = maps.freeBytes()
            ui.post {
                status.text = ""
                if (free < need) {
                    AlertDialog.Builder(this).setTitle("Not enough space")
                        .setMessage("Region $code's chart map is %.0f MB; downloading and converting it needs about %.0f MB free, and %.0f MB is free.".format(size / 1e6, need / 1e6, free / 1e6))
                        .setPositiveButton("OK", null).show()
                    return@post
                }
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) // for "ready" notices; optional
                fun go(mobile: Boolean) {
                    maps.start(code, size, mbtilesOnly, mobile)
                    status.text = "Region $code's chart map is downloading (see the notification); it is converted for OsmAnd when done, even if you close the app."
                    regionsChanged?.invoke()
                }
                AlertDialog.Builder(this).setTitle("Region $code chart map, %.0f MB".format(size / 1e6))
                    .setMessage("Android downloads it in the background and resumes after interruptions." +
                        if (mbtilesOnly) " The .mbtiles file is saved to Download/${ChartMaps.SUBDIR}." else " Then it is converted and saved to Download/${ChartMaps.SUBDIR} for OsmAnd.")
                    .setPositiveButton("On Wi-Fi only") { _, _ -> go(false) }
                    .setNeutralButton("Use mobile data too") { _, _ -> go(true) }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    /** Pick the ready regions to combine into one map (OsmAnd shows one overlay at a time). */
    private fun combineCharts(codes: List<String>) {
        if (Build.VERSION.SDK_INT < 30) { toast("Combining chart maps needs Android 11 or newer."); return }
        val pick = BooleanArray(codes.size) { true }
        AlertDialog.Builder(this).setTitle("Combine into one map for OsmAnd")
            .setMultiChoiceItems(codes.map { "Region $it" }.toTypedArray(), pick) { _, k, on -> pick[k] = on }
            .setPositiveButton("Combine") { _, _ ->
                val sel = codes.filterIndexed { k, _ -> pick[k] }
                if (sel.size < 2) { toast("Pick at least two regions."); return@setPositiveButton }
                maps.requestMerge(sel)
                status.text = "Combining regions ${sel.joinToString(" + ")} into one chart map..."
                regionsChanged?.invoke()
            }.setNegativeButton("Cancel", null).show()
    }

    /** Hand a chart map to OsmAnd; if it will not take it, show the folder. */
    private fun openChart(e: ChartMaps.Entry) {
        val uri = Uri.parse(e.uri ?: return)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        osmandPackage()?.let { i.setPackage(it) }
        try {
            startActivity(i)
        } catch (_: Exception) {
            toast("OsmAnd did not take it: tap ${e.file} in Files and pick OsmAnd.")
            showFolder(ChartMaps.SUBDIR)
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

    private fun editPlace(list: MutableList<Pair<String, String>>, k: Int, newName: String = "", newPos: String? = null) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val n = field("name").apply { setText(if (k >= 0) list[k].first else newName) }
        val p = field("LAT, LON").apply { setText(if (k >= 0) list[k].second else newPos ?: start.text.toString()) }
        box.addView(n)
        box.addView(p)
        val d = AlertDialog.Builder(this).setTitle(if (k >= 0) "Edit place" else "Add place").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val ll = Coords.parse(p.text.toString())
                if (n.text.isBlank() || ll == null) { toast("Need a name and LAT, LON"); return@setPositiveButton }
                val entry = n.text.toString().trim() to Coords.format(ll)
                if (k >= 0) { list[k] = entry; savePlaces(list) } else upsertPlaces(list, listOf(entry))
                toast("Saved place: ${entry.first}")
            }.setNegativeButton("Cancel", null)
        if (k >= 0) d.setNeutralButton("Delete") { _, _ -> list.removeAt(k); savePlaces(list) }
        d.show()
    }

    // ------------------------------------------------------------ location

    private fun useHere() {
        status.text = "Getting GPS fix..."
        locate { loc ->
            if (loc == null) status.text = "No location fix. Turn on Location or type a position."
            else {
                start.setText(Coords.format(LatLon(loc.latitude, loc.longitude)))
                status.text = "Start set to current location (accuracy %.0f m).".format(loc.accuracy)
            }
        }
    }

    /** One current fix (asks for the permission first if needed); null when there is none. */
    private fun locate(done: (Location?) -> Unit) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingFix = done
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, mainExecutor) { done(it ?: lm.getLastKnownLocation(provider)) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, { done(it) }, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            done(null)
        }
    }

    /** Last known position, without asking for anything (for the region map's "You"). */
    private fun lastFix(): LatLon? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = getSystemService(LocationManager::class.java)
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstNotNullOfOrNull { lm.getLastKnownLocation(it) }
                ?.let { LatLon(it.latitude, it.longitude) }
        } catch (_: Exception) { null }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val then = pendingFix ?: return
        pendingFix = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) locate(then)
        else { status.text = "Location permission denied; type a position instead."; then(null) }
    }

    // ------------------------------------------------------------ planning

    private fun plan() {
        val p = charts ?: run { toast("Download a chart region first (Regions)."); return }
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
        packButton.isEnabled = false // no area changes while planning
        actions.visibility = View.GONE
        result.text = ""
        route = null
        gpxUri = null
        worker.execute {
            val t0 = System.currentTimeMillis()
            try {
                val r = Planner(p).plan(routeName, listOf(a) + vias + listOf(b), d, airFt, BUF_M) { msg -> ui.post { status.text = msg } }
                val (uri, savedAs) = saveGpx(r)
                val sec = (System.currentTimeMillis() - t0) / 1000.0
                ui.post {
                    route = r
                    gpxUri = uri
                    result.text = Gpx.report(r, tripLines(a, vias, b), afterLines(p, r, sec), BUF_M)
                    status.text = "Planned in %.1f s. Saved to Download/$ROUTES_DIR/$savedAs".format(sec) +
                        if (savedAs != "${r.name}.gpx") "\n(${r.name}.gpx already exists from another app or an earlier install)" else ""
                    actions.visibility = View.VISIBLE
                    planButton.isEnabled = true; packButton.isEnabled = true
                }
            } catch (e: PlanException) {
                ui.post { status.text = ""; result.text = e.message; planButton.isEnabled = true; packButton.isEnabled = true }
            } catch (e: OutOfMemoryError) {
                ui.post { status.text = "Out of memory: try closer points or add via points."; planButton.isEnabled = true; packButton.isEnabled = true }
            } catch (e: Exception) {
                ui.post { status.text = "Error: ${e.message}"; planButton.isEnabled = true; packButton.isEnabled = true }
            }
        }
    }

    /** Start, via points and destination for the summary, with a saved place's name when one matches. */
    private fun tripLines(a: LatLon, vias: List<LatLon>, b: LatLon): List<String> {
        val named = places().mapNotNull { (n, pos) -> Coords.parse(pos)?.let { it to n } }
        fun show(p: LatLon): String {
            val n = named.firstOrNull { (q, _) -> abs(q.lat - p.lat) < 1e-5 && abs(q.lon - p.lon) < 1e-5 }?.second
            return if (n != null) "$n (${Coords.format(p)})" else Coords.format(p)
        }
        return listOf("start: ${show(a)}") + vias.mapIndexed { k, v -> "via ${k + 1}: ${show(v)}" } + listOf("destination: ${show(b)}")
    }

    /** Chart regions the route uses (with their build dates) and when it was planned. */
    private fun afterLines(charts: ChartSet, r: Route, sec: Double): List<String> {
        // Sampled every ~1 km along the legs: a long leg can cross a region with no waypoint in it
        val samples = r.points.zipWithNext().flatMap { (p, q) ->
            val n = ceil(Planner.distanceM(p, q) / 1000.0).toInt().coerceAtLeast(1)
            (0 until n).map { k -> LatLon(p.lat + (q.lat - p.lat) * k / n, p.lon + (q.lon - p.lon) * k / n) }
        } + r.points.last()
        val used = charts.packs.filter { pk -> samples.any { pk.covers(it.lat, it.lon) } }
        return listOf(
            "charts: " + used.joinToString(", ") { pk -> pk.areaName.substringBefore(":") + " (built ${pk.created.take(10)})" },
            "planned " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()) + " in %.1f s".format(sec))
    }

    /** Write the GPX into Download/noaa-charts/routes; returns (uri, file name). */
    private fun saveGpx(r: Route): Pair<Uri, String> {
        val (uri, name) = DownloadFiles.target(this, ROUTES_DIR, r.name, "gpx", "application/gpx+xml")
        contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Gpx.write(r).toByteArray()) }
        return uri to name
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

    private fun showFolder(sub: String = ROUTES_DIR) {
        val doc = "primary:" + Environment.DIRECTORY_DOWNLOADS + "/" + sub
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/" + Uri.encode(doc))
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.document/directory"))
        } catch (_: Exception) {
            toast("Open Files > Download > $sub")
        }
    }

    private fun shareGpx() {
        val uri = gpxUri ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share route"))
    }
}
