package com.jnyer27.noaaroute.cli

import com.jnyer27.noaaroute.AreaInfo
import com.jnyer27.noaaroute.ChartDepthChart
import com.jnyer27.noaaroute.ChartDepthRoute
import com.jnyer27.noaaroute.ChartSet
import com.jnyer27.noaaroute.Coords
import com.jnyer27.noaaroute.Gpx
import com.jnyer27.noaaroute.LatLon
import com.jnyer27.noaaroute.Pack
import com.jnyer27.noaaroute.PackCache
import com.jnyer27.noaaroute.PlanException
import com.jnyer27.noaaroute.Planner
import com.jnyer27.noaaroute.Regions
import com.jnyer27.noaaroute.Route
import java.io.File
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.system.exitProcess

/**
 * noaa-route: the NOAA Route app's planner on the command line. Same planner
 * (:core), same route packs (downloaded from the route-pack release into a cache
 * folder), same GPX and report as the app.
 */
fun main(args: Array<String>) {
    exitProcess(Cli(System.out, System.err).run(args))
}

/** Exit codes. */
object Exit {
    const val OK = 0
    const val NO_ROUTE = 1
    const val USAGE = 2
    const val DOWNLOAD = 3
}

class UsageException(msg: String) : Exception(msg)

class Options(
    val from: LatLon, val to: LatLon, val vias: List<LatLon>,
    val depthFt: Double, val air: String?, val noBridges: Boolean, val bufM: Double,
    val inside: Boolean, val moveEnds: Boolean, val resolve: Boolean,
    val name: String?, val out: File?, val packs: File?, val offline: Boolean, val beta: Boolean,
    val json: Boolean, val quiet: Boolean, val checks: Boolean,
    val chartDepth: Boolean = true, val charts: File? = null,
)

