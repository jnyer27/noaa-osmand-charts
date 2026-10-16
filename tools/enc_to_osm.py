#!/usr/bin/env python3
"""enc_to_osm.py
Proof of concept (issue #2): NOAA ENC (S-57) vector charts as an OsmAnd map.

  python3 tools/enc_to_osm.py -b=-76.50,39.24,-76.33,39.37 -o out/middle_river.osm

Reads every active ENC cell meeting the box from NOAA's catalog (GDAL applies the
update files, so Notice to Mariners corrections are in), keeps each object from the
most detailed chart that covers it (as the route packs do), and writes OSM XML with
the tags OsmAnd keeps in an .obf (obf_creation/rendering_types.xml) and draws with
its Marine style: OpenSeaMap seamark tags for marks, lights, wrecks, rocks,
obstructions, piers, bridges, cables and areas; contour=depth, contourarea=depth and
point=depth for depth contours, depth areas and soundings. OsmAndMapCreator turns the
file into an .obf (see .github/workflows/enc-vector-poc.yml).

Also writes <out>.report.json: per S-57 class, how many objects were read, kept and
written, and with which tags, so the tags found in the .obf can be compared.
Depths in labels are feet (US charts); depth values are meters.
"""

import argparse
import collections
import importlib.util
import json
import math
import os
import sys
from xml.sax.saxutils import quoteattr

from osgeo import gdal, ogr

gdal.UseExceptions()
ogr.UseExceptions()
# Updates applied; soundings split into points with their depth as Z
gdal.SetConfigOption("OGR_S57_OPTIONS", "RETURN_PRIMITIVES=OFF,RETURN_LINKAGES=OFF,LNAM_REFS=OFF,"
                     "UPDATES=APPLY,SPLIT_MULTIPOINT=ON,ADD_SOUNDG_DEPTH=ON")

# The route pack builder's catalog reader and cell download
_spec = importlib.util.spec_from_file_location(
    "build_route_pack", os.path.join(os.path.dirname(os.path.abspath(__file__)), "build_route_pack.py"))
brp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(brp)

FT = 0.3048

# S-57 attribute code tables -> OpenSeaMap values
COLOUR = {"1": "white", "2": "black", "3": "red", "4": "green", "5": "blue", "6": "yellow", "7": "grey",
          "8": "brown", "9": "amber", "10": "violet", "11": "orange", "12": "magenta", "13": "pink"}
BOYSHP = {"1": "conical", "2": "can", "3": "spherical", "4": "pillar", "5": "spar", "6": "barrel",
          "7": "super-buoy", "8": "ice_buoy"}
BCNSHP = {"1": "stake", "2": "withy", "3": "tower", "4": "lattice", "5": "pile", "6": "cairn", "7": "buoyant"}
CATLAM = {"1": "port", "2": "starboard", "3": "preferred_channel_starboard", "4": "preferred_channel_port"}
CATCAM = {"1": "north", "2": "east", "3": "south", "4": "west"}
LITCHR = {"1": "F", "2": "Fl", "3": "LFl", "4": "Q", "5": "VQ", "6": "UQ", "7": "Iso", "8": "Oc", "9": "IQ",
          "10": "IVQ", "11": "IUQ", "12": "Mo", "13": "FFl", "14": "FlLFl", "15": "OcFl", "16": "FLFl",
          "17": "Al.Oc", "18": "Al.LFl", "19": "Al.Fl", "20": "Al.Gr", "25": "Q+LFl", "26": "VQ+LFl",
          "27": "UQ+LFl", "28": "Al", "29": "Al.FFl"}
WATLEV = {"1": "part-submerged", "2": "dry", "3": "submerged", "4": "covers", "5": "awash", "6": "floods",
          "7": "floating"}
CATWRK = {"1": "non-dangerous", "2": "dangerous", "3": "distributed_remains", "4": "mast_showing",
          "5": "hull_showing"}
CATOBS = {"1": "stump", "2": "wellhead", "3": "diffuser", "4": "crib", "5": "fish_haven", "6": "foul_area",
          "7": "foul_ground", "8": "ice_boom", "9": "ground_tackle", "10": "boom"}
