#!/usr/bin/env python3
"""build_route_pack.py
Build a draft-independent "route pack" for the NOAA Route Android app from
NOAA ENC (S-57) data. Runs on GitHub Actions (Ubuntu with python3-gdal and
python3-numpy); see .github/workflows/route-pack.yml.

  python3 tools/build_route_pack.py                 (all areas in tools/areas.json)
  python3 tools/build_route_pack.py -a ncds_04      (one area)

Writes packs/<area>.bin per area plus packs/catalog.json.

Areas are NOAA Chart Display Service (NCDS) regions (`"ncds": "04"`, with the
region's rectangle as `ncds_bbox`): every Active cell in NOAA's ENC product
catalog whose coverage meets the pack's grid is downloaded on its own, so a
region never stops at a state line. The pack is clipped to where approach and
harbor charts (band 4+) exist in the region, plus 0.1 degree, instead of the
whole NCDS rectangle (region 04 reaches 64.95 W, far offshore). Legacy areas
built from state sets (`"states"`) still work.

Seams: a pack covers its clipped region rounded out to whole tiles, built from
every cell meeting that larger box, so a tile two neighboring packs share holds
the same data in both (the app takes a shared tile from whichever pack it reads
first).

All areas share one global grid so the app can join neighboring areas: rows of
GRID_DLAT degrees from 90 N, columns of GRID_DLON degrees from 180 W (20 m at
38 N; about 18 m wide at 45 N and 23 m at 25 N), cut into 256 x 256 tiles
numbered from that origin. Each area pack holds the tiles covering its bbox. Each tile holds 6 byte planes; the app applies the boat's
safe operating depth and air draft at planning time. Lower detail chart bands
are burned first and every band resets its own coverage, so the most detailed
chart wins (same rule as noaa-route.py).

Planes (0 always means "nothing here"):
  0 DEPTH   index into header depth_table: [drval1_m|null, drval2_m|null, kind]
            kind 0 = DEPARE, 1 = DRGARE. 0 = land, uncharted or no data.
  1 HAZARD  shallowest OBSTRN/WRECKS/UWTROC: 1 + VALSOU in dm (0-253); unknown
            depth is stored as 1 (0.0 m) so it is a hazard for any boat.
            255 = shoreline construction (SLCONS: pier, seawall, jetty) and no
            hazard: the app routes across it only at a high cost. Apps from
            before this value read it as a 25.4 m deep obstruction (no effect).
  2 SOUND   shallowest sounding: 1 + DEPTH in dm (0-253), negative -> 1.
  3 FLAGS   bit 0 STRUCT   pier/pylon drawn as an area (blocks, no buffer)
            bit 1 PROHIB   restricted area: entry prohibited (an area to be avoided
                           is only reported: IMO routing measures for large ships)
            bit 2 DECK     bridge part without vertical clearance
            bit 3 SPAN     bridge part with vertical clearance (plane 4)
            bit 4 OPENING  opening bridge (swing, bascule, lift, draw...)
            bit 5 CABLE    overhead cable (clearance in plane 5)
            bit 6 RESNOTE  restricted area with a navigation rule (reported)
            bit 7 BRIDGE   any bridge part (reported)
  4 SPANCLR shortest span clearance here, 0.5 m units (1-255)
  5 CABLECLR shortest cable clearance, 0.5 m units (1-255), 0 unknown. Where FLAGS
            has no CABLE bit, 255 (SNAG_MARK) marks a cell whose only hazard is a
            snag or stump (OBSTRN CATOBS 1, always submerged, unknown depth): its
            HAZARD value stays 1, so apps from before this mark keep it a wall,
            while newer apps let a route through it at a high cost over water
            charted deep enough (piles at the edge of a narrow dredged channel).
            254 (GATE_MARK), likewise without the CABLE bit: a lateral mark (buoy
            or daybeacon) or its wrong-side line, which newer apps cross only at a
            high cost (older apps ignore it).

File layout:
  "NRPK2\\n", u32 header length, header JSON (UTF-8), then for each tile in
  row-major order u64 offset + u32 length (length 0 = empty tile), then the
  zlib-compressed tiles (6 planes of 256*256 bytes each, plane-major).
"""

import argparse
import concurrent.futures
import functools
import hashlib
import json
import math
import os
import re
import shutil
import struct
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
import zlib

import numpy as np
from osgeo import gdal, ogr, osr

gdal.UseExceptions()
ogr.UseExceptions()
os.environ["OGR_S57_OPTIONS"] = "UPDATES=APPLY,SPLIT_MULTIPOINT=ON,ADD_SOUNDG_DEPTH=ON"
gdal.SetConfigOption("OGR_S57_OPTIONS", os.environ["OGR_S57_OPTIONS"])

ENC_URL = "https://charts.noaa.gov/ENCs/{}_ENCs.zip"
CATALOG_URL = "https://charts.noaa.gov/ENCs/ENCProdCat.xml"
REGION_MARGIN = 0.1  # degrees kept around a region's band 4+ charts
LAYERS = ["M_COVR", "DEPARE", "DRGARE", "LNDARE", "OBSTRN", "WRECKS", "UWTROC",
          "SOUNDG", "PILPNT", "PYLONS", "SLCONS", "RESARE", "BRIDGE", "CBLOHD",
          "BOYLAT", "BCNLAT", "FAIRWY"]
