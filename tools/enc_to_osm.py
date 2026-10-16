#!/usr/bin/env python3
"""enc_to_osm.py
NOAA ENC (S-57) vector charts as OsmAnd maps (issue #2).

  python3 tools/enc_to_osm.py -r 04 -o out/Ncds_04                     (an NCDS region)
  python3 tools/enc_to_osm.py -b=-76.50,39.20,-76.33,39.37 -o out/Test  (a box)

Reads every active ENC cell meeting the area from NOAA's catalog (GDAL applies the
update files, so Notice to Mariners corrections are in), keeps each object from the
most detailed chart that covers it (as the route packs do), and writes OSM XML with the
tags OsmAnd keeps in an .obf (obf_creation/rendering_types.xml) and draws with its
Nautical and Marine styles. Five files, built into two maps by
.github/workflows/enc-vector-poc.yml, like OsmAnd's own depth maps:

  <out>_contours.osm       depth contours and depth areas    } merged into
  <out>_soundings_z11.osm  soundings from coastal charts      } Depth_<name>.depth.obf,
  <out>_soundings_z13.osm  ... from approach charts           } one map section each,
  <out>_soundings_z15.osm  ... from harbor charts             } shown from zoom 11/13/15
  <out>_seamarks.osm       marks, lights, hazards, piers, bridges, cables, channels and
                           areas: <name>_seamarks.obf

Each sounding set is what a chart of that scale shows: the most detailed chart up to
band 3 (coastal), 4 (approach) or any band; storing every sounding at every zoom made
low zooms heavy. A cell's coverage is its panel polygons in NOAA's catalog, indexed so
an object is only cut against the more detailed cells that overlap it.

Also writes <out>.report.json: per S-57 class, objects read, kept and written, and their
tags, so tools/obf_compare.py can check them against the .obf.
Depths in labels are whole feet (US charts).
"""