# Shoreline construction, mapped onto the categories OsmAnd converts (rendering_types.xml:
# breakwater, groyne, pier -> man_made; training_wall, seawall -> wall; slipway). Wharves,
# landing steps and fenders draw as piers, moles as breakwaters, ramps as slipways,
# revetments and rip rap as seawalls
CATSLC = {"1": "breakwater", "2": "groyne", "3": "breakwater", "4": "pier", "5": "pier", "6": "pier",
          "7": "training_wall", "8": "seawall", "9": "seawall", "10": "seawall", "11": "pier",
          "12": "slipway", "13": "slipway", "14": "pier", "15": "pier", "16": "pier", "17": "slipway"}
CATLMK = {"1": "cairn", "2": "cemetery", "3": "chimney", "4": "dish_aerial", "5": "flagstaff", "6": "flare_stack",
          "7": "mast", "8": "windsock", "9": "monument", "10": "column", "11": "memorial", "12": "obelisk",
          "13": "statue", "14": "cross", "15": "dome", "16": "radar_scanner", "17": "tower", "18": "windmill",
          "19": "windmotor", "20": "spire", "21": "boulder"}
NATSUR = {"1": "mud", "2": "clay", "3": "silt", "4": "sand", "5": "stone", "6": "gravel", "7": "pebbles",
          "8": "cobbles", "9": "rock", "11": "lava", "14": "coral", "17": "shells", "18": "boulder"}
RESTRN = {"1": "no_anchoring", "2": "restricted_anchoring", "3": "no_fishing", "4": "restricted_fishing",
          "5": "no_trawling", "6": "restricted_trawling", "7": "no_entry", "8": "restricted_entry",
          "9": "no_dredging", "10": "restricted_dredging", "11": "no_diving", "12": "restricted_diving",
          "13": "no_wake", "14": "area_to_be_avoided", "15": "no_construction"}
CATBRG = {"1": "fixed", "2": "opening", "3": "swing", "4": "lifting", "5": "bascule", "6": "pontoon",
          "7": "draw", "8": "transporter", "9": "footbridge", "10": "viaduct", "11": "aqueduct",
          "12": "suspension"}

# Mark classes: S-57 class -> (seamark type, category attribute, category table, shape attribute, shape table)
MARKS = {
    "BOYLAT": ("buoy_lateral", "CATLAM", CATLAM, "BOYSHP", BOYSHP),
    "BCNLAT": ("beacon_lateral", "CATLAM", CATLAM, "BCNSHP", BCNSHP),
    "BOYCAR": ("buoy_cardinal", "CATCAM", CATCAM, "BOYSHP", BOYSHP),
    "BCNCAR": ("beacon_cardinal", "CATCAM", CATCAM, "BCNSHP", BCNSHP),
    "BOYSAW": ("buoy_safe_water", None, None, "BOYSHP", BOYSHP),
    "BCNSAW": ("beacon_safe_water", None, None, "BCNSHP", BCNSHP),
    "BOYISD": ("buoy_isolated_danger", None, None, "BOYSHP", BOYSHP),
    "BCNISD": ("beacon_isolated_danger", None, None, "BCNSHP", BCNSHP),
    "BOYSPP": ("buoy_special_purpose", None, None, "BOYSHP", BOYSHP),
    "BCNSPP": ("beacon_special_purpose", None, None, "BCNSHP", BCNSHP),
}

# Classes this proof of concept reads (everything else in the cells is counted as not mapped)
LAYERS = list(MARKS) + ["LIGHTS", "DAYMAR", "WRECKS", "UWTROC", "OBSTRN", "PILPNT", "SOUNDG", "DEPCNT",
                        "DEPARE", "DRGARE", "SLCONS", "BRIDGE", "CBLOHD", "FAIRWY", "RESARE", "ACHARE",
                        "CBLARE", "PIPARE", "MORFAC", "HRBFAC", "SMCFAC", "NAVLNE", "RECTRC", "SEAARE",
                        "LNDMRK", "CBLSUB", "PYLONS", "SBDARE", "FOGSIG", "MARCUL", "M_COVR"]


def val(f, name):
    i = f.GetFieldIndex(name)
    if i < 0 or not f.IsFieldSet(i):
        return None
    v = f.GetField(i)
    if isinstance(v, str):
        v = v.strip()
        return v or None
    return v


def codes(f, name):
    """List attribute (S-57 'A,B' or a GDAL string list) as a list of strings."""
    v = val(f, name)
    if v is None:
        return []
    if isinstance(v, (list, tuple)):
        return [str(x) for x in v]
    return [x for x in str(v).split(",") if x]


