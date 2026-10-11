# Project: NOAA charts for OsmAnd+ (Termux scripts)

Bash scripts that build offline NOAA nautical chart overlays (`.sqlitedb`) for OsmAnd+ on Android, run in Termux. Owner: Jason, Chesapeake Bay boater (home waters: Middle River, MD). Read `README.md` for user-facing usage.

## Files
- `noaa-charts.sh`: downloads NOAA NCDS regional MBTiles (paper-chart style, depths in feet) from `https://distribution.charts.noaa.gov/ncds/mbtiles/ncds_XX.mbtiles` and converts to OsmAnd `.sqlitedb`. `-M` merges multiple regions into one `ncds_XX_YY.sqlitedb` (`-S` = separate; asks if neither). **Working and confirmed on device.**
- `noaa-ecdis-tiles.sh`: pulls 256px tiles for a bbox from NOAA's ECDIS Display Service (S-52 style, depths in meters) via the ArcGIS MCS `export` endpoint and packs them into `.sqlitedb`. Auto-refresh/resume logic. **Confirmed on device.**
- `noaa-route.py`: route planner. NOAA ENC S-57 (state zip, updates applied by GDAL `UPDATES=APPLY`) -> per-cell GeoPackage cache -> rasterized Byte grid (gdal_rasterize, bands burned low to high detail, each cell resets its own M_COVR coverage) -> pure-Python A* (no numpy) -> GPX `<rte>`. Python because routing in bash is impractical. **Confirmed on device:** Middle River to Annapolis (3 ft draft, 26.3 nm) and Maryland Marina to Chesapeake City public dock (34.3 nm, via APG waters and C&D Canal) import and display correctly in OsmAnd (2026-10-11).
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

- **Route planner rules:** US ENC depth areas step 0/1.8/3.6 m, so a 3 ft draft falls inside the 0-1.8 m band. That band is "marginal" (cost x4, flagged), not blocked, with soundings < draft as hazards. Bridges: decks without VERCLR block, spans with VERCLR re-open (most detailed cell wins, same reset as depths); without this the route went under the Bay Bridge's low Sandy Point approach spans. Restricted areas: only navigation RESTRN codes are flagged; Maryland's bay-wide no-discharge zone (RESTRN 16) otherwise drowns them out.
- `noaa-route.py -l` starts from `termux-location` (GPS 90 s, then network 30 s). Needs Termux:API app + `pkg termux-api` + Location permission; verified a fix is returned (from inland, so no full route from a live fix yet).
- Route planner lessons from Hart-Miller to Bear Creek (2026-10-11): (1) search box padding must be generous (now max(0.06 deg, 25% of span), auto-widened x2.5 then x5 on failure); Sparrows Point sat on the old box edge. (2) Opening bridges (CATBRG 2,3,4,5,7,8) have no VERCLR and were walls; now passable and reported. (3) PILPNT/PYLONS are visible structures: blocked cell only, no hazard buffer (the 60 m buffer closed the swing span). (4) SPAN_OK is dilated 1 cell so an opening cuts the all-touched deck band. (5) Auto resolution 10-60 m, finest under 5M cells. (6) M_COVR reset now clears all 5 flag bands, otherwise overview cells' whole-bridge polygons (US3 "Eastern Channel Bridge", 58 ft) leaked into reports.
- Route planner speed/debug (2026-10-11): rasterizing per cell (~20 gdal calls per cell, ~60 ms process overhead each) took ~55 s for 45 cells; now one OGR VRT union layer per usage band (`band{N}.vrt`), ~9 s. On failure, `enclosure()` checks whether each end's water reaches the grid edge; if not, it stops (no wasted widening, which cost ~4 min) and lists the walls. DEPARE too shallow is burned as SHALLOW (5) so diagnostics can tell it from land.
- Single PILPNT/PYLONS points no longer block (one pile closed Ego Alley at 20 m grid); only area geometries do (`OGR_GEOMETRY IN ('POLYGON','MULTIPOLYGON')`).
- `-d` is the safe operating depth, not boat draft (Jason: 1.8 ft draft, 3 ft safe depth). Depths compared with ROUNDING_M = 0.05 since NOAA stores 6 ft as 1.8 m.
- GDAL on Termux needed `pkg install openexr` (missing `libOpenEXR` at first run).

## Known issues / ideas
- ECDIS service: after ~32k tiles in one run, some new connections to NOAA hung until `--connect-timeout 20`, dropping throughput from ~17 to ~1.5 tiles/s. Possible fix: shorter connect timeout or reusing connections (several URLs per curl).
- NCDS region latitude bounds (from each file's metadata): 02a 41.10-42.80, 03 39.73-41.10, 04 38.23-39.73, 05 35.50-38.23, 06 32.54-35.50. Region 04 ends near Toms River, NJ; northern NJ / NY Harbor needs 03.
- Overlap is large (about 251k shared tiles between 04 and 05). If seams look wrong at 39.73N or 38.23N, revisit the larger-image rule.
- NCDS region `ncds_04` last published May 2024 despite NOAA claiming weekly updates. ECDIS is the current-data option.
- `noaa-ecdis-tiles.sh -u feet` sends a guessed MCS `display_params` (`DisplayDepthUnits=2`); unverified against NOAA. If Jason reports depths still in meters, investigate the MCS `parameters` operation on the service.
- ECDIS size estimate is a 3-8 KB/tile range, from measured maps: north bay 50 mi z10-15 = 139 MB / 38,700 tiles (3.7 KB), Middle River z10-16 = 12 MB / 1,700 tiles (7 KB). Old 25 KB/tile guess overstated ~7x.

## Abandoned approach (context only)
Raw ENC S-57 to OSM (JOSM SeaChart `js57toosm`) to OsmAnd `.obf` via OsmAndMapCreator. It worked technically (needed an Android-native sqlite-jdbc swap), but `js57toosm` ignores ENC update files (.001+), so data lacks Notice to Mariners corrections. Dropped in favor of ECDIS tiles. Do not revive without solving update application (GDAL's S-57 reader applies updates).

## Style
- Jason prefers direct, concise communication.
- **No em dashes** in any prose, comments, or docs.
- Test changes before claiming they work; NOAA endpoints may be unreachable from some sandboxes, so a local stub server is fine for logic tests, but say what was and was not verified.
- Not a certified navigation tool; keep that caveat in the README.