import argparse
import collections
import importlib.util
import json
import math
import os
import sys
import time
import xml.etree.ElementTree as ET
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
    """Whole feet, as NOAA charts print them: NOAA stores feet rounded to 0.1 m, so the
    6 ft line is 1.8 m (5.9 ft) and a 10 ft sounding 3.0 m (9.8 ft). Under a foot, tenths."""
    f = m / FT
    if abs(f) < 0.05:
        return "0"
    return f"{f:.0f}" if abs(f) >= 0.95 else f"{f:.1f}"


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
            t["depth"] = feet(d)  # OsmAnd prints it beside the symbol
        return t
    if cls == "UWTROC":
        t = {"seamark:type": "rock"}
        w = WATLEV.get(str(val(f, "WATLEV")))
        if w:
            t["seamark:rock:water_level"] = w
        d = val(f, "VALSOU")
        if d is not None:
            t["depth"] = feet(d)  # OsmAnd prints it beside the symbol
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
            t["depth"] = feet(d)  # OsmAnd prints it beside the symbol
        return t
    if cls == "PILPNT":
        return {"seamark:type": "pile"}
    if cls == "SOUNDG":
        z = geom.GetZ() if geom.GetCoordinateDimension() == 3 else val(f, "DEPTH")
        if z is None:
            return None
        # One label: OsmAnd prints every text tag (a meters depth tag gave "9.8 / 3.0")
        return {"point": "depth", "name": feet(z)}
    if cls == "DEPCNT":
        v = val(f, "VALDCO")
        if v is None:
            return None
        # name and depth both carry the label, as in OsmAnd's own depth maps
        return {"contour": "depth", "contourtype": contourtype(v), "depth": feet(v), "name": feet(v)}
    if cls == "DEPARE":
        a = areatype(val(f, "DRVAL1"))
        if a is None:
            return None
        return {"contourarea": "depth", "areatype": a}
    if cls == "DRGARE":
        # OsmAnd has no dredged area type: its Nautical style draws fairways (tinted, outlined,
        # name along the edge), so a dredged channel is a fairway named with its depth. The
        # depth area tags stay for OsmAnd versions that draw depth areas.
        d1 = val(f, "DRVAL1")
        t = {"seamark:type": "fairway"}
        label = f"Dredged {feet(d1)} ft" if d1 is not None else "Dredged area"
        n = val(f, "OBJNAM")
        t["name"] = t["seamark:name"] = f"{n}, {label.lower()}" if n else label
        a = areatype(d1)
        if a is not None:
            t.update({"contourarea": "depth", "areatype": a})
        return t
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
    """Streams OSM XML: points as nodes, lines as ways, polygons as closed ways (or
    multipolygon relations when they have holes). Nodes, ways and relations go to
    separate temporary files joined at the end (OSM order), so a region does not have
    to fit in memory."""

    def __init__(self, path):
        self.path = path
        self.parts = [open(path + k, "w") for k in (".nodes", ".ways", ".rels")]
        self.next = -1
        self.count = 0

    def _id(self):
        i = self.next
        self.next -= 1
        return i

    @staticmethod
    def _tags(tags):
        return "".join(f"<tag k={quoteattr(k)} v={quoteattr(str(v))}/>" for k, v in tags.items() if v is not None)

    def node(self, x, y, tags=None):
        i = self._id()
        t = self._tags(tags) if tags else ""
        self.parts[0].write(f'<node id="{i}" version="1" lat="{y:.7f}" lon="{x:.7f}"' +
                            (f">{t}</node>\n" if t else "/>\n"))
        return i

    def way(self, pts, tags):
        pts = [p for k, p in enumerate(pts) if k == 0 or p[:2] != pts[k - 1][:2]]
        if len(pts) < 2:
            return None
        closed = len(pts) > 2 and pts[0][:2] == pts[-1][:2]
        refs = [self.node(p[0], p[1]) for p in (pts[:-1] if closed else pts)]
        if closed:
            refs.append(refs[0])
        i = self._id()
        nd = "".join(f'<nd ref="{r}"/>' for r in refs)
        self.parts[1].write(f'<way id="{i}" version="1">{nd}{self._tags(tags)}</way>\n')
        return i

    def add(self, geom, tags):
        """Write one geometry with tags; returns the number of OSM objects written."""
        gt = ogr.GT_Flatten(geom.GetGeometryType())
        n = 0
        if gt == ogr.wkbPoint:
            self.node(geom.GetX(), geom.GetY(), tags)
            n = 1
        elif gt in (ogr.wkbMultiPoint, ogr.wkbMultiLineString, ogr.wkbMultiPolygon, ogr.wkbGeometryCollection):
            return sum(self.add(geom.GetGeometryRef(k), tags) for k in range(geom.GetGeometryCount()))
        elif gt == ogr.wkbLineString:
            n = 1 if self.way(geom.GetPoints() or [], tags) else 0
        elif gt == ogr.wkbPolygon:
            rings = [geom.GetGeometryRef(k).GetPoints() or [] for k in range(geom.GetGeometryCount())]
            if len(rings) == 1:
                n = 1 if self.way(rings[0], tags) else 0
            else:
                members = [(self.way(r, {}), "outer" if k == 0 else "inner") for k, r in enumerate(rings)]
                mm = "".join(f'<member type="way" ref="{w}" role="{role}"/>' for w, role in members if w)
                tg = self._tags(dict(tags, type="multipolygon"))
                self.parts[2].write(f'<relation id="{self._id()}" version="1">{mm}{tg}</relation>\n')
                n = 1
        self.count += n
        return n

    def close(self):
        for f in self.parts:
            f.close()
        with open(self.path, "w") as out:
            out.write('<?xml version="1.0" encoding="UTF-8"?>\n<osm version="0.6" generator="enc_to_osm.py">\n')
            for k in (".nodes", ".ways", ".rels"):
                with open(self.path + k) as part:
                    while True:
                        chunk = part.read(1 << 20)
                        if not chunk:
                            break
                        out.write(chunk)
                os.remove(self.path + k)
            out.write("</osm>\n")


