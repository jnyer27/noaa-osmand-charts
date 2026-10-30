# Changelog

Changes to NOAA Route (the app and its route packs), newest first. App versions are `0.1.<build>`; route packs are rebuilt weekly from NOAA's charts. Every routing change here passed all reference routes before release (see [Safety and testing process](README.md#safety-and-testing-process)).

NOAA Route is a planning aid, not a certified navigation system. Check every leg against a current chart.

## 2026-10-30: in-app chart, proof of concept (not released yet)

Chart display:
- **View map.** Under **Regions**, opens the Middle River chart with no route: depth contours, soundings and marks, shaded for the safe operating depth on the form. The chart file is about 1 MB inside the app, and the first open copies about 1.1 MB into the app's private storage. It is not a file in Download/noaa-charts.
- **View on map.** After planning, a button draws the route on that same chart. Depth areas are shaded for your safe operating depth (darker blue where the charted range is under it, pale where it is deeper), with contours, soundings in feet, land, lateral buoys and daybeacons in their IALA-B colors, lights, wrecks, rocks, obstructions and bridges. Legs to check are an orange dashed line. Your GPS position is the blue dot when location is allowed. The chart is bundled and works offline. It covers Middle River only, from the marina creeks out to the bay off Hart-Miller. **Plan route**, the GPX file, **Open in OsmAnd** and **Share GPX** are unchanged, and OsmAnd is still how you follow a route.

## 2026-10-23: app 0.1.142 (route packs build 128 unchanged)

GPX:
- **Route points of legs to check are named "check" with markers off too.** Turning off **Check markers in the GPX** used to drop the "check" from route point names (WP17 instead of WP17 check), the one warning every chart app shows in its route point list. Now only the orange and green markers and their symbols go away.

## 2026-10-22: app 0.1.139 (route packs build 128 unchanged)

Planning:
- **Move ends to navigable water.** New checkbox, off by default. When your start or destination is in water charted under your safe depth (a marina in a 0-6 ft creek at 3 ft) or in the buffer around charted hazards, the route starts or ends at the nearest water, along the water and within 0.5 nm, charted at least your safe depth, instead of being routed out through the shallow water and flagged. When the point is in water your boat cannot get out of at all (closed in by shallow water, a hazard buffer, a bridge or cable too low for your air draft, or land), the route starts or ends at the nearest water within 0.5 nm that leads to the rest of the trip, instead of stopping with "No route found". The report says which point moved, how far and why, and the leg from your point to that water is a check ("connects your point to usable water"), with check markers in the GPX. Off, nothing changes: such points are refused with the explanation as before (ends walled in only by land still move up to 250 m).

## 2026-10-20: app 0.1.133 (route packs build 128 unchanged)

