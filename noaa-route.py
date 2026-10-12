#!/data/data/com.termux/files/usr/bin/python3
"""noaa-route.py
Plan a boat route over NOAA ENC vector charts and write it as a GPX route.
Runs in Termux. Needs: pkg install gdal python-numpy (plus openexr if gdal fails to load).

Usage:
  python3 noaa-route.py                      (no options: asks step by step)
  python3 noaa-route.py -f LAT,LON -t LAT,LON -d DEPTH_FT [options]
  python3 noaa-route.py -l -t LAT,LON -d DEPTH_FT [options]

Points are decimal degrees: 39.2508,-76.4886 or 39.2508 N, 76.4886 W.

Options:
  -f LAT,LON     Start point (decimal degrees, west is negative)
  -l             Start from the phone's current location (Termux:API app and
                 pkg termux-api, with Location permission granted)
  -t LAT,LON     End point
  -v LAT,LON     Via point, repeatable, visited in order
  -d FEET        Safe operating depth in feet: the minimum charted depth you
                 need (your draft plus your margin, e.g. 1.8 ft draft -> 3)
  -a FEET        Air draft in feet; bridge spans and overhead cables with less
                 charted clearance are avoided (default: not checked)
  -n NAME        Route name and file name (default: route)
  -o DIR         Output folder (default: ~/storage/downloads/noaa-charts/routes)
  -s STATES      NOAA ENC sets to use, comma separated (default: MD)
  -r METERS      Grid resolution in meters (default: auto, 10 m for short
                 trips up to 60 m for long ones)
  -b METERS      Buffer around hazards in meters (default: 60)
  -u             Check NOAA for newer ENC data before planning
  -O             Open the routes folder in the Files app when done; tap the
                 GPX there and pick OsmAnd (termux-open crashes OsmAnd on GPX)
  -h             Help

How depth is judged (charted depths are at MLLW, no tide added):
  - Depth areas whose shallowest value is >= your depth: safe.
  - Depth areas that start shallower but reach deeper (NOAA's usual 0-6 ft
    band): usable but avoided; soundings shallower than your depth inside
    them are treated as hazards. Legs through this water are flagged.
  - Land, drying areas, unsurveyed or uncharted water: never used.
  - Rocks, wrecks, obstructions shallower than depth + 1 ft (or of unknown
    depth): avoided with the hazard buffer. Bridge piers and piers drawn
    as areas are visible, so only the structure itself is avoided. Single
    charted piles are not avoided (a grid cell is far wider than a pile).
  - Bridges are only passed under spans that have a charted vertical
    clearance (the navigation spans), and with -a only if it is enough.
    Opening bridges (swing, bascule, lift, draw) are passed and reported
    with their closed clearance; check opening schedules.
  - Areas charted "entry prohibited" or "area to be avoided" are never used.
    Other restricted areas and overhead cables are not avoided (cables are
    with -a), but every crossing is reported with its rule and clearance.

NOT FOR NAVIGATION. Check every leg against a current chart before using it.
"""

import argparse
import heapq
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from array import array

try:
    import numpy as np
except ImportError:
    print("ERROR: numpy not found. Run: pkg install python-numpy", file=sys.stderr)
    sys.exit(1)
from urllib.parse import quote
from xml.sax.saxutils import escape

HOME = os.path.expanduser("~")
ENC_DIR = os.path.join(HOME, ".noaa-enc")
ENC_URL = "https://charts.noaa.gov/ENCs/{}_ENCs.zip"
S57_OPTS = "UPDATES=APPLY,SPLIT_MULTIPOINT=ON,ADD_SOUNDG_DEPTH=ON"
LAYERS = ["M_COVR", "DEPARE", "DRGARE", "LNDARE", "OBSTRN", "WRECKS", "UWTROC",
          "SOUNDG", "PILPNT", "PYLONS", "RESARE", "BRIDGE", "CBLOHD"]
FT = 0.3048
RESTRN = {"1": "anchoring prohibited", "2": "anchoring restricted", "3": "fishing prohibited",
          "4": "fishing restricted", "7": "entry prohibited", "8": "entry restricted",
          "13": "no wake", "14": "area to be avoided"}
M_PER_DEG = 111320.0
NM = 1852.0