class Coverage:
    """Each cell's coverage (its panel polygons in NOAA's ENC catalog), in a 0.25 degree
    grid so an object only meets the cells near it."""
    STEP = 0.25

    def __init__(self, catalog_path, names):
        self.cells = {}  # name -> (band, polygon, envelope)
        self.grid = collections.defaultdict(list)
        for c in ET.parse(catalog_path).getroot().iter("cell"):
            name = c.findtext("name")
            if name not in names or c.findtext("status") != "Active":
                continue
            mp = ogr.Geometry(ogr.wkbMultiPolygon)
            for panel in c.iter("panel"):
                ring = ogr.Geometry(ogr.wkbLinearRing)
                pts = []
                for v in panel.iter("vertex"):
                    lon, lat = float(v.findtext("long")), float(v.findtext("lat"))
                    pts.append((lon + 360 if lon < -180 else lon, lat))
                if len(pts) < 3:
                    continue
                for x, y in pts + [pts[0]]:
                    ring.AddPoint_2D(x, y)
                poly = ogr.Geometry(ogr.wkbPolygon)
                poly.AddGeometry(ring)
                mp.AddGeometry(poly)
            if mp.GetGeometryCount() == 0:
                continue
            g = mp.MakeValid() if not mp.IsValid() else mp
            env = g.GetEnvelope()  # minx, maxx, miny, maxy
            self.cells[name] = (int(name[2]), g, env)
            for gx in range(int(math.floor(env[0] / self.STEP)), int(math.floor(env[1] / self.STEP)) + 1):
                for gy in range(int(math.floor(env[2] / self.STEP)), int(math.floor(env[3] / self.STEP)) + 1):
                    self.grid[(gx, gy)].append(name)

    def better(self, env, band, cap=99):
        """Polygons of cells more detailed than band (up to cap) whose envelopes meet env."""
        seen, out = set(), []
        for gx in range(int(math.floor(env[0] / self.STEP)), int(math.floor(env[1] / self.STEP)) + 1):
            for gy in range(int(math.floor(env[2] / self.STEP)), int(math.floor(env[3] / self.STEP)) + 1):
                for name in self.grid.get((gx, gy), ()):
                    if name in seen:
                        continue
                    seen.add(name)
                    b, g, e = self.cells[name]
                    if band < b <= cap and e[0] <= env[1] and e[1] >= env[0] and e[2] <= env[3] and e[3] >= env[2]:
                        out.append(g)
        return out

    def keep(self, geom, band, cap=99):
        """The part of geom that no more detailed chart (up to cap) covers, or None."""
        cov = self.better(geom.GetEnvelope(), band, cap)
        if not cov:
            return geom
        if ogr.GT_Flatten(geom.GetGeometryType()) == ogr.wkbPoint:
            return None if any(c.Contains(geom) for c in cov) else geom
        for c in cov:
            geom = geom.Difference(c)
            if geom is None or geom.IsEmpty():
                return None
        return geom