def feet(m):
    return f"{m / FT:.0f}" if m >= 10 * FT else f"{m / FT:.1f}".rstrip("0").rstrip(".")


def contourtype(m):
    """The contour class OsmAnd draws a line with. Released OsmAnd (rules from 2023) only draws
    5m, 10m, 20m, 50m, 100m, 200m and 1000m; 2m, minor and depth areas only came to its
    development version in September 2026. So every line gets the drawn class at or below
    the next one up: NOAA's 3, 6 and 12 ft lines (0.9, 1.8, 3.6 m) draw as 5m class lines.
    The label is the name tag (feet), so the class does not change what the line reads."""
    for cls, upto in ((5, 7.5), (10, 15), (20, 35), (50, 75), (100, 150), (200, 600)):
        if m < upto:
            return f"{cls}m"
    return "1000m"


def areatype(d1):
    """OsmAnd depth area classes by shallowest depth: -1 dries, 0 = 0-2 m, 2 = 2-5 m,
    5 = 5-10 m; deeper areas keep the sea colour (no area written)."""
    if d1 is None:
        return None
    if d1 < 0:
        return "-1"
    if d1 < 2:
        return "0"
    if d1 < 5:
        return "2"
    if d1 < 10:
        return "5"
    return None


def name_tags(f):
    n = val(f, "OBJNAM")
    return {"name": n, "seamark:name": n} if n else {}


def mark_tags(cls, f):
    st, catattr, cattab, shpattr, shptab = MARKS[cls]
    t = {"seamark:type": st}
    if catattr:
        c = [cattab[x] for x in codes(f, catattr) if x in cattab]
        if c:
            t[f"seamark:{st}:category"] = c[0]
            # OsmAnd keeps beacon categories but drops buoy ones; its folded form
            # (seamark:category, rendering_types.xml) may survive
            t["seamark:category"] = c[0]
    col = [COLOUR[x] for x in codes(f, "COLOUR") if x in COLOUR]
    if col:
        t[f"seamark:{st}:colour"] = ";".join(col)
    s = [shptab[x] for x in codes(f, shpattr) if x in shptab]
    if s:
        t[f"seamark:{st}:shape"] = s[0]
    t["seamark:system"] = "iala-b"  # US waters (M_NSYS)
    t.update(name_tags(f))
    return t


def light_tags(f, prefix="seamark:light"):
    t = {}
    ch = [LITCHR[x] for x in codes(f, "LITCHR") if x in LITCHR]
    if ch:
        t[f"{prefix}:character"] = ch[0]
    col = [COLOUR[x] for x in codes(f, "COLOUR") if x in COLOUR]
    if col:
        t[f"{prefix}:colour"] = ";".join(col)
    grp = val(f, "SIGGRP")
    if grp and grp.strip("()"):
        t[f"{prefix}:group"] = grp.strip("()")
    per = val(f, "SIGPER")
    if per:
        t[f"{prefix}:period"] = f"{per:g}"
    rng = val(f, "VALNMR")
    if rng:
        t[f"{prefix}:range"] = f"{rng:g}"
    h = val(f, "HEIGHT")
    if h:
        t[f"{prefix}:height"] = f"{h:g}"
    return t


