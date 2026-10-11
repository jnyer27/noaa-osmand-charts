# NOAA Charts for OsmAnd+ (Termux scripts)

Two scripts that build offline NOAA nautical chart overlays for OsmAnd+ on Android, run from Termux, plus a route planner (`noaa-route.py`) that writes GPX routes from NOAA's vector chart data.

| Script | Chart style | Source | Depths | Best for |
|---|---|---|---|---|
| `noaa-charts.sh` | Traditional paper chart (NCDS) | NOAA pre-built MBTiles regions | Feet | Familiar paper-chart look, whole regions in one download |
| `noaa-ecdis-tiles.sh` | ECDIS / S-52 (same look as SiiTech) | NOAA ECDIS Display Service, tile by tile | Meters | Most current data, true buoy colors, custom areas |

Both produce an OsmAnd `.sqlitedb` raster map in `Download/noaa-charts/`.

> **Not a certified navigation system.** These are raster renderings of NOAA ENC data for situational awareness. Keep official charts and your eyes on the water.

---

## One-time setup

1. Install Termux (from F-Droid or GitHub, not the Play Store version).
2. Copy the scripts into a Termux folder, for example `~/downloads`, then:
   ```
   chmod +x noaa-charts.sh noaa-ecdis-tiles.sh
   ```
3. Run either script once. It installs `curl` and `sqlite` if needed and asks for storage permission. Accept the Android prompt.
4. Optional: install **Termux:API** so the scripts can hold a wake lock and keep the phone from sleeping during long downloads.
5. In OsmAnd+, enable **Menu > Plugins > Online maps**. Overlays and underlays will not draw without it.

---

## noaa-charts.sh (paper chart style)

Downloads one of NOAA's regional NCDS MBTiles files and converts it to OsmAnd format.

### Usage
```
./noaa-charts.sh [options] REGION [REGION ...]
```
`REGION` is the NCDS code, such as `04`, `ncds_04`, or `19a`. Find your code by tapping your area on the NOAA map: https://distribution.charts.noaa.gov/ncds/

### Options
| Flag | Meaning |
|---|---|
| `-o DIR` | Output folder (default `~/storage/downloads/noaa-charts`) |
| `-k` | Keep the original `.mbtiles` after conversion |
| `-m` | MBTiles only, skip conversion (for OpenCPN or other apps) |
| `-f` | Force re-download even if your copy is current |
| `-M` | Merge all regions into one map named after them, e.g. `ncds_04_05.sqlitedb` |
| `-S` | Keep regions as separate maps, do not merge |
| `-i FILE` | Show info for a local `.mbtiles` or `.sqlitedb` |
| `-l` | List known region codes |
| `-h` | Help |

### Examples
```
./noaa-charts.sh 04            # region 04 (covers the upper Chesapeake)
./noaa-charts.sh 04 05         # two regions
./noaa-charts.sh -k 04         # also keep the .mbtiles
./noaa-charts.sh -M 04 05      # two regions merged into ncds_04_05.sqlitedb
```

### Merging regions
With two or more regions and neither `-M` nor `-S`, the script asks before downloading (default: merge). When run without a terminal (cron, scripts) it does not ask and keeps the maps separate.

- The merged file name lists every region, sorted: `04 05` and `05 04` both give `ncds_04_05.sqlitedb`.
- The per-region `.sqlitedb` files are kept so a later update to one region can re-merge without downloading the others. Import only the merged file into OsmAnd.
- The merged map is rebuilt only when a region changed (or with `-f`, or if it is missing).
- Where regions overlap, the larger tile image wins (usually the one with more chart drawn, not a blank edge).
- `-m` (MBTiles only) disables merging.

### Updates
Re-run the same command. The script compares NOAA's file date with your copy and only downloads when NOAA has published something newer.

**Caveat:** NOAA says these files update weekly, but region 04 was last published in May 2024. If the script keeps reporting "up to date", the region itself is stale. Use the ECDIS script when currency matters.

