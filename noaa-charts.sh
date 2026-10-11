#!/data/data/com.termux/files/usr/bin/bash
# noaa-charts.sh
# Download NOAA Chart Display Service (NCDS) MBTiles and convert them to
# OsmAnd .sqlitedb overlay maps. Built for Termux on Android.
#
# Usage:
#   ./noaa-charts.sh [options] REGION [REGION ...]
#   REGION is the NCDS code, e.g. 04 or ncds_04 or 19a
#
# Options:
#   -o DIR    Output directory (default: ~/storage/downloads/noaa-charts)
#   -k        Keep the original .mbtiles after conversion
#   -m        MBTiles only, skip conversion to .sqlitedb
#   -f        Force re-download even if local copy is current
#   -M        Merge all regions into one .sqlitedb (named after the regions)
#   -S        Keep regions as separate maps, do not merge
#             (with 2+ regions and neither flag, the script asks)
#   -i FILE   Print info (bounds, zooms, tile count) for a local .mbtiles/.sqlitedb
#   -l        List known region codes
#   -h        Help
#
# Find your region code on the NOAA map: https://distribution.charts.noaa.gov/ncds/
# Re-run weekly; regions are only re-downloaded when NOAA publishes a newer file.

set -euo pipefail

BASE_URL="https://distribution.charts.noaa.gov/ncds/mbtiles"
OUT_DIR="${HOME}/storage/downloads/noaa-charts"
KEEP_MBTILES=0
CONVERT=1
FORCE=0
MERGE=""   # 1 = merge, 0 = separate, empty = ask