def feature_tags(cls, f, geom):
    """OSM tags for one S-57 object, or None if this class is not mapped."""
    if cls in MARKS:
        return mark_tags(cls, f)
    if cls == "LIGHTS":
        t = {"seamark:type": "light_major" if (val(f, "VALNMR") or 0) >= 10 else "light_minor"}
        t.update(light_tags(f))
        t.update(name_tags(f))
        return t
    if cls == "WRECKS":
        t = {"seamark:type": "wreck"}
        c = [CATWRK[x] for x in codes(f, "CATWRK") if x in CATWRK]
        if c:
            t["seamark:wreck:category"] = c[0]
        w = WATLEV.get(str(val(f, "WATLEV")))
        if w:
            t["seamark:wreck:water_level"] = w
        d = val(f, "VALSOU")
        if d is not None:
            t["depth"] = f"{d:g}"
        return t
    if cls == "UWTROC":
        t = {"seamark:type": "rock"}
        w = WATLEV.get(str(val(f, "WATLEV")))
        if w:
            t["seamark:rock:water_level"] = w
        d = val(f, "VALSOU")
        if d is not None:
            t["depth"] = f"{d:g}"
        return t
    if cls == "OBSTRN":
        t = {"seamark:type": "obstruction"}
        c = [CATOBS[x] for x in codes(f, "CATOBS") if x in CATOBS]
        if c:
            t["seamark:obstruction:category"] = c[0]
        w = WATLEV.get(str(val(f, "WATLEV")))
        if w:
            t["seamark:obstruction:water_level"] = w
        d = val(f, "VALSOU")
        if d is not None:
            t["depth"] = f"{d:g}"
        return t
    if cls == "PILPNT":
        return {"seamark:type": "pile"}
    if cls == "SOUNDG":
        z = geom.GetZ() if geom.GetCoordinateDimension() == 3 else val(f, "DEPTH")
        if z is None:
            return None
        return {"point": "depth", "depth": f"{z:.1f}", "name": feet(z)}
    if cls == "DEPCNT":
        v = val(f, "VALDCO")
        if v is None:
            return None
        return {"contour": "depth", "contourtype": contourtype(v), "depth": f"{v:g}", "name": feet(v)}
    if cls in ("DEPARE", "DRGARE"):
        a = areatype(val(f, "DRVAL1"))
        if a is None:
            return None
        return {"contourarea": "depth", "areatype": a}
    if cls == "SLCONS":
        t = {"seamark:type": "shoreline_construction"}
        c = [CATSLC[x] for x in codes(f, "CATSLC") if x in CATSLC]
        if c:
            t["seamark:shoreline_construction:category"] = c[0]
        return t
    if cls == "BRIDGE":
        t = {"seamark:type": "bridge"}
        c = [CATBRG[x] for x in codes(f, "CATBRG") if x in CATBRG]
        if c:
            t["seamark:bridge:category"] = c[0]
        v = val(f, "VERCLR")
        if v is not None:
            t["seamark:bridge:clearance_height"] = f"{v:g}"
        t.update(name_tags(f))
        return t
    if cls == "CBLOHD":
        t = {"seamark:type": "cable_overhead"}
        v = val(f, "VERCLR")
        if v is not None:
            t["seamark:cable_overhead:vertical_clearance_safe"] = f"{v:g}"  # OsmAnd's label tag
        return t
    if cls == "RESARE":
        t = {"seamark:type": "restricted_area"}
        r = [RESTRN[x] for x in codes(f, "RESTRN") if x in RESTRN]
        if r:
            t["seamark:restricted_area:restriction"] = ";".join(r)
        t.update(name_tags(f))
        return t
    if cls == "SEAARE":
        n = val(f, "OBJNAM")
        return {"seamark:type": "sea_area", "name": n, "seamark:name": n} if n else None
    if cls == "LNDMRK":
        t = {"seamark:type": "landmark"}
        c = [CATLMK[x] for x in codes(f, "CATLMK") if x in CATLMK]
        if c:
            t["seamark:landmark:category"] = c[0]
        t.update(name_tags(f))
        return t
    if cls == "SBDARE":
        t = {"seamark:type": "seabed_area"}
        c = [NATSUR[x] for x in codes(f, "NATSUR") if x in NATSUR]
        if not c:
            return None
        t["seamark:seabed_area:surface"] = ";".join(c)
        return t
    simple = {"FAIRWY": "fairway", "CBLSUB": "cable_submarine", "PYLONS": "pylon", "FOGSIG": "fog_signal",
              "MARCUL": "marine_farm", "ACHARE": "anchorage", "CBLARE": "cable_area", "PIPARE": "pipeline_area",
              "MORFAC": "mooring", "HRBFAC": "harbour", "SMCFAC": "small_craft_facility",
              "NAVLNE": "navigation_line", "RECTRC": "recommended_track"}
    if cls in simple:
        t = {"seamark:type": simple[cls]}
        t.update(name_tags(f))
        return t
    return None


