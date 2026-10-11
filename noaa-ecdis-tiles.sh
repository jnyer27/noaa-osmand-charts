#!/data/data/com.termux/files/usr/bin/bash
# noaa-ecdis-tiles.sh
# Build an offline OsmAnd .sqlitedb overlay from NOAA's ECDIS Display Service
# (IHO S-52 symbology, the same look as SiiTech). Runs in Termux.
#
# Usage:
#   ./noaa-ecdis-tiles.sh [options]
#
# Options:
#   -b W,S,E,N   Bounding box in decimal degrees (default: Middle River / Hart-Miller area)
#   -z MIN-MAX   Zoom range (default: 10-16)
#   -n NAME      Output name (default: noaa_ecdis_middle_river)
#   -o DIR       Output directory (default: ~/storage/downloads/noaa-charts)
#   -j N         Parallel downloads (default: 4, please keep it modest)
#   -u UNITS     Depth units: meters (default) or feet (experimental)
#   -r           Refresh: discard cached tiles and pull everything fresh from NOAA
#   -k           Keep cache: resume/retry only, never refresh
#   -y           Skip the confirmation prompt
#   -h           Help
#
# Example, upper Chesapeake from Middle River to the Bay Bridge:
#   ./noaa-ecdis-tiles.sh -b -76.55,38.95,-76.25,39.40 -z 10-16 -n upper_bay
#
# Re-running behavior:
#   - If the .sqlitedb already exists, the default is a REFRESH (fresh pull of
#     every tile), to pick up NOAA's weekly Friday ENC update.
#   - If the last run was interrupted or had failed tiles, the default is to
#     RESUME and fetch only what is missing.
#   - A refresh that ends with failed tiles keeps your previous .sqlitedb;
#     re-run to finish it.

set -euo pipefail

SERVICE="${ECDIS_URL:-https://gis.charttools.noaa.gov/arcgis/rest/services/MCS/ENCOnline/MapServer/exts/MaritimeChartService/MapServer/export}"
BBOX="-76.52,39.22,-76.33,39.36"
ZOOMS="10-16"
NAME="noaa_ecdis_middle_river"
OUT_DIR="${HOME}/storage/downloads/noaa-charts"
JOBS=4
UNITS="meters"
ASSUME_YES=0
MODE=""        # refresh | resume, decided below unless forced

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
usage() { sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

need() {
  command -v "$1" >/dev/null 2>&1 && return
  log "Installing $2"
  pkg install -y "$2" >/dev/null || die "Could not install $2"
}

while getopts ":b:z:n:o:j:u:rkyh" opt; do
  case $opt in
    b) BBOX="$OPTARG" ;;
    z) ZOOMS="$OPTARG" ;;
    n) NAME="$OPTARG" ;;
    o) OUT_DIR="$OPTARG" ;;
    j) JOBS="$OPTARG" ;;
    u) UNITS="$OPTARG" ;;
    r) MODE="refresh" ;;
    k) MODE="resume" ;;
    y) ASSUME_YES=1 ;;
    h) usage ;;
    \?) die "Unknown option -$OPTARG" ;;
    :) die "Option -$OPTARG needs a value" ;;
  esac
done

need curl curl
need sqlite3 sqlite

IFS=',' read -r W S E N <<<"$BBOX"
[[ -n "${N:-}" ]] || die "Bounding box must be W,S,E,N"
ZMIN="${ZOOMS%-*}"; ZMAX="${ZOOMS#*-}"
[[ "$ZMIN" =~ ^[0-9]+$ && "$ZMAX" =~ ^[0-9]+$ && "$ZMIN" -le "$ZMAX" && "$ZMAX" -le 17 ]] \
  || die "Zoom range must look like 10-16 (max 17)"

case "$UNITS" in
  meters) DISPLAY_PARAMS="" ;;
  # Experimental: ArcGIS Maritime Chart Service display parameter for depth units.
  # If depths still render in meters, the server ignored it; use meters.
  feet)   DISPLAY_PARAMS='{"ECDISParameters":{"version":"10.9","DynamicParameters":{"Parameter":[{"name":"DisplayDepthUnits","value":2}]}}}' ;;
  *) die "Units must be meters or feet" ;;