# Grid values
BLOCKED, SAFE, MARGINAL, HAZARD, STRUCT, SHALLOW = 0, 1, 2, 3, 4, 5  # STRUCT: visible, not buffered
# NOAA converts feet to meters at 0.1 m, so the 6 ft line is stored as 1.8 m (5.9 ft).
# Compare depths with this much slack so "6 ft" water counts for a 6 ft need.
ROUNDING_M = 0.05
# Flag raster bands (1-3 are reported per leg; 4 = no passage under, 5 = passable span)
FLAG_LAYERS = {"RESARE": 1, "BRIDGE": 2, "CBLOHD": 3}
NO_PASS, SPAN_OK = 4, 5
# S-57 CATBRG values for bridges that open: opening, swing, lifting, bascule, drawbridge, transporter
OPENING = {"2": "opening", "3": "swing", "4": "lift", "5": "bascule", "7": "draw", "8": "transporter"}
OPENING_SQL = " OR ".join(f"CATBRG LIKE '%\"{c}\"%'" for c in OPENING)


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def die(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def run(cmd, capture=False):
    env = dict(os.environ, OGR_S57_OPTIONS=S57_OPTS, CPL_LOG="/dev/null")
    p = subprocess.run(cmd, env=env, stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                       stderr=subprocess.PIPE, text=True)
    if p.returncode != 0:
        die(f"{os.path.basename(cmd[0])} failed: {p.stderr.strip()[-400:]}")
    return p.stdout if capture else ""


def point_or_none(s):
    """'39.25,-76.48', '39.25 -76.48' or '39.25084° N, 76.48861° W' -> (lat, lon)."""
    t = s.strip().upper().replace("\u00b0", " ").replace(",", " ")
    m = re.fullmatch(r"\s*([-+]?\d+(?:\.\d+)?)\s*([NS])?\s+([-+]?\d+(?:\.\d+)?)\s*([EW])?\s*", t)
    if not m:
        return None
    lat, lon = float(m.group(1)), float(m.group(3))
    if m.group(2) == "S":
        lat = -abs(lat)
    if m.group(4) == "W":
        lon = -abs(lon)
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return None
    return lat, lon


def parse_point(s):
    p = point_or_none(s)
    if p is None:
        die(f"Point must be LAT,LON in decimal degrees (e.g. 39.2508,-76.4886): {s}")
    return p


# ---------------------------------------------------------------- interview

DEFAULTS_F = os.path.join(ENC_DIR, "route-defaults.json")


def interview():
    """Ask for each setting; return the equivalent command-line arguments."""
    try:
        last = json.load(open(DEFAULTS_F))
    except (OSError, ValueError):
        last = {}

    def ask(prompt, default=None, check=None):
        while True:
            hint = f" [{default}]" if default not in (None, "") else ""
            try:
                v = input(f"{prompt}{hint}: ").strip()
            except EOFError:
                print()
                sys.exit(1)
            if not v and default is not None:
                v = str(default)
            err = check(v) if check else None
            if not err:
                return v
            print(f"  {err}")

    def point_check(v):
        return None if point_or_none(v) else "Use decimal degrees, e.g. 39.2508,-76.4886 or 39.2508 N, 76.4886 W"

    def num_check(lo, hi, optional=False):
        def f(v):
            if optional and not v:
                return None
            try:
                return None if lo < float(v) < hi else f"Enter a number between {lo} and {hi}"
            except ValueError:
                return "Enter a number in feet"
        return f

    print("NOAA route planner. Press Enter to accept the [default]. Ctrl-C to quit.")
    print("Tip: in OsmAnd, long-press a spot and tap the coordinates to copy them.\n")
    args = []
    start = ask("Start: l = current location, or LAT,LON", "l",
                lambda v: None if v.lower() == "l" else point_check(v))
    args += ["-l"] if start.lower() == "l" else ["-f", start]
    args += ["-t", ask("Destination LAT,LON", None, point_check)]
    while True:
        v = ask("Via point LAT,LON (Enter for none)", "",
                lambda v: None if not v else point_check(v))
        if not v:
            break
        args += ["-v", v]
    draft = ask("Safe operating depth in feet (draft plus margin)", last.get("draft"), num_check(0, 40))
    args += ["-d", draft]
    air = ask("Air draft in feet (Enter to skip bridge clearance checks)", last.get("air", ""),
              num_check(0, 300, optional=True))
    if air:
        args += ["-a", air]
    name = ask("Route name", time.strftime("route_%Y%m%d_%H%M"),
               lambda v: None if re.fullmatch(r"[\w.-]+", v) else "Letters, numbers, - _ . only")
    args += ["-n", name]
    if ask("Check NOAA for chart updates first (needs internet)? y/n", "n").lower().startswith("y"):
        args += ["-u"]
    if ask("Open the routes folder in Files when done (tap the GPX, pick OsmAnd)? y/n", "y").lower().startswith("y"):
        args += ["-O"]
    print()

    try:
        os.makedirs(ENC_DIR, exist_ok=True)
        json.dump({"draft": draft, "air": air}, open(DEFAULTS_F, "w"))
    except OSError:
        pass
    print("Same route next time without questions:")
    print("  python3 noaa-route.py " + " ".join(f'"{x}"' if " " in x else x for x in args) + "\n")
    return args


def current_location():
    """Current position from termux-location: GPS first, then network."""
    if not shutil.which("termux-location"):
        die("termux-location not found. Install the Termux:API app, then run: pkg install termux-api")
    for provider, wait in (("gps", 90), ("network", 30)):
        log(f"Getting current location ({provider}, up to {wait} s)")
        try:
            out = subprocess.run(["termux-location", "-p", provider, "-r", "once"],
                                 capture_output=True, text=True, timeout=wait).stdout
            j = json.loads(out)
        except subprocess.TimeoutExpired:
            log(f"No {provider} fix")
            continue
        except ValueError:
            continue
        if "error" in j:
            die(f"termux-location: {j['error']}\n"
                "Grant it: Android Settings > Apps > Termux:API > Permissions > Location > "
                "Allow, with Use precise location on.")
        if "latitude" in j and "longitude" in j:
            log(f"Current location {j['latitude']:.5f},{j['longitude']:.5f} "
                f"(accuracy {j.get('accuracy', 0):.0f} m, {provider})")
            return j["latitude"], j["longitude"]
    die("Could not get a location fix. Turn on Location, go outside or near a window, and retry, or use -f.")


# ---------------------------------------------------------------- ENC data

def update_enc(state, check):
    """Download/refresh one NOAA ENC set. Returns True if new data arrived."""
    os.makedirs(os.path.join(ENC_DIR, "dl"), exist_ok=True)
    zpath = os.path.join(ENC_DIR, "dl", f"{state}_ENCs.zip")
    sdir = os.path.join(ENC_DIR, state)
    if os.path.isfile(zpath) and os.path.isdir(sdir) and not check:
        return False
    part = zpath + ".part"
    if os.path.exists(part):
        os.remove(part)
    cmd = ["curl", "-fsSL", "-R", "--retry", "3", "--connect-timeout", "30", "-o", part]
    if os.path.isfile(zpath):
        cmd += ["-z", zpath]
    log(f"{state}: checking NOAA for ENC updates")
    if subprocess.run(cmd + [ENC_URL.format(state)]).returncode != 0:
        if os.path.isdir(sdir):
            log(f"{state}: download failed, using existing data")
            return False
        die(f"{state}: could not download {ENC_URL.format(state)}")
    if not os.path.isfile(part) or os.path.getsize(part) == 0:
        if os.path.exists(part):
            os.remove(part)
        log(f"{state}: up to date")
        if not os.path.isdir(sdir):
            extract(zpath, sdir)
            return True
        return False
    try:
        zipfile.ZipFile(part).testzip()
    except zipfile.BadZipFile:
        die(f"{state}: downloaded file is not a valid zip")
    os.replace(part, zpath)
    extract(zpath, sdir)
    return True


def extract(zpath, sdir):
    log(f"Extracting {os.path.basename(zpath)}")
    if os.path.isdir(sdir):
        shutil.rmtree(sdir)
    with zipfile.ZipFile(zpath) as z:
        z.extractall(sdir)


def find_cells(states):
    cells = {}
    for st in states:
        for root, _, files in os.walk(os.path.join(ENC_DIR, st)):
            for f in files:
                if f.endswith(".000") and f[:-4] not in cells:
                    cells[f[:-4]] = os.path.join(root, f)
    return cells


def newest_mtime(path):
    d = os.path.dirname(path)
    return max(os.path.getmtime(os.path.join(d, f)) for f in os.listdir(d))


def cell_extents(cells):
    cache_f = os.path.join(ENC_DIR, "extents.json")
    try:
        cache = json.load(open(cache_f))
    except (OSError, ValueError):
        cache = {}
    out, dirty = {}, False
    for name, path in cells.items():
        key = f"{path}:{newest_mtime(path):.0f}"
        if cache.get(name, {}).get("key") != key:
            txt = run(["ogrinfo", "-ro", "-so", path, "M_COVR"], capture=True)
            m = re.search(r"Extent: \(([-\d.]+), ([-\d.]+)\) - \(([-\d.]+), ([-\d.]+)\)", txt)
            cache[name] = {"key": key, "ext": [float(v) for v in m.groups()] if m else None}
            dirty = True
        out[name] = cache[name]["ext"]
    if dirty:
        json.dump(cache, open(cache_f, "w"))
    return out


def cell_gpkg(name, path):
    """Convert one S-57 cell (with all update files applied) to a GeoPackage."""
    gdir = os.path.join(ENC_DIR, "gpkg")
    os.makedirs(gdir, exist_ok=True)
    g = os.path.join(gdir, name + ".gpkg")
    lj = g + ".layers"
    if os.path.isfile(g) and os.path.isfile(lj) and os.path.getmtime(g) >= newest_mtime(path):
        return g, json.load(open(lj))
    txt = run(["ogrinfo", "-ro", "-q", path], capture=True)
    have = [l for l in LAYERS if re.search(rf"^\d+: {l}\b", txt, re.M)]
    for f in (g, lj):
        if os.path.exists(f):
            os.remove(f)
    run(["ogr2ogr", "-f", "GPKG", "-nlt", "GEOMETRY", "-skipfailures", g, path] + have)
    json.dump(have, open(lj, "w"))
    return g, have


def band_vrt(work, band, names, cells):
    """OGR VRT that unions each layer across all cells of one usage band."""
    layers = {}
    for n in names:
        g, have = cell_gpkg(n, cells[n])
        for l in have:
            layers.setdefault(l, []).append(g)
    xml = ["<OGRVRTDataSource>"]
    for l, gs in layers.items():
        xml.append(f'<OGRVRTUnionLayer name="{l}">')
        xml += [f'<OGRVRTLayer name="{l}"><SrcDataSource>{escape(g)}</SrcDataSource>'
                f'<SrcLayer>{l}</SrcLayer></OGRVRTLayer>' for g in gs]
        xml.append("</OGRVRTUnionLayer>")
    xml.append("</OGRVRTDataSource>")
    path = os.path.join(work, f"band{band}.vrt")
    with open(path, "w") as f:
        f.write("\n".join(xml))
    return path, set(layers)


# ---------------------------------------------------------------- grid

class Grid:
    def __init__(self, pts, res, widen=1.0):
        lats = [p[0] for p in pts]
        lons = [p[1] for p in pts]
        span = max(max(lats) - min(lats), max(lons) - min(lons))
        pad = max(0.06, span * 0.25) * widen
        latmid = (min(lats) + max(lats)) / 2
        padx = pad / math.cos(math.radians(latmid))
        self._box(min(lats) - pad, max(lats) + pad, min(lons) - padx, max(lons) + padx, res)

    @classmethod
    def around(cls, lat, lon, half_m, res, align=None):
        """Box of +/- half_m meters around a point; with align, on that grid's cell lattice."""
        g = cls.__new__(cls)
        dy = half_m / M_PER_DEG
        dx = half_m / (M_PER_DEG * math.cos(math.radians(lat)))
        if align is None:
            g._box(lat - dy, lat + dy, lon - dx, lon + dx, res)
            return g
        g.res, g.dlat, g.dlon = align.res, align.dlat, align.dlon
        g.north = align.north - math.floor((align.north - (lat + dy)) / align.dlat) * align.dlat
        g.west = align.west + math.floor((lon - dx - align.west) / align.dlon) * align.dlon
        g.h = int(math.ceil(2 * dy / align.dlat)) + 1
        g.w = int(math.ceil(2 * dx / align.dlon)) + 1
        g.south = g.north - g.h * g.dlat
        g.east = g.west + g.w * g.dlon
        return g

    def _box(self, south, north, west, east, res):
        self.south, self.north, self.west = south, north, west
        latmid = (south + north) / 2
        self.dlat = res / M_PER_DEG
        self.dlon = res / (M_PER_DEG * math.cos(math.radians(latmid)))
        self.w = int(math.ceil((east - west) / self.dlon))
        self.h = int(math.ceil((north - south) / self.dlat))
        self.east = self.west + self.w * self.dlon
        self.south = self.north - self.h * self.dlat
        self.res = res

    def cell(self, lat, lon):
        c = int((lon - self.west) / self.dlon)
        r = int((self.north - lat) / self.dlat)
        return min(max(r, 0), self.h - 1), min(max(c, 0), self.w - 1)

    def latlon(self, i):
        r, c = divmod(i, self.w)
        return self.north - (r + 0.5) * self.dlat, self.west + (c + 0.5) * self.dlon


def build_rasters(grid, cells, ext, draft_m, air_m, work):
    gt = os.path.join(work, "grid.tif")
    ft = os.path.join(work, "flags.tif")
    for f, bands in ((gt, 1), (ft, 5)):
        run(["gdal_create", "-q", "-of", "GTiff", "-outsize", str(grid.w), str(grid.h),
             "-bands", str(bands), "-ot", "Byte", "-burn", "0", "-a_srs", "EPSG:4326",
             "-a_ullr", repr(grid.west), repr(grid.north), repr(grid.east), repr(grid.south), f])

    d = draft_m - ROUNDING_M
    hz = draft_m + 1 * FT
    steps = [  # layer, where, burn, all_touched
        ("M_COVR", "CATCOV = 1", BLOCKED, False),
        ("DEPARE", f"DRVAL1 < 0 OR (DRVAL2 IS NOT NULL AND DRVAL2 <= {d})", SHALLOW, False),
        ("DEPARE", f"DRVAL1 IS NULL OR (DRVAL1 >= 0 AND DRVAL1 < {d} AND (DRVAL2 IS NULL OR DRVAL2 > {d}))", MARGINAL, False),
        ("DEPARE", f"DRVAL1 >= {d}", SAFE, False),
        ("DRGARE", f"DRVAL1 IS NULL OR DRVAL1 < {d}", MARGINAL, False),
        ("DRGARE", f"DRVAL1 >= {d}", SAFE, False),
        ("LNDARE", None, BLOCKED, False),
        ("RESARE", "RESTRN LIKE '%\"7\"%' OR RESTRN LIKE '%\"14\"%'", BLOCKED, False),
        ("OBSTRN", f"VALSOU IS NULL OR VALSOU < {hz}", HAZARD, True),
        ("WRECKS", f"VALSOU IS NULL OR VALSOU < {hz}", HAZARD, True),
        ("UWTROC", f"VALSOU IS NULL OR VALSOU < {hz}", HAZARD, True),
        ("SOUNDG", f"DEPTH < {d}", HAZARD, False),
        # Single piles are thin and visible; only piers/pylons drawn as areas block
        ("PILPNT", "OGR_GEOMETRY IN ('POLYGON','MULTIPOLYGON')", STRUCT, True),
        ("PYLONS", "OGR_GEOMETRY IN ('POLYGON','MULTIPOLYGON')", STRUCT, True),
    ]

    def inside(e):
        return e and e[0] < grid.east and e[2] > grid.west and e[1] < grid.north and e[3] > grid.south

    use = sorted((n for n in cells if inside(ext[n])), key=lambda n: (n[2], n))
    if not use:
        die("No NOAA ENC cells cover this area. Try another -s set (e.g. VA, DE, NJ).")
    log(f"Rasterizing {len(use)} chart cells at {grid.res:.0f} m ({grid.w} x {grid.h} cells)")

    def raster(src, layer, burn, where=None, at=False, bands=None, target=gt):
        cmd = ["gdal_rasterize", "-q", "-l", layer]
        for b in bands or []:
            cmd += ["-b", str(b)]
        for _ in (bands or [None]):
            cmd += ["-burn", str(burn)]
        if where:
            cmd += ["-where", where]
        if at:
            cmd += ["-at"]
        run(cmd + [src, target])

    # One union layer per chart layer per usage band (a few dozen gdal calls
    # instead of ~20 per cell). Lower detail bands go first; each band then
    # resets its own coverage so the more detailed data replaces it.
    air = air_m if air_m else 0
    for band in sorted({n[2] for n in use}):
        src, have = band_vrt(work, band, [n for n in use if n[2] == band], cells)
        for layer, where, burn, at in steps:
            if layer in have:
                raster(src, layer, burn, where, at)
        if "M_COVR" in have:
            raster(src, "M_COVR", 0, "CATCOV = 1", bands=range(1, 6), target=ft)
        for layer, fb in FLAG_LAYERS.items():
            if layer in have:
                where = " OR ".join(f"RESTRN LIKE '%\"{c}\"%'" for c in RESTRN) if layer == "RESARE" else None
                raster(src, layer, 1, where, True, [fb], ft)
        # Bridge decks without a charted clearance (approach spans) block passage;
        # spans with enough clearance, or that open, re-open it. Cables only with -a.
        if "BRIDGE" in have:
            raster(src, "BRIDGE", 1, f"VERCLR IS NULL OR VERCLR < {air}", True, [NO_PASS], ft)
            raster(src, "BRIDGE", 1, f"VERCLR >= {air} OR {OPENING_SQL}", True, [SPAN_OK], ft)
        if air_m and "CBLOHD" in have:
            raster(src, "CBLOHD", 1, f"VERCLR IS NULL OR VERCLR < {air_m}", True, [NO_PASS], ft)

    gb, fb = os.path.join(work, "grid.bin"), os.path.join(work, "flags.bin")
    run(["gdal_translate", "-q", "-of", "ENVI", gt, gb])
    run(["gdal_translate", "-q", "-of", "ENVI", "-co", "INTERLEAVE=BSQ", ft, fb])
    with open(gb, "rb") as f:
        nav = bytearray(f.read())
    with open(fb, "rb") as f:
        flags = f.read()
    n = grid.w * grid.h
    return nav, [flags[k * n:(k + 1) * n] for k in range(5)], use


# ---------------------------------------------------------------- costs

def buffer_cells(buf_m, res):
    """Hazard buffer in cells, rounded down so a coarse grid never inflates it."""
    return max(1, int(math.floor(buf_m / res + 0.25)))


def _grow(mask, offsets):
    """OR of mask shifted by each (dr, dc): a dilation with that structuring element."""
    h, w = mask.shape
    out = mask.copy()
    for dr, dc in offsets:
        if dr == 0 and dc == 0:
            continue
        out[max(dr, 0):h + min(dr, 0), max(dc, 0):w + min(dc, 0)] |= \
            mask[max(-dr, 0):h + min(-dr, 0), max(-dc, 0):w + min(-dc, 0)]
    return out


def prepare(grid, nav, flags, hazard_buf_m):
    """Usable cells, step costs and danger proximity, as whole-array numpy operations.
    Returns (ok bytearray, cost array('f'), prox bytearray) for the search code."""
    h, w = grid.h, grid.w
    navA = np.frombuffer(bytes(nav), np.uint8).reshape(h, w)
    nopass = np.frombuffer(flags[NO_PASS - 1], np.uint8).reshape(h, w) > 0
    spanok = np.frombuffer(flags[SPAN_OK - 1], np.uint8).reshape(h, w) > 0
    # Widen passable bridge spans by a cell so they cut through the whole
    # (all-touched, so often two cells thick) bridge deck
    spanok = _grow(spanok, [(dr, dc) for dr in (-1, 0, 1) for dc in (-1, 0, 1)])
    ok = ((navA == SAFE) | (navA == MARGINAL)) & ~(nopass & ~spanok)

    # Hazard buffer
    rad = buffer_cells(hazard_buf_m, grid.res)
    disc = [(dr, dc) for dr in range(-rad, rad + 1) for dc in range(-rad, rad + 1) if dr * dr + dc * dc <= rad * rad]
    ok &= ~_grow(navA == HAZARD, disc)

    # Distance (4-connected steps, capped at K) to the nearest unusable cell
    K = 5
    cross = [(-1, 0), (1, 0), (0, -1), (0, 1)]
    dist = np.full((h, w), K, np.uint8)
    reach = ~ok
    dist[reach] = 0
    for d in range(1, K):
        reach = _grow(reach, cross)
        dist[reach & (dist > d)] = d

    prox = np.where(ok, K - dist, 0).astype(np.uint8)           # 0 in open water, up to K-1 next to danger
    c = (1.0 + 0.6 * prox) * np.where(navA == MARGINAL, 4.0, 1.0)
    cost = np.where(ok, c, np.inf).astype(np.float32)
    cost_arr = array("f")
    cost_arr.frombytes(cost.tobytes())
    return bytearray(ok.astype(np.uint8).tobytes()), cost_arr, bytearray(prox.tobytes())


WALLS = {"shallow": "water shallower than your -d depth",
         "land": "land or uncharted water",
         "hazard": "the -b buffer around soundings shallower than -d, rocks, wrecks or obstructions",
         "struct": "piles or bridge piers",
         "nopass": "a bridge or overhead cable without enough charted clearance (-a)"}


def enclosure(grid, ok, nav, flags, start):
    """Water reachable from start: (cells, touches grid edge, {reason: (count, example cell)},
    hazard-buffer wall cells)."""
    w, h = grid.w, grid.h
    seen = bytearray(w * h)
    seen[start] = 1
    stack, n, edge, walls, hz = [start], 0, False, {}, []
    while stack:
        k = stack.pop()
        n += 1
        r, c = divmod(k, w)
        if r in (0, h - 1) or c in (0, w - 1):
            edge = True
        for j, valid in ((k - w, r > 0), (k + w, r < h - 1), (k - 1, c > 0), (k + 1, c < w - 1)):
            if not valid or seen[j]:
                continue
            seen[j] = 1
            if ok[j]:
                stack.append(j)
                continue
            v = nav[j]
            why = ("nopass" if v in (SAFE, MARGINAL) and flags[NO_PASS - 1][j] else
                   "hazard" if v in (SAFE, MARGINAL, HAZARD) else
                   "shallow" if v == SHALLOW else "struct" if v == STRUCT else "land")
            cnt, ex = walls.get(why, (0, j))
            walls[why] = (cnt + 1, ex)
            if why == "hazard":
                hz.append(j)
    return n, edge, walls, hz


def region(grid, ok, starts):
    """Usable water reachable from any of starts (4-connected)."""
    w, h = grid.w, grid.h
    seen = bytearray(w * h)
    stack = []
    for s0 in starts:
        if not seen[s0]:
            seen[s0] = 1
            stack.append(s0)
    while stack:
        k = stack.pop()
        r, c = divmod(k, w)
        for j, valid in ((k - w, r > 0), (k + w, r < h - 1), (k - 1, c > 0), (k + 1, c < w - 1)):
            if valid and not seen[j] and ok[j]:
                seen[j] = 1
                stack.append(j)
    return seen


def nearest_in(grid, mask, lat, lon, max_m):
    """Nearest cell of mask to the point within max_m: (cell, meters) or None."""
    r0, c0 = grid.cell(lat, lon)
    best = None
    for rad in range(0, int(max_m / grid.res) + 1):
        for dr in range(-rad, rad + 1):
            for dc in (range(-rad, rad + 1) if abs(dr) == rad else (-rad, rad)):
                r, c = r0 + dr, c0 + dc
                if 0 <= r < grid.h and 0 <= c < grid.w and mask[r * grid.w + c]:
                    dd = math.hypot(dr, dc)
                    if best is None or dd < best[0]:
                        best = (dd, r * grid.w + c)
        if best and best[0] <= rad:
            break
    if best and best[0] * grid.res <= max_m:
        return best[1], best[0] * grid.res
    return None


def snap(grid, ok, lat, lon, max_m=3000, quiet=False):
    r0, c0 = grid.cell(lat, lon)
    if ok[r0 * grid.w + c0]:
        return r0 * grid.w + c0, 0.0
    lim = int(max_m / grid.res)
    best = None
    for rad in range(1, lim + 1):
        for dr in range(-rad, rad + 1):
            for dc in (range(-rad, rad + 1) if abs(dr) == rad else (-rad, rad)):
                r, c = r0 + dr, c0 + dc
                if 0 <= r < grid.h and 0 <= c < grid.w and ok[r * grid.w + c]:
                    dd = math.hypot(dr, dc)
                    if best is None or dd < best[0]:
                        best = (dd, r * grid.w + c)
        if best and best[0] <= rad:
            return best[1], best[0] * grid.res
    if quiet:
        return None
    die(f"No navigable water within {max_m} m of {lat},{lon} for this depth")


def astar(grid, cost, start, goal):
    w, h, res = grid.w, grid.h, grid.res
    gr, gc = divmod(goal, w)
    INF = float("inf")
    g = array("d", [INF]) * (w * h)
    parent = array("i", [-1]) * (w * h)
    closed = bytearray(w * h)
    g[start] = 0.0
    heap = [(0.0, start)]
    D2 = math.sqrt(2)
    nbrs = ((-1, 0, 1.0), (1, 0, 1.0), (0, -1, 1.0), (0, 1, 1.0),
            (-1, -1, D2), (-1, 1, D2), (1, -1, D2), (1, 1, D2))
    while heap:
        _, i = heapq.heappop(heap)
        if closed[i]:
            continue
        if i == goal:
            break
        closed[i] = 1
        r, c = divmod(i, w)
        gi, ci = g[i], cost[i]
        for dr, dc, step in nbrs:
            rr, cc = r + dr, c + dc
            if not (0 <= rr < h and 0 <= cc < w):
                continue
            j = rr * w + cc
            cj = cost[j]
            if cj == INF or closed[j]:
                continue
            if dr and dc and (cost[r * w + cc] == INF or cost[rr * w + c] == INF):
                continue  # no corner cutting past land
            ng = gi + step * res * (ci + cj) * 0.5
            if ng < g[j]:
                g[j] = ng
                parent[j] = i
                heapq.heappush(heap, (ng + math.hypot(rr - gr, cc - gc) * res, j))
    if parent[goal] == -1 and goal != start:
        return None
    path = [goal]
    while path[-1] != start:
        path.append(parent[path[-1]])
    return path[::-1]


def line_cells(grid, a, b):
    w = grid.w
    r0, c0 = divmod(a, w)
    r1, c1 = divmod(b, w)
    n = max(abs(r1 - r0), abs(c1 - c0)) * 2 + 1
    seen, out = set(), []
    for k in range(n + 1):
        t = k / n
        i = int(round(r0 + (r1 - r0) * t)) * w + int(round(c0 + (c1 - c0) * t))
        if i not in seen:
            seen.add(i)
            out.append(i)
    return out


def simplify(grid, path, ok, nav, prox):
    """Drop path points while the straight line stays in water no shallower and
    no closer to danger than the cells the planned path itself used."""
    def fits(a, b, maxp, nmarg):
        m = 0
        for i in line_cells(grid, path[a], path[b]):
            if not ok[i] or prox[i] > maxp + 1:  # +1: grid staircase tolerance
                return False
            if nav[i] == MARGINAL:
                m += 1
                if m > nmarg + 1:
                    return False
        return True

    keep = [0]
    a = 0
    maxp = prox[path[0]]
    nmarg = 1 if nav[path[0]] == MARGINAL else 0
    j = 1
    while j < len(path):
        p2 = max(maxp, prox[path[j]])
        m2 = nmarg + (nav[path[j]] == MARGINAL)
        if j == a + 1 or fits(a, j, p2, m2):
            maxp, nmarg = p2, m2
            j += 1
        else:
            a = j - 1
            keep.append(a)
            maxp = max(prox[path[a]], prox[path[j]])
            nmarg = (nav[path[a]] == MARGINAL) + (nav[path[j]] == MARGINAL)
    if keep[-1] != len(path) - 1:
        keep.append(len(path) - 1)
    return [path[k] for k in keep]


# ---------------------------------------------------------------- reporting

def bearing(a, b):
    (la1, lo1), (la2, lo2) = a, b
    y = math.sin(math.radians(lo2 - lo1)) * math.cos(math.radians(la2))
    x = (math.cos(math.radians(la1)) * math.sin(math.radians(la2)) -
         math.sin(math.radians(la1)) * math.cos(math.radians(la2)) * math.cos(math.radians(lo2 - lo1)))
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def distance_m(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (*a, *b))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def feature_notes(grid, cells, ext, use, layer, lat, lon):
    e = grid.res * 1.5 / M_PER_DEG
    found = []
    for n in sorted(use, key=lambda n: (n[2], n), reverse=True):
        x = ext[n]
        if not (x[0] <= lon <= x[2] and x[1] <= lat <= x[3]):
            continue
        g, have = cell_gpkg(n, cells[n])
        if layer not in have:
            continue
        txt = run(["ogr2ogr", "-f", "GeoJSON", "/vsistdout/", g, layer, "-spat",
                   repr(lon - e), repr(lat - e), repr(lon + e), repr(lat + e)], capture=True)
        try:
            feats = json.loads(txt).get("features", [])
        except ValueError:
            feats = []
        for f in feats:
            p = f.get("properties", {})
            clr = p.get("VERCLR") or p.get("VERCCL")
            if layer == "RESARE":
                rules = [RESTRN[c] for c in (p.get("RESTRN") or []) if c in RESTRN]
                if not rules:
                    continue  # e.g. no-discharge zones, not a navigation limit
                cfr = re.search(r"\d+ CFR [\d.]+", p.get("INFORM") or "")
                name = p.get("OBJNAM") or "restricted area"
                found.append(f"{name}: {', '.join(rules)}" + (f" ({cfr.group(0)})" if cfr else ""))
            elif layer == "BRIDGE":
                kinds = [OPENING[c] for c in (p.get("CATBRG") or []) if c in OPENING]
                name = p.get("OBJNAM") or "bridge"
                if kinds:
                    closed = p.get("VERCCL") or p.get("VERCLR")
                    found.append(f"{name}: {kinds[0]} bridge, may need an opening"
                                 + (f" (closed clearance {closed / FT:.0f} ft)" if closed else ""))
                elif clr:
                    found.append(f"{name} span (clearance {clr / FT:.0f} ft)")
            else:
                found.append(f"{p.get('OBJNAM') or 'overhead cable'}"
                             + (f" (clearance {clr / FT:.0f} ft)" if clr else " (clearance not charted)"))
        if feats:
            break  # the most detailed cell with features here is authoritative
    return found


def shallowest_near(work, use, a, b, within_m=100):
    """Shallowest charted sounding (m) within within_m of the segment a-b, or None.
    Uses the most detailed usage band that has soundings there."""
    kx = M_PER_DEG * math.cos(math.radians((a[0] + b[0]) / 2))
    ky = M_PER_DEG
    e = within_m / ky
    ax, ay, bx, by = a[1] * kx, a[0] * ky, b[1] * kx, b[0] * ky
    dx, dy = bx - ax, by - ay
    L2 = dx * dx + dy * dy or 1.0
    for band in sorted({n[2] for n in use}, reverse=True):
        vrt = os.path.join(work, f"band{band}.vrt")
        if not os.path.isfile(vrt):
            continue
        txt = run(["ogr2ogr", "-f", "CSV", "/vsistdout/", vrt, "SOUNDG", "-select", "DEPTH",
                   "-lco", "GEOMETRY=AS_XY", "-spat", repr(min(a[1], b[1]) - e * ky / kx),
                   repr(min(a[0], b[0]) - e), repr(max(a[1], b[1]) + e * ky / kx),
                   repr(max(a[0], b[0]) + e)], capture=True)
        best = None
        for line in txt.splitlines()[1:]:
            try:
                x, y, dep = (float(v) for v in line.split(",")[:3])
            except ValueError:
                continue
            px, py = x * kx, y * ky
            t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L2))
            if math.hypot(px - ax - t * dx, py - ay - t * dy) <= within_m:
                best = dep if best is None else min(best, dep)
        if best is not None:
            return best
    return None