M_PER_DEG = 111320.0
TILE = 256
GRID_DLAT = 20.0 / M_PER_DEG
GRID_DLON = 20.0 / (M_PER_DEG * math.cos(math.radians(38.0)))
NPLANES = 6
DEPTH, HAZARD, SOUND, FLAGS, SPANCLR, CABLECLR = range(NPLANES)
STRUCT, PROHIB, DECK, SPAN, OPENING, CABLE, RESNOTE, BRIDGE = (1 << b for b in range(8))
HZ_PIER = 255  # HAZARD plane: shoreline construction, no hazard
SNAG_MARK = 255  # CABLECLR plane without the CABLE flag: the cell's hazard is only a snag
GATE_MARK = 254  # CABLECLR plane without the CABLE flag: a channel marker or its wrong side
RESTRN = {"1": "anchoring prohibited", "2": "anchoring restricted", "3": "fishing prohibited",
          "4": "fishing restricted", "7": "entry prohibited", "8": "entry restricted",
          "13": "no wake", "14": "area to be avoided"}
OPENING_KINDS = {"2": "opening", "3": "swing", "4": "lift", "5": "bascule", "7": "draw", "8": "transporter"}


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def listval(v):
    """S-57 list attributes come back as lists, JSON strings or comma strings."""
    if v is None:
        return []
    if isinstance(v, (list, tuple)):
        return [str(x) for x in v]
    s = str(v).strip()
    if s.startswith("["):
        try:
            return [str(x) for x in json.loads(s)]
        except ValueError:
            pass
    return [x for x in re.split(r"[,\s]+", s.strip("()")) if x]


def fnum(f, name):
    i = f.GetFieldIndex(name)
    if i < 0 or not f.IsFieldSet(i) or f.IsFieldNull(i):
        return None
    return f.GetFieldAsDouble(i)


def fstr(f, name):
    i = f.GetFieldIndex(name)
    if i < 0 or not f.IsFieldSet(i) or f.IsFieldNull(i):
        return None
    return f.GetField(i)


# ---------------------------------------------------------------- ENC data

def fetch(states, work):
    """Download and unpack NOAA ENC state sets (once per run); cells deduplicated."""
    cells = {}
    for st in states:
        z = os.path.join(work, f"{st}_ENCs.zip")
        d = os.path.join(work, st)
        if not os.path.isdir(d):
            log(f"Downloading {ENC_URL.format(st)}")
            subprocess.run(["curl", "-fsSL", "--retry", "5", "-o", z, ENC_URL.format(st)], check=True)
            with zipfile.ZipFile(z) as zf:
                zf.extractall(d)
        for root, _, files in os.walk(d):
            for f in files:
                if f.endswith(".000") and f[:-4] not in cells:
                    cells[f[:-4]] = os.path.join(root, f)
    log(f"{len(cells)} chart cells from {', '.join(states)}")
    return cells


def enc_catalog(work):
    """Active cells from NOAA's ENC product catalog: name, zip URL, usage band,
    coverage bbox (w, s, e, n) and zip size."""
    path = os.path.join(work, "ENCProdCat.xml")
    if not os.path.isfile(path):
        log(f"Downloading {CATALOG_URL}")
        subprocess.run(["curl", "-fsSL", "--retry", "5", "-o", path, CATALOG_URL], check=True)
    cells = []
    for c in ET.parse(path).getroot().iter("cell"):
        if c.findtext("status") != "Active":
            continue
        # The catalog writes the western Pacific west of 180 W (Guam at -215.4, Attu at
        # -186.8); the charts themselves and the NCDS rectangles use east longitudes
        v = [(lon + 360 if lon < -180 else lon, float(x.findtext("lat")))
             for x in c.iter("vertex") for lon in [float(x.findtext("long"))]]
        if not v:
            continue
        name = c.findtext("name")
        cells.append({"name": name, "url": c.findtext("zipfile_location"), "band": int(name[2]),
                      "bbox": (min(a for a, _ in v), min(b for _, b in v), max(a for a, _ in v), max(b for _, b in v)),
                      "size": int(c.findtext("zipfile_size") or 0)})
    log(f"ENC catalog: {len(cells)} active cells")
    return cells


def meets(b, w, s, e, n):
    return b[0] < e and b[2] > w and b[1] < n and b[3] > s


def region_bbox(area, catalog):
    """The part of an NCDS region where approach and harbor charts exist, plus REGION_MARGIN."""
    w, s, e, n = area["ncds_bbox"]
    detail = [c["bbox"] for c in catalog if c["band"] >= 4 and meets(c["bbox"], w, s, e, n)]
    if not detail:
        raise SystemExit(f"{area['id']}: no band 4+ charts in the region")
    m = REGION_MARGIN
    return [round(max(w, min(b[0] for b in detail) - m), 2), round(max(s, min(b[1] for b in detail) - m), 2),
            round(min(e, max(b[2] for b in detail) + m), 2), round(min(n, max(b[3] for b in detail) + m), 2)]


def fetch_cells(metas, work):
    """Download and unpack single cells (base edition + updates); {name: path to .000}."""
    d = os.path.join(work, "cells")
    os.makedirs(d, exist_ok=True)

    def one(m):
        root = os.path.join(d, m["name"])
        base = os.path.join(root, "ENC_ROOT", m["name"], m["name"] + ".000")
        if not os.path.isfile(base):  # fetched for an earlier area in this run
            z = root + ".zip"
            for attempt in range(5):
                try:
                    req = urllib.request.Request(m["url"], headers={"User-Agent": "noaa-osmand-charts route pack builder"})
                    with urllib.request.urlopen(req, timeout=120) as r, open(z, "wb") as f:
                        shutil.copyfileobj(r, f)
                    with zipfile.ZipFile(z) as zf:
                        zf.extractall(root)
                    os.remove(z)
                    break
                except Exception as ex:  # network hiccup: back off and retry
                    if attempt == 4:
                        raise SystemExit(f"{m['name']}: download failed: {ex}")
                    time.sleep(5 * (attempt + 1))
        return m["name"], base

    with concurrent.futures.ThreadPoolExecutor(8) as ex:
        cells = dict(ex.map(one, metas))
    log(f"{len(cells)} chart cells ({sum(m['size'] for m in metas) / 1e6:.0f} MB of zips)")
    return cells