### Reading this chart style
- **Depths are in feet.**
- **Magenta teardrop = a light**, whatever its color. Read the color from the label: `Fl G 4s` is green, `Fl R 4s` is red.
- **Black dot with a height (e.g. "15ft") = a fixed beacon on a pile**, not a buoy.
- Even numbers are red, odd numbers are green ("red right returning").

---

## noaa-ecdis-tiles.sh (ECDIS / SiiTech style)

Requests tiles for a bounding box from NOAA's live ECDIS Display Service and packages them for OsmAnd. NOAA updates the underlying ENC data **every Friday afternoon**, including Notice to Mariners corrections.

### Usage
```
./noaa-ecdis-tiles.sh [options]
```
With no options it builds the Middle River / Hart-Miller Island area at zoom 10 to 16.

### Options
| Flag | Meaning |
|---|---|
| `-b W,S,E,N` | Bounding box in decimal degrees: West, South, East, North |
| `-z MIN-MAX` | Zoom range (default `10-16`, max 17) |
| `-n NAME` | Map name, also the file name (default `noaa_ecdis_middle_river`) |
| `-o DIR` | Output folder (default `~/storage/downloads/noaa-charts`) |
| `-j N` | Parallel downloads (default 4; please keep it modest) |
| `-u UNITS` | `meters` (default) or `feet` (experimental, may be ignored by NOAA) |
| `-r` | Force a refresh: discard cached tiles and pull everything fresh |
| `-k` | Keep cache: resume or retry only, never refresh |
| `-y` | Skip the confirmation prompt |
| `-h` | Help |

### Examples
```
# Home waters at full detail (the default box)
./noaa-ecdis-tiles.sh

# Northern Chesapeake: Susquehanna Flats to below the Bay Bridge
./noaa-ecdis-tiles.sh -b -76.65,38.85,-75.95,39.62 -z 10-15 -n north_bay

# A small, sharp map of one harbor
./noaa-ecdis-tiles.sh -b -76.50,38.95,-76.45,38.99 -z 12-16 -n annapolis
```

### Choosing area and zoom
Each zoom level roughly **quadruples** the tile count.

| Area | Zoom | Tiles | Rough time on Wi-Fi |
|---|---|---|---|
| Middle River (default box) | 10-16 | a few thousand | 10-20 min |
| Northern Bay | 10-15 | ~7,900 | 30-60 min |
| Northern Bay | 10-16 | ~31,000 | several hours |

Recommended approach: a large area at zoom 15, plus small zoom-16 maps for the creeks and harbors you actually use. The script shows the tile count and asks before downloading, and warns above 20,000 tiles.

### Refresh and resume (automatic)
Each run prints the mode it chose:

| Situation | What it does |
|---|---|
| The `.sqlitedb` already exists | **Refresh**: pulls every tile fresh from NOAA |
| Last run was interrupted, or a refresh did not finish | **Resume**: fetches only missing tiles |
| Last run had failed tiles | **Resume**: retries only those |
| No map yet | First build |

Your existing map is protected: if a refresh ends with failed tiles, the old `.sqlitedb` is left untouched. Re-run the same command to finish, and the new map replaces the old one only when complete.

**Weekly routine:** run on a Saturday to catch Friday's NOAA update, then re-import into OsmAnd (see below).
```
./noaa-ecdis-tiles.sh -y
./noaa-ecdis-tiles.sh -y -b -76.65,38.85,-75.95,39.62 -z 10-15 -n north_bay
```

### Reading this chart style
- **Depths are in meters.** The small subscript digit is the decimal: `3₃` = 3.3 m (about 10.8 ft), `0₉` = 0.9 m.
- Buoys and beacons show their **actual colors and daymark shapes** (red triangle, green square). Light rings are colored by light color.

---

## noaa-route.py (route planner, GPX output)

Plans a route for your safe operating depth over NOAA ENC vector charts (the same data the ECDIS service draws, with all NOAA update files applied) and writes a GPX route for OsmAnd.

> **Planning aid only.** Charted depths are at MLLW with no tide added, soundings are sparse, and the bottom changes. Check every leg against a current chart before running it.

### Setup
```
pkg install gdal
```
If GDAL then fails with `libOpenEXR... not found`, also run `pkg install openexr`.

