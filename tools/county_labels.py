#!/usr/bin/env python3
"""County and state lines with their names, for the base map (tools/build_basemap.sh).

The Protomaps boundaries layer has no names, and its county lines can sit a few hundred
meters from the Census lines (Harford/Baltimore County in the Gunpowder River), so names
placed from one source on lines from the other landed on the wrong side. Both now come
from the Census TIGER/Line county file (public domain; its polygons run out into the
water to the legal county limits, unlike the shoreline-clipped cartographic files):

  lines:  {"state": 0|1}                  every line between two counties (1 = state line)
  labels: {"a": "Harford County", "b": "Baltimore County", "rot": 33.0, "rank": 0-3}

Neighboring TIGER counties share exact vertices, so a line between two counties is the
run of ring segments both have. A shapefile ring keeps its polygon on the right, so the
county on each side is exact. A label point lies on the line every STEP_M; the app
draws `a` above and `b` below, rotated by `rot` (MapLibre text-rotate, degrees
clockwise, reading along the line), so the line runs through the gap between them. Across
a state line each name gets its state (Kent County, MD). The rank sets how close in
the label shows (RANKS; ChartTiles shows rank r from about zoom 11.3 + r, spacing labels
about 250 px apart, and lets them overlap the chart's soundings, which are drawn on top),
so a line has a label on screen at any zoom. Pure Python (no GDAL), so it also runs on the phone. Output is
newline-delimited GeoJSON features for tippecanoe.

  tools/county_labels.py <tl_YYYY_us_county.zip> <west,south,east,north> > counties.geojsonl
"""
import json, math, struct, sys, zipfile

STEP_M = 187.5          # between label points along a line; each rank shows from a zoom:
RANKS = (64, 32, 16, 8, 4, 2)  # rank 0 every 64 steps (12 km), 1 every 32 (6 km), ... 5 every 2, 6 the rest
MIN_LINE_M = 300.0      # shorter lines get no label
TANGENT_M = 200.0       # the line's direction is taken over +-this along it
PAD_DEG = 0.05          # lines are kept this far outside the box (labels only inside)

STATES = {"01": "AL", "02": "AK", "04": "AZ", "05": "AR", "06": "CA", "08": "CO", "09": "CT", "10": "DE",
          "11": "DC", "12": "FL", "13": "GA", "15": "HI", "16": "ID", "17": "IL", "18": "IN", "19": "IA",
          "20": "KS", "21": "KY", "22": "LA", "23": "ME", "24": "MD", "25": "MA", "26": "MI", "27": "MN",
          "28": "MS", "29": "MO", "30": "MT", "31": "NE", "32": "NV", "33": "NH", "34": "NJ", "35": "NM",
          "36": "NY", "37": "NC", "38": "ND", "39": "OH", "40": "OK", "41": "OR", "42": "PA", "44": "RI",
          "45": "SC", "46": "SD", "47": "TN", "48": "TX", "49": "UT", "50": "VT", "51": "VA", "53": "WA",
          "54": "WV", "55": "WI", "56": "WY", "60": "AS", "66": "GU", "69": "MP", "72": "PR", "78": "VI"}


def read_dbf(b):
    n, hlen, rlen = struct.unpack_from("<IHH", b, 4)
    fields, o = [], 32
    while b[o] != 0x0D:
        fields.append((b[o:o + 11].split(b"\0")[0].decode(), b[o + 16]))
        o += 32
    rows = []
    for i in range(n):
        r, p, row = hlen + i * rlen + 1, 0, {}
        for name, ln in fields:
            row[name] = b[r + p:r + p + ln].decode("latin-1").strip()
            p += ln
        rows.append(row)
    return rows


def read_shp(b, keep):
    """Polygon rings [(lon, lat), ...] per record, or None for records whose box misses `keep`."""
    out, o = [], 100
    w, s, e, n = keep
    while o < len(b):
        clen = struct.unpack_from(">i", b, o + 4)[0] * 2
        r = o + 8
        o = r + clen
        if struct.unpack_from("<i", b, r)[0] != 5:
            out.append(None)
            continue
        x0, y0, x1, y1 = struct.unpack_from("<4d", b, r + 4)
        if x1 < w or x0 > e or y1 < s or y0 > n:
            out.append(None)
            continue
        nparts, npts = struct.unpack_from("<2i", b, r + 36)
        parts = list(struct.unpack_from(f"<{nparts}i", b, r + 44)) + [npts]
        pts = struct.unpack_from(f"<{2 * npts}d", b, r + 44 + 4 * nparts)
        out.append([[(pts[2 * k], pts[2 * k + 1]) for k in range(parts[j], parts[j + 1])] for j in range(nparts)])
    return out


