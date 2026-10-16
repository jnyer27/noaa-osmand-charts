#!/usr/bin/env bash
# osm_to_obf.sh NAME [OMC_DIR]
# The OsmAnd maps of one area from enc_to_osm.py's files out/NAME_*.osm (run in the
# folder holding them), like OsmAnd's own depth maps:
#   NAME_depth.depth.obf  contours and depth areas (default zooms) merged with three
#                         sounding sections (zoom 11-12, 13-14, 15+)
#   NAME_seamarks.obf     marks, lights, hazards, piers, bridges, channels and areas
# Not Depth_<name>: OsmAnd drops a trailing _<number> from map names as a version.
# Then checks them: inspector dumps (objects shown at zoom 15, and 11 and 13 for the
# sounding sections), tools/obf_compare.py with essential tags -> compare.md.
# Exit status 1 if a check fails. JAVA_OPTS sets OsmAndMapCreator's heap.
set -euo pipefail
NAME=$1
OMC=$(cd "${2:-../omc}" && pwd)
TOOLS=$(cd "$(dirname "$0")" && pwd)

# run CMD...: OsmAndMapCreator utilities in a folder of its own (it keeps work files
# there, named after the output); prints the folder
run() {
	local d; d=$(mktemp -d)
	(cd "$d" && bash "$OMC/utilities.sh" "$@" > log.txt 2>&1) || { tail -40 "$d/log.txt" >&2; return 1; }
	echo "$d"
}

parts=()
for f in contours:: soundings_z11::11-12 soundings_z13::13-14 soundings_z15::15-; do
	osm="$PWD/${NAME}_${f%%::*}.osm" zooms=${f#*::}
	if ! grep -q -m1 '<node' "$osm"; then echo "$osm: empty, skipped"; continue; fi
	d=$(run generate-map "$osm" ${zooms:+--map-zooms=$zooms})
	obf=$(ls "$d"/*.obf | head -1)
	echo "$(basename "$osm") -> $(du -h "$obf" | cut -f1)${zooms:+, zooms $zooms}"
	parts+=("$obf")
done
[ ${#parts[@]} -gt 0 ] || { echo "No depth data"; exit 1; }
depth="$PWD/${NAME}_depth.depth.obf"
rm -f "$depth"
if [ ${#parts[@]} -eq 1 ]; then mv "${parts[0]}" "$depth"; else run merge-index "$depth" "${parts[@]}" > /dev/null; fi
d=$(run generate-obf-no-address "$PWD/${NAME}_seamarks.osm")
mv "$(ls "$d"/*.obf | head -1)" "${NAME}_seamarks.obf"
ls -l ./*.obf

inspect() { JAVA_OPTS="-Xmx6g" "$OMC/inspector.sh" -vmap -vmapobjects "$@" 2>&1 || true; }
inspect "$depth" > inspect_depth.txt
inspect "${NAME}_seamarks.obf" > inspect_seamarks.txt
for z in 11 13; do inspect -zoom=$z "$depth" > inspect_depth_z$z.txt; done
echo "== depth map sections"
grep -E 'Map data|Map level|Total map objects' inspect_depth.txt || true

rc=0
python3 "$TOOLS/obf_compare.py" --title "$NAME depth map: tags written vs kept" \
	--osm "${NAME}_contours.osm" "${NAME}"_soundings_z*.osm --dump inspect_depth.txt \
	--require contour=depth contourarea=depth point=depth depth ${REQUIRE_DEPTH:-} \
	> compare.md || rc=1
python3 "$TOOLS/obf_compare.py" --title "$NAME seamarks map: tags written vs kept" \
	--osm "${NAME}_seamarks.osm" --dump inspect_seamarks.txt \
	--require seamark:type seamark:name name ${REQUIRE_SEAMARKS:-} \
	>> compare.md || rc=1
# each sounding section holds every sounding of its set (dump at its own zoom)
echo -e "\n### Sounding sections\n" >> compare.md
for z in 11 13 15; do
	dump=inspect_depth_z$z.txt; [ $z = 15 ] && dump=inspect_depth.txt
	w=$(grep -c '<node' "${NAME}_soundings_z$z.osm" || true)
	k=$(grep -c 'point-depth' "$dump" || true)
	echo "- zoom $z: $w written, $k shown" >> compare.md
	[ "$k" -ge "$w" ] || { echo "  **Missing soundings at zoom $z**" >> compare.md; rc=1; }
done
grep -A2 '^### Required' compare.md
grep -A4 '^### Sounding' compare.md
exit $rc