def report_legs(grid, wps_ll, wps_idx, nav, flags, cells, ext, use, work, seen_before=None):
    legs = []
    seen_before = seen_before if seen_before is not None else set()
    for k in range(len(wps_idx) - 1):
        a, b = wps_idx[k], wps_idx[k + 1]
        lc = line_cells(grid, a, b)
        marg = sum(1 for i in lc if nav[i] == MARGINAL)
        if marg < 2:
            marg = 0  # a single edge cell is rasterization noise, not a shoal crossing
        notes = []
        for layer, band in FLAG_LAYERS.items():
            # Sample where a flagged stretch starts, then every ~500 m along it
            hits, run_len, every = [], 0, max(1, int(500 / grid.res))
            for i in lc:
                if flags[band - 1][i]:
                    if run_len % every == 0:
                        hits.append(i)
                    run_len += 1
                else:
                    run_len = 0
            names = []
            for i in hits[:12]:
                for s in feature_notes(grid, cells, ext, use, layer, *grid.latlon(i)):
                    if s not in names:
                        names.append(s)
            seen = seen_before | {n for l in legs for n in l["notes"]}  # report each area once, where first entered
            notes += [s for s in names if s not in seen]
        d = distance_m(wps_ll[k], wps_ll[k + 1])
        low = shallowest_near(work, use, wps_ll[k], wps_ll[k + 1]) if marg else None
        legs.append({"dist": d, "brg": bearing(wps_ll[k], wps_ll[k + 1]),
                     "shoal_m": marg * d / max(len(lc), 1), "low": low, "notes": notes})
    return legs


