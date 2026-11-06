#!/usr/bin/env python3
"""Search rows for the app from an OpenStreetMap base map (tools/build_basemap.sh).

Reads `tippecanoe-decode` output (one feature per line) of the places, pois and roads
layers at one zoom on stdin and writes a JSON array of [name, kind, lat, lon] rows:
towns and neighborhoods (kind from kind_detail), boating places (the poi kind) and
named roads, one row per road name every ~2 km (ROAD_CELL degrees), at a vertex of
the road. MapSearch.kt reads them from the PMTiles metadata `search` key.

    tippecanoe-decode -Z 14 -z 14 -l places -l pois -l roads base.pmtiles |
        python3 tools/basemap_search.py > search.json
"""
import json
import sys

ROAD_CELL = 0.02
PLACE_KINDS = {"city", "town", "village", "hamlet", "suburb", "neighbourhood", "quarter"}
ROAD_KINDS = {"highway", "major_road", "minor_road"}


def first_point(geom):
    """A point of the geometry: the point itself, or a middle vertex of a line."""
    c = geom.get("coordinates")
    while isinstance(c, list) and c and isinstance(c[0], list):
        c = c[len(c) // 2]
    if isinstance(c, list) and len(c) >= 2 and all(isinstance(x, (int, float)) for x in c[:2]):
        return c[0], c[1]
    return None


def main():
    rows = {}
    layer = None
    for line in sys.stdin:
        s = line.strip().lstrip(",").strip().rstrip(",")
        if s.startswith('{ "type": "FeatureCollection", "properties": { "layer"'):
            try:
                layer = json.loads(s + "]}")["properties"]["layer"]
            except Exception:
                layer = s.split('"layer": "', 1)[1].split('"', 1)[0]
            continue
        if not s.startswith('{ "type": "Feature"'):
            continue
        try:
            f = json.loads(s)
        except ValueError:
            continue
        p = f.get("properties") or {}
        name = (p.get("name") or "").strip()
        if not name:
            continue
        pt = first_point(f.get("geometry") or {})
        if pt is None:
            continue
        lon, lat = pt
        if layer == "places":
            kind = p.get("kind_detail")
            if kind not in PLACE_KINDS:
                continue
            key = (name, kind, round(lat, 2), round(lon, 2))
        elif layer == "pois":
            kind = p.get("kind") or "poi"
            key = (name, kind, round(lat, 3), round(lon, 3))
        elif layer == "roads":
            if p.get("kind") not in ROAD_KINDS:
                continue
            kind = "road"
            key = (name, kind, round(lat / ROAD_CELL), round(lon / ROAD_CELL))
        else:
            continue
        if key not in rows:
            rows[key] = [name, kind, round(lat, 5), round(lon, 5)]
    out = sorted(rows.values())
    json.dump(out, sys.stdout, separators=(",", ":"), ensure_ascii=False)
    kinds = {}
    for r in out:
        kinds[r[1]] = kinds.get(r[1], 0) + 1
    print(f"search rows: {len(out)} {dict(sorted(kinds.items(), key=lambda x: -x[1])[:12])}", file=sys.stderr)


if __name__ == "__main__":
    main()