Vector charts for OsmAnd:
- **OsmAnd plugin.** NOAA vector charts now come as an OsmAnd plugin too. **Get the OsmAnd plugin**, at the top of the Regions screen and on every region card, opens it in OsmAnd, which then lists every region under Extra maps in Maps & Resources and downloads its depth and seamarks maps itself, with no files to import. They are the same maps from the same weekly builds as **Download vector charts**; use one way or the other for a region. See [The OsmAnd plugin](https://jnyer27.github.io/noaa-osmand-charts/osmand.html#the-osmand-plugin) in the guide, with screenshots.
- The plugin covers all 46 regions (vector chart build 9, 2026-10-20).

## 2026-10-19: app 0.1.129 (route packs build 128 unchanged)

Planning:
- **Try to avoid checks.** A new button under the report, shown when the route has legs to check. It replans each stretch of flagged legs with what caused the checks (shallow water, hazard buffers, piers and seawalls, piles, security zones) as land, and keeps the new stretch only when its checks are gone (or only less shallow water is left) and it is at most 0.2 nm or 10% longer. Your start, via points and destination never move. The adjusted route is saved as `<name>_adjusted.gpx` and the report, Open in OsmAnd and Share GPX switch to it; the original file is unchanged. The status line says how many stretches were resolved. On the reference routes: the Cove Point security zone is now passed around (+0.03 nm), Maryland Marina to Mobjack Bay 5 of 5 stretches resolved (+0.02 nm), Maryland Marina to Ego Alley 1 of 3, Solomons to off Stingray Point 1 of 2. Narrow ICW channels mostly stay as they are: their checks are piles in the channel or soundings at its edge, with no way around.
- All reference routes unchanged.

## 2026-10-19: route packs build 128 (app 0.1.116 unchanged)

Routing and safety:
- **Coast Guard security zones are checks.** NOAA charts many security zones (33 CFR 165) with only the regulation's number and no restriction code, so routes went through them unreported: a Maryland to North Carolina route crossed the Cove Point LNG terminal's zone (33 CFR 165.502). A leg through any charted security zone now has a check note, with check markers in the GPX: "Cove Point Liquefied Natural Gas Terminal Security Zone: security zone, entry may be prohibited: check (33 CFR 165.502)". Routes are not blocked, because many zones apply only around particular vessels or when the Coast Guard announces them (New York harbor and the Hudson, the Houston Ship Channel). Read the regulation and the Local Notice to Mariners; if a zone applies, add a via point to go around it.
- The weekly build reads the current list of security zone regulations from eCFR, so new zones are caught as soon as NOAA charts them.
- All reference routes unchanged.

## 2026-10-18: app 0.1.116 (route packs build 120 unchanged)

Report and GPX:
- **Hazard notes name the hazard.** "passes near charted shoal soundings or obstructions" now names the closest one whose buffer the leg uses, with its charted depth and how close the leg comes, as a range the size of a 20 m grid cell: "passes 25-45 m from a charted 3.2 ft sounding: check", "a charted obstruction (2.1 ft)", "(depth unknown or 0 ft)", or "charted piles or snags". Rocks, wrecks and other obstructions are all called obstructions. Each hazard along a stretch of legs is named once. All reference routes are unchanged.
- **Screenshots of the check markers in OsmAnd** in the [user guide](https://jnyer27.github.io/noaa-osmand-charts/report.html#in-the-gpx-file) and README: the orange start and green end of a stretch on the map, and a marker's notes in the Check group.

## 2026-10-18: app 0.1.114 (route packs build 120 unchanged)

Report and GPX:
- **Shallow-water notes name your safe depth.** "95 m where charted depth may be under 5 ft" replaces "95 m in 0-6 ft", which was wrong for a safe depth over 6 ft (the band that spans 8 ft is 6-12 ft). The shallowest nearby sounding adds "under your 5 ft" when it is shallower. The guide explains how the length is estimated and that the sounding is searched within 100 m of the whole leg.
- **Check markers in the GPX** (new checkbox, on by default): an orange waypoint where each stretch of legs to check starts ("Check: legs 16-18 (0.21 nm)", with the notes) and a green one where it ends. OsmAnd shows them with its own icons in a Check group and announces them during navigation; other chart apps show them as waypoints (Danger Area, Flag, Green). Route points of checked legs are named "WP09 check".
- **Every report note is in the GPX** route points' descriptions, not only the shallow-water one.
- The app's GPX output is validated against the GPX 1.1 schema on every build.

## 2026-10-18: app 0.1.107 (route packs build 120, the weekly rebuild of 2026-10-17, unchanged)

App:
- **Share from Google Maps.** Google's Share button sends a short link with no coordinates; the app now follows it to the full Google Maps link and reads the position there. This one step needs the internet (planning stays offline). If Google gives only a place's name, the app says so and suggests sharing a dropped pin.
- **Google place links give the place itself**: its pin, not the center of the map view, which could be hundreds of meters away. Dropped-pin links (`/maps/search/lat,lon`) are read too.
- [Getting started](https://jnyer27.github.io/noaa-osmand-charts/getting-started.html#4-share-places-from-other-map-apps) has a new step on sharing places from other map apps, with [osm2gmaps](https://f-droid.org/packages/net.retiolus.osm2gmaps) for apps whose links NOAA Route cannot read.

## 2026-10-18: documentation (app and route packs unchanged)

- **OsmAnd screenshots** in the user guide and README: the same spot off Middle River and Hart-Miller Island with the chart map (NOAA's paper chart as an overlay) and with the vector charts (depth and seamarks maps drawn by OsmAnd).
- **OsmAnd's depth rules:** the README and user guide explain that OsmAnd's current release (5.4) draws all shallow contours alike and does not shade depth areas, and link to OsmAnd's new depth rules (dashed shallow contours, darker lines, depth area shading). Shading and the darker lines will appear once an OsmAnd release includes them; the shallow contours will then be written in the new 2 m class.
- **README FAQ:** the comparison of chart sources now covers the vector charts beside NCDS and ECDIS (regions 04 and 05 were still last published in May 2024). The vector chart FAQ records what was settled on the phone, and Troubleshooting suggests the vector charts for real buoy colors.
- **Check the route before you go** on [Planning a route](https://jnyer27.github.io/noaa-osmand-charts/planning.html#check-the-route-before-you-go) and in the README's Quick start: every flagged leg needs a look on a current chart, what to look for, and the check markers in OsmAnd.
- **Current Regions screenshot** on Getting started and the README top image, replacing the one from before the vector charts.
- **[ECDIS feature request](https://github.com/jnyer27/noaa-osmand-charts/issues/1)** refocused on the full S-52 look (daymarks, light sectors), since the vector charts now give weekly corrected charts.

## 2026-10-17: user guide and documentation (app and route packs unchanged)

- **[User guide](https://jnyer27.github.io/noaa-osmand-charts/)** on GitHub Pages: installing, getting started, every field on the main screen, reading the route report (what each "check" note means and what to do), following routes and sharing points in OsmAnd, chart maps and vector charts, the Regions screen and region card in every state, how the planner chooses a route, safety and limits, and troubleshooting. Written from the app's own buttons and messages, with screenshots of the main screen, the Regions screen, a region card and the share dialog.
- **Report a route problem:** a [GitHub issue form](https://github.com/jnyer27/noaa-osmand-charts/issues/new?template=route-problem.yml) asks for the spot, the trip, depth, air draft, Stay inside, installed regions, app version, and the route's GPX file and a screenshot.
- **README:** a Safety and testing process section, this changelog, the same screenshots, and the Regions screen, region card, share dialog and places described in full.
- For developers: `tools/cloud-setup.sh` and `tools/run_refs.sh` run the reference routes on any Linux machine or in a Claude Code cloud session.

## 2026-10-17: app 0.1.105 (route packs build 111 unchanged)

Routing and safety:
- **Coinjock bridge on the long-trip overview.** A 320 m overview cell that stands for a channel has no bridge when the channel water in it has none over it, and otherwise the bridge's highest span. It used to take the lowest span and any deck anywhere in the cell, so depending on how the trip's grid lined up, the Coinjock bridge cell closed for a 10 ft air draft and a Maryland to Little River trip went round Piney Island down the open side of Currituck Sound. New reference route: the same trip with the end point shifted (the alignment that failed).

## 2026-10-17: app 0.1.99, route packs build 111

Routing and safety:
- **ICW charted only as a fairway or a track.** Depth areas inside a charted fairway now count as channel (along Oak Island the ICW has no dredged area, and a 5 ft route drifted 74 m off it). So do cells on a charted recommended track that are deep enough and more than 200 m from any fairway or dredged area (in Bogue Sound the ICW is charted only as a track).
- **Channel marker lines follow the track.** A mark on or beside a recommended track gets its wrong-side line pointing away from the track. Lines start 20 m from the mark, and the mark's own cell stays open, because a channel one cell wide can have its buoys in that cell. In Bogue Sound the old lines ran along the ICW and blocked it.
- **Thin channels stay joined.** Where a charted deeper strip is thinner than a 20 m cell, gaps of up to two cells take its depth (the ICW at Bogue Sound buoy 11A).
- **Coinjock cut and other one-cell channels.** A channel one grid cell wide that runs diagonally past a land or pier corner can be followed cell to cell, without the pier price of the Coinjock bridge fenders at its corners. A 450 nm trip had left the ICW at Currituck Sound Light 111 for 12 nm of open sound.
- **No more 20 m zig-zags out of narrow channels** (Sneads Ferry, Snows Cut). 0-6 ft water beside a channel keeps its full near-shore cost. 0-6 ft water inside a charted dredged area or fairway is a shoaled stretch of the channel, not a gut, and skips it (south of Masonboro Inlet; Wrightsville to Southport had gone out the inlet).
- **Long trips keep narrow channels on the overview.** A 320 m overview block holding a clear dredged or fairway cell counts as that channel, without its channel markers. A block of 0-6 ft sound no longer turns safe because one cell in it is deep. A guide point cut off at full detail (a pocket by Topsail Inlet) is dropped instead of failing a Stay inside trip.
- **Guide point rejoin.** After a long trip is planned, the route is planned again at full detail for 2 km either side of each guide point and kept if cheaper and shorter. Leaving Snows Cut, a guide point had drawn a 200 m run up the Cape Fear River and back. Maryland Marina to Little River is 2.3 nm shorter.
- New reference routes: Light 111 to Albemarle Sound, the full Maryland Marina to Little River trip (5 ft, Stay inside), Oak Island, Bogue Sound, Sneads Ferry, Snows Cut. Wrightsville to Southport and Pensacola to Mobile now require the inside route.

## 2026-10-16

Routing and safety:
- **Stay inside (avoid open ocean).** New checkbox. Open ocean is the water reached from 12 nm offshore without crossing land or a COLREGS demarcation line, with openings under 600 m sealed and only 6 ft+ water flooded, then grown back 300 m. Maryland Marina to the Neuse inside is 300 nm through the Virginia Cut and the sounds.
- **Virginia Cut opened.** A ruined railway bridge on the Elizabeth River was a wall, and the Centerville Turnpike swing bridge's pivot put a hazard buffer over the channel. Ruined bridges are no longer walls, and unknown-depth hazards within about 20 m of a bridge count as bridge structure. Chesapeake to North Carolina trips had silently gone outside.
- **Pier areas by cell center**, so close bridge piers (Route 168) no longer close a canal.
- **Bridges over dredged channels** take the channel span; water under the same bridge outside the channel costs like a pier line (the Jordan Bridge, charted as one polygon without piers). Pier or fender lines in a dredged channel under a span you clear are the span's fenders, so the route uses that span (North Landing River bridge).
- **Canal staircases straightened** into long legs within one cell of the planned path (Virginia Cut east of Great Bridge: 39 tiny legs before).
- **Fewer open-water spikes.** A guide point off the best water is skipped when a full-detail piece is cheaper; overshoots at guide-point joins are trimmed.
- **Keeping to the middle.** A gentle cost rise over 300 m keeps routes toward the middle of gaps under 600 m. 0-6 ft water within 40 m of land, drying ground or a hazard buffer costs 10 times more, so routes do not take marsh guts as shortcuts (Goosecastle Point, Currituck Sound).
- **Straight legs no worse than the path.** A leg may not run a larger share through 0-6 ft water or hazard buffers, cross more piers, or move closer to danger than the planned path.
- **Overhead cables** charted only with a safe clearance (VERCSA) use it.

App:
- Vector charts for OsmAnd per region (download, open, update, delete).

## 2026-10-15

- All 46 NOAA chart regions (NCDS) have route data, built in parallel. Lake Erie depths are reported to Low Water Datum.
- Areas to be avoided (IMO routing measures for large ships) are reported, not avoided.
- Route pack workflow: test-only mode (reference routes on existing packs) and promote mode (publish tested beta packs from main).

## 2026-10-14

Routing and safety:
- **Channel markers.** Lateral buoys and daybeacons are never passed on the wrong side, in either direction.
- **Dredged channels preferred**, with a lower keep-off-the-shore cost inside them.
- **Long trips** settle each end at 20 m within 20 km (which inlet, which creek) before the overview.
- **Softer hazard buffer in deep-enough water**: over water charted at your depth, only the hazard itself is a wall and the buffer is used only when there is no other way, flagged for checking. Passable snags and piles of unknown depth are crossed only when needed and flagged.
- **Foul ground** is navigable unless it has a charted depth that is too shallow.

App:
- NCDS regions screen, trip region check, automatic Wi-Fi updates. NOAA chart maps for OsmAnd from the Regions screen.
- Route summary lists start, via points, destination, hazard buffer, charts used and when planned.
- Branch builds read a separate beta channel of route packs.

## 2026-10-13

- **Routes keep off the shore, piers and seawalls.** Cost rises up to 3.4 times within about 100 m of danger; pier and seawall lines are crossed only when needed and flagged.

## 2026-10-11 and 2026-10-12

- First NOAA Route app: a Kotlin port of `noaa-route.py` with offline route packs on a global 20 m grid, reference routes and full-detail long trips.
- When no route exists, the app names the limiting sounding or obstruction and the deepest depth setting that works. Ends cut off in a marina basin move to connected water and are reported.
- Shared positions and GPX files from OsmAnd; places.
- Before the app: the Termux scripts `noaa-charts.sh`, `noaa-ecdis-tiles.sh` and `noaa-route.py` (proof of concept).