The first run downloads NOAA's ENC set for Maryland (about 32 MB) to `~/.noaa-enc/`. Add `-u` to check NOAA for newer data (they update weekly).

### Usage
Easiest: run it with no options and answer the questions (start, destination, via points, draft, air draft, name, update check, open in OsmAnd):
```
python3 noaa-route.py
```
Your last draft and air draft become the defaults, and at the end it prints the full command so you can repeat the same route without questions.

Or give everything on the command line:
```
python3 noaa-route.py -f LAT,LON -t LAT,LON -d DEPTH_FT [options]
```
Points can be typed as `39.2508,-76.4886` or `39.2508 N, 76.4886 W`.

| Flag | Meaning |
|---|---|
| `-f LAT,LON` | Start (decimal degrees, west negative) |
| `-l` | Start from the phone's current location (see below) |
| `-t LAT,LON` | End |
| `-v LAT,LON` | Via point, repeatable, visited in order |
| `-d FEET` | Safe operating depth: the minimum charted depth you need, i.e. your draft plus margin (1.8 ft draft: use 3) |
| `-a FEET` | Air draft; bridge spans and overhead cables with less charted clearance are avoided |
| `-n NAME` | Route and file name (default `route`) |
| `-o DIR` | Output folder (default `~/storage/downloads/noaa-charts/routes`) |
| `-s STATES` | ENC sets, comma separated (default `MD`; e.g. `MD,VA,DE`) |
| `-r METERS` | Grid resolution (default auto: 10 m for short trips, up to 60 m for long ones) |
| `-b METERS` | Buffer around hazards (default 60) |
| `-u` | Check NOAA for newer ENC data first |
| `-O` | Open the routes folder in the Files app when done; tap the GPX and pick OsmAnd+ |

### Starting from where you are (`-l`)
Uses `termux-location`. One-time setup:
1. Install the **Termux:API** app (same source as Termux) and run `pkg install termux-api`.
2. Android **Settings > Apps > Termux:API > Permissions > Location > Allow**, with **Use precise location** on.

It tries GPS for up to 90 s, then the network location, and prints the fix and its accuracy.

### Example
```
# From here to Chesapeake City public dock
python3 noaa-route.py -l -t 39.5268,-75.8107 -d 3 -n here_to_chesapeake_city

# Middle River to Annapolis, 3 ft safe operating depth
python3 noaa-route.py -f 39.32,-76.40 -t 38.975,-76.482 -d 3 -n middle_river_annapolis
```
Takes 15-30 seconds on the phone. It prints each leg's course and distance with notes, then writes the GPX to `Download/noaa-charts/routes/`.

**Opening a route in OsmAnd:** use a file manager (Files app > Download > noaa-charts > routes), tap the `.gpx` and pick OsmAnd+. With `-O` (or answering yes in the interview) the planner opens that folder in Files for you. Do **not** use `termux-open` for GPX files: OsmAnd crashes on GPX shared from Termux (both view and send), though `.sqlitedb` maps import fine that way.

