# Project: NOAA charts for OsmAnd+ (Termux scripts)

Bash scripts that build offline NOAA nautical chart overlays (`.sqlitedb`) for OsmAnd+ on Android, run in Termux. Owner: Jason, Chesapeake Bay boater (home waters: Middle River, MD). Read `README.md` for user-facing usage.

## Files
- `noaa-charts.sh`: downloads NOAA NCDS regional MBTiles (paper-chart style, depths in feet) from `https://distribution.charts.noaa.gov/ncds/mbtiles/ncds_XX.mbtiles` and converts to OsmAnd `.sqlitedb`. `-M` merges multiple regions into one `ncds_XX_YY.sqlitedb` (`-S` = separate; asks if neither). **Working and confirmed on device.**
- `noaa-ecdis-tiles.sh`: pulls 256px tiles for a bbox from NOAA's ECDIS Display Service (S-52 style, depths in meters) via the ArcGIS MCS `export` endpoint and packs them into `.sqlitedb`. Auto-refresh/resume logic. **Confirmed on device.**
- `README.md`: user guide. Keep it in sync with any script changes.

## Status
- [x] Both scripts working on Jason's phone (Termux, aarch64)
- [x] README written
- [x] Private GitHub repo created and pushed: https://github.com/jnyer27/noaa-osmand-charts
- [x] Region merge (`-M`) confirmed on device: `ncds_03_04_05.sqlitedb` (1.7 GB, 1,185,109 tiles) imports and renders in OsmAnd (2026-10-11)

## First task: push to GitHub
1. `git init -b main && git add . && git commit -m "NOAA NCDS and ECDIS chart scripts for OsmAnd+"`
2. Check `gh auth status`; if not logged in, ask Jason to run `gh auth login` himself (web browser flow). Never ask for or handle tokens.
3. `gh repo create noaa-osmand-charts --private --source . --push`
4. Confirm with `gh repo view --web` or by printing the repo URL.

## Hard-won technical decisions (do not regress)
- **OsmAnd `.sqlitedb` format must match tarwirdur/mbtiles2osmand exactly:**
  - `CREATE TABLE tiles (x int, y int, z int, s int, image blob, PRIMARY KEY (x,y,z,s));`
  - `CREATE TABLE info (maxzoom Int, minzoom Int);` with values `max(z), min(z)` of stored z
  - `y = 2^zoom - 1 - tile_row` (MBTiles TMS to XYZ); `z = 17 - zoom` (BigPlanet inverted zoom)
  - An earlier version added `inversiveZoom`, `tilesize`, `ellipsoid`, `timecolumn`, `url` columns to `info`: **OsmAnd displayed nothing.** Do not add extra columns.
- **Termux storage race:** `termux-setup-storage` returns before the user answers and then rebuilds `~/storage`. Scripts wait for the `~/storage/downloads` symlink before creating folders. Keep this.
- **OsmAnd imports a copy.** Editing a file in Download does not change what OsmAnd shows; the user must delete the old map in Maps & Resources > Local and re-import.
- **ECDIS refresh safety:** a refresh that ends with failed tiles must never overwrite an existing complete `.sqlitedb`. The `.refreshing` marker in the tile cache tracks an unfinished refresh.
- Shebang is `#!/data/data/com.termux/files/usr/bin/bash` (Termux path).
- Scripts use `set -euo pipefail`; watch for `grep` with no matches killing pipelines.

- **Merge:** per-region `.sqlitedb` files are kept as merge sources. Overlapping tiles keep the larger image via `INSERT ... ON CONFLICT DO UPDATE` (needs SQLite 3.24+). Merged map is rebuilt only if a region changed, `-f`, or it is missing.

## Known issues / ideas
- NCDS region latitude bounds (from each file's metadata): 02a 41.10-42.80, 03 39.73-41.10, 04 38.23-39.73, 05 35.50-38.23, 06 32.54-35.50. Region 04 ends near Toms River, NJ; northern NJ / NY Harbor needs 03.
- Overlap is large (about 251k shared tiles between 04 and 05). If seams look wrong at 39.73N or 38.23N, revisit the larger-image rule.
- NCDS region `ncds_04` last published May 2024 despite NOAA claiming weekly updates. ECDIS is the current-data option.
- `noaa-ecdis-tiles.sh -u feet` sends a guessed MCS `display_params` (`DisplayDepthUnits=2`); unverified against NOAA. If Jason reports depths still in meters, investigate the MCS `parameters` operation on the service.
- ECDIS script's tile-size estimate (25 KB/tile) is rough.

## Abandoned approach (context only)
Raw ENC S-57 to OSM (JOSM SeaChart `js57toosm`) to OsmAnd `.obf` via OsmAndMapCreator. It worked technically (needed an Android-native sqlite-jdbc swap), but `js57toosm` ignores ENC update files (.001+), so data lacks Notice to Mariners corrections. Dropped in favor of ECDIS tiles. Do not revive without solving update application (GDAL's S-57 reader applies updates).

## Style
- Jason prefers direct, concise communication.
- **No em dashes** in any prose, comments, or docs.
- Test changes before claiming they work; NOAA endpoints may be unreachable from some sandboxes, so a local stub server is fine for logic tests, but say what was and was not verified.
- Not a certified navigation tool; keep that caveat in the README.