@functools.lru_cache(maxsize=None)
def check_units(name, path):
    """Depths must be meters; returns the sounding datum code (12 = MLLW)."""
    ds = ogr.Open(path)
    lyr = ds.GetLayerByName("DSID")
    f = lyr.GetNextFeature() if lyr is not None else None
    if f is None:
        raise SystemExit(f"{name}: no DSID record")
    duni = f.GetField("DSPM_DUNI") if f.GetFieldIndex("DSPM_DUNI") >= 0 else None
    if duni not in (None, 1):
        raise SystemExit(f"{name}: depth units code {duni}, expected 1 (meters)")
    return f.GetField("DSPM_SDAT") if f.GetFieldIndex("DSPM_SDAT") >= 0 else None


def to_gpkg(name, path, work):
    """One GeoPackage per cell with NOAA's update files applied."""
    g = os.path.join(work, "gpkg", name + ".gpkg")
    os.makedirs(os.path.dirname(g), exist_ok=True)
    if os.path.isfile(g):  # converted for an earlier area in this run
        ds = ogr.Open(g)
        return g, [ds.GetLayer(i).GetName() for i in range(ds.GetLayerCount())]
    src = ogr.Open(path)
    have = [l for l in LAYERS if src.GetLayerByName(l) is not None]
    src = None
    gdal.VectorTranslate(g, path, format="GPKG", layers=have, geometryType="GEOMETRY",
                         skipFailures=True)
    return g, have


def coverage_extent(g):
    ds = ogr.Open(g)
    lyr = ds.GetLayerByName("M_COVR")
    if lyr is None:
        return None
    lyr.SetAttributeFilter("CATCOV = 1")
    ext = None
    for f in lyr:
        e = f.GetGeometryRef().GetEnvelope()  # minx, maxx, miny, maxy
        ext = e if ext is None else (min(ext[0], e[0]), max(ext[1], e[1]), min(ext[2], e[2]), max(ext[3], e[3]))
    return ext


# ---------------------------------------------------------------- rasterizing

class Grid:
    """The part of the global grid covering [west, south, east, north], tile aligned."""

    def __init__(self, west, south, east, north):
        self.res = 20.0
        self.dlat, self.dlon = GRID_DLAT, GRID_DLON
        self.tx0 = int(math.floor((west + 180) / (GRID_DLON * TILE)))
        self.ty0 = int(math.floor((90 - north) / (GRID_DLAT * TILE)))
        tx1 = int(math.ceil((east + 180) / (GRID_DLON * TILE)))
        ty1 = int(math.ceil((90 - south) / (GRID_DLAT * TILE)))
        self.w = (tx1 - self.tx0) * TILE
        self.h = (ty1 - self.ty0) * TILE
        self.west = -180 + self.tx0 * TILE * GRID_DLON
        self.north = 90 - self.ty0 * TILE * GRID_DLAT
        self.srs = osr.SpatialReference()
        self.srs.ImportFromEPSG(4326)
        self.srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)

    @classmethod
    def tiles(cls, tx0, ty0, tx1, ty1):
        """The grid covering tiles tx0..tx1-1, ty0..ty1-1 of the global grid."""
        e = 1e-9  # keep the edges inside their tiles
        return cls(-180 + tx0 * TILE * GRID_DLON + e, 90 - ty1 * TILE * GRID_DLAT + e,
                   -180 + tx1 * TILE * GRID_DLON - e, 90 - ty0 * TILE * GRID_DLAT - e)

    def mem(self):
        ds = gdal.GetDriverByName("MEM").Create("", self.w, self.h, 1, gdal.GDT_Byte)
        ds.SetGeoTransform((self.west, self.dlon, 0, self.north, 0, -self.dlat))
        ds.SetProjection(self.srs.ExportToWkt())
        return ds


def burn(grid, feats, all_touched=False, merge="replace"):
    """Rasterize [(geometry, value 1-255)] into a new uint8 array.
    merge="min" keeps the smallest value per cell (features burned largest first)."""
    out = np.zeros((grid.h, grid.w), np.uint8)
    if not feats:
        return out
    if merge == "min":
        feats = sorted(feats, key=lambda fv: -fv[1])
    mem_ds = ogr.GetDriverByName("Memory").CreateDataSource("")
    lyr = mem_ds.CreateLayer("l", grid.srs, ogr.wkbUnknown)
    lyr.CreateField(ogr.FieldDefn("v", ogr.OFTInteger))
    for geom, v in feats:
        f = ogr.Feature(lyr.GetLayerDefn())
        f.SetGeometry(geom)
        f.SetField("v", int(v))
        lyr.CreateFeature(f)
    ds = grid.mem()
    opts = ["ATTRIBUTE=v"] + (["ALL_TOUCHED=TRUE"] if all_touched else [])
    gdal.RasterizeLayer(ds, [1], lyr, options=opts)
    out = ds.GetRasterBand(1).ReadAsArray()
    return out


def features(gpkgs, layer):
    for g, have in gpkgs:
        if layer not in have:
            continue
        ds = ogr.Open(g)
        for f in ds.GetLayerByName(layer):
            geom = f.GetGeometryRef()
            if geom is not None and not geom.IsEmpty():
                yield f, geom.Clone()


# The app plans for safe depths under 40 ft (12.19 m); deeper charted water is all the same to it
DEEP_M = 12.5
COARSE_M = 0.3