class OsmWriter:
    """Points as nodes, lines as ways, polygons as closed ways (or multipolygon relations
    when they have holes). Shared coordinates reuse one node."""

    def __init__(self):
        self.nodes, self.node_tags, self.ways, self.rels = {}, {}, [], []
        self.next = -1

    def _id(self):
        i = self.next
        self.next -= 1
        return i

    def node(self, x, y, tags=None):
        key = (round(x, 7), round(y, 7))
        if tags:  # tagged nodes are their own object
            i = self._id()
            self.nodes[(key, i)] = i
            self.node_tags[i] = (key, tags)
            return i
        if key not in self.nodes:
            self.nodes[key] = self._id()
        return self.nodes[key]

    def way(self, pts, tags):
        refs = [self.node(x, y) for x, y in pts]
        refs = [r for k, r in enumerate(refs) if k == 0 or r != refs[k - 1]]
        if len(refs) < 2:
            return None
        i = self._id()
        self.ways.append((i, refs, tags))
        return i

    def add(self, geom, tags):
        """Write one geometry with tags; returns the number of OSM objects written."""
        gt = ogr.GT_Flatten(geom.GetGeometryType())
        if gt == ogr.wkbPoint:
            self.node(geom.GetX(), geom.GetY(), tags)
            return 1
        if gt in (ogr.wkbMultiPoint, ogr.wkbMultiLineString, ogr.wkbMultiPolygon, ogr.wkbGeometryCollection):
            return sum(self.add(geom.GetGeometryRef(k), tags) for k in range(geom.GetGeometryCount()))
        if gt == ogr.wkbLineString:
            return 1 if self.way(geom.GetPoints() or [], tags) else 0
        if gt == ogr.wkbPolygon:
            rings = [geom.GetGeometryRef(k).GetPoints() or [] for k in range(geom.GetGeometryCount())]
            if len(rings) == 1:
                return 1 if self.way(rings[0], tags) else 0
            members = [(self.way(r, {}), "outer" if k == 0 else "inner") for k, r in enumerate(rings)]
            members = [(w, role) for w, role in members if w]
            self.rels.append((self._id(), members, dict(tags, type="multipolygon")))
            return 1
        return 0

    def write(self, path):
        def tagxml(tags):
            return "".join(f"<tag k={quoteattr(k)} v={quoteattr(str(v))}/>" for k, v in tags.items() if v is not None)
        with open(path, "w") as out:
            out.write('<?xml version="1.0" encoding="UTF-8"?>\n<osm version="0.6" generator="enc_to_osm.py">\n')
            for key, i in self.nodes.items():
                if isinstance(key[0], tuple):
                    continue  # tagged node, below
                out.write(f'<node id="{i}" version="1" lat="{key[1]}" lon="{key[0]}"/>\n')
            for i, (key, tags) in self.node_tags.items():
                out.write(f'<node id="{i}" version="1" lat="{key[1]}" lon="{key[0]}">{tagxml(tags)}</node>\n')
            for i, refs, tags in self.ways:
                nd = "".join(f'<nd ref="{r}"/>' for r in refs)
                out.write(f'<way id="{i}" version="1">{nd}{tagxml(tags)}</way>\n')
            for i, members, tags in self.rels:
                mm = "".join(f'<member type="way" ref="{w}" role="{role}"/>' for w, role in members)
                out.write(f'<relation id="{i}" version="1">{mm}{tagxml(tags)}</relation>\n')
            out.write("</osm>\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-b", "--bbox", required=True, help="W,S,E,N")
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("-w", "--work", default="enc-work")
    a = ap.parse_args()
    w, s, e, n = map(float, a.bbox.split(","))
    box = ogr.CreateGeometryFromWkt(f"POLYGON(({w} {s},{e} {s},{e} {n},{w} {n},{w} {s}))")
    os.makedirs(a.work, exist_ok=True)
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)

    enc = brp.enc_catalog(a.work)
    cells = brp.fetch_cells([c for c in enc if brp.meets(c["bbox"], w, s, e, n)], a.work)
    by_band = collections.defaultdict(list)
    for name, path in sorted(cells.items()):
        by_band[int(name[2])].append((name, path))

    osm = OsmWriter()
    report = collections.defaultdict(lambda: {"read": 0, "kept": 0, "written": 0, "tags": collections.Counter()})
    unmapped = collections.Counter()
    covered = None  # coverage of the more detailed bands already written
    lights = []  # lights are merged into the mark they stand on (OpenSeaMap tagging)
    marks = []   # (x, y, tags) of written marks

    for band in sorted(by_band, reverse=True):  # most detailed first
        band_cov = None
        for name, path in by_band[band]:
            ds = ogr.Open(path)
            for li in range(ds.GetLayerCount()):
                lyr = ds.GetLayer(li)
                cls = lyr.GetName()
                if cls not in LAYERS:
                    if cls not in ("DSID",) and lyr.GetFeatureCount() > 0:
                        unmapped[cls] += lyr.GetFeatureCount()
                    continue
                lyr.SetSpatialFilter(box)
                for f in lyr:
                    g = f.GetGeometryRef()
                    if g is None or g.IsEmpty():
                        continue
                    g = g.Clone()
                    if cls == "M_COVR":
                        if val(f, "CATCOV") == 1:
                            band_cov = g if band_cov is None else band_cov.Union(g)
                        continue
                    report[cls]["read"] += 1
                    # Keep only what the more detailed charts do not cover
                    if covered is not None:
                        if ogr.GT_Flatten(g.GetGeometryType()) in (ogr.wkbPoint, ogr.wkbMultiPoint):
                            if covered.Contains(g):
                                continue
                        else:
                            g = g.Difference(covered)
                            if g is None or g.IsEmpty():
                                continue
                    g = g.Intersection(box) if not box.Contains(g) else g
                    if g is None or g.IsEmpty():
                        continue
                    if cls == "SOUNDG" and ogr.GT_Flatten(g.GetGeometryType()) == ogr.wkbMultiPoint:
                        parts = [g.GetGeometryRef(k).Clone() for k in range(g.GetGeometryCount())]
                    else:
                        parts = [g]
                    for p in parts:
                        tags = feature_tags(cls, f, p)
                        if tags is None:
                            continue
                        report[cls]["kept"] += 1
                        if cls == "LIGHTS" and ogr.GT_Flatten(p.GetGeometryType()) == ogr.wkbPoint:
                            lights.append((p.GetX(), p.GetY(), tags, f))
                            continue
                        if cls in MARKS and ogr.GT_Flatten(p.GetGeometryType()) == ogr.wkbPoint:
                            marks.append([p.GetX(), p.GetY(), tags, cls])
                            continue
                        if cls == "DAYMAR":
                            continue
                        report[cls]["written"] += osm.add(p, tags)
                        report[cls]["tags"].update(f"{k}={v}" if k in ("seamark:type", "contour", "contourarea",
                                                                         "contourtype", "areatype", "point") or
                                                   k.endswith((":category", ":colour", ":shape", ":restriction",
                                                               ":water_level", ":character")) else k
                                                   for k, v in tags.items())
        if band_cov is not None:
            covered = band_cov if covered is None else covered.Union(band_cov)

    # A light within about 2 m of a mark goes on the mark's node; the rest stand alone
    m_per_deg = 111320.0
    for x, y, ltags, f in lights:
        best = None
        for mk in marks:
            d = math.hypot((mk[0] - x) * m_per_deg * math.cos(math.radians(y)), (mk[1] - y) * m_per_deg)
            if d < 2 and (best is None or d < best[0]):
                best = (d, mk)
        if best:
            best[1][2].update({k: v for k, v in light_tags(f).items()})
            report["LIGHTS"]["tags"]["(merged into a mark)"] += 1
        else:
            pt = ogr.Geometry(ogr.wkbPoint)
            pt.AddPoint_2D(x, y)
            report["LIGHTS"]["written"] += osm.add(pt, ltags)
            report["LIGHTS"]["tags"].update(f"{k}={v}" if k == "seamark:type" else k for k in ltags for v in [ltags[k]])
    for x, y, tags, cls in marks:
        pt = ogr.Geometry(ogr.wkbPoint)
        pt.AddPoint_2D(x, y)
        report[cls]["written"] += osm.add(pt, tags)
        report[cls]["tags"].update(f"{k}={v}" if k == "seamark:type" or k.endswith((":category", ":colour", ":shape"))
                                   else k for k, v in tags.items())

    osm.write(a.out)
    rep = {"bbox": [w, s, e, n], "cells": sorted(cells),
           "classes": {k: {"read": v["read"], "kept": v["kept"], "written": v["written"],
                           "tags": dict(v["tags"].most_common())} for k, v in sorted(report.items())},
           "not_mapped": dict(unmapped.most_common())}
    json.dump(rep, open(a.out + ".report.json", "w"), indent=1)
    print(f"Wrote {a.out}: {len(osm.node_tags)} tagged nodes, {len(osm.ways)} ways, {len(osm.rels)} relations "
          f"from {len(cells)} cells")
    for k, v in rep["classes"].items():
        print(f"  {k:7} read {v['read']:5}  kept {v['kept']:5}  written {v['written']:5}")
    print("  not mapped:", ", ".join(f"{k} {v}" for k, v in rep["not_mapped"].items()))


if __name__ == "__main__":
    sys.exit(main())
