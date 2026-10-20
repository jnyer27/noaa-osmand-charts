#!/usr/bin/env python3
"""build_osf.py
The vector charts as an OsmAnd custom plugin (an .osf package), so OsmAnd downloads and
updates them itself under Maps & Resources instead of the user importing each file.

  python3 tools/build_osf.py --catalog maps/catalog.json --maps maps \\
      --base https://github.com/jnyer27/noaa-osmand-charts/releases/download/vector-maps/ -o osf

Reads the vector-maps catalog (vector-maps.yml) and tools/areas.json and writes to -o:

  Ncds_XX_depth_2.depth.obf.zip  OsmAnd only downloads zipped maps, named with a version
  Ncds_XX_seamarks_2.obf.zip     suffix it strips again: these install as
                                 nautical/Ncds_XX_depth.depth.obf (type "depthmap", listed
                                 under Nautical maps) and Ncds_XX_seamarks.obf (type "map"),
                                 the names the app's own downloads have. Written for each
                                 map found in --maps; the zip's name and size are added to
                                 the catalog's file entry, so regions not rebuilt this run
                                 keep the zips already published.
  osmand-items.json              {"<ncds>": [download item, ...]}, the items in OsmAnd's
                                 custom download format, one list per region
  noaa-charts.osf                the plugin: items.json (PLUGIN, RESOURCES and DOWNLOADS)
                                 and res/ with the plugin image

Each region's download group reads its items from osmand-items.json on the release
("items-url", CustomRegion.DynamicDownloadItems in OsmAnd), so a weekly build reaches
OsmAnd without reinstalling the plugin. --static writes the items into the plugin instead
(what OsmAnd's documented examples do), for testing if the dynamic list does not load.
"""

import argparse
import calendar
import json
import os
import sys
import time
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLUGIN_ID = "noaa.charts"
HEADER_COLOR = "#0B3D61"
GROUP_ICON = "ic_world_globe_dark"
# OsmAnd's map version (IndexConstants.BINARY_MAP_VERSION), stripped from the file name on install
VERSION_SUFFIX = "_2"
KIND = {
    "depth": {"ext": ".depth.obf", "type": "depthmap", "label": "depth map",
              "about": "Depth contours, depth areas and soundings (feet). Listed under Nautical maps; "
                       "use the Nautical map style."},
    "seamarks": {"ext": ".obf", "type": "map", "label": "seamarks map",
                 "about": "Buoys and beacons, lights, wrecks, rocks and obstructions, piers, bridges, "
                          "overhead cables, fairways and dredged channels, restricted and anchorage areas."},
}
DESCRIPTION = (
    "NOAA's official vector charts (ENC) as OsmAnd maps, rebuilt every week with Notices to Mariners "
    "corrections: a depth map (contours and soundings) and a seamarks map (buoys, lights, hazards, "
    "channels and bridges) for each NOAA chart region. Download them under Menu > Maps & Resources, Extra maps, "
    "NOAA vector charts, and use the Nautical map style.<br /><br />"
    "For depth-aware boat routes on the same charts, use the "
    "<a href=\"https://jnyer27.github.io/noaa-osmand-charts/\">NOAA Route app</a>.<br /><br />"
    "Not for navigation: a planning aid. Charted depths are at MLLW with no tide added."
)


def zip_name(kind, file):
    """Ncds_04_depth.depth.obf -> Ncds_04_depth_2.depth.obf.zip"""
    ext = KIND[kind]["ext"]
    if not file.endswith(ext):
        raise SystemExit(f"{file}: expected a {ext} file for {kind}")
    return file[:-len(ext)] + VERSION_SUFFIX + ext + ".zip"


def write_zips(catalog, maps, out):
    """Zips each catalog map found in maps into out and records it in its file entry."""
    for r in catalog["regions"]:
        for f in r["files"]:
            src = os.path.join(maps, f["file"])
            if not os.path.exists(src):
                continue
            name = zip_name(f["kind"], f["file"])
            dst = os.path.join(out, name)
            with zipfile.ZipFile(dst + ".part", "w", zipfile.ZIP_DEFLATED) as z:
                z.write(src, f["file"])
            os.replace(dst + ".part", dst)
            f["osmand_zip"], f["osmand_zip_size"] = name, os.path.getsize(dst)
            print(f"{name}: {f['osmand_zip_size'] / 1e6:.1f} MB")


def region_items(r, base):
    """OsmAnd download items for a catalog region; files without a zip yet are left out."""
    stamp = calendar.timegm(time.strptime(r["created"], "%Y-%m-%dT%H:%M:%SZ"))
    short = r["name"].split(":")[0]
    items = []
    for f in r["files"]:
        if not f.get("osmand_zip"):
            continue
        k = KIND[f["kind"]]
        name = f"{short} {k['label']}"
        items.append({
            "name": {"": name},
            "filename": f["osmand_zip"],
            "type": k["type"],
            "timestamp": stamp,
            "containerSize": f["osmand_zip_size"],
            "contentSize": f["size"],
            "description": {"text": {"": f"{k['about']}<br />NOAA data from {r['created'][:10]}."}},
            "downloadurl": base + f["osmand_zip"],
            "firstsubname": {"": name},
        })
    return items


