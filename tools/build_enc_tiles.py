#!/usr/bin/env python3
"""build_enc_tiles.py
Offline vector tiles of NOAA ENC charts for the in-app map (Middle River proof of concept).

  python3 tools/build_enc_tiles.py
  python3 tools/build_enc_tiles.py -b -76.48,39.27,-76.32,39.36 -o android/app/src/main/assets/middle_river.mbtiles

Reads the active ENC cells that meet the box (GDAL applies the update files, so Notice to
Mariners corrections are in), keeps each object from the most detailed chart that covers it
(same rule as tools/enc_to_osm.py), and writes one MBTiles file of Mapbox Vector Tiles.
Depths are whole feet, the way US charts print them (NOAA stores 6 ft as 1.8 m).

Layers: depare, drgare, lndare, depcnt, coalne, fairwy, slcons, slcons_area, bridge,
bridge_area, soundg, buoy, beacon, lights, wreck, wreck_area, rock, rock_area, obstrn,
obstrn_line, obstrn_area.

Not in this proof of concept: cardinal / safe-water / special-purpose marks, daymark
topmarks (the daybeacon itself is BCNLAT), light sectors, restricted areas, overhead
cables, recommended tracks, seabed areas, piles drawn as points. The app's planner is
unchanged and still uses the route packs.

The default box is Middle River, MD, from Sue Creek and Maryland Marina out to the bay
off Hart-Miller Island. Re-run when you want the bundled chart brought up to date, then
replace the file in android/app/src/main/assets/.
"""

import argparse
import importlib.util
import json
import os
import sqlite3
import sys
import time

from osgeo import gdal, ogr, osr

gdal.UseExceptions()
ogr.UseExceptions()
gdal.SetConfigOption("OGR_S57_OPTIONS",
                     "RETURN_PRIMITIVES=OFF,RETURN_LINKAGES=OFF,LNAM_REFS=OFF,"
                     "UPDATES=APPLY,SPLIT_MULTIPOINT=ON,ADD_SOUNDG_DEPTH=ON")

FT = 0.3048
# Maryland Marina (39.32371, -76.39998) sits inside this box.
DEFAULT_BBOX = (-76.48, 39.27, -76.32, 39.36)
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_OUT = os.path.join(HERE, "..", "android", "app", "src", "main", "assets", "middle_river.mbtiles")

# What each zoom of the tile file bothers to store. The style shows the same cutoffs.
MINZOOM = {
    "depare": 8, "drgare": 8, "lndare": 8, "coalne": 8,
    "depcnt": 11, "fairwy": 12,
    "bridge": 12, "bridge_area": 12,
    "slcons": 13, "slcons_area": 13,
    "buoy": 13, "beacon": 13, "lights": 13,
    "soundg": 14, "wreck": 14, "wreck_area": 14, "rock": 14, "rock_area": 14,
    "obstrn": 14, "obstrn_line": 14, "obstrn_area": 14,
}
POLY_LAYERS = {"depare", "drgare", "lndare", "slcons_area", "bridge_area", "wreck_area", "rock_area", "obstrn_area"}
LINE_LAYERS = {"depcnt", "coalne", "fairwy", "slcons", "bridge", "obstrn_line"}
POINT_LAYERS = {"soundg", "buoy", "beacon", "lights", "wreck", "rock", "obstrn"}

SIDES = {"1": "port", "2": "starboard", "3": "pref_stbd", "4": "pref_port"}
LIGHT_COL = {"1": "W", "2": "Bk", "3": "R", "4": "G", "5": "Bu", "6": "Y", "7": "Gy",
             "8": "Br", "9": "Am", "10": "Vi", "11": "Or", "12": "Mg", "13": "Pk"}