def write_gpx(path, name, wps, draft_ft, legs):
    total = sum(l["dist"] for l in legs) / NM
    out = ['<?xml version="1.0" encoding="UTF-8"?>',
           '<gpx version="1.1" creator="noaa-route.py" xmlns="http://www.topografix.com/GPX/1/1">',
           "<rte>", f"<name>{escape(name)}</name>",
           f"<desc>{escape(f'{total:.1f} nm, planned for {draft_ft:g} ft depth at MLLW from NOAA ENC. Not for navigation.')}</desc>"]
    for k, (lat, lon) in enumerate(wps):
        label = "Start" if k == 0 else "End" if k == len(wps) - 1 else f"WP{k:02d}"
        desc = ""
        if k < len(legs):
            l = legs[k]
            desc = f"Next: {l['brg']:03.0f} T, {l['dist'] / NM:.2f} nm"
            if l["shoal_m"] > 0:
                desc += f", {l['shoal_m']:.0f} m in 0-6 ft charted water"
                if l["low"] is not None:
                    desc += f" (shallowest sounding nearby {l['low'] / FT:.1f} ft)"
        out.append(f'<rtept lat="{lat:.6f}" lon="{lon:.6f}"><name>{label}</name>'
                   + (f"<desc>{escape(desc)}</desc>" if desc else "") + "</rtept>")
    out += ["</rte>", "</gpx>"]
    with open(path, "w") as f:
        f.write("\n".join(out) + "\n")