class Cli(private val out: PrintStream, private val err: PrintStream, private val baseUrl: String? = null,
          private val chartBaseUrl: String? = null) {
    companion object {
        const val RELEASES = "https://github.com/jnyer27/noaa-osmand-charts/releases/download/"
        const val CREATOR = "NOAA Route (command line)"

        val USAGE = """
            |Usage: noaa-route -f LAT,LON -t LAT,LON -d DEPTH_FT -a AIR_FT [options]
            |
            |Plans a boat route on NOAA charts with the NOAA Route app's planner and writes a GPX route.
            |Points: "39.32371,-76.39998", "39.32371 N, 76.39998 W" or "39 19.42 N, 76 24.0 W" (quote spaces).
            |
            |  -f, --from POINT      start
            |  -t, --to POINT        destination
            |  -v, --via POINT       via point, in order (repeat for more)
            |  -d, --depth FT        safe operating depth in feet (required)
            |  -a, --air FT          air draft in feet (required unless --no-bridges)
            |      --no-bridges      don't check bridges and cables
            |  -b, --buffer M        hazard buffer in meters (default 60)
            |      --inside          Stay inside: no open ocean
            |      --move-ends       move ends to navigable water
            |      --resolve         also try to avoid checks; writes NAME_adjusted.gpx
            |      --no-check-markers  no check markers in the GPX
            |      --no-chart-depth  skip the check of each leg against the chart's depth areas
            |                        (saves downloading the region chart, about 30 MB a region)
            |  -n, --name NAME       route name (default route_YYYYMMDD_HHMM)
            |  -o, --out FILE        GPX file (default NAME.gpx in the current folder)
            |      --packs DIR       route data folder (default ~/.cache/noaa-route/packs)
            |      --charts DIR      region chart folder (default ~/.cache/noaa-route/charts)
            |      --offline         use the route data already downloaded; no network
            |      --beta            route data from the route-pack-beta release
            |      --json            print a JSON summary instead of the report
            |  -q, --quiet           no progress messages
            |  -h, --help            this help
            |      --version         print the version
            |
            |Exit codes: 0 planned, 1 no route, 2 bad input, 3 route data could not be downloaded.
            |NOAA Route is a planning aid, not a certified navigation system. Check every leg against a current chart.
        """.trimMargin()

        fun parse(args: Array<String>): Options? {
            var from: LatLon? = null
            var to: LatLon? = null
            val vias = ArrayList<LatLon>()
            var depth: Double? = null
            var air: String? = null
            var noBridges = false
            var buf = 60.0
            var inside = false
            var moveEnds = false
            var resolve = false
            var name: String? = null
            var outFile: File? = null
            var packs: File? = null
            var offline = false
            var beta = false
            var json = false
            var quiet = false
            var checks = true
            var chartDepth = true
            var charts: File? = null
            var i = 0
            fun value(opt: String): String {
                if (i + 1 >= args.size) throw UsageException("$opt needs a value")
                return args[++i]
            }
            fun point(opt: String): LatLon {
                val v = value(opt)
                return Coords.parse(v) ?: throw UsageException("$opt: not a position: $v")
            }
            while (i < args.size) {
                when (val a = args[i]) {
                    "-h", "--help" -> return null
                    "-f", "--from" -> from = point(a)
                    "-t", "--to" -> to = point(a)
                    "-v", "--via" -> vias.add(point(a))
                    "-d", "--depth" -> depth = value(a).toDoubleOrNull()?.takeIf { it > 0 && it < 40 }
                        ?: throw UsageException("Depth must be feet, 0-40")
                    "-a", "--air" -> air = value(a)
                    "--no-bridges" -> noBridges = true
                    "-b", "--buffer" -> buf = value(a).toDoubleOrNull()?.takeIf { it >= 0 && it <= 500 }
                        ?: throw UsageException("Buffer must be meters, 0-500")
                    "--inside" -> inside = true
                    "--move-ends" -> moveEnds = true
                    "--resolve" -> resolve = true
                    "--no-check-markers" -> checks = false
                    "--no-chart-depth" -> chartDepth = false
                    "--charts" -> charts = File(value(a))
                    "-n", "--name" -> name = value(a)
                    "-o", "--out" -> outFile = File(value(a))
                    "--packs" -> packs = File(value(a))
                    "--offline" -> offline = true
                    "--beta" -> beta = true
                    "--json" -> json = true
                    "-q", "--quiet" -> quiet = true
                    else -> throw UsageException("Unknown option: $a")
                }
                i++
            }
            return Options(
                from ?: throw UsageException("Give a start with -f"), to ?: throw UsageException("Give a destination with -t"),
                vias, depth ?: throw UsageException("Give a safe operating depth in feet with -d"),
                air, noBridges, buf, inside, moveEnds, resolve, name, outFile, packs, offline, beta, json, quiet, checks,
                chartDepth, charts,
            )
        }

        private fun cacheRoot() =
            File(System.getenv("XDG_CACHE_HOME")?.let { File(it) } ?: File(System.getProperty("user.home"), ".cache"), "noaa-route")

        /** Default route data folder: $NOAA_ROUTE_PACKS, else <cache>/noaa-route/packs[-beta]. */
        fun defaultPacks(beta: Boolean): File =
            System.getenv("NOAA_ROUTE_PACKS")?.let { File(it) } ?: File(cacheRoot(), if (beta) "packs-beta" else "packs")

        /** Default region chart folder: $NOAA_ROUTE_CHARTS, else <cache>/noaa-route/charts. */
        fun defaultCharts(): File = System.getenv("NOAA_ROUTE_CHARTS")?.let { File(it) } ?: File(cacheRoot(), "charts")

        /** Chart regions the route passes through, sampled every ~1 km (as in the app). */
        fun usedPacks(charts: ChartSet, r: Route): List<Pack> {
            val samples = r.points.zipWithNext().flatMap { (p, q) ->
                val n = ceil(Planner.distanceM(p, q) / 1000.0).toInt().coerceAtLeast(1)
                (0 until n).map { k -> LatLon(p.lat + (q.lat - p.lat) * k / n, p.lon + (q.lon - p.lon) * k / n) }
            } + r.points.last()
            return charts.packs.filter { pk -> samples.any { pk.covers(it.lat, it.lon) } }
        }

        private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }

    private var quiet = false
    private fun log(msg: String) { if (!quiet) err.println(msg) }

    fun run(args: Array<String>): Int {
        if ("--version" in args) {
            out.println("noaa-route " + (Cli::class.java.`package`?.implementationVersion ?: "dev"))
            return Exit.OK
        }
        val parsed = try {
            parse(args)
        } catch (e: UsageException) {
            err.println(e.message)
            err.println("noaa-route --help for usage")
            return Exit.USAGE
        }
        if (parsed == null) {
            out.println(USAGE)
            return Exit.OK
        }
        val o: Options = parsed
        quiet = o.quiet
        val airFt = try { Planner.airDraftFt(o.air ?: "", o.noBridges) } catch (e: PlanException) {
            err.println(if (o.air == null) "Give an air draft in feet with -a, or use --no-bridges" else e.message)
            return Exit.USAGE
        }
        val name = (o.name ?: o.out?.nameWithoutExtension ?: ("route_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())))
            .replace(Regex("[^\\w.-]+"), "_")
        val gpxFile = o.out ?: File("$name.gpx")
        val pts = listOf(o.from) + o.vias + listOf(o.to)

        // Route data: the catalog, updates of what is already here, then what this trip needs
        val dir = o.packs ?: defaultPacks(o.beta)
        val cache = PackCache(dir, File(dir, "catalog.json"), baseUrl ?: (RELEASES + if (o.beta) "route-pack-beta/" else "route-pack/"))
        var catalog: List<AreaInfo>
        if (o.offline) {
            catalog = cache.cachedCatalog()
        } else {
            catalog = try {
                val n = cache.updateAll()
                if (n > 0) log("Updated route data for $n region(s)")
                cache.cachedCatalog()
            } catch (e: Exception) {
                val saved = cache.cachedCatalog()
                if (saved.isEmpty()) {
                    err.println("Could not download the route data catalog: ${e.message}")
                    return Exit.DOWNLOAD
                }
                log("Could not check for route data updates (${e.message}); using what is downloaded")
                saved
            }
        }
        var packs = openPacks(dir)
        val need = Regions.need(pts, catalog, packs.map { it.areaId }.toSet()) { p -> packs.any { it.covers(p.lat, p.lon) } }
        if (need.download.isNotEmpty()) {
            if (o.offline) {
                err.println("This trip needs route data that is not downloaded: " +
                    need.download.joinToString { it.shortName } + ". Run once without --offline.")
                packs.forEach { it.close() }
                return Exit.DOWNLOAD
            }
            for (a in need.download) {
                log("Downloading %s (%.1f MB)".format(Locale.US, a.name, a.size / 1e6))
                try { cache.download(a) } catch (e: Exception) {
                    err.println("Could not download ${a.shortName}: ${e.message}")
                    packs.forEach { it.close() }
                    return Exit.DOWNLOAD
                }
            }
            packs.forEach { it.close() }
            packs = openPacks(dir)
        }
        if (packs.isEmpty()) {
            err.println(if (need.outside) "No NOAA Route chart region covers these points." else "No route data downloaded.")
            return Exit.NO_ROUTE
        }

        try {
            val charts = ChartSet(packs)
            val planner = Planner(charts)
            val t0 = System.currentTimeMillis()
            val planned = try {
                planner.plan(name, pts, o.depthFt, airFt, o.bufM, o.inside, o.moveEnds) { log(it) }
            } catch (e: PlanException) {
                err.println(e.message)
                return Exit.NO_ROUTE
            } catch (e: OutOfMemoryError) {
                err.println("Out of memory: try closer points or via points, or give Java more memory (-Xmx2g).")
                return Exit.NO_ROUTE
            }
            val sec = (System.currentTimeMillis() - t0) / 1000.0
            // Each leg against the chart's depth areas, as the app does after planning
            val (depthCharts, depthLine) = if (o.chartDepth) findDepthCharts(o, planned) else emptyList<ChartDepthChart>() to
                "chart depth check: off (--no-chart-depth)"
            val r = ChartDepthRoute.annotate(planned, depthCharts)
            val used = usedPacks(charts, r)
            val datum = Pack.datumName(used.flatMap { it.datums })
            write(gpxFile, r, datum, o.checks, pts)
            val trip = listOf("start: ${Coords.format(o.from)}") + o.vias.mapIndexed { k, v -> "via ${k + 1}: ${Coords.format(v)}" } +
                listOf("destination: ${Coords.format(o.to)}") +
                listOfNotNull("Stay inside: on (no open ocean)".takeIf { o.inside }, "Move ends to navigable water: on".takeIf { o.moveEnds })
            val after = listOf(
                "charts: " + used.joinToString(", ") { pk -> pk.areaName.substringBefore(":") + " (built ${pk.created.take(10)})" },
                depthLine,
                "planned " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()) + " in %.1f s".format(Locale.US, sec))

            var adjusted: Pair<Route, File>? = null
            var resolveSummary: String? = null
            if (o.resolve && Gpx.checkRuns(r).isNotEmpty()) {
                val res = try { planner.resolveChecks(r, pts, o.bufM, o.inside) { log(it) } } catch (e: OutOfMemoryError) { null }
                resolveSummary = res?.summary ?: "Out of memory while avoiding checks."
                if (res != null && res.resolved + res.partly > 0) {
                    val ar = ChartDepthRoute.annotate(res.route, depthCharts)
                    val f = File(gpxFile.absoluteFile.parentFile, "${gpxFile.nameWithoutExtension}_adjusted.gpx")
                    write(f, ar, datum, o.checks, pts)
                    adjusted = ar to f
                }
            }

            if (o.json) {
                out.println(json(r, gpxFile, adjusted, resolveSummary, used, datum, depthLine))
            } else {
                out.println(Gpx.report(r, trip, after, o.bufM, datum, "Saved to ${gpxFile.path}"))
                resolveSummary?.let { out.println(); out.println(it) }
                adjusted?.let { (ar, f) ->
                    out.println()
                    out.println(Gpx.report(ar, trip, after, o.bufM, datum, "Saved to ${f.path} (the original route file is unchanged)"))
                }
            }
            return Exit.OK
        } finally {
            packs.forEach { it.close() }
        }
    }

    /**
     * The region charts the route's points fall in, downloaded (or updated) when needed and
     * checked to open, with the report line saying what was checked. A chart that cannot be
     * had is left out and said so: the depth check is a safety check, so it is never skipped
     * silently.
     */
    private fun findDepthCharts(o: Options, r: Route): Pair<List<ChartDepthChart>, String> {
        val cache = ChartCache(o.charts ?: defaultCharts(), chartBaseUrl ?: (RELEASES + "vector-maps/"))
        val sets = cache.sets(o.offline).filter { ChartDepthRoute.hits(it.bounds, r) }
        if (sets.isEmpty()) {
            val why = "no region chart covers this route" + if (o.offline) " (or none downloaded yet)" else ""
            err.println("Chart depth check skipped: $why. Legs were checked on the 20 m grid only.")
            return emptyList<ChartDepthChart>() to "chart depth check: skipped, $why"
        }
        val ok = ArrayList<ChartDepthChart>()
        val names = ArrayList<String>()
        val missing = ArrayList<String>()
        for (set in sets) {
            if (!cache.current(set) && !o.offline) {
                log("Downloading %s chart for the depth check (%.1f MB)".format(Locale.US, set.shortName, set.size / 1e6))
                try { cache.download(set) } catch (e: Exception) { log("Could not download the ${set.shortName} chart: ${e.message}") }
            }
            if (!cache.have(set)) { missing.add("${set.shortName} (not downloaded)"); continue }
            val chart = cache.chart(set)
            try {
                chart.open().close()
            } catch (e: Throwable) { // a native SQLite library that will not load is an Error, not an Exception
                missing.add("${set.shortName} (cannot read: ${e.message ?: e.javaClass.simpleName})")
                continue
            }
            ok.add(chart)
            names.add(set.shortName + if (cache.current(set)) " chart" else " chart (older copy)")
        }
        if (missing.isNotEmpty()) {
            err.println("Chart depth check skipped for " + missing.joinToString() + ". Those legs were checked on the 20 m grid only.")
        }
        val line = "chart depth check: " + listOfNotNull(
            names.takeIf { it.isNotEmpty() }?.joinToString(),
            missing.takeIf { it.isNotEmpty() }?.let { "skipped for " + it.joinToString() },
        ).joinToString("; ")
        return ok to line
    }

    private fun openPacks(dir: File): List<Pack> =
        dir.listFiles { f -> f.name.endsWith(".bin") }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            try { Pack(f) } catch (e: Exception) { log("Skipping ${f.name}: ${e.message}"); null }
        }

    private fun write(f: File, r: Route, datum: String, checks: Boolean, pts: List<LatLon>) {
        f.absoluteFile.parentFile?.mkdirs()
        f.writeText(Gpx.write(r, datum, checks, pts, CREATOR))
    }

    private fun json(r: Route, gpx: File, adjusted: Pair<Route, File>?, resolveSummary: String?, used: List<Pack>, datum: String,
                     depthLine: String): String {
        fun routeJson(rt: Route, f: File) = "{\"name\":${jsonStr(rt.name)},\"gpx\":${jsonStr(f.path)}," +
            "\"nm\":${"%.2f".format(Locale.US, rt.totalNm)},\"legs\":${rt.legs.size}," +
            "\"checkLegs\":${rt.legs.count { Gpx.flagged(it) }},\"checkRuns\":${Gpx.checkRuns(rt).size}," +
            "\"messages\":[${rt.messages.joinToString(",") { jsonStr(it) }}]}"
        return "{\"route\":${routeJson(r, gpx)}" +
            (adjusted?.let { (ar, f) -> ",\"adjusted\":${routeJson(ar, f)}" } ?: "") +
            (resolveSummary?.let { ",\"resolve\":${jsonStr(it)}" } ?: "") +
            ",\"datum\":${jsonStr(datum)},\"regions\":[${used.joinToString(",") { jsonStr(it.areaId) }}]," +
            "\"chartDepth\":${jsonStr(depthLine.removePrefix("chart depth check: "))}}"
    }
}
