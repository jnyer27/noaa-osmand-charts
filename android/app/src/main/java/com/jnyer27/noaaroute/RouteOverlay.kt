package com.jnyer27.noaaroute

import java.util.Locale
import kotlin.math.floor

/**
 * Colours for the chart screen's Route data overlay: one pack cell, drawn the way
 * [Planner.buildGrid] classifies it. Depth uses [Planner.classify] (the same rounding).
 * Hazards, piers, prohibited areas, bridges, cables, snags and channel markers use the
 * same plane tests as the planner. Dredged water and fairway water are told apart from
 * the depth-table kind, because [ChartSet.window] stores both as the dredged bit.
 *
 * Plain Kotlin. The activity turns the colours into bitmaps; nothing here touches Android.
 */
object RouteOverlay {
    const val PREF = "routeOverlay"
    const val TOGGLE = "Route data"
    const val DRAWING = "Drawing route data..."
    const val NO_PACK = "Route data: no route pack downloaded for this view"
    const val NO_PACK_NONE = "Route data: no route pack downloaded for this view. Download a region under Regions."
    const val NOTHING = "Route data is on. No charted cells in the route pack for this view."

    /** Bitmap edge, and the pack tile edge. Strides below are powers of two that divide this. */
    const val PIX = 256

    /** How many images may sit on the map at once. Past that, cells are grouped. */
    const val MAX_IMAGES = 48

    /** A view wider than this, in tiles, is the camera before it has settled. */
    private const val MAX_SPAN_TILES = 2500

    enum class Kind {
        NONE,
        SHALLOW,
        MARGINAL,
        SAFE,
        DREDGED,
        DREDGED_MARGINAL,
        FAIRWAY,
        FAIRWAY_MARGINAL,
        HAZARD,
        PIER,
        PROHIBITED,
        LOW_BRIDGE,
        LOW_CABLE,
        GATE,
        SNAG,
    }

    /** One decompressed tile plus the depth table of the pack it came from. */
    class TilePlanes(val data: ByteArray, val table: List<DepthRange?>)

    /** One image on the map: stride by stride pack tiles, origin on the global grid. */
    class Block(val gtx: Int, val gty: Int, val stride: Int)

    /**
     * Higher wins when several cells share a pixel (zoomed out) and when several
     * marks share a cell. Point marks sit above the depth fill so a rock or a
     * wrong-side line stays visible. Entry prohibited sits above the fill and
     * below those marks.
     */
    fun rank(kind: Kind): Int = when (kind) {
        Kind.NONE -> 0
        Kind.SAFE -> 10
        Kind.FAIRWAY -> 14
        Kind.DREDGED -> 15
        Kind.MARGINAL -> 23
        Kind.FAIRWAY_MARGINAL -> 24
        Kind.DREDGED_MARGINAL -> 25
        Kind.SHALLOW -> 30
        Kind.PROHIBITED -> 35
        Kind.GATE -> 40
        Kind.SNAG -> 50
        Kind.HAZARD -> 60
        Kind.PIER -> 80
        Kind.LOW_CABLE -> 90
        Kind.LOW_BRIDGE -> 100
    }

    private val RANK = IntArray(Kind.entries.size) { rank(Kind.entries[it]) }

    // Alphas are part of the colour: deep water stays faint so the chart shows through,
    // and a hazard is nearly solid so it reads as a dot on that fill.
    private val COLOR = intArrayOf(
        0,                  // NONE, land or no data
        0xB4E53935.toInt(), // SHALLOW, red
        0xB4F9A825.toInt(), // MARGINAL, amber
        0x381565C0,         // SAFE, faint blue
        0xA000897B.toInt(), // DREDGED, teal
        0xD0B8860B.toInt(), // DREDGED_MARGINAL, dark gold
        0x903949AB.toInt(), // FAIRWAY, indigo
        0xC07E57C2.toInt(), // FAIRWAY_MARGINAL
        0xF0D50000.toInt(), // HAZARD, bright red
        0xE6212121.toInt(), // PIER, near black
        0xD06A1B9A.toInt(), // PROHIBITED, purple (even pixels only)
        0xF0880E4F.toInt(), // LOW_BRIDGE, maroon
        0xF0FF6D00.toInt(), // LOW_CABLE, orange
        0xF000ACC1.toInt(), // GATE, cyan
        0xF06D4C41.toInt(), // SNAG, brown
    )

