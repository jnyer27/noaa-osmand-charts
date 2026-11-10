#!/usr/bin/env python3
"""County name labels for the base map's county lines (tools/build_basemap.sh).

The Protomaps boundaries layer has no names, so the app's purple county lines could not
be labeled. This reads the Census TIGER/Line county file (public domain; its polygons
run out into the water to the legal county limits, so the Chesapeake's lines are there,
unlike the shoreline-clipped cartographic files) and writes GeoJSON points on the county
lines, one every SPACING_M along each line shared by two counties, as newline-delimited
features for tippecanoe:

  {"a": "Baltimore County", "b": "Harford County", "rot": -38.2, "rank": 1}

The app draws `a` above and `b` below, rotated by `rot` (MapLibre text-rotate, degrees
clockwise, so the text reads along the line), with the line passing between them: `a` is
the county on the text's top side. When the two counties are in different states each
name gets its state (Kent County, MD). rank 0 is every RANK0_EVERY'th point, for the
lower zooms. Pure Python (no GDAL), so it also runs on the phone.

  tools/county_labels.py <tl_YYYY_us_county.zip> <west,south,east,north> > counties.geojsonl
"""
import io, json, math, struct, sys, zipfile

SPACING_M = 1500.0      # between labels along a line
RANK0_EVERY = 4         # every 4th label (6 km) also shows at lower zooms
TANGENT_M = 200.0       # the line's direction is taken over +-this along it
SIDE_M = (40.0, 120.0)  # a side's county must be the same at both distances
PAD_DEG = 0.3           # counties this far outside the box are read, for the side test

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
        name = b[o:o + 11].split(b"\0")[0].decode()
        fields.append((name, b[o + 16]))
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
        rings = [[(pts[2 * k], pts[2 * k + 1]) for k in range(parts[j], parts[j + 1])] for j in range(nparts)]
        out.append(((x0, y0, x1, y1), rings))
    return out


def inside(rings, x, y):
    """Even-odd over every ring, so holes count."""
    c = False
    for ring in rings:
        j = len(ring) - 1
        for i in range(len(ring)):
            xi, yi = ring[i]
            xj, yj = ring[j]
            if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
                c = not c
            j = i
    return c


def main():
    zpath, bbox = sys.argv[1], [float(v) for v in sys.argv[2].split(",")]
    w, s, e, n = bbox
    with zipfile.ZipFile(zpath) as z:
        names = z.namelist()
        shp = z.read(next(x for x in names if x.endswith(".shp")))
        dbf = z.read(next(x for x in names if x.endswith(".dbf")))
    rows = read_dbf(dbf)
    geoms = read_shp(shp, (w - PAD_DEG, s - PAD_DEG, e + PAD_DEG, n + PAD_DEG))
    del shp, dbf
    counties = []
    for row, g in zip(rows, geoms):
        if g is None:
            continue
        name = row["NAMELSAD"]
        if name.endswith(" city"):
            name = name[:-5] + " City"
        counties.append({"id": row["GEOID"], "st": row["STATEFP"], "name": name, "box": g[0], "rings": g[1]})
    print(f"{len(counties)} counties", file=sys.stderr)

    def county_at(x, y):
        for c in counties:
            x0, y0, x1, y1 = c["box"]
            if x0 <= x <= x1 and y0 <= y <= y1 and inside(c["rings"], x, y):
                return c
        return None

    def side(x, y, bearing, kx, ky):
        """The county on that side of the line, when the same at both test distances."""
        got = None
        for d in SIDE_M:
            c = county_at(x + math.sin(math.radians(bearing)) * d / kx, y + math.cos(math.radians(bearing)) * d / ky)
            if c is None or (got is not None and c is not got):
                return None
            got = c
        return got

    count = 0
    for c in counties:
        for ring in c["rings"]:
            if len(ring) < 3:
                continue
            ky = 111320.0
            kx = ky * math.cos(math.radians(ring[0][1]))
            # Arc length along the closed ring
            cum = [0.0]
            for i in range(1, len(ring)):
                (xa, ya), (xb, yb) = ring[i - 1], ring[i]
                cum.append(cum[-1] + math.hypot((xb - xa) * kx, (yb - ya) * ky))
            total = cum[-1]
            if total < SPACING_M:
                continue

            def at(t):
                t %= total
                lo, hi = 0, len(cum) - 1
                while hi - lo > 1:
                    mid = (lo + hi) // 2
                    if cum[mid] <= t:
                        lo = mid
                    else:
                        hi = mid
                seg = cum[hi] - cum[lo] or 1.0
                f = (t - cum[lo]) / seg
                (xa, ya), (xb, yb) = ring[lo], ring[hi]
                return xa + (xb - xa) * f, ya + (yb - ya) * f

            k = 0
            t = SPACING_M / 2
            while t < total:
                x, y = at(t)
                here = t
                t += SPACING_M
                k += 1
                if not (w <= x <= e and s <= y <= n):
                    continue
                (xa, ya), (xb, yb) = at(here - TANGENT_M), at(here + TANGENT_M)
                dx, dy = (xb - xa) * kx, (yb - ya) * ky
                if dx == 0 and dy == 0:
                    continue
                rot = math.degrees(math.atan2(dx, dy)) - 90.0
                while rot > 90:
                    rot -= 180
                while rot < -90:
                    rot += 180
                # With text rotated by rot, its top side faces bearing rot
                top, bottom = side(x, y, rot, kx, ky), side(x, y, rot + 180, kx, ky)
                if top is None or bottom is None or top is bottom or c not in (top, bottom):
                    continue
                # Each line is in both counties' rings: write it from the lower GEOID's only
                if c["id"] != min(top["id"], bottom["id"]):
                    continue
                a, b = top["name"], bottom["name"]
                if top["st"] != bottom["st"]:
                    a += ", " + STATES.get(top["st"], top["st"])
                    b += ", " + STATES.get(bottom["st"], bottom["st"])
                print(json.dumps({"type": "Feature", "geometry": {"type": "Point", "coordinates": [round(x, 6), round(y, 6)]},
                                  "properties": {"a": a, "b": b, "rot": round(rot, 1), "rank": 0 if (k - 1) % RANK0_EVERY == 0 else 1}},
                                 separators=(",", ":")))
                count += 1
    print(f"{count} labels", file=sys.stderr)


if __name__ == "__main__":
    main()
