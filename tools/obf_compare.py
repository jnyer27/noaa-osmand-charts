#!/usr/bin/env python3
"""obf_compare.py --osm A.osm [B.osm ...] --dump A.txt [...] [--require TAG ...] [--title T]
Issue #2: which tags written by enc_to_osm.py survived into an .obf (the OSM files that
went into one .obf against its inspector dump).
Counts each key=value in the OSM file and in OsmAnd's BinaryInspector output
(inspector.sh -vmap -vmapobjects). An object can appear once per map zoom section in
the dump, so dump counts may be multiples; zero means OsmAnd dropped the tag.
OsmAnd also folds seamark:<type>:<attr> into seamark:<attr> (rendering_types.xml),
so the folded form is counted too. Prints a Markdown report.

--require: tags (key=value, or a key for any value) that must be written and kept; the
exit status is 1 if one is missing, so a build fails instead of silently losing them."""

import argparse
import collections
import re
import sys
import xml.etree.ElementTree as ET

# Tag keys whose values matter for rendering (others are counted by key only)
VALUE_KEYS = ("seamark:type", "contour", "contourarea", "contourtype", "areatype", "point")


def osm_tags(path):
    tags = collections.Counter()
    objects = collections.Counter()
    for _, el in ET.iterparse(path):
        if el.tag in ("node", "way", "relation"):
            kv = {t.get("k"): t.get("v") for t in el.findall("tag")}
            if kv:
                objects[el.tag] += 1
            for k, v in kv.items():
                tags[(k, v if k in VALUE_KEYS else None)] += 1
            el.clear()
    return tags, objects


def dump_tags(path):
    """Tags of the objects in a BinaryInspector -vmapobjects dump. Lines look like
    'Point types [seamark:type-buoy_lateral (27)] add_types [seamark:system-iala-b(14), ...]
    Names [name(1) - Back River Buoy 11] id -62964 ...': key-value pairs (keys have no
    '-'), and Names as key(n) - value. Each object is listed once per map zoom level."""
    tags, lines = collections.Counter(), 0
    for line in open(path, errors="replace"):
        if not line.startswith(("Point ", "Way ", "Polygon ", "Multipolygon ", "Relation ")):
            continue
        lines += 1
        for block in ("types", "add_types"):
            m = re.search(block + r" \[([^\]]*)\]", line)
            if m:
                for item in m.group(1).split(", "):
                    item = re.sub(r" ?\(\d+\)$", "", item.strip())
                    if "-" in item:
                        k, v = item.split("-", 1)
                        tags[(k, v if k in VALUE_KEYS else None)] += 1
        m = re.search(r"Names \[(.*?)\] id ", line)
        if m:
            for k in re.findall(r"([A-Za-z_:]+)\(\d+\) - ", m.group(1)):
                tags[(k, None)] += 1
    return tags, lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--osm", nargs="+", required=True)
    ap.add_argument("--dump", nargs="+", required=True)
    ap.add_argument("--require", nargs="*", default=[])
    ap.add_argument("--title", default="tags written vs kept in the .obf")
    a = ap.parse_args()
    tags, objects, kept, lines = collections.Counter(), collections.Counter(), collections.Counter(), 0
    for p in a.osm:
        t, o = osm_tags(p)
        tags.update(t)
        objects.update(o)
    for p in a.dump:
        k, n = dump_tags(p)
        kept.update(k)
        lines += n
    print(f"## {a.title}\n")
    print(f"OSM objects with tags: {dict(objects)}. Objects listed in the .obf dump: {lines} "
          "(each object once per map zoom level, so dump counts can be multiples).\n")
    print("| Tag written | In .osm | In .obf dump | Kept? |")
    print("|---|---|---|---|")
    lost = []
    for (k, v), n in sorted(tags.items(), key=lambda kv: (kv[0][0], kv[0][1] or "")):
        c = kept.get((k, v), 0)
        label = f"`{k}={v}`" if v is not None else f"`{k}` (any value)"
        print(f"| {label} | {n} | {c} | {'yes' if c else '**no**'} |")
        if not c:
            lost.append(label)
    print("\n### Not kept by OsmAnd (dropped, or turned into another tag)\n")
    print(", ".join(lost) if lost else "Everything written was found in the .obf dump.")
    extra = sorted((k, v) for (k, v) in kept if (k, v) not in tags)
    print("\n### In the .obf but not written as such (OsmAnd's conversions)\n")
    print(", ".join(f"`{k}={v}`" if v is not None else f"`{k}`" for k, v in extra) or "None.")
    missing = []
    for r in a.require:
        k, v = r.split("=", 1) if "=" in r else (r, None)
        if not tags.get((k, v)) or not kept.get((k, v)):
            missing.append(f"`{r}` ({'not kept' if tags.get((k, v)) else 'not written'})")
    print("\n### Required tags\n")
    print("**Missing:** " + ", ".join(missing) if missing else f"All {len(a.require)} present.")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