    /** ARGB for [kind]. Prohibited cells hatch: every other pixel is left clear. */
    fun argb(kind: Kind, row: Int, col: Int): Int {
        if (kind == Kind.NONE) return 0
        if (kind == Kind.PROHIBITED && ((row + col) and 1) != 0) return 0
        return COLOR[kind.ordinal]
    }

    /** Depth-area class only, including dredged and fairway. Land and uncharted are [Kind.NONE]. */
    fun depthKind(range: DepthRange?, depthM: Double): Kind {
        val cls = Planner.classify(range, depthM - Planner.ROUNDING_M)
        return when (cls) {
            Planner.SHALLOW -> Kind.SHALLOW
            Planner.MARGINAL -> when {
                range?.dredged == true -> Kind.DREDGED_MARGINAL
                range?.fairway == true -> Kind.FAIRWAY_MARGINAL
                else -> Kind.MARGINAL
            }
            Planner.SAFE -> when {
                range?.dredged == true -> Kind.DREDGED
                range?.fairway == true -> Kind.FAIRWAY
                else -> Kind.SAFE
            }
            else -> Kind.NONE
        }
    }

    /**
     * Class of one cell. Plane values may be signed bytes; only the low 8 bits count.
     * [depthM] and [airM] are meters. [airM] null means the form's air draft is empty:
     * spans and cables are not marked, and a bridge deck with no charted clearance still is,
     * because the planner will not go under that deck either way.
     */
    fun kind(
        depthIndex: Int, hazard: Int, sound: Int, flags: Int, spanClr: Int, cableClr: Int,
        table: List<DepthRange?>, depthM: Double, airM: Double?,
    ): Kind {
        val hzv = hazard and 0xff
        val sv = sound and 0xff
        val fl = flags and 0xff
        val sp = spanClr and 0xff
        val cc = cableClr and 0xff
        val opening = fl and Pack.F_OPENING != 0
        val span = fl and Pack.F_SPAN != 0
        val deck = fl and Pack.F_DECK != 0
        val air = airM ?: 0.0
        val clr = sp / 2.0
        // A span whose charted clearance covers the air draft is passable even when the
        // same cell also carries the deck flag (the deck band beside the opening).
        val spanClears = span && clr >= air
        val lowBridge = !opening && !spanClears && (deck || (span && clr < air))
        val lowCable = airM != null && fl and Pack.F_CABLE != 0 && (cc == 0 || cc / 2.0 < airM)
        val pier = fl and Pack.F_STRUCT != 0 || hzv == Pack.HZ_PIER
        val snag = hzv == 1 && fl and Pack.F_CABLE == 0 && cc == Pack.SNAG_MARK
        val gate = fl and Pack.F_CABLE == 0 && cc == Pack.GATE_MARK
        // Obstructions to the safe depth plus one foot. A sounding whose chart label
        // (whole feet, as the tiles print them) is under the safe depth. A label
        // equal to it is not a hazard. Depth areas still use Planner.classify and its 5 cm slack.
        val hazardHit = hzv != 0 && hzv != Pack.HZ_PIER && !snag && (hzv - 1) / 10.0 < depthM + Pack.FT
        val soundHit = sv != 0 && Planner.soundingShallow((sv - 1) / 10.0, Planner.depthFeet(depthM))
        return when {
            lowBridge -> Kind.LOW_BRIDGE
            lowCable -> Kind.LOW_CABLE
            pier -> Kind.PIER
            hazardHit || soundHit -> Kind.HAZARD
            snag -> Kind.SNAG
            gate -> Kind.GATE
            fl and Pack.F_PROHIB != 0 -> Kind.PROHIBITED
            else -> depthKind(table.getOrNull(depthIndex and 0xff), depthM)
        }
    }

    /** [kind] for cell (row, col) of a plane-major tile, the layout [Pack.tileData] returns. */
    fun kindInTile(
        data: ByteArray, row: Int, col: Int, tile: Int, table: List<DepthRange?>,
        depthM: Double, airM: Double?,
    ): Kind {
        val tt = tile * tile
        val i = row * tile + col
        return kind(
            data[Pack.P_DEPTH * tt + i].toInt(), data[Pack.P_HAZARD * tt + i].toInt(),
            data[Pack.P_SOUND * tt + i].toInt(), data[Pack.P_FLAGS * tt + i].toInt(),
            data[Pack.P_SPANCLR * tt + i].toInt(), data[Pack.P_CABLECLR * tt + i].toInt(),
            table, depthM, airM,
        )
    }