esac

# ---- storage ----
if [[ "$OUT_DIR" == "$HOME/storage/"* && ! -L "$HOME/storage/downloads" ]]; then
  log "Requesting storage permission (accept the Android prompt)"
  termux-setup-storage
  for _ in $(seq 1 60); do
    [[ -L "$HOME/storage/downloads" && -w "$HOME/storage/downloads/" ]] && break
    sleep 1
  done
  [[ -L "$HOME/storage/downloads" ]] || die "Storage permission not granted"
  sleep 2
fi
mkdir -p "$OUT_DIR"

# Tile cache lives in Termux home (fast, private), final file goes to OUT_DIR
WORK="${HOME}/.noaa-ecdis/${NAME}"
DST="$OUT_DIR/${NAME}.sqlitedb"
mkdir -p "$WORK"

if [[ -z "$MODE" ]]; then
  if [[ -f "$WORK/.refreshing" ]]; then
    MODE="resume"; WHY="finishing an interrupted refresh"
  elif [[ -s "$WORK/failed.txt" ]]; then
    MODE="resume"; WHY="retrying tiles that failed last time"
  elif [[ -f "$DST" ]]; then
    MODE="refresh"; WHY="existing map from $(date -r "$DST" '+%a %Y-%m-%d'), pulling NOAA's latest"
  else
    MODE="resume"; WHY="first build"
  fi
else
  [[ "$MODE" == "refresh" ]] && WHY="forced with -r" || WHY="forced with -k"
fi
log "Mode: $MODE ($WHY)"

if [[ "$MODE" == "refresh" ]]; then
  rm -rf "$WORK"
  mkdir -p "$WORK"
  touch "$WORK/.refreshing"
fi

# ---- tile list ----
TILELIST="$WORK/tiles.txt"
awk -v W="$W" -v S="$S" -v E="$E" -v N="$N" -v zmin="$ZMIN" -v zmax="$ZMAX" '
  function lon2x(lon, n) { return int((lon + 180) / 360 * n) }
  function lat2y(lat, n,   r) { r = lat * atan2(0, -1) / 180
    return int((1 - log(sin(r)/cos(r) + 1/cos(r)) / atan2(0, -1)) / 2 * n) }
  BEGIN {
    for (z = zmin; z <= zmax; z++) {
      n = 2 ^ z
      for (x = lon2x(W, n); x <= lon2x(E, n); x++)
        for (y = lat2y(N, n); y <= lat2y(S, n); y++)
          print z, x, y
    }
  }' > "$TILELIST"

TOTAL=$(wc -l < "$TILELIST")
HAVE=$(find "$WORK" -name '*.png' 2>/dev/null | wc -l)
log "Area $BBOX, zoom $ZMIN-$ZMAX: $TOTAL tiles ($HAVE already cached), depths in $UNITS"
# Measured 3.7 KB/tile (land-heavy north bay, z10-15) to 7 KB/tile (open water, z16)
log "Estimated size: $(( TOTAL * 3 / 1024 ))-$(( TOTAL * 8 / 1024 )) MB (more open water = larger)"

if [[ $ASSUME_YES -eq 0 ]]; then
  [[ $TOTAL -gt 20000 ]] && log "That is a lot of tiles. Consider a smaller box or lower max zoom."
  read -r -p "Continue? [y/N] " ans
  [[ "$ans" =~ ^[Yy] ]] || exit 0
fi

command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock
trap 'command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock' EXIT