### How it decides where you can go
| Charted | Treatment |
|---|---|
| Depth area at least your depth (usually the 6 ft+ areas) | Used freely |
| Depth area spanning your depth (NOAA's usual 0-6 ft band) | Used only when needed; soundings shallower than your depth are hazards. Legs through it are flagged "check soundings" |
| Land, drying, unsurveyed or uncharted water | Never used |
| Rocks, wrecks, obstructions shallower than your depth + 1 ft or of unknown depth | Avoided with the hazard buffer |
| Bridge piers and piers drawn as areas | Avoided, without the buffer (they are visible) |
| Single charted piles | Not avoided: a grid cell is 10-60 m wide and would close narrow channels. Keep a lookout |
| Bridges | Passed only under spans with a charted clearance (the navigation spans), and with `-a` only if it is enough. Opening bridges (swing, bascule, lift, draw) are passed and reported with their closed clearance; check the opening schedule |
| Entry prohibited / area to be avoided | Never used |
| Other restricted areas (entry restricted, no wake, anchoring) | Used, but reported with the CFR rule |

Depths are compared with 5 cm of slack because NOAA stores feet rounded to 0.1 m (the 6 ft line is 1.8 m = 5.9 ft). NOAA's depth areas usually step 0 / 6 / 12 ft, so for a 3 ft depth most creeks fall in the flagged 0-6 ft band. The planner keeps those stretches as short as it can.

A point on land or a dock is moved to the nearest usable water, and the output says how far.

The planner searches a box around your points. If the only way runs outside it (around a point of land), it widens the box and tries again, up to twice. If a point is boxed in (by shallow water, land, a hazard buffer, or a bridge or cable too low for `-a`), it stops right away and says which, with an example position.

---

## Getting maps into OsmAnd+

OsmAnd keeps maps in its own private folder, so maps are **imported**, not copied.

1. Import from Termux:
   ```
   termux-open --chooser ~/storage/downloads/noaa-charts/<name>.sqlitedb
   ```
   and choose **OsmAnd+**. Or tap the file in a file manager (Download > noaa-charts) and use **Open with / Share > OsmAnd+**.
2. Go to **Configure map > Overlay map** (or **Underlay map**) and select the chart.
3. Slide the **transparency** control all the way right for a fully visible chart.

### Replacing a map after an update
OsmAnd reads the **copy it made at import**, not the file in Download. After any refresh:
1. **Maps & Resources > Local**, find the old chart, delete it.
2. Import the new file as above.

### Stacking maps
Use the large-area map as the **underlay** and a detailed home-waters map as the **overlay**. The sharper map shows on top wherever they overlap. You can also put an NCDS map and an ECDIS map in the two slots and flip between styles.

**Watch the units when switching:** NCDS shows feet, ECDIS shows meters. A "9" means 9 ft on one and 9 m on the other.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| Download finishes, then "file missing" or "not a valid MBTiles" | Storage permission was granted mid-run. Run the command again. |
| Chart imported but nothing shows | Check the Online maps plugin is on, the chart is selected, and transparency is not at the far left. |
| Still blank after that | The area may not be covered. For NCDS, run `./noaa-charts.sh -i <file>` to check bounds, or try another region code. |
| Changes not appearing after a refresh | Delete the old copy in OsmAnd's Local list and re-import. |
| OsmAnd crashes when a GPX is opened from Termux | Known: open GPX routes from the Files app instead (`noaa-route.py -O` opens the folder). |
| ECDIS run reports many failed tiles | NOAA's service may be updating (Friday afternoons) or down. Re-run later; it resumes. |
| "No tiles downloaded" | Same as above, or no network. |
| All lights look red/magenta (NCDS) | Normal paper-chart symbology. Read the label, or use the ECDIS map. |

---

## Files and folders

| Location | Contents |
|---|---|
| `Download/noaa-charts/*.sqlitedb` | Finished maps to import |
| `~/.noaa-ecdis/<name>/` | ECDIS tile cache used for resume/retry (safe to delete) |
| `Download/noaa-charts/routes/*.gpx` | Routes from `noaa-route.py` |
| `~/.noaa-enc/` | ENC chart data and converted cells for the route planner (safe to delete; re-downloads). Works offline once downloaded |
| `~/.noaa-enc/route-defaults.json` | Last draft and air draft, used as interview defaults |
| `Download/noaa-charts/ncds_XX_YY.sqlitedb` | Merged map (`-M`); the per-region files beside it are its sources |
| `Download/noaa-charts/.ncds_XX.stamp` | NCDS update timestamps (delete to force a re-check) |

## Data sources
- NOAA Chart Display Service MBTiles: https://distribution.charts.noaa.gov/ncds/
- NOAA ECDIS Display Service: https://gis.charttools.noaa.gov/arcgis/rest/services/MCS/ENCOnline/MapServer/exts/MaritimeChartService/MapServer
- NOAA ENC downloads (route planner): https://charts.noaa.gov/ENCs/ENCs.shtml
- OsmAnd `.sqlitedb` format follows https://github.com/tarwirdur/mbtiles2osmand