def downloads(catalog, areas, base, static):
    """The DOWNLOADS item: a header group, then one group per region with OsmAnd zips."""
    groups = [{
        "path": "noaa",
        "name": {"": "NOAA vector charts"},
        "icon": {"": GROUP_ICON},
        "header-color": HEADER_COLOR,
        "description": {
            "text": {"": "NOAA ENC charts by NOAA chart region, rebuilt weekly. Not for navigation."},
            "button": [{"": "User guide", "url": "https://jnyer27.github.io/noaa-osmand-charts/"}],
        },
    }]
    for r in catalog["regions"]:
        if not any(f.get("osmand_zip") for f in r["files"]):
            continue
        g = {
            "scope-id": "offline-maps",
            "path": f"noaa/{r['ncds']}",
            "name": {"": r["name"]},
            "icon": {"": GROUP_ICON},
            "header-color": HEADER_COLOR,
        }
        about = areas.get(r["ncds"], {}).get("description")
        if about:
            g["description"] = {"text": {"": about}}
        if static:
            g["items"] = region_items(r, base)
        else:
            keys = ("name", "filename", "type", "timestamp", "containerSize", "contentSize",
                    "description", "downloadurl", "firstsubname")
            g["items-url"] = {"url": base + "osmand-items.json", "format": "json",
                              "items-path": r["ncds"], "mapping": {k: "@" + k for k in keys}}
        groups.append(g)
    return {"type": "DOWNLOADS", "pluginId": PLUGIN_ID, "items": groups}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--catalog", required=True, help="vector-maps catalog.json (zip entries are added to it)")
    ap.add_argument("--maps", help="folder with this run's .obf maps to zip")
    ap.add_argument("--base", required=True, help="URL the release's files are downloaded from, ending in /")
    ap.add_argument("-o", "--out", required=True, help="output folder")
    ap.add_argument("--version", type=int, default=1, help="plugin version (default 1)")
    ap.add_argument("--image", default=os.path.join(ROOT, "docs", "osmand-vector-charts.jpg"),
                    help="plugin image (default docs/osmand-vector-charts.jpg)")
    ap.add_argument("--icon", help="plugin icon, a PNG (none by default)")
    ap.add_argument("--static", action="store_true", help="write the download items into the plugin")
    a = ap.parse_args()
    if not a.base.endswith("/"):
        raise SystemExit("--base must end in /")
    os.makedirs(a.out, exist_ok=True)

    catalog = json.load(open(a.catalog))
    areas = {x["ncds"]: x for x in json.load(open(os.path.join(ROOT, "tools", "areas.json")))["areas"]
             if x.get("ncds")}
    if a.maps:
        write_zips(catalog, a.maps, a.out)
        with open(a.catalog, "w") as fh:
            json.dump(catalog, fh, indent=1)

    items = {r["ncds"]: region_items(r, a.base) for r in catalog["regions"]}
    with open(os.path.join(a.out, "osmand-items.json"), "w") as fh:
        json.dump(items, fh, indent=1)
    n = sum(len(v) for v in items.values())
    if not n:
        raise SystemExit("No region has OsmAnd zips: run with --maps")
    missing = [r["ncds"] for r in catalog["regions"] if not items[r["ncds"]]]
    if missing:
        print(f"No OsmAnd zips yet, left out: {' '.join(missing)}", file=sys.stderr)

    plugin = {
        "type": "PLUGIN", "pluginId": PLUGIN_ID, "version": a.version,
        "name": {"": "NOAA vector charts"},
        "description": {"": DESCRIPTION},
        "image": {"": "@plugin-image" + os.path.splitext(a.image)[1]},
    }
    if a.icon:
        plugin["icon"] = {"": "@plugin-icon.png"}
    manifest = {"version": 1, "items": [
        plugin,
        {"type": "RESOURCES", "pluginId": PLUGIN_ID, "file": "res"},
        downloads(catalog, areas, a.base, a.static),
    ]}
    osf = os.path.join(a.out, "noaa-charts.osf")
    with zipfile.ZipFile(osf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("items.json", json.dumps(manifest, indent=1))
        z.write(a.image, "res/" + plugin["image"][""][1:])
        if a.icon:
            z.write(a.icon, "res/plugin-icon.png")
    print(f"{osf}: {len(catalog['regions']) - len(missing)} regions, {n} maps"
          f"{' (items in the plugin)' if a.static else ' (items from osmand-items.json)'}")


if __name__ == "__main__":
    main()