    /**
     * Paint one [Block] into [pixels] (PIX by PIX, row 0 at the north). Each pixel keeps
     * the highest-rank cell in its stride-by-stride square, so a zoomed-out view still
     * shows a hazard or a marker. Returns how many pixels are not clear, or -1 if [alive]
     * became false between tiles.
     */
    fun paintBlock(
        stride: Int, tile: Int, tileAt: (tx: Int, ty: Int) -> TilePlanes?,
        depthM: Double, airM: Double?, pixels: IntArray, alive: () -> Boolean = { true },
    ): Int {
        require(tile == PIX && pixels.size == PIX * PIX && stride >= 1)
        pixels.fill(0)
        val tt = tile * tile
        val rank = IntArray(PIX * PIX)
        for (ty in 0 until stride) {
            if (!alive()) return -1
            for (tx in 0 until stride) {
                val view = tileAt(tx, ty) ?: continue
                val data = view.data
                val table = view.table
                val depthLut = IntArray(256) { depthKind(table.getOrNull(it), depthM).ordinal }
                val row0 = ty * tile
                val col0 = tx * tile
                for (lr in 0 until tile) {
                    val py = (row0 + lr) / stride
                    val rowBase = lr * tile
                    val pixRow = py * PIX
                    for (lc in 0 until tile) {
                        val px = (col0 + lc) / stride
                        val i = rowBase + lc
                        val hz = data[Pack.P_HAZARD * tt + i].toInt() and 0xff
                        val sv = data[Pack.P_SOUND * tt + i].toInt() and 0xff
                        val fl = data[Pack.P_FLAGS * tt + i].toInt() and 0xff
                        val cb = data[Pack.P_CABLECLR * tt + i].toInt() and 0xff
                        val ord = if (hz == 0 && sv == 0 && fl == 0 && cb == 0) {
                            depthLut[data[Pack.P_DEPTH * tt + i].toInt() and 0xff]
                        } else {
                            kind(
                                data[Pack.P_DEPTH * tt + i].toInt(), hz, sv, fl,
                                data[Pack.P_SPANCLR * tt + i].toInt(), cb, table, depthM, airM,
                            ).ordinal
                        }
                        val r = RANK[ord]
                        val idx = pixRow + px
                        if (r > rank[idx]) {
                            rank[idx] = r
                            pixels[idx] = argb(Kind.entries[ord], py, px)
                        }
                    }
                }
            }
        }
        var drawn = 0
        for (p in pixels) if (p != 0) drawn++
        return drawn
    }

    /** Global tile column and row for a point. Same numbering as the pack builder: 90 N, 180 W. */
    fun globalTile(lat: Double, lon: Double, dlat: Double, dlon: Double, tile: Int): Pair<Int, Int> {
        val gtx = floor((lon + 180.0) / (dlon * tile)).toInt()
        val gty = floor((90.0 - lat) / (dlat * tile)).toInt()
        return gtx to gty
    }

    /** West, south, east, north of global tile (gtx, gty). */
    fun tileBounds(gtx: Int, gty: Int, dlat: Double, dlon: Double, tile: Int): DoubleArray {
        val west = -180.0 + gtx * tile * dlon
        val north = 90.0 - gty * tile * dlat
        return doubleArrayOf(west, north - tile * dlat, west + tile * dlon, north)
    }

    /** West, south, east, north of a block whose origin is a global tile. */
    fun blockBounds(gtx: Int, gty: Int, stride: Int, dlat: Double, dlon: Double, tile: Int): DoubleArray {
        val west = -180.0 + gtx * tile * dlon
        val north = 90.0 - gty * tile * dlat
        val span = stride * tile
        return doubleArrayOf(west, north - span * dlat, west + span * dlon, north)
    }

    /** True when global tile (gtx, gty) falls inside a pack's own tile rectangle. */
    fun packCoversTile(tx0: Int, ty0: Int, width: Int, height: Int, tile: Int, gtx: Int, gty: Int): Boolean {
        val tx = gtx - tx0
        val ty = gty - ty0
        return tx >= 0 && ty >= 0 && tx < width / tile && ty < height / tile
    }