OBS_CAT = {"1": "Snag", "2": "Well", "5": "Fish haven", "6": "Foul", "7": "Foul"}


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def load_enc_to_osm():
    path = os.path.join(HERE, "enc_to_osm.py")
    spec = importlib.util.spec_from_file_location("enc_to_osm", path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["enc_to_osm"] = mod
    spec.loader.exec_module(mod)
    return mod


def feet(m):
    """Whole feet, as the chart prints them. 1.8 m (NOAA's 6 ft) comes back as 6."""
    if m is None:
        return None
    f = float(m) / FT
    if abs(f) < 0.5:
        return 0
    return int(round(f))


def ascii_text(s, limit=48):
    if s is None:
        return None
    t = "".join(c if 32 <= ord(c) < 127 else " " for c in str(s))
    t = " ".join(t.split())
    return t[:limit] or None


def num(f, name, eto):
    v = eto.val(f, name)
    if v is None or isinstance(v, str):
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def make_box(w, s, e, n):
    return ogr.CreateGeometryFromWkt(f"POLYGON(({w} {s},{e} {s},{e} {n},{w} {n},{w} {s}))")


def clean(geom):
    if geom is None or geom.IsEmpty():
        return None
    if not geom.IsValid():
        geom = geom.MakeValid()
    if geom is None or geom.IsEmpty():
        return None
    return geom


def explode(geom):
    if geom is None or geom.IsEmpty():
        return
    gt = ogr.GT_Flatten(geom.GetGeometryType())
    if gt in (ogr.wkbPoint, ogr.wkbLineString, ogr.wkbPolygon):
        yield geom
        return
    if gt in (ogr.wkbMultiPoint, ogr.wkbMultiLineString, ogr.wkbMultiPolygon, ogr.wkbGeometryCollection):
        for i in range(geom.GetGeometryCount()):
            yield from explode(geom.GetGeometryRef(i).Clone())


def clip_keep(geom, band, box, cov):
    geom = geom.Clone()
    geom.FlattenTo2D()
    if geom.IsEmpty() or not geom.Intersects(box):
        return None
    geom = clean(geom.Intersection(box))
    if geom is None:
        return None
    kept = cov.keep(geom, band)
    return clean(kept)


def point_z(geom):
    gt = ogr.GT_Flatten(geom.GetGeometryType())
    if gt == ogr.wkbPoint and geom.Is3D():
        return geom.GetZ()
    return None


def light_label(f, eto):
    ch = [eto.LITCHR[x] for x in eto.codes(f, "LITCHR") if x in eto.LITCHR]
    cols = [LIGHT_COL[x] for x in eto.codes(f, "COLOUR") if x in LIGHT_COL]
    grp = eto.val(f, "SIGGRP")
    per = eto.val(f, "SIGPER")
    parts = []
    if ch:
        g = ""
        if grp and str(grp).strip("()"):
            g = str(grp).strip()
            if not g.startswith("("):
                g = f"({g})"
        parts.append(ch[0] + g)
    if cols:
        parts.append(cols[0])
    if per:
        parts.append(f"{float(per):g}s")
    name = ascii_text(eto.val(f, "OBJNAM"), 24)
    label = " ".join(parts)
    if name and label:
        return f"{name} {label}"[:48]
    return ascii_text(label or name)


def bridge_label(f, eto):
    name = ascii_text(eto.val(f, "OBJNAM"), 32)
    clr = feet(num(f, "VERCLR", eto))
    if clr is None:
        clr = feet(num(f, "VERCSA", eto))
    kind = [eto.CATBRG[x] for x in eto.codes(f, "CATBRG") if x in eto.CATBRG]
    bits = []
    if name:
        bits.append(name)
    if clr is not None:
        bits.append(f"{clr} ft")
    elif kind and kind[0] in ("opening", "swing", "lifting", "bascule", "draw", "transporter"):
        bits.append(kind[0])
    return " ".join(bits) or None


def side_of(f, eto):
    for c in eto.codes(f, "CATLAM"):
        if c in SIDES:
            return SIDES[c]
    return None


def depth_pair(f, eto):
    """Shoal and deep limits in feet. A missing deep limit means the shoal limit is the depth
    (a dredged channel charted as '10 ft', or the deepest depth area)."""
    d1 = feet(num(f, "DRVAL1", eto))
    if d1 is None:
        return None
    d2 = feet(num(f, "DRVAL2", eto))
    if d2 is None:
        d2 = d1
    if d2 < d1:
        d1, d2 = d2, d1
    return d1, d2


def hazard_label(kind, ft, cat):
    word = cat or kind
    if ft is None:
        return word
    return f"{word} {ft}"


class Collector:
    def __init__(self):
        self.polys = {}   # layer -> props tuple -> [geom]
        self.lines = {}
        self.points = []  # (layer, props tuple, x, y) deduped
        self.seen_pts = set()
        self.counts = {}

    def _bump(self, layer):
        self.counts[layer] = self.counts.get(layer, 0) + 1

    def add_poly(self, layer, props, geom):
        if geom.GetArea() < 1e-12:
            return
        key = tuple(sorted(props.items()))
        self.polys.setdefault(layer, {}).setdefault(key, []).append(geom)
        self._bump(layer)

    def add_line(self, layer, props, geom):
        if geom.Length() < 1e-7:
            return
        key = tuple(sorted(props.items()))
        self.lines.setdefault(layer, {}).setdefault(key, []).append(geom)
        self._bump(layer)

    def add_point(self, layer, props, geom):
        x, y = round(geom.GetX(), 6), round(geom.GetY(), 6)
        key = (layer, x, y, tuple(sorted(props.items())))
        if key in self.seen_pts:
            return
        self.seen_pts.add(key)
        self.points.append((layer, props, x, y))
        self._bump(layer)


def props_of(cls, feat, geom, eto, z=None):
    """(layer, props) for one exploded geometry, or None when this object is not drawn.
    z is a sounding's depth in meters, read before the geometry was flattened."""
    gt = ogr.GT_Flatten(geom.GetGeometryType())
    if cls == "DEPARE":
        d = depth_pair(feat, eto)
        if d is None or gt != ogr.wkbPolygon:
            return None
        return "depare", {"d1": d[0], "d2": d[1]}
    if cls == "DRGARE":
        d = depth_pair(feat, eto)
        if d is None or gt != ogr.wkbPolygon:
            return None
        p = {"d1": d[0], "d2": d[1]}
        name = ascii_text(eto.val(feat, "OBJNAM"))
        if name:
            p["name"] = name
        return "drgare", p
    if cls == "LNDARE":
        return ("lndare", {}) if gt == ogr.wkbPolygon else None
    if cls == "DEPCNT":
        ft = feet(num(feat, "VALDCO", eto))
        if ft is None or gt != ogr.wkbLineString:
            return None
        return "depcnt", {"ft": ft}
    if cls == "COALNE":
        return ("coalne", {}) if gt == ogr.wkbLineString else None
    if cls == "FAIRWY":
        if gt == ogr.wkbPolygon:
            ring = geom.GetGeometryRef(0)
            if ring is None:
                return None
            line = ogr.Geometry(ogr.wkbLineString)
            for x, y, *_ in (ring.GetPoints() or []):
                line.AddPoint_2D(x, y)
            geom = line  # boundary, so the fairway reads as a magenta edge
            gt = ogr.wkbLineString
        if gt != ogr.wkbLineString:
            return None
        p = {}
        name = ascii_text(eto.val(feat, "OBJNAM"))
        if name:
            p["name"] = name
        return "fairwy", p, geom
    if cls == "SLCONS":
        cat = [eto.CATSLC[x] for x in eto.codes(feat, "CATSLC") if x in eto.CATSLC]
        p = {"cat": cat[0]} if cat else {}
        if gt == ogr.wkbPolygon:
            return "slcons_area", p
        if gt == ogr.wkbLineString:
            return "slcons", p
        return None
    if cls == "BRIDGE":
        p = {}
        label = bridge_label(feat, eto)
        if label:
            p["label"] = label
        if gt == ogr.wkbPolygon:
            return "bridge_area", p
        if gt == ogr.wkbLineString:
            return "bridge", p
        return None
    if cls == "SOUNDG":
        depth_m = z if z is not None else num(feat, "DEPTH", eto)
        ft = feet(depth_m)
        if ft is None or gt != ogr.wkbPoint:
            return None
        return "soundg", {"ft": ft}
    if cls in ("BOYLAT", "BCNLAT"):
        if gt != ogr.wkbPoint:
            return None
        side = side_of(feat, eto)
        if side is None:
            return None
        p = {"side": side}
        name = ascii_text(eto.val(feat, "OBJNAM"), 24)
        if name:
            p["name"] = name
        return ("buoy" if cls == "BOYLAT" else "beacon"), p
    if cls == "LIGHTS":
        if gt != ogr.wkbPoint:
            return None
        label = light_label(feat, eto)
        return "lights", ({"label": label} if label else {})
    if cls in ("WRECKS", "UWTROC", "OBSTRN"):
        ft = feet(num(feat, "VALSOU", eto))
        if cls == "WRECKS":
            kind, base = "Wk", "wreck"
        elif cls == "UWTROC":
            kind, base = "Rk", "rock"
        else:
            cat = [OBS_CAT[x] for x in eto.codes(feat, "CATOBS") if x in OBS_CAT]
            kind, base = "Obst", "obstrn"
            word = hazard_label(kind, ft, cat[0] if cat else None)
            p = {"label": word}
            if ft is not None:
                p["ft"] = ft
            if gt == ogr.wkbPoint:
                return base, p
            if gt == ogr.wkbLineString:
                return base + "_line", p
            if gt == ogr.wkbPolygon:
                return base + "_area", p
            return None
        p = {"label": hazard_label(kind, ft, None)}
        if ft is not None:
            p["ft"] = ft
        if gt == ogr.wkbPoint:
            return base, p
        if gt == ogr.wkbPolygon:
            return base + "_area", p
        return None
    return None


def add_object(col, cls, feat, geom, eto, z=None):
    got = props_of(cls, feat, geom, eto, z)
    if got is None:
        return
    if len(got) == 3:
        layer, props, geom = got
    else:
        layer, props = got
    gt = ogr.GT_Flatten(geom.GetGeometryType())
    if layer in POLY_LAYERS and gt == ogr.wkbPolygon:
        col.add_poly(layer, props, geom)
    elif layer in LINE_LAYERS and gt == ogr.wkbLineString:
        col.add_line(layer, props, geom)
    elif layer in POINT_LAYERS and gt == ogr.wkbPoint:
        col.add_point(layer, props, geom)


def union_polys(geoms):
    if len(geoms) == 1:
        return geoms[0]
    multi = ogr.Geometry(ogr.wkbMultiPolygon)
    for g in geoms:
        gt = ogr.GT_Flatten(g.GetGeometryType())
        if gt == ogr.wkbPolygon:
            multi.AddGeometry(g)
        elif gt == ogr.wkbMultiPolygon:
            for i in range(g.GetGeometryCount()):
                multi.AddGeometry(g.GetGeometryRef(i))
    try:
        return multi.UnionCascaded()
    except RuntimeError as ex:
        log(f"union failed ({ex}); keeping {len(geoms)} polygons")
        return multi


def srs4326():
    srs = osr.SpatialReference()
    srs.ImportFromEPSG(4326)
    srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    return srs


def field_defs(layer):
    if layer in ("depare", "drgare"):
        fields = [("d1", ogr.OFTInteger), ("d2", ogr.OFTInteger)]
        if layer == "drgare":
            fields.append(("name", ogr.OFTString))
        return fields
    if layer == "depcnt" or layer == "soundg":
        return [("ft", ogr.OFTInteger)]
    if layer in ("buoy", "beacon"):
        return [("side", ogr.OFTString), ("name", ogr.OFTString)]
    if layer in ("lights", "bridge", "bridge_area"):
        return [("label", ogr.OFTString)]
    if layer in ("slcons", "slcons_area"):
        return [("cat", ogr.OFTString)]
    if layer == "fairwy":
        return [("name", ogr.OFTString)]
    if layer in ("wreck", "wreck_area", "rock", "rock_area", "obstrn", "obstrn_line", "obstrn_area"):
        return [("ft", ogr.OFTInteger), ("label", ogr.OFTString)]
    return []


def wkb_of(layer):
    if layer in POLY_LAYERS:
        return ogr.wkbMultiPolygon
    if layer in LINE_LAYERS:
        return ogr.wkbMultiLineString
    return ogr.wkbPoint


def write_gpkg(col, path):
    drv = ogr.GetDriverByName("GPKG")
    if os.path.exists(path):
        drv.DeleteDataSource(path)
    ds = drv.CreateDataSource(path)
    srs = srs4326()
    layers = {}

    def layer_for(name):
        if name in layers:
            return layers[name]
        ly = ds.CreateLayer(name, srs, wkb_of(name), options=["SPATIAL_INDEX=NO"])
        for fname, ftype in field_defs(name):
            ly.CreateField(ogr.FieldDefn(fname, ftype))
        layers[name] = ly
        return ly

    def write(name, geom, props):
        ly = layer_for(name)
        feat = ogr.Feature(ly.GetLayerDefn())
        feat.SetGeometry(geom)
        for k, v in props.items():
            if v is not None:
                feat.SetField(k, v)
        ly.CreateFeature(feat)

    ds.StartTransaction()
    for name, groups in col.polys.items():
        for key, geoms in groups.items():
            props = dict(key)
            merged = clean(union_polys(geoms))
            if merged is None:
                continue
            for part in explode(merged):
                if ogr.GT_Flatten(part.GetGeometryType()) == ogr.wkbPolygon and part.GetArea() >= 1e-12:
                    write(name, part, props)
    for name, groups in col.lines.items():
        for key, geoms in groups.items():
            props = dict(key)
            if len(geoms) == 1:
                write(name, geoms[0], props)
                continue
            multi = ogr.Geometry(ogr.wkbMultiLineString)
            for g in geoms:
                multi.AddGeometry(g)
            write(name, multi, props)
    for name, props, x, y in col.points:
        pt = ogr.Geometry(ogr.wkbPoint)
        pt.AddPoint_2D(x, y)
        write(name, pt, props)
    ds.CommitTransaction()
    names = [layers[n].GetName() for n in layers]
    counts = {n: layers[n].GetFeatureCount() for n in layers}
    ds = None
    return names, counts


def write_mbtiles(gpkg, out, layer_names, bounds):
    w, s, e, n = bounds
    conf = {
        "minzoom": 8,
        "maxzoom": 16,
        "layers": {name: {"minzoom": MINZOOM.get(name, 8), "maxzoom": 16} for name in layer_names},
    }
    conf_path = gpkg + ".conf.json"
    with open(conf_path, "w") as f:
        json.dump(conf, f)
    if os.path.exists(out):
        os.remove(out)
    gdal.VectorTranslate(out, gpkg, format="MVT", datasetCreationOptions=[
        "FORMAT=MBTILES",
        "MINZOOM=8",
        "MAXZOOM=16",
        "NAME=Middle River ENC",
        "DESCRIPTION=NOAA ENC charts for Middle River, MD. Depths in whole feet. Public domain.",
        "COMPRESS=YES",
        "MAX_SIZE=2000000",
        "SIMPLIFICATION_MAX_ZOOM=0",
        f"CONF={conf_path}",
        f"BOUNDS={w},{s},{e},{n}",
        f"CENTER={(w + e) / 2},{(s + n) / 2},12",
    ])
    return conf_path


def summarize(path):
    db = sqlite3.connect(path)
    meta = dict(db.execute("select name, value from metadata"))
    zooms = db.execute("select zoom_level, count(*) from tiles group by 1 order by 1").fetchall()
    sample = db.execute("select tile_data from tiles order by zoom_level desc limit 1").fetchone()
    db.close()
    gzipped = bool(sample and sample[0][:2] == b"\x1f\x8b")
    size = os.path.getsize(path)
    log(f"wrote {path} ({size / 1e6:.2f} MB), gzip tiles: {gzipped}")
    log("metadata: " + ", ".join(f"{k}={v[:80]}" for k, v in meta.items() if k != "json"))
    log("tiles per zoom: " + ", ".join(f"z{z}={n}" for z, n in zooms))
    ds = ogr.Open(path)
    layers = []
    for i in range(ds.GetLayerCount()):
        ly = ds.GetLayer(i)
        layers.append(f"{ly.GetName()}={ly.GetFeatureCount()}")
    ds = None
    log("mvt layers: " + ", ".join(layers))
    return size, meta, zooms


def main():
    ap = argparse.ArgumentParser(description="Build Middle River ENC vector tiles (MBTiles).")
    ap.add_argument("-b", "--bbox", help="W,S,E,N (default: Middle River, MD)")
    ap.add_argument("-o", "--out", default=DEFAULT_OUT)
    ap.add_argument("-w", "--work", default=os.path.expanduser("~/.cache/noaa-route/enc-work"))
    a = ap.parse_args()
    if a.bbox:
        w, s, e, n = (float(x) for x in a.bbox.split(","))
    else:
        w, s, e, n = DEFAULT_BBOX
    out = os.path.abspath(a.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    os.makedirs(a.work, exist_ok=True)

    eto = load_enc_to_osm()
    brp = eto.brp
    t0 = time.time()
    enc = brp.enc_catalog(a.work)
    metas = [c for c in enc if brp.meets(c["bbox"], w, s, e, n)]
    log(f"box {[w, s, e, n]}: {len(metas)} cells ({', '.join(sorted(m['name'] for m in metas))})")
    cells = brp.fetch_cells(metas, a.work)
    cov = eto.Coverage(os.path.join(a.work, "ENCProdCat.xml"), set(cells))
    box = make_box(w, s, e, n)
    classes = ["DEPARE", "DRGARE", "LNDARE", "DEPCNT", "COALNE", "FAIRWY", "SLCONS", "BRIDGE",
               "SOUNDG", "BOYLAT", "BCNLAT", "LIGHTS", "WRECKS", "UWTROC", "OBSTRN"]
    col = Collector()
    for name in sorted(cells, key=lambda nm: (-int(nm[2]), nm)):
        band = int(name[2])
        src = ogr.Open(cells[name])
        if src is None:
            raise SystemExit(f"{name}: GDAL could not open the cell")
        t1 = time.time()
        before = sum(col.counts.values())
        for cls in classes:
            layer = src.GetLayerByName(cls)
            if layer is None:
                continue
            layer.SetSpatialFilter(box)
            layer.ResetReading()
            while True:
                feat = layer.GetNextFeature()
                if feat is None:
                    break
                geom = feat.GetGeometryRef()
                if geom is None:
                    continue
                # A sounding multipoint that the driver did not split still has one depth per point.
                gt0 = ogr.GT_Flatten(geom.GetGeometryType())
                if gt0 == ogr.wkbMultiPoint:
                    pieces = [geom.GetGeometryRef(i).Clone() for i in range(geom.GetGeometryCount())]
                else:
                    pieces = [geom]
                for g in pieces:
                    z = point_z(g)
                    piece = clip_keep(g, band, box, cov)
                    if piece is None:
                        continue
                    for part in explode(piece):
                        add_object(col, cls, feat, part, eto, z)
        src = None
        log(f"{name} band {band}: {sum(col.counts.values()) - before} objects in {time.time() - t1:.1f}s")

    log("kept: " + ", ".join(f"{k}={v}" for k, v in sorted(col.counts.items())))
    for required in ("depare", "lndare", "soundg"):
        if col.counts.get(required, 0) == 0:
            raise SystemExit(f"no {required} features inside the box; the chart would be empty")

    gpkg = os.path.join(a.work, "middle_river.gpkg")
    names, counts = write_gpkg(col, gpkg)
    log("gpkg: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
    write_mbtiles(gpkg, out, names, (w, s, e, n))
    size, _, _ = summarize(out)
    log(f"done in {time.time() - t0:.0f}s, {size} bytes")


if __name__ == "__main__":
    main()