KNOWN=(01a 01b 02a 02b 03 04 05 06 07 08 09 10 11 12 13 14 15 16 17a 17b 18
       19a 19b 19c 19d 20a 20b 20c 21 22a 22b 23a 23b 24a 24b 25a 25b 26a 26b
       27 28a 28b 29 30 31a 31b)

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
usage() { sed -n '2,23p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

need() {
  command -v "$1" >/dev/null 2>&1 && return
  log "Installing $2"
  pkg install -y "$2" >/dev/null || die "Could not install $2"
}

human() { awk -v b="$1" 'BEGIN{s="B KB MB GB TB";split(s,u," ");i=1;while(b>=1024&&i<5){b/=1024;i++}printf "%.1f %s",b,u[i]}'; }

info() {
  local f="$1"
  [[ -f "$f" ]] || die "No such file: $f"
  if sqlite3 "$f" "SELECT 1 FROM sqlite_master WHERE name='metadata'" | grep -q 1; then
    echo "File:    $f (MBTiles)"
    sqlite3 -separator ': ' "$f" \
      "SELECT name, value FROM metadata WHERE name IN ('name','description','format','bounds','minzoom','maxzoom','version');"
    echo "Tiles:   $(sqlite3 "$f" 'SELECT count(*) FROM tiles;')"
  else
    echo "File:    $f (OsmAnd sqlitedb)"
    sqlite3 -header -column "$f" "SELECT maxzoom AS stored_max, minzoom AS stored_min, 17-maxzoom AS real_minzoom, 17-minzoom AS real_maxzoom FROM info;"
    echo "Tiles:   $(sqlite3 "$f" 'SELECT count(*) FROM tiles;')"
  fi
}

remote_epoch() {
  # Last-Modified of the remote file as epoch seconds, or 0 if unknown
  local lm
  lm=$(curl -sIL --connect-timeout 30 "$1" | tr -d '\r' | awk -F': ' 'tolower($1)=="last-modified"{v=$2} END{print v}')
  [[ -n "$lm" ]] && date -d "$lm" +%s 2>/dev/null || echo 0
}

remote_size() {
  curl -sIL --connect-timeout 30 "$1" | tr -d '\r' \
    | awk -F': ' 'tolower($1)=="content-length"{v=$2} END{print v+0}'
}

free_bytes() { df -Pk "$1" | awk 'NR==2{print $4*1024}'; }

download() {
  local code="$1" url="${BASE_URL}/ncds_${code}.mbtiles"
  local dest="${OUT_DIR}/ncds_${code}.mbtiles" stamp="${OUT_DIR}/.ncds_${code}.stamp"
  local r_epoch l_epoch size free

  r_epoch=$(remote_epoch "$url")
  size=$(remote_size "$url")
  [[ "$size" -gt 0 ]] || die "ncds_${code}: not found on NOAA server (check the code)"

  l_epoch=$(cat "$stamp" 2>/dev/null || echo 0)
  if [[ $FORCE -eq 0 && "$r_epoch" -gt 0 && "$l_epoch" -ge "$r_epoch" ]]; then
    log "ncds_${code}: up to date ($(date -d "@$r_epoch" '+%Y-%m-%d'))"
    return 1
  fi

  # Need room for the download plus a converted copy of similar size
  free=$(free_bytes "$OUT_DIR")
  local needed=$(( CONVERT == 1 ? size * 2 : size ))
  [[ "$free" -gt "$needed" ]] || die "ncds_${code}: need ~$(human "$needed"), only $(human "$free") free"

  log "ncds_${code}: downloading $(human "$size")"
  if ! curl -fL -R -C - --retry 5 --retry-delay 15 --connect-timeout 30 \
        --progress-bar -o "${dest}.part" "$url"; then
    log "ncds_${code}: resume failed, restarting download"
    rm -f "${dest}.part"
    curl -fL -R --retry 5 --retry-delay 15 --connect-timeout 30 \
      --progress-bar -o "${dest}.part" "$url" || die "ncds_${code}: download failed"
  fi

  [[ -s "${dest}.part" ]] || die "ncds_${code}: download finished but ${dest}.part is missing (output folder was removed mid-download?)"
  sqlite3 "${dest}.part" "SELECT count(*) FROM metadata;" >/dev/null 2>&1 \
    || die "ncds_${code}: downloaded file is not a valid MBTiles database"

  mv -f "${dest}.part" "$dest"
  echo "$r_epoch" > "$stamp"
  return 0
}

convert() {
  # Mirrors tarwirdur/mbtiles2osmand exactly:
  #   y = 2^z - 1 - tile_row   (TMS -> XYZ)
  #   z = 17 - zoom_level      (OsmAnd/BigPlanet inverted zoom, OsmAnd's default)
  #   info table holds only maxzoom/minzoom = max(z)/min(z) of the stored values
  local src="$1" dst="${1%.mbtiles}.sqlitedb"

  log "$(basename "$src"): converting to OsmAnd .sqlitedb"
  rm -f "${dst}.tmp"
  sqlite3 "${dst}.tmp" >/dev/null <<SQL || die "Conversion failed for $src"
PRAGMA journal_mode=OFF;
PRAGMA synchronous=OFF;
CREATE TABLE tiles (x int, y int, z int, s int, image blob, PRIMARY KEY (x,y,z,s));
CREATE TABLE info (maxzoom Int, minzoom Int);
ATTACH DATABASE '$src' AS mb;
INSERT INTO tiles (x, y, z, s, image)
  SELECT tile_column, ((1 << zoom_level) - 1 - tile_row), 17 - zoom_level, 0, tile_data
  FROM mb.tiles;
DETACH DATABASE mb;
INSERT INTO info (maxzoom, minzoom) SELECT max(z), min(z) FROM tiles;
SQL

  mv -f "${dst}.tmp" "$dst"
  log "$(basename "$dst"): ready ($(human "$(stat -c %s "$dst")"))"
  [[ $KEEP_MBTILES -eq 1 ]] || rm -f "$src"
}

merge() {
  # Combine per-region .sqlitedb files into one. Where regions overlap and
  # both have a tile, keep the larger image (usually the one with more chart
  # drawn on it rather than a mostly blank edge tile).
  local dst="$1"; shift
  local src total=0 free

  for src in "$@"; do total=$(( total + $(stat -c %s "$src") )); done
  free=$(free_bytes "$OUT_DIR")
  [[ "$free" -gt "$total" ]] || die "Merge: need ~$(human "$total"), only $(human "$free") free"

  log "$(basename "$dst"): merging $# regions"
  rm -f "${dst}.tmp"
  {
    echo "PRAGMA journal_mode=OFF;"
    echo "PRAGMA synchronous=OFF;"
    echo "CREATE TABLE tiles (x int, y int, z int, s int, image blob, PRIMARY KEY (x,y,z,s));"
    echo "CREATE TABLE info (maxzoom Int, minzoom Int);"
    for src in "$@"; do
      echo "ATTACH DATABASE '$src' AS src;"
      echo "INSERT INTO tiles (x, y, z, s, image) SELECT x, y, z, s, image FROM src.tiles WHERE true"
      echo "  ON CONFLICT (x, y, z, s) DO UPDATE SET image = excluded.image"
      echo "  WHERE length(excluded.image) > length(tiles.image);"
      echo "DETACH DATABASE src;"
    done
    echo "INSERT INTO info (maxzoom, minzoom) SELECT max(z), min(z) FROM tiles;"
  } | sqlite3 "${dst}.tmp" >/dev/null || die "Merge failed"

  mv -f "${dst}.tmp" "$dst"
  log "$(basename "$dst"): ready ($(human "$(stat -c %s "$dst")"))"
}

# ---- argument parsing ----
while getopts ":o:kmfMSi:lh" opt; do
  case $opt in
    o) OUT_DIR="$OPTARG" ;;
    k) KEEP_MBTILES=1 ;;
    m) CONVERT=0; KEEP_MBTILES=1 ;;
    f) FORCE=1 ;;
    M) MERGE=1 ;;
    S) MERGE=0 ;;
    i) need sqlite3 sqlite; info "$OPTARG"; exit 0 ;;
    l) printf '%s\n' "${KNOWN[@]/#/ncds_}" | column -c 80 2>/dev/null || printf '%s\n' "${KNOWN[@]/#/ncds_}"
       echo; echo "Map of regions: https://distribution.charts.noaa.gov/ncds/"; exit 0 ;;
    h) usage ;;
    \?) die "Unknown option -$OPTARG" ;;
    :) die "Option -$OPTARG needs a value" ;;
  esac
