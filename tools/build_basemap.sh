#!/usr/bin/env bash
# OpenStreetMap base map for the app's chart screen, one NCDS region per file.
# Cuts the region out of the latest Protomaps daily build (OSM data, basemap layers v4)
# with go-pmtiles (HTTP range requests, no planet download), then keeps only the layers
# the chart needs: roads, places, boundaries, pois (marinas) and transit (ferries,
# rail). OSM water, earth, landuse and landcover are dropped: the NOAA chart paints
# land, shoreline and depth, and OSM's shoreline does not match it.
#
#   tools/build_basemap.sh <name> <west,south,east,north> <maxzoom> [outdir]
#
# Writes <outdir>/<name>_basemap.pmtiles (trimmed) and <name>_basemap_full.pmtiles
# (all layers, for size comparison). Needs pmtiles and tile-join (tippecanoe) on PATH.
set -euo pipefail
name=$1 bbox=$2 maxzoom=$3 out=${4:-.}
mkdir -p "$out"
build=$(curl -fsS https://build-metadata.protomaps.dev/builds.json |
  python3 -c 'import json,sys; print(json.load(sys.stdin)[-1]["key"])')
echo "Protomaps build $build"
full="$out/${name}_basemap_full.pmtiles"
pmtiles extract "https://build.protomaps.com/$build" "$full" --bbox="$bbox" --maxzoom="$maxzoom" --download-threads=8
tile-join --force --no-tile-size-limit \
  -l roads -l places -l boundaries -l pois -l transit \
  -n "${name} base map (OpenStreetMap)" \
  -A '<a href="https://openstreetmap.org/copyright">&copy; OpenStreetMap contributors</a>, Protomaps' \
  -o "$out/${name}_basemap.pmtiles" "$full"
echo "$build" > "$out/${name}_basemap.build"
