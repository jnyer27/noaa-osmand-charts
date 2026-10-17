# Changelog

Changes to NOAA Route (the app and its route packs), newest first. App versions are `0.1.<build>`; route packs are rebuilt weekly from NOAA's charts. Every routing change here passed all reference routes before release (see [Safety and testing process](README.md#safety-and-testing-process)).

NOAA Route is a planning aid, not a certified navigation system. Check every leg against a current chart.

## Unreleased

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
