#!/usr/bin/env bash
# OpenStreetMap base map for the app's chart screen, one NCDS region per file.
# Cuts the region out of the latest Protomaps daily build (OSM data, basemap layers v4)
# with go-pmtiles (HTTP range requests, no planet download), then keeps only the layers
# the chart needs: roads (with rail and ferries), places, boundaries, and pois of
# boating kinds only (marinas, ramps, ferry terminals, lighthouses, boatyards,
# beaches; the other 99% are shops and restaurants). OSM water, earth, landuse and
# landcover are dropped: the NOAA chart paints land, shoreline and depth, and OSM's
# shoreline does not match it. Protomaps boundaries carry no names, so a `counties` layer
# of county name labels on the county lines is added from the Census TIGER/Line county
# file (tools/county_labels.py).
#
#   tools/build_basemap.sh <name> <west,south,east,north> <maxzoom> [outdir]
#
# Writes <outdir>/<name>_basemap.pmtiles (trimmed) and <name>_basemap_full.pmtiles
# (all layers, for size comparison). The trimmed file's metadata gets a `search` list
# (tools/basemap_search.py). Needs pmtiles, tippecanoe, tile-join and tippecanoe-decode on PATH.
# TIGER_ZIP may point at an already downloaded tl_<year>_us_county.zip.
set -euo pipefail
name=$1 bbox=$2 maxzoom=$3 out=${4:-.}
mkdir -p "$out"
build=$(curl -fsS https://build-metadata.protomaps.dev/builds.json |
  python3 -c 'import json,sys; print(json.load(sys.stdin)[-1]["key"])')
echo "Protomaps build $build"
full="$out/${name}_basemap_full.pmtiles"
pmtiles extract "https://build.protomaps.com/$build" "$full" --bbox="$bbox" --maxzoom="$maxzoom" --download-threads=8
# County name labels on the county lines (rank 0 every 6 km, rank 1 between, 1.5 km apart)
tiger=${TIGER_ZIP:-$out/tl_2024_us_county.zip}
[ -s "$tiger" ] || curl -fsSL --retry 5 --retry-delay 10 -o "$tiger" \
  https://www2.census.gov/geo/tiger/TIGER2024/COUNTY/tl_2024_us_county.zip
python3 "$(dirname "$0")/county_labels.py" "$tiger" "$bbox" > "$out/${name}_counties.geojsonl"
inputs=("$full")
if [ -s "$out/${name}_counties.geojsonl" ]; then
  tippecanoe --force -q -o "$out/${name}_counties.pmtiles" -l counties -Z 9 -z "$maxzoom" -r1 \
    --no-feature-limit --no-tile-size-limit "$out/${name}_counties.geojsonl"
  inputs+=("$out/${name}_counties.pmtiles")
fi
tile-join --force --no-tile-size-limit \
  -l roads -l places -l boundaries -l pois -l counties \
  -j '{"pois": ["in", "kind", "marina", "slipway", "ferry_terminal", "lighthouse", "boat_rental", "boat_storage", "boatyard", "harbour", "beach"]}' \
  -n "${name} base map (OpenStreetMap)" \
  -A '<a href="https://openstreetmap.org/copyright">&copy; OpenStreetMap contributors</a>, Protomaps' \
  -o "$out/${name}_basemap.pmtiles" "${inputs[@]}"
# Search rows (towns, marinas, roads) for the app, stored in the file's metadata
tippecanoe-decode -Z "$maxzoom" -z "$maxzoom" -l places -l pois -l roads "$out/${name}_basemap.pmtiles" |
  python3 "$(dirname "$0")/basemap_search.py" > "$out/${name}_search.json"
pmtiles show --metadata "$out/${name}_basemap.pmtiles" > "$out/${name}_meta.json"
python3 - "$out/${name}_meta.json" "$out/${name}_search.json" <<'PY'
import json, sys
meta = json.load(open(sys.argv[1]))
meta["search"] = json.load(open(sys.argv[2]))
json.dump(meta, open(sys.argv[1], "w"), separators=(",", ":"), ensure_ascii=False)
PY
pmtiles edit "$out/${name}_basemap.pmtiles" --metadata="$out/${name}_meta.json"
echo "$build" > "$out/${name}_basemap.build"
