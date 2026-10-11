#!/usr/bin/env python3
"""build_route_pack.py
Build a draft-independent "route pack" for the NOAA Route Android app from
NOAA ENC (S-57) data. Runs on GitHub Actions (Ubuntu with python3-gdal and
python3-numpy); see .github/workflows/route-pack.yml.

  python3 tools/build_route_pack.py -s MD -r 20 -o md-route-pack.bin

The pack is a 20 m grid (constant degree spacing, like noaa-route.py) cut into
256 x 256 tiles. Each tile holds 6 byte planes; the app applies the boat's
safe operating depth and air draft at planning time. Lower detail chart bands
are burned first and every band resets its own coverage, so the most detailed
chart wins (same rule as noaa-route.py).

Planes (0 always means "nothing here"):
  0 DEPTH   index into header depth_table: [drval1_m|null, drval2_m|null, kind]
            kind 0 = DEPARE, 1 = DRGARE. 0 = land, uncharted or no data.
  1 HAZARD  shallowest OBSTRN/WRECKS/UWTROC: 1 + VALSOU in dm (0-253); unknown
            depth is stored as 1 (0.0 m) so it is a hazard for any boat.
  2 SOUND   shallowest sounding: 1 + DEPTH in dm (0-253), negative -> 1.
  3 FLAGS   bit 0 STRUCT   pier/pylon drawn as an area (blocks, no buffer)
            bit 1 PROHIB   restricted area: entry prohibited / area to be avoided
            bit 2 DECK     bridge part without vertical clearance
            bit 3 SPAN     bridge part with vertical clearance (plane 4)
            bit 4 OPENING  opening bridge (swing, bascule, lift, draw...)
            bit 5 CABLE    overhead cable (clearance in plane 5)
            bit 6 RESNOTE  restricted area with a navigation rule (reported)
            bit 7 BRIDGE   any bridge part (reported)
  4 SPANCLR shortest span clearance here, 0.5 m units (1-255)
  5 CABLECLR shortest cable clearance, 0.5 m units (1-255), 0 unknown

File layout:
  "NRPK1\\n", u32 header length, header JSON (UTF-8), then for each tile in
  row-major order u64 offset + u32 length (length 0 = empty tile), then the
  zlib-compressed tiles (6 planes of 256*256 bytes each, plane-major).
"""

import argparse
import json
import math
import os
import re
import shutil
import struct
import subprocess
import sys
import time
import zipfile
import zlib

import numpy as np
from osgeo import gdal, ogr, osr

gdal.UseExceptions()
ogr.UseExceptions()
os.environ["OGR_S57_OPTIONS"] = "UPDATES=APPLY,SPLIT_MULTIPOINT=ON,ADD_SOUNDG_DEPTH=ON"
gdal.SetConfigOption("OGR_S57_OPTIONS", os.environ["OGR_S57_OPTIONS"])

ENC_URL = "https://charts.noaa.gov/ENCs/{}_ENCs.zip"
LAYERS = ["M_COVR", "DEPARE", "DRGARE", "LNDARE", "OBSTRN", "WRECKS", "UWTROC",
          "SOUNDG", "PILPNT", "PYLONS", "RESARE", "BRIDGE", "CBLOHD"]
M_PER_DEG = 111320.0
TILE = 256
NPLANES = 6
DEPTH, HAZARD, SOUND, FLAGS, SPANCLR, CABLECLR = range(NPLANES)
STRUCT, PROHIB, DECK, SPAN, OPENING, CABLE, RESNOTE, BRIDGE = (1 << b for b in range(8))
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
    cells = {}
    for st in states:
        z = os.path.join(work, f"{st}_ENCs.zip")
        log(f"Downloading {ENC_URL.format(st)}")
        subprocess.run(["curl", "-fsSL", "--retry", "5", "-o", z, ENC_URL.format(st)], check=True)
        d = os.path.join(work, st)
        with zipfile.ZipFile(z) as zf:
            zf.extractall(d)
        for root, _, files in os.walk(d):
            for f in files:
                if f.endswith(".000") and f[:-4] not in cells:
                    cells[f[:-4]] = os.path.join(root, f)
    log(f"{len(cells)} chart cells")
    return cells


def to_gpkg(name, path, work):
    """One GeoPackage per cell with NOAA's update files applied."""
    g = os.path.join(work, "gpkg", name + ".gpkg")
    os.makedirs(os.path.dirname(g), exist_ok=True)
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
    def __init__(self, west, south, east, north, res):
        latmid = (south + north) / 2
        self.res = res
        self.dlat = res / M_PER_DEG
        self.dlon = res / (M_PER_DEG * math.cos(math.radians(latmid)))
        self.w = int(math.ceil((east - west) / self.dlon / TILE)) * TILE
        self.h = int(math.ceil((north - south) / self.dlat / TILE)) * TILE
        self.west, self.north = west, north
        self.srs = osr.SpatialReference()
        self.srs.ImportFromEPSG(4326)
        self.srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)

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


def dm(v):
    return int(min(253, max(0, round(v * 10)))) + 1


def half_m(v):
    return int(min(255, max(1, round(v * 2))))