def open_folder(path):
    """Show a shared-storage folder in Android's Files app. Returns True if launched.

    GPX files sent from Termux (termux-open, view or send) crash OsmAnd, but the
    same file opened from the Files app imports fine.
    """
    base = "/storage/emulated/0/"
    real = os.path.realpath(path)
    if not real.startswith(base) or not shutil.which("am"):
        return False
    uri = ("content://com.android.externalstorage.documents/document/primary%3A"
           + quote(real[len(base):], safe=""))
    p = subprocess.run(["am", "start", "-a", "android.intent.action.VIEW",
                        "-t", "vnd.android.document/directory", "-d", uri],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return p.returncode == 0


# ---------------------------------------------------------------- planning
# Same structure as the Android app's Planner.kt: each leg is routed on one
# full-detail window, or for long legs, sketched on a coarse overview and then
# routed at 20 m between open-water guide points about every 18 km.

CUT_OFF_M = 250.0     # how far an end may move to escape a pocket walled in by land/structures
GUIDE_SPACING_M = 18000.0
PIECE_RES = 20.0


class Ctx:
    def __init__(self, a, cells, ext, work):
        self.a, self.cells, self.ext, self.work = a, cells, ext, work
        self.draft_m = a.draft * FT
        self.air_m = a.air * FT if a.air else None
        self.messages, self.moved, self.seen = [], set(), set()


def auto_grid(pts, widen, res=None, max_cells=5_000_000):
    if res:
        return Grid(pts, res, widen)
    for res in (10, 15, 20, 30, 45, 60, 90, 120):
        g = Grid(pts, res, widen)
        if g.w * g.h <= max_cells:
            return g
    return g


def build(grid, ctx, draft_m=None):
    """Rasterize and prepare a grid; returns a dict with everything planning needs."""
    if grid.w * grid.h > 8_000_000:
        die(f"Area too large at {grid.res:.0f} m ({grid.w} x {grid.h}). Add -v points closer together.")
    nav, flags, use = build_rasters(grid, ctx.cells, ctx.ext, draft_m if draft_m is not None else ctx.draft_m,
                                    ctx.air_m, ctx.work)
    ok, cost, prox = prepare(grid, nav, flags, ctx.a.buf)
    return {"nav": nav, "flags": flags, "use": use, "ok": ok, "cost": cost, "prox": prox,
            "rad": buffer_cells(ctx.a.buf, grid.res)}


def label(n, pt):
    return f"Point {n} ({pt[0]:.5f},{pt[1]:.5f})" if n else f"A guide point the planner placed at {pt[0]:.5f},{pt[1]:.5f}"


def hazard_sources(grid, G, wall_cells, work):
    """Shallowest sounding and rock/wreck/obstruction behind the hazard-buffer walls:
    ((depth m, (lat, lon)) or None, (valsou m or None for unknown, (lat, lon)) or None)."""
    w, rad = grid.w, G["rad"]
    hz = set()
    for j in wall_cells:
        r0, c0 = divmod(j, w)
        for dr in range(-rad, rad + 1):
            for dc in range(-rad, rad + 1):
                rr, cc = r0 + dr, c0 + dc
                if dr * dr + dc * dc <= rad * rad and 0 <= rr < grid.h and 0 <= cc < w and G["nav"][rr * w + cc] == HAZARD:
                    hz.add(rr * w + cc)
    if not hz:
        return None, None
    lls = [grid.latlon(i) for i in hz]
    e = grid.res / M_PER_DEG
    spat = [repr(min(x[1] for x in lls) - 2 * e), repr(min(x[0] for x in lls) - e),
            repr(max(x[1] for x in lls) + 2 * e), repr(max(x[0] for x in lls) + e)]
    snd = obs = None
    for band in sorted({n[2] for n in G["use"]}, reverse=True):
        vrt = os.path.join(work, f"band{band}.vrt")
        if not os.path.isfile(vrt):
            continue
        for layer, field in (("SOUNDG", "DEPTH"), ("OBSTRN", "VALSOU"), ("WRECKS", "VALSOU"), ("UWTROC", "VALSOU")):
            p = subprocess.run(["ogr2ogr", "-f", "GeoJSON", "/vsistdout/", vrt, layer, "-spat", *spat],
                               env=dict(os.environ, OGR_S57_OPTIONS=S57_OPTS), capture_output=True, text=True)
            try:
                feats = json.loads(p.stdout).get("features", []) if p.returncode == 0 else []
            except ValueError:
                feats = []
            for f in feats:
                g = f.get("geometry") or {}
                c = g.get("coordinates")
                while isinstance(c, list) and c and isinstance(c[0], list):
                    c = c[0]
                if not c:
                    continue
                lat, lon = c[1], c[0]
                rr, cc = grid.cell(lat, lon)
                if rr * w + cc not in hz:
                    continue
                v = (f.get("properties") or {}).get(field)
                if layer == "SOUNDG":
                    if v is not None and (snd is None or v < snd[0]):
                        snd = (v, (lat, lon))
                elif obs is None or (obs[0] is not None and (v is None or v < obs[0])):
                    obs = (v, (lat, lon))
        if snd or obs:
            break  # most detailed band with something here wins
    return snd, obs


def deepest_escape(pt, ctx, like):
    """Deepest -d (0.5 ft steps, below the one that failed) at which the water around
    pt leads out of a 4 km box on the same cell lattice as grid `like`, or None."""
    def escapes(ft):
        g = Grid.around(pt[0], pt[1], 4000, like.res, align=like)
        G = build(g, ctx, ft * FT)
        hit = snap(g, G["ok"], *pt, quiet=True)
        return hit is not None and enclosure(g, G["ok"], G["nav"], G["flags"], hit[0])[1]
    log("Looking for the deepest setting that gets out from there")
    lo, hi = 1, int(math.ceil(ctx.a.draft * 2)) - 1   # half-feet
    if hi < lo or not escapes(lo / 2):
        return None
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if escapes(mid / 2):
            lo = mid
        else:
            hi = mid - 1
    return lo / 2


def no_route(ctx, grid, G, who, pt, enc):
    n, _, walls, hz = enc
    a = ctx.a
    lines = [f"No route found for {a.draft:g} ft depth" + (f" and {a.air:g} ft air draft." if a.air else "."),
             f"{who} is in a pocket of about {n * grid.res * grid.res / 1e4:.1f} ha of usable water, closed off by:"]
    for why, (cnt, ex) in sorted(walls.items(), key=lambda kv: -kv[1][0]):
        la, lo = grid.latlon(ex)
        edge = f"{cnt * grid.res:.0f} m of edge"
        if why == "hazard":
            snd, obs = hazard_sources(grid, G, hz, ctx.work)
            if snd:
                lines.append(f"  - soundings as shallow as {snd[0] / FT:.1f} ft (near {snd[1][0]:.5f},{snd[1][1]:.5f}), "
                             f"with their -b safety buffer ({edge})")
            if obs:
                what = ("a rock, wreck or obstruction of unknown depth" if obs[0] is None
                        else f"rocks, wrecks or obstructions as shallow as {obs[0] / FT:.1f} ft")
                lines.append(f"  - {what} (near {obs[1][0]:.5f},{obs[1][1]:.5f}), with its -b safety buffer")
            if not snd and not obs:
                lines.append(f"  - {WALLS[why]} ({edge}, e.g. near {la:.5f},{lo:.5f})")
        else:
            lines.append(f"  - {WALLS[why]} ({edge}, e.g. near {la:.5f},{lo:.5f})")
    if any(k in walls for k in ("hazard", "shallow")):
        best = deepest_escape(pt, ctx, grid)
        lines.append(f"With -d {best:g} or less the water here leads out of this area; try that "
                     "(the rest of the trip may still have shallower spots)." if best is not None
                     else "Even -d 0.5 does not lead out from here.")
    lines.append("Move the point into open water, or relax -b, -d or -a as appropriate.")
    die("\n".join(lines))


def plan_piece(p, q, np_, nq, ctx, res=None):
    """Route p to q on one full-detail window: (waypoints, legs)."""
    widths = (1.0, 2.5, 5.0)
    for attempt, widen in enumerate(widths):
        grid = auto_grid([p, q], widen, res)
        G = build(grid, ctx)
        ok = G["ok"]
        sp, mp = snap(grid, ok, *p)
        sq, mq = snap(grid, ok, *q)
        for n, m in ((np_, mp), (nq, mq)):
            if n and m > grid.res and n not in ctx.moved:
                ctx.moved.add(n)
                ctx.messages.append(f"Point {n} moved {m:.0f} m to the nearest usable water")
                log(ctx.messages[-1])
        path = astar(grid, G["cost"], sp, sq)
        if path is None:
            # An end in a pocket walled in only by land or structures (a marina basin behind
            # a pier too thin for the grid): move it to the nearest connected water.
            ends = [enclosure(grid, ok, G["nav"], G["flags"], i) for i in (sp, sq)]
            for e in sorted((0, 1), key=lambda e: ends[e][0]):
                if ends[e][1] or any(k not in ("land", "struct") for k in ends[e][2]):
                    continue
                other = region(grid, ok, [sq if e == 0 else sp])
                hit = nearest_in(grid, other, *(p if e == 0 else q), CUT_OFF_M)
                if not hit:
                    continue
                retry = astar(grid, G["cost"], hit[0], sq) if e == 0 else astar(grid, G["cost"], sp, hit[0])
                if retry:
                    path = retry
                    n = np_ if e == 0 else nq
                    walls = " and ".join(WALLS[k] for k in sorted(ends[e][2], key=lambda k: -ends[e][2][k][0]))
                    msg = (f"Point {n}" if n else "A guide point") + f": the water there is cut off by {walls}; the route " + \
                          ("starts" if e == 0 else "ends") + f" {hit[1]:.0f} m away at the nearest connected water."
                    ctx.messages.append(msg)
                    log(msg)
                    if n:
                        ctx.moved.add(n)
                    break
        if path:
            idx = simplify(grid, path, ok, G["nav"], G["prox"])
            wps = [grid.latlon(i) for i in idx]
            wps[0], wps[-1] = p, q
            legs = report_legs(grid, wps, idx, G["nav"], G["flags"], ctx.cells, ctx.ext, G["use"], ctx.work, ctx.seen)
            for l in legs:
                ctx.seen.update(l["notes"])
            return wps, legs, grid.res
        ends = [enclosure(grid, ok, G["nav"], G["flags"], i) for i in (sp, sq)]
        closed = [e for e in (0, 1) if not ends[e][1]]
        if closed or attempt == len(widths) - 1:
            e = closed[0] if closed else min((0, 1), key=lambda e: ends[e][0])
            no_route(ctx, grid, G, label(np_ if e == 0 else nq, p if e == 0 else q), p if e == 0 else q, ends[e])
        log("No route inside this area, widening the search")


def guide_points(p, q, np_, nq, ctx):
    """Long legs: open-water guide points about every GUIDE_SPACING_M along a coarse
    overview route, with each end's full-detail reachable water stamped in first."""
    widths = (1.0, 2.5, 5.0)
    for attempt, widen in enumerate(widths):
        grid = auto_grid([p, q], widen)
        log(f"Long trip: overview at {grid.res:.0f} m")
        G = build(grid, ctx)
        ok, cost = G["ok"], G["cost"]
        ends = []
        for pt in (p, q):
            fg = Grid.around(pt[0], pt[1], 4000, PIECE_RES)
            F = build(fg, ctx)
            s0, _ = snap(fg, F["ok"], *pt)
            enc = enclosure(fg, F["ok"], F["nav"], F["flags"], s0)
            if not enc[1] and all(k in ("land", "struct") for k in enc[2]):
                border = [i for i in range(fg.w * fg.h) if F["ok"][i] and
                          (i < fg.w or i >= fg.w * (fg.h - 1) or i % fg.w in (0, fg.w - 1))]
                hit = nearest_in(fg, region(fg, F["ok"], border), *pt, CUT_OFF_M)
                if hit:
                    s0 = hit[0]
            reach = np.flatnonzero(np.frombuffer(bytes(region(fg, F["ok"], [s0])), np.uint8))
            lat = fg.north - (reach // fg.w + 0.5) * fg.dlat
            lon = fg.west + (reach % fg.w + 0.5) * fg.dlon
            rr = np.clip(((grid.north - lat) / grid.dlat).astype(np.int64), 0, grid.h - 1)
            cc = np.clip(((lon - grid.west) / grid.dlon).astype(np.int64), 0, grid.w - 1)
            ci = rr * grid.w + cc
            okv = np.frombuffer(ok, np.uint8)
            costv = np.frombuffer(cost, np.float32)
            okv[ci] = 1
            costv[ci[np.isinf(costv[ci])]] = 2.0
            r0, c0 = grid.cell(*fg.latlon(s0))
            ends.append(r0 * grid.w + c0)
        path = astar(grid, cost, ends[0], ends[1])
        if path:
            cum = [0.0]
            for k in range(1, len(path)):
                cum.append(cum[-1] + distance_m(grid.latlon(path[k - 1]), grid.latlon(path[k])))
            total = cum[-1]
            n = max(1, int(math.ceil(total / GUIDE_SPACING_M)))
            out, frm = [], 0
            reach_cells = max(1, int(2000 / grid.res))
            for k in range(1, n):
                target = total * k / n
                j = frm
                while j < len(path) - 1 and cum[j] < target:
                    j += 1
                best = j
                for d in range(reach_cells + 1):
                    cand = [x for x in (j + d, j - d) if frm < x < len(path) - 1 and
                            G["nav"][path[x]] == SAFE and G["prox"][path[x]] == 0]
                    if cand:
                        best = cand[0]
                        break
                if cum[best] < 4000 or total - cum[best] < 4000:
                    continue
                out.append(grid.latlon(path[best]))
                frm = best
            return out
        encs = [enclosure(grid, ok, G["nav"], G["flags"], i) for i in ends]
        closed = [e for e in (0, 1) if not encs[e][1]]
        if closed or attempt == len(widths) - 1:
            e = closed[0] if closed else min((0, 1), key=lambda e: encs[e][0])
            pt = p if e == 0 else q
            no_route(ctx, grid, G, label(np_ if e == 0 else nq, pt) + f" (overview at {grid.res:.0f} m)", pt, encs[e])
        log("No route inside this area, widening the search")


def plan(pts, ctx):
    """Waypoints and legs for the whole trip through pts."""
    wps, legs = [], []
    for k in range(len(pts) - 1):
        p, q = pts[k], pts[k + 1]
        long_leg = not ctx.a.res and auto_grid([p, q], 1.0).res > PIECE_RES
        chain = [p] + (guide_points(p, q, k + 1, k + 2, ctx) if long_leg else []) + [q]
        for j in range(len(chain) - 1):
            log(f"Routing leg {k + 1} of {len(pts) - 1}" + (f", stretch {j + 1} of {len(chain) - 1}" if len(chain) > 2 else ""))
            w, l, _ = plan_piece(chain[j], chain[j + 1], k + 1 if j == 0 else None,
                                 k + 2 if j == len(chain) - 2 else None, ctx,
                                 PIECE_RES if long_leg else ctx.a.res)
            wps += w if not wps else w[1:]
            legs += l
    return wps, legs


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("-f", dest="start")
    ap.add_argument("-l", dest="here", action="store_true")
    ap.add_argument("-t", dest="end")
    ap.add_argument("-v", dest="via", action="append", default=[])
    ap.add_argument("-d", dest="draft", type=float)
    ap.add_argument("-a", dest="air", type=float)
    ap.add_argument("-n", dest="name", default="route")
    ap.add_argument("-o", dest="out", default=os.path.join(HOME, "storage/downloads/noaa-charts/routes"))
    ap.add_argument("-s", dest="states", default="MD")
    ap.add_argument("-r", dest="res", type=float)
    ap.add_argument("-b", dest="buf", type=float, default=60.0)
    ap.add_argument("-u", dest="update", action="store_true")
    ap.add_argument("-O", dest="open", action="store_true")
    ap.add_argument("-h", dest="help", action="store_true")
    argv = sys.argv[1:]
    if not argv and sys.stdin.isatty():
        try:
            argv = interview()
        except KeyboardInterrupt:
            print()
            sys.exit(1)
    a = ap.parse_args(argv)
    if a.start and a.here:
        die("Use either -f or -l, not both")
    if a.help or not ((a.start or a.here) and a.end and a.draft):
        print(__doc__.strip())
        sys.exit(0 if a.help else 1)
    if not 0 < a.draft < 40:
        die("Depth (-d) must be in feet, between 0 and 40")
    if not shutil.which("gdal_rasterize"):
        die("GDAL not found. Run: pkg install gdal")

    rest = [parse_point(v) for v in a.via] + [parse_point(a.end)]
    pts = [current_location() if a.here else parse_point(a.start)] + rest
    states = [s.strip().upper() for s in a.states.split(",") if s.strip()]
    draft_m = a.draft * FT
    if a.out.startswith(os.path.join(HOME, "storage")) and not os.path.islink(os.path.join(HOME, "storage/downloads")):
        die("Storage not set up. Run termux-setup-storage, accept the prompt, then retry.")
    os.makedirs(a.out, exist_ok=True)

    for st in states:
        update_enc(st, a.update)
    cells = find_cells(states)
    ext = cell_extents(cells)

    work = os.path.join(ENC_DIR, "work")
    os.makedirs(work, exist_ok=True)

    ctx = Ctx(a, cells, ext, work)
    wps_ll, legs = plan(pts, ctx)
    gpx = os.path.join(a.out, f"{a.name}.gpx")
    write_gpx(gpx, a.name, wps_ll, a.draft, legs)

    total = sum(l["dist"] for l in legs)
    print()
    air = f", air draft {a.air:g} ft" if a.air else ", air draft not checked"
    print(f"Route {a.name}: {total / NM:.1f} nm, {len(wps_ll)} points, depth {a.draft:g} ft (MLLW){air}")
    for m in ctx.messages:
        print(m)
    print(f"{'Leg':>3}  {'Course':>6}  {'Dist nm':>7}  Notes")
    for k, l in enumerate(legs):
        notes = []
        if l["shoal_m"] > 0:
            low = f", shallowest sounding within 100 m {l['low'] / FT:.1f} ft" if l["low"] is not None else ", no soundings within 100 m"
            notes.append(f"{l['shoal_m']:.0f} m in 0-6 ft charted water{low}")
        notes += l["notes"]
        print(f"{k + 1:>3}  {l['brg']:>5.0f}T  {l['dist'] / NM:>7.2f}  {'; '.join(notes)}")
    print()
    log(f"GPX written: {gpx}")
    shown = os.path.realpath(gpx).replace("/storage/emulated/0/", "")
    if a.open and open_folder(a.out):
        log(f"Opened {os.path.dirname(shown)} in Files: tap {os.path.basename(gpx)} and pick OsmAnd+")
    else:
        log(f"Import: in a file manager open {shown} and pick OsmAnd+")
    log("Not for navigation. Verify every leg against a current chart.")


if __name__ == "__main__":
    main()
