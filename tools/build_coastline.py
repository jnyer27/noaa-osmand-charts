#!/usr/bin/env python3
"""build_coastline.py
Make the small built-in coastline the NOAA Route app draws under the NCDS
region map (android/app/src/main/assets/coastline.bin).

  python3 tools/build_coastline.py ne_10m_land.geojson ne_10m_lakes.geojson

Inputs are Natural Earth 1:10m land and lakes (public domain,
https://www.naturalearthdata.com/, GeoJSON copies at
https://github.com/nvkelso/natural-earth-vector/tree/master/geojson).
Land is clipped to boxes around NOAA's chart regions and simplified to about
300 m; the Great Lakes and a few other large lakes come out as water drawn
over it (Natural Earth's land layer has no holes for them).

File layout (little endian): "NRCL1\\n", u32 ring count, then per ring:
u8 kind (0 land, 1 water), u32 point count, that many (i32 lon, i32 lat) in
1e-5 degree units.
"""

import json
import struct
import sys

# Boxes (W, S, E, N) covering every NCDS region with a margin; land outside them is dropped
BOXES = [
    (-98.8, 8.1, -64.4, 49.9),      # Atlantic, Gulf, Caribbean, Great Lakes (01-18)
    (-145.0, 14.5, -113.4, 61.0),   # West coast and offshore (19a-20c)
    (-153.3, 44.2, -129.4, 62.1),   # Southeast Alaska and the Gulf of Alaska (21-24b)
    (-180.0, 44.2, -152.0, 62.1),   # Western Alaska and the Aleutians (25a-26b)
    (-180.0, 61.0, -133.5, 82.0),   # Arctic Alaska (27)
    (-180.0, -18.0, -143.5, 45.2),  # Hawaii and the central Pacific (28a-29)
    (130.5, 0.0, 180.0, 68.5),      # Guam, the Marianas and the western Aleutians (30-31b)
]
LAKES = {"Lake Superior", "Lake Michigan", "Lake Huron", "Lake Erie", "Lake Ontario", "Lake Champlain",
         "Lake of the Woods", "Lake St. Clair", "Lake Okeechobee", "Lake Pontchartrain"}
TOLERANCE = 0.003  # degrees, about 300 m
MIN_AREA = 0.0004  # square degrees; smaller islands are dropped


def rings_of(geom):
    if geom["type"] == "Polygon":
        return geom["coordinates"]
    if geom["type"] == "MultiPolygon":
        return [r for poly in geom["coordinates"] for r in poly]
    return []


def clip(ring, box):
    """Sutherland-Hodgman clip of a closed ring to an axis-aligned box."""
    w, s, e, n = box
    pts = [tuple(p[:2]) for p in ring]
    for inside, cut in (
        (lambda p: p[0] >= w, lambda a, b: (w, a[1] + (b[1] - a[1]) * (w - a[0]) / (b[0] - a[0]))),
        (lambda p: p[0] <= e, lambda a, b: (e, a[1] + (b[1] - a[1]) * (e - a[0]) / (b[0] - a[0]))),
        (lambda p: p[1] >= s, lambda a, b: (a[0] + (b[0] - a[0]) * (s - a[1]) / (b[1] - a[1]), s)),
        (lambda p: p[1] <= n, lambda a, b: (a[0] + (b[0] - a[0]) * (n - a[1]) / (b[1] - a[1]), n)),
    ):
        if not pts:
            break
        out = []
        for i, b in enumerate(pts):
            a = pts[i - 1]
            if inside(b):
                if not inside(a):
                    out.append(cut(a, b))
                out.append(b)
            elif inside(a):
                out.append(cut(a, b))
        pts = out
    return pts


def simplify(pts, tol):
    """Douglas-Peucker on an open polyline (iterative)."""
    if len(pts) < 3:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        i, j = stack.pop()
        (ax, ay), (bx, by) = pts[i], pts[j]
        dx, dy = bx - ax, by - ay
        L2 = dx * dx + dy * dy
        best, k = -1.0, -1
        for m in range(i + 1, j):
            px, py = pts[m]
            if L2 == 0:
                d = (px - ax) ** 2 + (py - ay) ** 2
            else:
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L2))
                d = (px - ax - t * dx) ** 2 + (py - ay - t * dy) ** 2
            if d > best:
                best, k = d, m
        if best > tol * tol:
            keep[k] = True
            stack += [(i, k), (k, j)]
    return [p for p, kp in zip(pts, keep) if kp]


def area(pts):
    return abs(sum(pts[i - 1][0] * p[1] - p[0] * pts[i - 1][1] for i, p in enumerate(pts))) / 2


def main():
    land_path, lakes_path = sys.argv[1], sys.argv[2]
    out = []
    for kind, path, keep in ((0, land_path, lambda f: True),
                             (1, lakes_path, lambda f: (f["properties"].get("name") or "") in LAKES)):
        for f in json.load(open(path))["features"]:
            if not keep(f):
                continue
            for ring in rings_of(f["geometry"]):
                for box in BOXES:
                    pts = clip(ring, box)
                    if len(pts) < 4 or area(pts) < MIN_AREA:
                        continue
                    pts = simplify(pts + [pts[0]], TOLERANCE)[:-1]
                    if len(pts) >= 3:
                        out.append((kind, pts))
    path = "android/app/src/main/assets/coastline.bin"
    with open(path, "wb") as fh:
        fh.write(b"NRCL1\n")
        fh.write(struct.pack("<I", len(out)))
        for kind, pts in out:
            fh.write(struct.pack("<BI", kind, len(pts)))
            fh.write(b"".join(struct.pack("<ii", round(x * 1e5), round(y * 1e5)) for x, y in pts))
    npts = sum(len(p) for _, p in out)
    print(f"{path}: {len(out)} rings ({sum(1 for k, _ in out if k)} water), {npts} points, {8 * npts / 1e3:.0f} KB")


if __name__ == "__main__":
    main()