def build(cells, grid, work):
    planes = np.zeros((NPLANES, grid.h, grid.w), np.uint8)
    depth_table = [None]  # index 0 = no water
    depth_index = {}
    notes = []

    def inside(e):
        return e and e[0] < grid.west + grid.w * grid.dlon and e[1] > grid.west \
            and e[2] < grid.north and e[3] > grid.north - grid.h * grid.dlat

    by_band = {}
    for name, path in sorted(cells.items()):
        g, have = to_gpkg(name, path, work)
        e = coverage_extent(g)
        if inside(e):
            by_band.setdefault(int(name[2]), []).append((g, have))

    for band in sorted(by_band):
        gp = by_band[band]
        log(f"Band {band}: {len(gp)} cells")
        cov = burn(grid, [(geom, 1) for f, geom in features(gp, "M_COVR")
                          if fnum(f, "CATCOV") == 1]) > 0
        planes[:, cov] = 0

        # Depth areas -> depth table index
        depth_feats = []
        for kind, layer in ((0, "DEPARE"), (1, "DRGARE")):
            for f, geom in features(gp, layer):
                key = (fnum(f, "DRVAL1"), fnum(f, "DRVAL2"), kind)
                key = tuple(None if v is None else round(v, 2) for v in key[:2]) + (kind,)
                if key not in depth_index:
                    if len(depth_table) >= 256:
                        raise SystemExit("More than 255 distinct depth ranges")
                    depth_index[key] = len(depth_table)
                    depth_table.append(list(key))
                depth_feats.append((geom, depth_index[key]))
        d = burn(grid, depth_feats)
        planes[DEPTH] = np.where(d > 0, d, planes[DEPTH])
        land = burn(grid, [(geom, 1) for f, geom in features(gp, "LNDARE")]) > 0
        planes[DEPTH][land] = 0

        # Hazards and soundings, shallowest wins
        hz = []
        for layer in ("OBSTRN", "WRECKS", "UWTROC"):
            for f, geom in features(gp, layer):
                v = fnum(f, "VALSOU")
                hz.append((geom, 1 if v is None else dm(v)))
        h = burn(grid, hz, all_touched=True, merge="min")
        planes[HAZARD] = np.where((h > 0) & ((planes[HAZARD] == 0) | (h < planes[HAZARD])), h, planes[HAZARD])
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
            if "7" in codes or "14" in codes:
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
            if clr is not None or kinds:
                closed = fnum(f, "VERCCL") or clr
                notes.append(note("bridge", band, geom, name=fstr(f, "OBJNAM") or "bridge",
                                  clr=clr, opening=kinds[0] if kinds else None, closed=closed))
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
        planes[CABLECLR] = np.where(cc > 0, cc, planes[CABLECLR])
        planes[FLAGS] |= flags

    return planes, depth_table, notes


def note(kind, band, geom, **attrs):
    g = geom.Clone()
    g.FlattenTo2D()
    g = g.SimplifyPreserveTopology(0.00005) or g  # ~5 m
    minx, maxx, miny, maxy = g.GetEnvelope()
    gj = json.loads(g.ExportToJson())

    def rnd(c):
        return [round(c[0], 6), round(c[1], 6)] if isinstance(c[0], (int, float)) else [rnd(x) for x in c]

    return {"kind": kind, "band": band, "bbox": [round(minx, 6), round(miny, 6), round(maxx, 6), round(maxy, 6)],
            "type": gj["type"], "coords": rnd(gj["coordinates"]),
            **{k: (round(v, 2) if isinstance(v, float) else v) for k, v in attrs.items() if v is not None}}


# ---------------------------------------------------------------- output

def write_pack(path, grid, planes, depth_table, notes, states):
    tw, th = grid.w // TILE, grid.h // TILE
    header = {
        "format": "NRPK", "version": 1, "created": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source": [ENC_URL.format(s) for s in states], "states": states,
        "west": grid.west, "north": grid.north, "dlat": grid.dlat, "dlon": grid.dlon, "res": grid.res,
        "width": grid.w, "height": grid.h, "tile": TILE, "planes": NPLANES,
        "depth_table": depth_table, "notes": notes,
    }
    hb = json.dumps(header, separators=(",", ":")).encode()
    index, blobs = [], []
    for ty in range(th):
        for tx in range(tw):
            t = planes[:, ty * TILE:(ty + 1) * TILE, tx * TILE:(tx + 1) * TILE]
            if not t.any():
                index.append(None)
                continue
            blobs.append(zlib.compress(np.ascontiguousarray(t).tobytes(), 9))
            index.append(len(blobs) - 1)
    base = 6 + 4 + len(hb) + len(index) * 12
    with open(path + ".tmp", "wb") as f:
        f.write(b"NRPK1\n")
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
    ap.add_argument("-s", "--states", default="MD")
    ap.add_argument("-r", "--res", type=float, default=20.0)
    ap.add_argument("-b", "--bbox", default="-77.50,37.71,-74.55,39.68", help="W,S,E,N")
    ap.add_argument("-w", "--work", default="enc-work")
    ap.add_argument("-o", "--out", default="md-route-pack.bin")
    a = ap.parse_args()
    states = [s.strip().upper() for s in a.states.split(",") if s.strip()]
    W, S, E, N = (float(v) for v in a.bbox.split(","))
    os.makedirs(a.work, exist_ok=True)
    cells = fetch(states, a.work)
    grid = Grid(W, S, E, N, a.res)
    log(f"Grid {grid.w} x {grid.h} at {a.res:g} m")
    planes, depth_table, notes = build(cells, grid, a.work)
    write_pack(a.out, grid, planes, depth_table, notes, states)


if __name__ == "__main__":
    main()