    /**
     * Pack tiles meeting the view, in row-major order, that [covers] accepts.
     * Null when the view is too large to walk (the camera has not settled on the chart).
     * A view that ends on a tile boundary includes that next tile, so the edge is not left bare.
     */
    fun tilesInView(
        west: Double, south: Double, east: Double, north: Double,
        dlat: Double, dlon: Double, tile: Int, covers: (gtx: Int, gty: Int) -> Boolean,
    ): List<Pair<Int, Int>>? {
        if (!(east > west && north > south) || dlat <= 0.0 || dlon <= 0.0 || tile <= 0) return emptyList()
        val gtx0 = floor((west + 180.0) / (dlon * tile)).toInt()
        val gtx1 = floor((east + 180.0) / (dlon * tile)).toInt()
        val gty0 = floor((90.0 - north) / (dlat * tile)).toInt()
        val gty1 = floor((90.0 - south) / (dlat * tile)).toInt()
        if (gtx1 < gtx0 || gty1 < gty0) return emptyList()
        if (gtx1 - gtx0 > MAX_SPAN_TILES || gty1 - gty0 > MAX_SPAN_TILES) return null
        val out = ArrayList<Pair<Int, Int>>((gtx1 - gtx0 + 1) * (gty1 - gty0 + 1))
        for (gty in gty0..gty1) for (gtx in gtx0..gtx1) if (covers(gtx, gty)) out.add(gtx to gty)
        return out
    }

    /** Group tiles into images, raising the stride until there are at most [maxImages]. */
    fun chooseBlocks(tiles: List<Pair<Int, Int>>, maxImages: Int = MAX_IMAGES): List<Block> {
        if (tiles.isEmpty()) return emptyList()
        var stride = 1
        var blocks = group(tiles, stride)
        while (blocks.size > maxImages && stride < 32) {
            stride *= 2
            blocks = group(tiles, stride)
        }
        if (blocks.size <= maxImages) return blocks
        val cx = tiles.sumOf { it.first } / tiles.size
        val cy = tiles.sumOf { it.second } / tiles.size
        return blocks.sortedBy { b ->
            val dx = b.gtx - cx
            val dy = b.gty - cy
            dx * dx + dy * dy
        }.take(maxImages)
    }

    private fun group(tiles: List<Pair<Int, Int>>, stride: Int): List<Block> {
        val seen = HashSet<Long>()
        val out = ArrayList<Block>()
        for ((x, y) in tiles) {
            val bx = Math.floorDiv(x, stride) * stride
            val by = Math.floorDiv(y, stride) * stride
            val key = (bx.toLong() shl 32) xor (by.toLong() and 0xffffffffL)
            if (seen.add(key)) out.add(Block(bx, by, stride))
        }
        return out
    }

    /** The colour key, plus how long this view took and whether it is zoomed out. */
    fun legend(depthFt: Double, airFt: Double?, images: Int, seconds: Double, stride: Int): String {
        val depth = ChartTiles.depthText(depthFt)
        val air = if (airFt == null) "Air draft is not set, so span and cable clearances are not marked."
            else "Air draft ${ChartTiles.depthText(airFt)} ft."
        val sec = if (seconds < 0.05) "under 0.1" else String.format(Locale.US, "%.1f", seconds)
        val zoom = if (stride > 1) {
            " Zoomed out: one pixel covers $stride by $stride cells, and a hazard or marker in that square still shows."
        } else ""
        return "Planner grid at $depth ft. $air Drawn in $sec s, $images on screen.$zoom\n" +
            "Red: too shallow or drying. Amber: straddles your depth. Faint blue: deep enough.\n" +
            "Teal: dredged, deep enough. Dark gold: dredged, may be under your depth.\n" +
            "Indigo: fairway or track, deep enough. Lighter purple: fairway that may be shoal.\n" +
            "Bright red: hazard. Black: pier or structure. Purple hatch: entry prohibited.\n" +
            "Maroon: bridge too low. Orange: cable too low. Cyan: wrong side of a mark. Brown: pile or snag.\n" +
            "Clear: land or no data."
    }
}