# Outputs, and which classes go where
DEPTH_CLASSES = {"DEPCNT", "DEPARE"}
SOUNDING_SETS = (("z11", 3), ("z13", 4), ("z15", 99))  # (file suffix, most detailed band)
# Areas OsmAnd outlines (or labels) are merged across chart cells: charts cut areas at
# each cell's edge, and the edges showed as straight lines. Depth areas are fill only
# (and not drawn by released OsmAnd), so they are not merged.
MERGED = {"SBDARE", "DRGARE", "FAIRWY", "RESARE", "CBLARE", "PIPARE", "ACHARE", "SEAARE", "MARCUL"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-r", "--region", help="NCDS region code, e.g. 04")
    ap.add_argument("-b", "--bbox", help="W,S,E,N instead of a region")
    ap.add_argument("-o", "--out", required=True, help="output prefix, e.g. out/Ncds_04")
    ap.add_argument("-w", "--work", default="enc-work")
    a = ap.parse_args()
    os.makedirs(a.work, exist_ok=True)
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    enc = brp.enc_catalog(a.work)
    if a.region:
        areas = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "areas.json")))["areas"]
        area = next((x for x in areas if x.get("ncds") == a.region), None) or sys.exit(f"No region {a.region} in areas.json")
        w, s, e, n = brp.region_bbox(area, enc)
    elif a.bbox:
        w, s, e, n = map(float, a.bbox.split(","))
    else:
        sys.exit("Give -r REGION or -b W,S,E,N")
    box = ogr.CreateGeometryFromWkt(f"POLYGON(({w} {s},{e} {s},{e} {n},{w} {n},{w} {s}))")
    t0 = time.time()

    cells = brp.fetch_cells([c for c in enc if brp.meets(c["bbox"], w, s, e, n)], a.work)
    cov = Coverage(os.path.join(a.work, "ENCProdCat.xml"), set(cells))
    print(f"Area {[round(x, 3) for x in (w, s, e, n)]}: {len(cells)} cells", flush=True)

    out = {"contours": OsmWriter(a.out + "_contours.osm"), "seamarks": OsmWriter(a.out + "_seamarks.osm")}
    for suffix, _ in SOUNDING_SETS:
        out["soundings_" + suffix] = OsmWriter(a.out + f"_soundings_{suffix}.osm")
    report = collections.defaultdict(lambda: {"read": 0, "kept": 0, "written": 0, "tags": collections.Counter()})
    unmapped = collections.Counter()
    lights, marks = [], []  # lights are merged into the mark they stand on (OpenSeaMap tagging)
    areas_by_tags = collections.OrderedDict()

    def count_tags(cls, tags):
        report[cls]["tags"].update(f"{k}={v}" if k in ("seamark:type", "contour", "contourarea", "contourtype",
                                                        "areatype", "point") or
                                   k.endswith((":category", ":colour", ":shape", ":restriction",
                                               ":water_level", ":character")) else k
                                   for k, v in tags.items())

    for k, (name, path) in enumerate(sorted(cells.items())):
        band = int(name[2])
        if name not in cov.cells:
            print(f"  {name}: no coverage in the catalog, skipped")
            continue
        ds = ogr.Open(path)
        for li in range(ds.GetLayerCount()):
            lyr = ds.GetLayer(li)
            cls = lyr.GetName()
            if cls not in LAYERS or cls == "M_COVR":
                if cls not in ("DSID", "M_COVR"):
                    unmapped[cls] += lyr.GetFeatureCount()
                continue
            lyr.SetSpatialFilter(box)
            for f in lyr:
                g = f.GetGeometryRef()
                if g is None or g.IsEmpty():
                    continue
                g = g.Clone()
                report[cls]["read"] += 1
                if not box.Contains(g):
                    g = g.Intersection(box)
                    if g is None or g.IsEmpty():
                        continue
                if cls == "SOUNDG":
                    pts = [g.GetGeometryRef(j).Clone() for j in range(g.GetGeometryCount())] \
                        if ogr.GT_Flatten(g.GetGeometryType()) == ogr.wkbMultiPoint else [g]
                    for p in pts:
                        tags = feature_tags(cls, f, p)
                        if tags is None:
                            continue
                        flat = ogr.Geometry(ogr.wkbPoint)
                        flat.AddPoint_2D(p.GetX(), p.GetY())
                        for suffix, cap in SOUNDING_SETS:
                            if band <= cap and cov.keep(flat, band, cap) is not None:
                                report[cls]["written"] += out["soundings_" + suffix].add(flat, tags)
                        report[cls]["kept"] += 1
                    continue
                g = cov.keep(g, band)
                if g is None:
                    continue
                tags = feature_tags(cls, f, g)
                if tags is None:
                    continue
                report[cls]["kept"] += 1
                point = ogr.GT_Flatten(g.GetGeometryType()) == ogr.wkbPoint
                if cls == "LIGHTS" and point:
                    lights.append((g.GetX(), g.GetY(), tags, light_tags(f)))
                    continue
                if cls in MARKS and point:
                    marks.append([g.GetX(), g.GetY(), tags, cls])
                    continue
                if cls == "DAYMAR":
                    continue
                if cls in MERGED and ogr.GT_Flatten(g.GetGeometryType()) in (ogr.wkbPolygon, ogr.wkbMultiPolygon):
                    areas_by_tags.setdefault((cls, tuple(sorted(tags.items()))), []).append(g)
                    continue
                dest = out["contours"] if cls in DEPTH_CLASSES else out["seamarks"]
                report[cls]["written"] += dest.add(g, tags)
                count_tags(cls, tags)
        ds = None
        if (k + 1) % 25 == 0 or k + 1 == len(cells):
            print(f"  {k + 1}/{len(cells)} cells, {time.time() - t0:.0f} s", flush=True)

    merged_from = 0
    for (cls, tagitems), parts in areas_by_tags.items():
        if len(parts) == 1:
            u = parts[0]
        else:
            mp = ogr.Geometry(ogr.wkbMultiPolygon)
            for g in parts:
                for j in (range(g.GetGeometryCount()) if ogr.GT_Flatten(g.GetGeometryType()) == ogr.wkbMultiPolygon
                          else [None]):
                    mp.AddGeometry(g if j is None else g.GetGeometryRef(j))
            u = mp.UnionCascaded()
        merged_from += len(parts)
        report[cls]["written"] += out["seamarks"].add(u, dict(tagitems))
        count_tags(cls, dict(tagitems))
    print(f"Areas: {merged_from} pieces merged into {len(areas_by_tags)} tag groups across cells")

    # A light within about 2 m of a mark goes on the mark's node (marks bucketed by
    # about 100 m); the rest stand alone
    bucket = collections.defaultdict(list)
    for mk in marks:
        bucket[(round(mk[0] * 1000), round(mk[1] * 1000))].append(mk)
    for x, y, ltags, lattrs in lights:
        best = None
        bx, by = round(x * 1000), round(y * 1000)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for mk in bucket.get((bx + dx, by + dy), ()):
                    d = math.hypot((mk[0] - x) * 111320.0 * math.cos(math.radians(y)), (mk[1] - y) * 111320.0)
                    if d < 2 and (best is None or d < best[0]):
                        best = (d, mk)
        if best:
            best[1][2].update(lattrs)
            report["LIGHTS"]["tags"]["(merged into a mark)"] += 1
        else:
            pt = ogr.Geometry(ogr.wkbPoint)
            pt.AddPoint_2D(x, y)
            report["LIGHTS"]["written"] += out["seamarks"].add(pt, ltags)
            count_tags("LIGHTS", ltags)
    for x, y, tags, cls in marks:
        pt = ogr.Geometry(ogr.wkbPoint)
        pt.AddPoint_2D(x, y)
        report[cls]["written"] += out["seamarks"].add(pt, tags)
        count_tags(cls, tags)

    for wtr in out.values():
        wtr.close()
    rep = {"area": [w, s, e, n], "cells": sorted(cells), "seconds": round(time.time() - t0),
           "files": {k: {"path": v.path, "objects": v.count} for k, v in out.items()},
           "classes": {k: {"read": v["read"], "kept": v["kept"], "written": v["written"],
                           "tags": dict(v["tags"].most_common())} for k, v in sorted(report.items())},
           "not_mapped": dict(unmapped.most_common())}
    json.dump(rep, open(a.out + ".report.json", "w"), indent=1)
    print(f"Done in {time.time() - t0:.0f} s from {len(cells)} cells:")
    for k, v in out.items():
        print(f"  {v.path}: {v.count} objects")
    for k, v in rep["classes"].items():
        print(f"  {k:7} read {v['read']:7}  kept {v['kept']:7}  written {v['written']:7}")
    print("  not mapped:", ", ".join(f"{k} {v}" for k, v in rep["not_mapped"].items()))


if __name__ == "__main__":
    sys.exit(main())