# ---- fetch ----
fetch_tile() {
  local z="$1" x="$2" y="$3"
  local f="$WORK/$z/$x/$y.png"
  [[ -s "$f" ]] && return 0
  mkdir -p "$WORK/$z/$x"

  # Web Mercator (EPSG:3857 / 102100) tile bounds
  local bbox
  bbox=$(awk -v z="$z" -v x="$x" -v y="$y" 'BEGIN {
    o = 20037508.342789244; s = 2 * o / (2 ^ z)
    printf "%.4f,%.4f,%.4f,%.4f", -o + x*s, o - (y+1)*s, -o + (x+1)*s, o - y*s }')

  local args=(-sS -fG --retry 3 --retry-delay 5 --connect-timeout 20 --max-time 60
              --data-urlencode "bbox=$bbox"
              --data-urlencode "bboxSR=102100"
              --data-urlencode "imageSR=102100"
              --data-urlencode "size=256,256"
              --data-urlencode "dpi=96"
              --data-urlencode "format=png"
              --data-urlencode "transparent=false"
              --data-urlencode "f=image")
  [[ -n "$DISPLAY_PARAMS" ]] && args+=(--data-urlencode "display_params=$DISPLAY_PARAMS")

  if curl "${args[@]}" -o "$f.part" "$SERVICE" 2>/dev/null \
     && [[ "$(head -c 4 "$f.part" | od -An -tx1 | tr -d ' \n')" == "89504e47" ]]; then
    mv -f "$f.part" "$f"
  else
    rm -f "$f.part"
    echo "$z $x $y" >> "$WORK/failed.txt"
  fi
}
export -f fetch_tile
export WORK SERVICE DISPLAY_PARAMS

rm -f "$WORK/failed.txt"
log "Downloading with $JOBS parallel requests"
xargs -P "$JOBS" -n 3 bash -c 'fetch_tile "$@"' _ < "$TILELIST" &
XPID=$!
while kill -0 "$XPID" 2>/dev/null; do
  printf '\r  %d / %d tiles' "$(find "$WORK" -name '*.png' | wc -l)" "$TOTAL"
  sleep 3
done
wait "$XPID" || true
printf '\r  %d / %d tiles\n' "$(find "$WORK" -name '*.png' | wc -l)" "$TOTAL"

FAILED=$( [[ -f "$WORK/failed.txt" ]] && wc -l < "$WORK/failed.txt" || echo 0 )
[[ "$FAILED" -gt 0 ]] && log "$FAILED tiles failed. Re-run the same command to retry just those."
GOT=$(find "$WORK" -name '*.png' | wc -l)
[[ "$GOT" -gt 0 ]] || die "No tiles downloaded. NOAA's service may be down (it updates Friday afternoons)."

# Never replace a complete map with a partial refresh
if [[ "$FAILED" -gt 0 && -f "$WORK/.refreshing" && -f "$DST" ]]; then
  log "Refresh incomplete, so your existing $(basename "$DST") was left untouched."
  log "Re-run the same command to fetch the remaining $FAILED tiles and finish the refresh."
  exit 1
fi

# ---- package (same format as tarwirdur/mbtiles2osmand) ----
SQL="$WORK/pack.sql"
log "Packaging $GOT tiles into $(basename "$DST")"
{
  echo "PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;"
  echo "CREATE TABLE tiles (x int, y int, z int, s int, image blob, PRIMARY KEY (x,y,z,s));"
  echo "CREATE TABLE info (maxzoom Int, minzoom Int);"
  echo "BEGIN;"
  find "$WORK" -name '*.png' | awk -F/ '{
      y = $NF; sub(/\.png$/, "", y); x = $(NF-1); z = $(NF-2)
      printf "INSERT INTO tiles VALUES (%d, %d, %d, 0, readfile(\x27%s\x27));\n", x, y, 17 - z, $0 }'
  echo "COMMIT;"
  echo "INSERT INTO info (maxzoom, minzoom) SELECT max(z), min(z) FROM tiles;"
} > "$SQL"

rm -f "$DST.tmp"
sqlite3 "$DST.tmp" < "$SQL" >/dev/null || die "Packaging failed"
mv -f "$DST.tmp" "$DST"
rm -f "$WORK/.refreshing"

log "Ready: $DST ($(du -h "$DST" | cut -f1))"
log "Next run refreshes from NOAA by default (use -k to only resume/retry)."
log "Import: termux-open --chooser \"$DST\""