def depth_key(d1, d2, kind, step=None):
    """Depth table key (drval1, drval2, kind), folded where the app's planner cannot tell
    ranges apart (Planner.classify): any drying depth area (drval1 < 0) is too shallow,
    depths from DEEP_M down are deep enough for any boat, and a dredged area is judged by
    its drval1 alone. A region has to fit 255 keys (one byte per grid cell). With step,
    both values are rounded DOWN to it: shallower, so never less cautious."""
    if kind == 1:
        d2 = None
    if kind == 0 and d1 is not None and d1 < 0:
        return (-0.1, None, 0)
    if d1 is not None and d1 >= DEEP_M:
        return (DEEP_M, None, kind)
    if d2 is not None and d2 >= DEEP_M:
        d2 = None
    if step:
        d1, d2 = (None if v is None else round(math.floor(v / step + 1e-9) * step, 2) for v in (d1, d2))
    return (None if d1 is None else round(d1, 2), None if d2 is None else round(d2, 2), kind)


# Lateral marks (buoys BOYLAT, daybeacons BCNLAT). A mark's channel side is the same
# whichever way a boat goes, so red right returning (green right going out) is: never pass
# between a mark and the water it guards. Each mark gets a "wrong side" line GATE_M long,
# pointing away from its channel (from the side of the nearest fairway or dredged area
# within CHANNEL_M, else toward the nearest opposite-colour mark within PAIR_M); crossing
# it, or the mark's own cell, costs GATE_COST like a pier and is flagged.
GATE_M = 100.0
CHANNEL_M = 150.0
PAIR_M = 400.0


