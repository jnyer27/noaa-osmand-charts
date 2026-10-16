#!/usr/bin/env python3
"""obf_compare.py <file.osm> <inspector dump>
Proof of concept (issue #2): which tags written by enc_to_osm.py survived into the .obf.
Counts each key=value in the OSM file and in OsmAnd's BinaryInspector output
(inspector.sh -vmap -vmapobjects). An object can appear once per map zoom section in
the dump, so dump counts may be multiples; zero means OsmAnd dropped the tag.
OsmAnd also folds seamark:<type>:<attr> into seamark:<attr> (rendering_types.xml),
so the folded form is counted too. Prints a Markdown report."""

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


def main():
    osm, dump = sys.argv[1], sys.argv[2]
    tags, objects = osm_tags(osm)
    text = open(dump, errors="replace").read()
    tokens = collections.Counter(re.findall(r"[A-Za-z_:]+=[^\s,;\]\)}]+", text))
    keys_in_dump = collections.Counter(t.split("=", 1)[0] for t in tokens.elements())

    def in_dump(k, v):
        forms = [k]
        m = re.match(r"seamark:[a-z_]+:([a-z_]+)$", k)
        if m:
            forms.append("seamark:" + m.group(1))  # OsmAnd's folded form
        if v is None:
            return sum(keys_in_dump.get(f, 0) for f in forms), forms
        return sum(tokens.get(f"{f}={v}", 0) for f in forms) + text.count(f"{k} {v}"), forms

    print("## ENC vector proof of concept: tags written vs kept in the .obf\n")
    print(f"OSM objects with tags: {dict(objects)}. Inspector dump: {len(text.splitlines())} lines.\n")
    print("| Tag written | In .osm | In .obf dump | Kept? |")
    print("|---|---|---|---|")
    lost = []
    for (k, v), n in sorted(tags.items(), key=lambda kv: (kv[0][0], kv[0][1] or "")):
        c, forms = in_dump(k, v)
        label = f"`{k}={v}`" if v is not None else f"`{k}` (any value)"
        print(f"| {label} | {n} | {c} | {'yes' if c else '**no**'} |")
        if not c:
            lost.append(label)
    print("\n### Not kept by OsmAnd\n")
    print(", ".join(lost) if lost else "Everything written was found in the .obf dump.")
    print("\n### Most common key=value tokens in the dump\n")
    for t, n in tokens.most_common(60):
        print(f"- `{t}`: {n}")


if __name__ == "__main__":
    main()