def runs(counties, near):
    """Lines between two counties: (right county, left county, [points]) from the lower GEOID's rings."""
    owners = {}
    for c in counties:
        for ring in c["rings"]:
            for i in range(1, len(ring)):
                p, q = ring[i - 1], ring[i]
                owners.setdefault((p, q) if p < q else (q, p), []).append(c)
    for c in counties:
        for ring in c["rings"]:
            # The county beside each segment, None where it is not a shared line we keep
            other = []
            for i in range(1, len(ring)):
                p, q = ring[i - 1], ring[i]
                os_ = [o for o in owners[(p, q) if p < q else (q, p)] if o is not c]
                o = os_[0] if len(os_) == 1 else None
                other.append(o if o is not None and c["id"] < o["id"] and (near(p) or near(q)) else None)
            if not any(other):
                continue
            # Start where the neighbor changes, so a line across the ring's start is one run
            n = len(other)
            start = next((i for i in range(n) if other[i] is not other[i - 1]), 0)
            cur, pts = None, []
            for k in range(n):
                i = (start + k) % n
                if other[i] is not cur:
                    if cur is not None:
                        yield c, cur, pts
                    cur, pts = other[i], [ring[i]]
                if cur is not None:
                    pts.append(ring[i + 1])
            if cur is not None:
                yield c, cur, pts


def main():
    zpath, bbox = sys.argv[1], [float(v) for v in sys.argv[2].split(",")]
    w, s, e, n = bbox
    with zipfile.ZipFile(zpath) as z:
        names = z.namelist()
        shp = z.read(next(x for x in names if x.endswith(".shp")))
        dbf = z.read(next(x for x in names if x.endswith(".dbf")))
    counties = []
    for row, rings in zip(read_dbf(dbf), read_shp(shp, (w - PAD_DEG, s - PAD_DEG, e + PAD_DEG, n + PAD_DEG))):
        if rings is None:
            continue
        name = row["NAMELSAD"]
        if name.endswith(" city"):
            name = name[:-5] + " City"
        counties.append({"id": row["GEOID"], "st": row["STATEFP"], "name": name, "rings": rings})
    del shp, dbf
    print(f"{len(counties)} counties", file=sys.stderr)

    def near(p):
        return w - PAD_DEG <= p[0] <= e + PAD_DEG and s - PAD_DEG <= p[1] <= n + PAD_DEG

    def out(geom, props):
        print(json.dumps({"type": "Feature", "geometry": geom, "properties": props}, separators=(",", ":")))

    nlines = nlabels = 0
    for right, left, pts in runs(counties, near):
        state = right["st"] != left["st"]
        out({"type": "LineString", "coordinates": [[round(x, 6), round(y, 6)] for x, y in pts]}, {"state": int(state)})
        nlines += 1
        ky = 111320.0
        kx = ky * math.cos(math.radians(pts[0][1]))
        cum = [0.0]
        for i in range(1, len(pts)):
            cum.append(cum[-1] + math.hypot((pts[i][0] - pts[i - 1][0]) * kx, (pts[i][1] - pts[i - 1][1]) * ky))
        total = cum[-1]
        if total < MIN_LINE_M:
            continue

        def at(t):
            t = min(max(t, 0.0), total)
            lo, hi = 0, len(cum) - 1
            while hi - lo > 1:
                mid = (lo + hi) // 2
                if cum[mid] <= t:
                    lo = mid
                else:
                    hi = mid
            f = (t - cum[lo]) / ((cum[hi] - cum[lo]) or 1.0)
            (xa, ya), (xb, yb) = pts[lo], pts[hi]
            return xa + (xb - xa) * f, ya + (yb - ya) * f

        a_name, b_name = right["name"], left["name"]
        if state:
            a_name += ", " + STATES.get(right["st"], right["st"])
            b_name += ", " + STATES.get(left["st"], left["st"])
        t = min(STEP_M / 2, total / 2)
        k = 0
        while t < total:
            here, t, k = t, t + STEP_M, k + 1
            x, y = at(here)
            if not (w <= x <= e and s <= y <= n):
                continue
            (xa, ya), (xb, yb) = at(here - TANGENT_M), at(here + TANGENT_M)
            dx, dy = (xb - xa) * kx, (yb - ya) * ky
            if dx == 0 and dy == 0:
                continue
            travel = math.degrees(math.atan2(dx, dy))   # bearing along the ring
            rot = travel - 90.0
            while rot > 90:
                rot -= 180
            while rot < -90:
                rot += 180
            # The text's top faces bearing rot; the ring's own county is on its right
            top_is_right = math.cos(math.radians(rot - (travel + 90))) > 0
            a, b = (a_name, b_name) if top_is_right else (b_name, a_name)
            out({"type": "Point", "coordinates": [round(x, 6), round(y, 6)]},
                {"a": a, "b": b, "rot": round(rot, 1), "rank": next((r for r, m in enumerate(RANKS) if (k - 1) % m == 0), len(RANKS))})
            nlabels += 1
    print(f"{nlines} lines, {nlabels} labels", file=sys.stderr)


if __name__ == "__main__":
    main()