def channel_gates(marks, fairways, dredged):
    """Wrong-side lines of lateral marks. marks: [(lon, lat, catlam)], catlam 1 = port-hand
    (green in the US), 2 = starboard-hand (red), 3/4 = preferred channel (no line).
    fairways, dredged: lists of exterior rings [(lon, lat), ...]. Returns
    [((lon, lat), (lon, lat))]: from each mark GATE_M away from its channel, kept out of
    fairways and dredged areas (a wrong guess can never block a charted channel)."""
    if not marks:
        return []
    lat0 = sum(m[1] for m in marks) / len(marks)
    kx, ky = 111320.0 * math.cos(math.radians(lat0)), 111320.0
    to = lambda lon, lat: (lon * kx, lat * ky)
    back = lambda x, y: (x / kx, y / ky)
    rings = [[[to(*p) for p in r] for r in group if len(r) >= 3] for group in (fairways, dredged)]
    # Bounding boxes, so each mark only looks at channels near it (a region has thousands)
    boxes = [[(min(p[0] for p in r), min(p[1] for p in r), max(p[0] for p in r), max(p[1] for p in r)) for r in g] for g in rings]

    def near(g, x, y, m):
        return [r for r, b in zip(rings[g], boxes[g]) if b[0] - m <= x <= b[2] + m and b[1] - m <= y <= b[3] + m]
    pts = [(to(lon, lat), cat) for lon, lat, cat in marks]

    def inside(x, y, ring):
        c = False
        for i in range(len(ring)):
            (x1, y1), (x2, y2) = ring[i - 1], ring[i]
            if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
                c = not c
        return c

    def nearest(x, y, ring):
        best = None
        for i in range(len(ring)):
            (ax, ay), (bx, by) = ring[i - 1], ring[i]
            dx, dy = bx - ax, by - ay
            L2 = dx * dx + dy * dy or 1e-9
            t = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / L2))
            px, py = ax + t * dx, ay + t * dy
            d = math.hypot(x - px, y - py)
            if best is None or d < best[0]:
                best = (d, px, py, dx, dy)
        return best

    def nearest_side(x, y, ring):
        """Nearest of the ring's long edges (its sides, not its end caps): (distance,
        edge dx, dy, edge midpoint)."""
        segs = [(ring[i - 1], ring[i]) for i in range(len(ring))]
        lens = [math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in segs]
        if not lens:
            return None
        long_ = min(60.0, 0.3 * max(lens))
        best = None
        for (a, b), L in zip(segs, lens):
            if L < long_:
                continue
            dx, dy = b[0] - a[0], b[1] - a[1]
            t = max(0.0, min(1.0, ((x - a[0]) * dx + (y - a[1]) * dy) / (L * L)))
            d = math.hypot(x - a[0] - t * dx, y - a[1] - t * dy)
            if best is None or d < best[0]:
                best = (d, dx, dy, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
        return best

    out = []
    for (x, y), cat in pts:
        if cat not in (1, 2):
            continue
        v = None
        for g in (0, 1):
            cand = [(nearest_side(x, y, r), r) for r in near(g, x, y, CHANNEL_M)]
            cand = [(n, r) for n, r in cand if n is not None and n[0] <= CHANNEL_M]
            if not cand:
                continue
            (d, dx, dy, mx, my), ring = min(cand, key=lambda c: c[0][0])
            # Perpendicular to the channel's side, pointing into the channel (an end cap
            # would point along the channel and block its own entrance)
            L = math.hypot(dx, dy) or 1.0
            n = (-dy / L, dx / L)
            v = n if inside(mx + 3 * n[0], my + 3 * n[1], ring) else (-n[0], -n[1])
            break
        if v is None:
            opp = [(math.hypot(ox - x, oy - y), ox, oy) for (ox, oy), oc in pts if oc in (1, 2) and oc != cat]
            opp = [o for o in opp if o[0] <= PAIR_M]
            if opp:
                _, ox, oy = min(opp)
                v = (ox - x, oy - y)
        if v is None:
            continue
        L = math.hypot(*v)
        if L == 0:
            continue
        ux, uy = -v[0] / L, -v[1] / L
        # The line never runs through a charted fairway or dredged area: it stops where
        # one begins (a wrong guess can then never block a charted channel); a mark inside
        # its channel starts the line where it leaves the channel
        all_rings = near(0, x, y, GATE_M) + near(1, x, y, GATE_M)
        start, end, t = None, None, 0.0
        while t <= GATE_M:
            px, py = x + ux * t, y + uy * t
            inch = any(inside(px, py, r) for r in all_rings)
            if not inch and start is None:
                start = t
            elif inch and start is not None:
                end = t - 5
                break
            t += 5
        if start is None:
            continue
        end = GATE_M if end is None else end
        if end - start < 30:
            continue
        out.append((back(x + ux * start, y + uy * start), back(x + ux * end, y + uy * end)))
    return out


def exterior_rings(geom):
    """Exterior rings [(lon, lat), ...] of a polygon or multipolygon."""
    g = geom.Clone()
    g.FlattenTo2D()
    t = g.GetGeometryType()
    polys = [g] if t == ogr.wkbPolygon else [g.GetGeometryRef(i) for i in range(g.GetGeometryCount())] if t == ogr.wkbMultiPolygon else []
    return [[(x, y) for x, y in p.GetGeometryRef(0).GetPoints()] for p in polys if p.GetGeometryCount()]


def dm(v):
    return int(min(253, max(0, round(v * 10)))) + 1


def half_m(v):
    return int(min(255, max(1, round(v * 2))))


def build(cells, grid, work, depth=None, quiet=False):
    """Rasterize the charts into the grid's planes. depth: (depth_table, depth_index)
    shared by the blocks of a region built in blocks (build_blocks)."""
    planes = np.zeros((NPLANES, grid.h, grid.w), np.uint8)
    depth_table, depth_index = depth or ([None], {})  # index 0 = no water
    coarsened = 0
    notes = []
    say = (lambda m: None) if quiet else log

    def inside(e):
        return e and e[0] < grid.west + grid.w * grid.dlon and e[1] > grid.west \
            and e[2] < grid.north and e[3] > grid.north - grid.h * grid.dlat

    by_band = {}
    datums = set()
    for name, path in sorted(cells.items()):
        g, have = to_gpkg(name, path, work)
        e = coverage_extent(g)
        if inside(e):
            datums.add(check_units(name, path))
            by_band.setdefault(int(name[2]), []).append((g, have))
    say(f"{sum(len(v) for v in by_band.values())} cells in area, sounding datum codes {sorted(d for d in datums if d is not None)}")

    for band in sorted(by_band):
        gp = by_band[band]
        say(f"Band {band}: {len(gp)} cells")
        cov = burn(grid, [(geom, 1) for f, geom in features(gp, "M_COVR")
                          if fnum(f, "CATCOV") == 1]) > 0
        planes[:, cov] = 0

        # Depth areas -> depth table index
        depth_feats = []
        for kind, layer in ((0, "DEPARE"), (1, "DRGARE")):
            for f, geom in features(gp, layer):
                key = depth_key(fnum(f, "DRVAL1"), fnum(f, "DRVAL2"), kind)
                if key not in depth_index and len(depth_table) >= 256:
                    key = depth_key(fnum(f, "DRVAL1"), fnum(f, "DRVAL2"), kind, COARSE_M)
                    coarsened += 1
                if key not in depth_index:
                    if len(depth_table) >= 256:
                        raise SystemExit("More than 255 distinct depth ranges, even rounded down to "
                                         f"{COARSE_M} m")
                    depth_index[key] = len(depth_table)
                    depth_table.append(list(key))
                d1, d2 = fnum(f, "DRVAL1"), fnum(f, "DRVAL2")
                order = (kind, -1e9 if d1 is None else d1, -1e9 if d2 is None else d2)
                depth_feats.append((order, geom, depth_index[key]))
        # Burned shallow to deep (a later feature wins a cell), dredged areas after depth
        # areas, as noaa-route.py does: where areas overlap the deeper one wins. In chart
        # order a 3.3 ft dredged basin beside Masonboro Sound overwrote the 12 ft ICW
        # channel's cells, and at 5 ft that shut the 1-2 cell wide channel.
        depth_feats.sort(key=lambda t: t[0])
        d = burn(grid, [(g_, v) for _, g_, v in depth_feats])
        planes[DEPTH] = np.where(d > 0, d, planes[DEPTH])
        # Not all touched: that narrowed harbor entrances shut (Rock Hall); the
        # planner's shore proximity cost keeps routes off land instead
        land = burn(grid, [(geom, 1) for f, geom in features(gp, "LNDARE")]) > 0
        planes[DEPTH][land] = 0

        # Hazards and soundings, shallowest wins
        hz, snags = [], []
        for layer in ("OBSTRN", "WRECKS", "UWTROC"):
            for f, geom in features(gp, layer):
                v = fnum(f, "VALSOU")
                # Foul ground (CATOBS 7) is safe to navigate over, only bad for anchoring: a
                # hazard only with a charted depth (unknown-depth foul ground around the
                # Mid-Hudson Bridge piers closed the Hudson at Poughkeepsie)
                if v is None and layer == "OBSTRN" and fnum(f, "CATOBS") == 7:
                    continue
                if v is None and layer == "OBSTRN" and fnum(f, "CATOBS") == 1 and fnum(f, "WATLEV") == 3:
                    snags.append((geom, 1))
                else:
                    hz.append((geom, 1 if v is None else dm(v)))
        # Cells with a real hazard before this band (not a snag, not a pier mark)
        prior_real = (planes[HAZARD] != 0) & (planes[HAZARD] != HZ_PIER) & (planes[CABLECLR] != SNAG_MARK)
        h_real = burn(grid, hz, all_touched=True, merge="min")
        sn = burn(grid, snags, all_touched=True) > 0
        h = np.where(sn & (h_real == 0), 1, h_real).astype(np.uint8)
        planes[HAZARD] = np.where((h > 0) & ((planes[HAZARD] == 0) | (h < planes[HAZARD])), h, planes[HAZARD])
        # Snag mark only where a snag is the cell's only hazard
        # (not under a cable from an earlier band: CABLECLR is its clearance there)
        planes[CABLECLR][sn & (h_real == 0) & ~prior_real & ((planes[FLAGS] & CABLE) == 0)] = SNAG_MARK
        planes[CABLECLR][(h_real > 0) & (planes[CABLECLR] == SNAG_MARK)] = 0
        # Piers, seawalls, jetties, breakwaters: one cell wide (not all touched) so a
        # bulkheaded channel keeps its middle; only where no hazard is charted
        pier = burn(grid, [(geom, 1) for f, geom in features(gp, "SLCONS")]) > 0
        planes[HAZARD][pier & (planes[HAZARD] == 0)] = HZ_PIER
        sd = burn(grid, [(geom, dm(fnum(f, "DEPTH") or 0.0)) for f, geom in features(gp, "SOUNDG")],
                  merge="min")
        planes[SOUND] = np.where((sd > 0) & ((planes[SOUND] == 0) | (sd < planes[SOUND])), sd, planes[SOUND])

        # Flags
        flags = np.zeros((grid.h, grid.w), np.uint8)
        area = lambda g_: g_.GetGeometryType() in (ogr.wkbPolygon, ogr.wkbMultiPolygon,
                                                   ogr.wkbPolygon25D, ogr.wkbMultiPolygon25D)
        st = [(geom, 1) for layer in ("PILPNT", "PYLONS") for f, geom in features(gp, layer) if area(geom)]
        flags |= np.where(burn(grid, st, all_touched=True) > 0, STRUCT, 0).astype(np.uint8)
        pro, resn = [], []
        for f, geom in features(gp, "RESARE"):
            codes = listval(fstr(f, "RESTRN"))
            # Entry prohibited blocks. An area to be avoided (14) is only reported: on US
            # charts these are IMO routing measures for large ships (the Aleutian ATBAs,
            # for ships of 400 gross tons and up, cover Adak, Dutch Harbor and Sand Point)
            if "7" in codes:
                pro.append((geom, 1))
            rules = [RESTRN[c] for c in codes if c in RESTRN]
            if rules:
                resn.append((geom, 1))
                cfr = re.search(r"\d+ CFR [\d.]+", fstr(f, "INFORM") or "")
                notes.append(note("resare", band, geom, name=fstr(f, "OBJNAM") or "restricted area",
                                  rules=rules, cfr=cfr.group(0) if cfr else None))
        flags |= np.where(burn(grid, pro) > 0, PROHIB, 0).astype(np.uint8)
        flags |= np.where(burn(grid, resn, all_touched=True) > 0, RESNOTE, 0).astype(np.uint8)
        deck, span, opening, anyb = [], [], [], []
        for f, geom in features(gp, "BRIDGE"):
            clr = fnum(f, "VERCLR")
            kinds = [OPENING_KINDS[c] for c in listval(fstr(f, "CATBRG")) if c in OPENING_KINDS]
            anyb.append((geom, 1))
            if clr is None:
                deck.append((geom, 1))
            else:
                span.append((geom, half_m(clr)))
            if kinds:
                opening.append((geom, 1))
            # Every bridge part is kept so the report can tell that a detailed
            # chart has the bridge here (and ignore overview-chart outlines)
            notes.append(note("bridge", band, geom, name=fstr(f, "OBJNAM") or "bridge", clr=clr,
                              opening=kinds[0] if kinds else None,
                              closed=(fnum(f, "VERCCL") or clr) if kinds else None))
        flags |= np.where(burn(grid, deck, all_touched=True) > 0, DECK, 0).astype(np.uint8)
        sp = burn(grid, span, all_touched=True, merge="min")
        flags |= np.where(sp > 0, SPAN, 0).astype(np.uint8)
        planes[SPANCLR] = np.where(sp > 0, sp, planes[SPANCLR])
        flags |= np.where(burn(grid, opening, all_touched=True) > 0, OPENING, 0).astype(np.uint8)
        flags |= np.where(burn(grid, anyb, all_touched=True) > 0, BRIDGE, 0).astype(np.uint8)
        cab, cabclr = [], []
        for f, geom in features(gp, "CBLOHD"):
            clr = fnum(f, "VERCLR")
            cab.append((geom, 1))
            if clr is not None:
                cabclr.append((geom, half_m(clr)))
            notes.append(note("cable", band, geom, name=fstr(f, "OBJNAM") or "overhead cable", clr=clr))
        flags |= np.where(burn(grid, cab, all_touched=True) > 0, CABLE, 0).astype(np.uint8)
        cc = burn(grid, cabclr, all_touched=True, merge="min")
        # A cable of unknown clearance over a snag mark: the plane is the cable's again (0)
        planes[CABLECLR][((flags & CABLE) != 0) & (cc == 0) & (planes[CABLECLR] == SNAG_MARK)] = 0
        planes[CABLECLR] = np.where(cc > 0, cc, planes[CABLECLR])
        planes[FLAGS] |= flags

        # Lateral marks and their wrong-side lines (see channel_gates), only where the
        # cable plane is free (no cable clearance, no snag mark)
        marks = []
        for layer in ("BOYLAT", "BCNLAT"):
            for f, geom in features(gp, layer):
                if geom.GetGeometryType() in (ogr.wkbPoint, ogr.wkbPoint25D):
                    marks.append((geom.GetX(), geom.GetY(), int(fnum(f, "CATLAM") or 0)))
        if marks:
            fair = [r for f, geom in features(gp, "FAIRWY") for r in exterior_rings(geom)]
            drg = [r for f, geom in features(gp, "DRGARE") for r in exterior_rings(geom)]
            gl = []
            for a, b in channel_gates(marks, fair, drg):
                ln = ogr.Geometry(ogr.wkbLineString)
                ln.AddPoint_2D(*a)
                ln.AddPoint_2D(*b)
                gl.append((ln, 1))
            for x, y, _ in marks:
                pt = ogr.Geometry(ogr.wkbPoint)
                pt.AddPoint_2D(x, y)
                gl.append((pt, 1))
            gate = burn(grid, gl) > 0
            planes[CABLECLR][gate & ((planes[FLAGS] & CABLE) == 0) & (planes[CABLECLR] == 0)] = GATE_MARK
            say(f"{len(marks)} lateral marks, {len(gl) - len(marks)} wrong-side lines")

    if coarsened:
        log(f"Depth table full: {coarsened} depth areas rounded down to {COARSE_M} m (shallower, so routes only get more cautious)")
    return planes, depth_table, notes, sorted(d for d in datums if d is not None)


# Regions whose box is larger than this many grid cells are built in blocks: their whole
# grid would not fit in memory (region 29, American Samoa and the central Pacific atolls,
# is 8.8 billion cells, about 53 GB of planes, with charts in 254 of its tiles)
BLOCK_CELLS = 0.8e9
BLOCK = 16       # tiles per block side (4096 x 4096 cells, about 100 MB of planes)
NEAR_TILES = 2   # tiles kept around approach and harbor charts (band 4+), about 10 km


def build_blocks(cells, grid, work, detail):
    """Build a large region block by block: only blocks within NEAR_TILES of the band 4+
    charts in detail [(w, s, e, n)], each rasterized with a one-tile margin so features
    reaching in from a neighbor (a hazard buffer, a mark's wrong-side line) are complete,
    keeping its inner tiles. Every step of build() looks at most a few hundred meters
    around a feature, so the tiles match a whole-grid build. Returns ({(tx, ty): tile
    array}, depth_table, notes, datums); tx, ty are relative to grid."""
    tw, th = grid.w // TILE, grid.h // TILE
    want = set()
    for w, s, e, n in detail:
        x0 = int(math.floor((w + 180) / (GRID_DLON * TILE))) - grid.tx0 - NEAR_TILES
        x1 = int(math.floor((e + 180) / (GRID_DLON * TILE))) - grid.tx0 + NEAR_TILES
        y0 = int(math.floor((90 - n) / (GRID_DLAT * TILE))) - grid.ty0 - NEAR_TILES
        y1 = int(math.floor((90 - s) / (GRID_DLAT * TILE))) - grid.ty0 + NEAR_TILES
        for ty in range(max(0, y0), min(th - 1, y1) + 1):
            for tx in range(max(0, x0), min(tw - 1, x1) + 1):
                want.add((tx // BLOCK, ty // BLOCK))
    blocks = sorted(want, key=lambda b: (b[1], b[0]))
    # Each cell's coverage once (minx, maxx, miny, maxy), so a block only opens its own cells
    ext = {n: coverage_extent(to_gpkg(n, p, work)[0]) for n, p in cells.items()}
    log(f"Building in {len(blocks)} blocks of {BLOCK} x {BLOCK} tiles (region grid {tw} x {th} tiles)")
    depth = ([None], {})
    tiles, notes, seen, datums = {}, [], set(), set()
    for k, (bx, by) in enumerate(blocks):
        x0, y0 = bx * BLOCK, by * BLOCK
        x1, y1 = min(tw, x0 + BLOCK), min(th, y0 + BLOCK)
        m = 1  # margin in tiles
        sub = Grid.tiles(grid.tx0 + x0 - m, grid.ty0 + y0 - m, grid.tx0 + x1 + m, grid.ty0 + y1 + m)
        sw, ss, se, sn = sub.west, sub.north - sub.h * sub.dlat, sub.west + sub.w * sub.dlon, sub.north
        mine = {n: p for n, p in cells.items()
                if ext[n] and ext[n][0] < se and ext[n][1] > sw and ext[n][2] < sn and ext[n][3] > ss}
        planes, _, block_notes, ds = build(mine, sub, work, depth, quiet=True)
        datums.update(ds)
        for nt in block_notes:  # a feature meeting several blocks is noted by each
            key = json.dumps(nt, sort_keys=True)
            if key not in seen:
                seen.add(key)
                notes.append(nt)
        for ty in range(y0, y1):
            for tx in range(x0, x1):
                ox, oy = (tx - x0 + m) * TILE, (ty - y0 + m) * TILE
                t = planes[:, oy:oy + TILE, ox:ox + TILE]
                if t.any():
                    tiles[(tx, ty)] = np.ascontiguousarray(t)
        if (k + 1) % 10 == 0 or k + 1 == len(blocks):
            log(f"Block {k + 1}/{len(blocks)}: {len(tiles)} tiles with data, {len(depth[0]) - 1} depth ranges, {len(notes)} notes")
    return tiles, depth[0], notes, sorted(datums)


def note(kind, band, geom, **attrs):
    g = geom.Clone()
    g.FlattenTo2D()
    g = g.SimplifyPreserveTopology(0.00005) or g  # ~5 m
    minx, maxx, miny, maxy = g.GetEnvelope()
    gj = json.loads(g.ExportToJson())

    def rnd(c):
        return [round(c[0], 6), round(c[1], 6)] if isinstance(c[0], (int, float)) else [rnd(x) for x in c]

    coords = gj.get("coordinates")  # None for geometry collections: bbox only
    return {"kind": kind, "band": band, "bbox": [round(minx, 6), round(miny, 6), round(maxx, 6), round(maxy, 6)],
            "type": gj["type"] if coords is not None else "Bbox", "coords": rnd(coords) if coords is not None else None,
            **{k: (round(v, 2) if isinstance(v, float) else v) for k, v in attrs.items() if v is not None}}


# ---------------------------------------------------------------- output

def write_pack(path, area, grid, tiles, depth_table, notes, datums, created):
    """tiles: the region's planes (NPLANES x h x w), or {(tx, ty): tile} from build_blocks."""
    tw, th = grid.w // TILE, grid.h // TILE
    if isinstance(tiles, dict):
        tile_at = tiles.get
    else:
        def tile_at(k, planes=tiles):
            tx, ty = k
            return planes[:, ty * TILE:(ty + 1) * TILE, tx * TILE:(tx + 1) * TILE]
    header = {
        "format": "NRPK", "version": 2, "created": created,
        "area": {"id": area["id"], "name": area["name"], "bbox": area["bbox"]},
        "source": [ENC_URL.format(s) for s in area["states"]] if "states" in area else [CATALOG_URL],
        "states": area.get("states", []), "ncds": area.get("ncds"),
        "sounding_datums": datums,
        "west": grid.west, "north": grid.north, "dlat": grid.dlat, "dlon": grid.dlon, "res": grid.res,
        "tx0": grid.tx0, "ty0": grid.ty0,
        "width": grid.w, "height": grid.h, "tile": TILE, "planes": NPLANES,
        "depth_table": depth_table, "notes": notes,
    }
    hb = json.dumps(header, separators=(",", ":")).encode()
    index, blobs = [], []
    for ty in range(th):
        for tx in range(tw):
            t = tile_at((tx, ty))
            if t is None or not t.any():
                index.append(None)
                continue
            blobs.append(zlib.compress(np.ascontiguousarray(t).tobytes(), 9))
            index.append(len(blobs) - 1)
    base = 6 + 4 + len(hb) + len(index) * 12
    with open(path + ".tmp", "wb") as f:
        f.write(b"NRPK2\n")
        f.write(struct.pack("<I", len(hb)))
        f.write(hb)
        offs, pos = [], base
        for b in blobs:
            offs.append(pos)
            pos += len(b)
        for i in index:
            f.write(struct.pack("<QI", 0, 0) if i is None else struct.pack("<QI", offs[i], len(blobs[i])))
        for b in blobs:
            f.write(b)
    os.replace(path + ".tmp", path)
    log(f"Wrote {path}: {grid.w} x {grid.h} cells, {len(blobs)}/{len(index)} tiles with data, "
        f"{os.path.getsize(path) / 1e6:.1f} MB, {len(depth_table) - 1} depth ranges, {len(notes)} notes")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-a", "--area", help="area id from areas.json (default: all)")
    ap.add_argument("--areas", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "areas.json"))
    ap.add_argument("-w", "--work", default="enc-work")
    ap.add_argument("-o", "--out", default="packs")
    ap.add_argument("--blocks", action="store_true", help="build in blocks whatever the region's size (for checking build_blocks)")
    a = ap.parse_args()
    areas = json.load(open(a.areas))["areas"]
    if a.area:
        areas = [x for x in areas if x["id"] == a.area] or sys.exit(f"Unknown area {a.area}")
    os.makedirs(a.work, exist_ok=True)
    os.makedirs(a.out, exist_ok=True)
    catalog, enc = [], None
    for area in areas:
        log(f"== {area['id']}: {area['name']}")
        if "ncds" in area:
            enc = enc or enc_catalog(a.work)
            area = dict(area, bbox=region_bbox(area, enc))
            grid = Grid(*area["bbox"])
            gw, gn = grid.west, grid.north
            ge, gs = gw + grid.w * grid.dlon, gn - grid.h * grid.dlat
            log(f"Region {area['ncds']}: charts in {area['bbox']}, grid box {[round(x, 3) for x in (gw, gs, ge, gn)]}")
            cells = fetch_cells([c for c in enc if meets(c["bbox"], gw, gs, ge, gn)], a.work)
        else:
            cells = fetch(area["states"], a.work)
            grid = Grid(*area["bbox"])
        log(f"Grid {grid.w} x {grid.h} (tiles from {grid.tx0},{grid.ty0})")
        if "ncds" in area and (a.blocks or grid.w * grid.h > BLOCK_CELLS):
            detail = [c["bbox"] for c in enc if c["band"] >= 4 and meets(c["bbox"], gw, gs, ge, gn)]
            planes, depth_table, notes, datums = build_blocks(cells, grid, a.work, detail)
        else:
            planes, depth_table, notes, datums = build(cells, grid, a.work)
        if datums and datums != [12]:
            log(f"Note: sounding datums {datums}; the app reports Low Water Datum if 25 (Great Lakes, IGLD 1985) is among them, else MLLW")
        path = os.path.join(a.out, area["id"] + ".bin")
        # One timestamp for the pack header and its catalog entry: the app offers an
        # update while the catalog's is later than the installed pack's, and separate
        # timestamps (seconds apart) kept "update available" on forever
        created = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        write_pack(path, area, grid, planes, depth_table, notes, datums, created)
        data = open(path, "rb").read()
        entry = {"id": area["id"], "name": area["name"], "description": area.get("description", ""),
                 "bbox": area["bbox"], "states": area.get("states", []), "file": area["id"] + ".bin",
                 "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                 "created": created, "sounding_datums": datums}
        # NCDS region fields, and the hand-over from an older area (apps that know them
        # swap it for its regions; older apps ignore unknown fields)
        for k in ("ncds", "ncds_bbox", "deprecated", "replaced_by"):
            if k in area:
                entry[k] = area[k]
        catalog.append(entry)
    json.dump({"version": 2, "areas": catalog}, open(os.path.join(a.out, "catalog.json"), "w"), indent=1)
    log(f"Wrote catalog with {len(catalog)} area(s)")


if __name__ == "__main__":
    main()