done
shift $((OPTIND - 1))
[[ $# -gt 0 ]] || usage

# Normalize region codes and drop duplicates, keeping the order given
CODES=()
for raw in "$@"; do
  code="${raw#ncds_}"; code="${code%.mbtiles}"; code="${code%.sqlitedb}"; code="${code,,}"
  [[ " ${CODES[*]} " == *" $code "* ]] || CODES+=("$code")
done

if [[ ${#CODES[@]} -lt 2 ]]; then
  MERGE=0
elif [[ $CONVERT -eq 0 ]]; then
  [[ "$MERGE" == 1 ]] && log "Merge needs .sqlitedb output; ignoring -M because of -m"
  MERGE=0
elif [[ -z "$MERGE" ]]; then
  if [[ -t 0 ]]; then
    read -rp "Merge ${#CODES[@]} regions (${CODES[*]}) into one map? [Y/n] " ans
    [[ "${ans,,}" == n* ]] && MERGE=0 || MERGE=1
  else
    log "Multiple regions and no -M/-S given, not interactive: keeping maps separate"
    MERGE=0
  fi
fi

need curl curl
need sqlite3 sqlite

if [[ "$OUT_DIR" == "$HOME/storage/"* && ! -L "$HOME/storage/downloads" ]]; then
  log "Requesting storage permission (accept the Android prompt)"
  termux-setup-storage
  # termux-setup-storage returns before the user answers, then wipes and
  # rebuilds ~/storage. Wait for the symlink so we don't write into a dir
  # that is about to be deleted.
  for _ in $(seq 1 60); do
    [[ -L "$HOME/storage/downloads" && -w "$HOME/storage/downloads/" ]] && break
    sleep 1
  done
  [[ -L "$HOME/storage/downloads" ]] || die "Storage permission not granted. Run termux-setup-storage, then retry."
  sleep 2
fi
mkdir -p "$OUT_DIR" || die "Cannot create $OUT_DIR"

# Keep the phone from sleeping mid-download if Termux:API wake lock is available
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock
trap 'command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock' EXIT

changed=0
for code in "${CODES[@]}"; do
  if download "$code"; then
    changed=1
    [[ $CONVERT -eq 1 ]] && convert "${OUT_DIR}/ncds_${code}.mbtiles"
  fi
done

if [[ $MERGE -eq 1 ]]; then
  # Sorted so "04 05" and "05 04" produce the same file
  mapfile -t sorted < <(printf '%s\n' "${CODES[@]}" | sort)
  merged="${OUT_DIR}/ncds_$(IFS=_; echo "${sorted[*]}").sqlitedb"
  parts=()
  for code in "${sorted[@]}"; do
    f="${OUT_DIR}/ncds_${code}.sqlitedb"
    [[ -f "$f" ]] || die "Cannot merge: $(basename "$f") is missing. Re-run with -f to rebuild it."
    parts+=("$f")
  done
  # The per-region files are kept so a later update to one region can re-merge
  # without downloading the others again.
  if [[ $changed -eq 1 || $FORCE -eq 1 || ! -f "$merged" ]]; then
    merge "$merged" "${parts[@]}"
  else
    log "$(basename "$merged"): up to date"
  fi
fi

log "Done. Files are in: $OUT_DIR"
if [[ $MERGE -eq 1 ]]; then
  log "In OsmAnd+: import $(basename "$merged") (the per-region files are only needed for future merges)."
elif [[ $CONVERT -eq 1 ]]; then
  log "In OsmAnd+: open each .sqlitedb from your file manager with OsmAnd to import it."
fi
