# Changelog

Changes to NOAA Route (the app and its route packs), newest first. App versions are `0.1.<build>`; route packs are rebuilt weekly from NOAA's charts. Every routing change here passed all reference routes before release (see [Safety and testing process](README.md#safety-and-testing-process)).

NOAA Route is a planning aid, not a certified navigation system. Check every leg against a current chart.

## 2026-11-03: tap a leg for its details, add a via from the chart (not released yet)

The chart:
- **Tap a leg for its details.** A box opens with the leg's number in the title ("Leg 12: check" for a leg to check), its course and distance, and each note on its own line, instead of one long line under the map. The leg is highlighted in yellow while the box is open. How close a tap has to be follows the zoom, about a fingertip, so neighboring legs are easier to pick apart.
- **Add a via point or set the destination from the chart.** Press and hold anywhere on the chart for **Add as via point** or **Set as destination**; a leg's details box has **Add via here** and **Destination here** for the spot you tapped. The planning screen gets that position. A via point goes between the trip points where it adds the least distance, not at the end of the list. The trip is planned again and the chart reopens on the new route; if it cannot be planned, the planning screen says why.

No pack rebuild.

## 2026-11-03: Move ends keeps a 0-6 ft slip, Coinjock and Oak Island notes (app 0.1.195, route packs build 153 unchanged)

Planning:
- **Move ends to navigable water keeps a point in water that may be under your safe depth.** A marina in a 0-6 ft creek at 3 ft starts where you put it, and its legs are flagged (orange on the chart, check markers in the GPX), the same as with the option off. The option now moves only a point on land, in water charted entirely under your safe depth, in the buffer around a charted hazard, or boxed in, and only to the nearest usable water clear of hazards. This reverses the 2026-10-22 behavior, which walked such a point out to water charted at least your safe depth (issue #27).
- **A pin on land or a dock ends at the nearest usable water.** With Move ends on, it was walked on to water charted at least your safe depth: at Ego Alley at 2 ft the route stopped 158 m out instead of in the alley, 56 m from the pin, and at Oriental the last leg crossed a pier.
- **The Coinjock bridge leg keeps to the dredged cut.** The straight line south of the 65 ft span drifted a cell west of the cut's staircase and crossed the pier cell beside it, with "runs along or across charted piers or seawalls: check" for every boat. The fender cells in the span are part of the opening; the bridge note names the span (issue #34).

The report:
- **A fairway leg is not "under your depth" for a shoal outside the fairway.** On a leg mostly in a charted fairway or dredged channel, a sounding outside the channel is named only within the hazard buffer of the line. The Oak Island fairway at 2 ft said "under your 2 ft" for a 1.0 ft sounding 93 m off the line, outside the fairway; it now leads with the fairway's own soundings. The 0 ft obstruction in the fairway is still a check (issue #41).

No pack rebuild.

## 2026-11-02: the chart key can be hidden (in app 0.1.185)

The chart:

- **Tap the key under the map to hide it.** It collapses to one line, "Not for navigation. Show key". The map gets that space. Tap the line to open the key again. The choice is remembered the next time the chart opens. A chart that fails to load still shows the error in full.
- **Follow keeps the chart on the boat.** The button recenters on the GPS fix and stays with it as you move. Pan the map and Follow turns off. If location is not allowed, or GPS is off, a message says so and the chart does not crash.
- **A compass shows which way the chart is turned.** Tap it for north up. **Course up** turns the chart with your heading while Follow is on and the boat is moving. The key says north is up, or that the map turns with your course, matching the choice.
- **The app follows the phone's light or dark mode.** The planning screen, the report and Regions turn dark with the phone; the report's coloured rows use lighter shades of the same colours at night. On the chart, Follow, the compass, Course up, the key, and Back use a light pair or a dark pair together. The tapped leg's note gets a dark amber bar at night. Android is told not to auto-darken the app (in its themes, where the setting takes effect; in the manifest it was ignored), which had turned the white panels dark and left the black lettering on them.

No pack rebuild.

## 2026-11-02: the helm says what the buttons do (in app 0.1.185)

The screen:

- **The region map has a key.** Green is route data installed, amber is an update, blue dashed is published and not downloaded, faint is no route data. "chart map" means an OsmAnd file, not the route data and not the chart in this app. Via points are drawn. The GPS fix is blue and is nudged off the start when they sit on top of each other. A tap has a little slack.
- **Route data is called route data.** Plan says "Download route data for planning first" when none is installed. The region card's main button is "Download this region for planning". OsmAnd files are under "For OsmAnd". The .mbtiles-only button is off the card. Deleting route data asks first and names what is removed.
- **The button is "Look for a way around the checks".** The line that comes back leads with how many check stretches are still on the route. If any remain, the button stays. Share follows the route on screen, and the save line says the original file is unchanged.
- **Depth, air draft, and the helpers.** The depth label keeps the example "e.g. 3 (draft plus margin)" even when a saved depth is showing. Stay inside says "Stay inside the inlets and bay mouths", not the territorial sea baseline. Region rows are at least 48 dp and look like buttons. The app uses a light theme so helper text stays dark on white.
- **The chart key names the boat's depth.** Lighter blue may be under your depth. A sounding in red is under it. North is up, and the key says about how wide the chart is. "Not for navigation" is its own sentence.
- **A bridge span says vertical clearance**, so the number is the height under the span. A position can be degrees and decimal minutes, such as 39 19.4 N, 76 24.0 W.

No pack rebuild.

## 2026-11-02: land pins, bridge notes, and what a check covers (in app 0.1.185)

Planning:

- **A pin on land or a dock stays at the nearest usable water.** Move ends was treating that cell as shallow water and walking on to the nearest water charted at least the safe depth. At Wrightsville Beach that walk left the ICW and started the trip to the east. The Alligator-Pungo end did the same and drew the line back across a 2 ft sounding, with no sentence saying the pin was on land. The report now says the point is on land or a dock and how far the water is. Move ends still walks a point that is already in the water out of a depth area under your safe depth. That sentence says whether the water may be under your depth, is charted entirely under it, or is near a hazard. One distance per point.
- **A refusal blocked by a bridge or cable names that structure, and does not suggest a shallower depth.** Bear Creek at 55 ft of air draft is still refused. The message leads with the fixed span and the cable under the air draft (about 55 ft and 46 ft at the entrance), not the opening bridges inside the creek and not "try 0.5 ft".
- **An opening bridge says whether it has to open.** A closed clearance under your air draft says the bridge has to open. A closed clearance over your air draft says how many feet it clears by. With no air draft the note still says it may need an opening.
- **A cable beside the route is not reported as if the boat were on it.** On the White Oak River the channel cable is about 100 ft. A 54.8 ft cable on the next cells was rounded to 55 ft and listed on the channel leg. The tenth of a foot is kept when the whole-foot label rounds up. A cable the route only passes beside, and that is under the air draft, says the route is not on it and is not a check.
- **Aberdeen Proving Ground stays a check for as long as the route is inside it.** The entry-restricted note (33 CFR 334.140) is repeated on each of those legs, and it says this is a firing danger zone and that the ship channel south of Pooles Island is the other way. The zone is not blocked.
- **The C&D Canal note no longer says entry is restricted.** 33 CFR 162.40 is the canal traffic rule. Anchoring stays restricted. The route is unchanged.
- **A pier cell on a span, beside the dredged opening, is a wall.** The opening stays open. A straight line that comes within about 14 m of both that fender and the opening is not used. Coinjock's one chord through the fender and the dredged span is split. The line can still pass about 12 m from the fender cell's center, which is the corner of the next cell.
- **A security zone on part of a long leg says how much of the leg is inside.** Cove Point is about a mile of a 3 nm leg, and the note says that length. The leg is still one leg.
- **A fairway whose soundings are well under the boat is not called "under your depth".** Along Oak Island at 2 ft the fairway is stored as a wide depth range, so the old note said the water may be under 2 ft while the soundings on it are 9 to 13 ft. The note now leads with that sounding. A sounding the chart labels under your safe depth is still "under your depth". A printed 2 ft is not called under a 2 ft safe depth.

No pack rebuild. The packs already store the clearances and the regulation numbers. The Termux script does not have Move ends.

## 2026-11-02: stay out of prohibited water, and stay in the Carolina cuts (in app 0.1.185)

Planning:

- **A pin inside entry-prohibited water does not get a line drawn into it.** The Snows Cut close look ends at 34.02000 N, 77.94200 W, inside Military Ocean Terminal Sunny Point, 33 CFR 334.450. The route used to stop about 80 m outside and then draw an unchecked leg back to the pin, and the channel leg before that was labeled "entry prohibited" for its whole length. The route now ends on the last water outside the area, names the area and the regulation, and does not draw that leg. A channel leg that only clips the boundary is not called prohibited for the whole leg.
- **At 4 ft, Bogue Sound stays in the 6-12 ft fairway.** A 3.9 ft sounding is not a wall at a 4 ft safe depth, and the route left the fairway for 0-6 ft water north of buoys 10A, 11A and 12, inside the circle the 5 ft route already avoids. Open 0-6 ft water within about 160 m of a safe dredged channel or fairway now costs more than the channel, so that northern water does not win.
- **Snows Cut at 2 ft and 4 ft stays in the dredged cut.** The line used to step east into 0-6 ft water beside charted 0.7 ft and 1.0 ft soundings, about 34.0387 N, 77.9292 W, while the fairway continued on the other side. That corner is no longer the cheaper line.
- **A shoal leg names the distance to a sounding under your safe depth even when the leg never steps into the hazard buffer.** The Bogue Sound hop at 2 ft used to say only that the depth area may be under 2 ft. It now also says how far it passes the 1.0 ft sounding. Part of that 2 ft line still touches the north edge of the marked strip (about 34.7236 N), the same latitude a 6 ft route was already accepted on. The 4 ft route holds the 6-12 ft fairway one cell south of the old northern run.

No pack rebuild. The Termux script does not have this pin stop or the extra cost beside a channel.

## 2026-11-02: Kent Narrows and the Rock Hall bar (in app 0.1.185)

Planning:

- **Kent Narrows at 6 ft is not drawn at 40 m on the wrong side of the marks.** Ego Alley to St. Michaels via the narrows had no route in the 20 m window, widened, and came down the north side of the cut beside charted 0.3 ft to 3.9 ft soundings, including the wrong side of a mark. A coarser grid is no longer used for a narrow cut when that line crosses a mark. The app stops and tells you to move a point or add a via in the marked channel.
- **Rock Hall at 6 ft with Move ends stops before the 0-6 ft bar.** The harbor pin is in the 0-6 ft band. The route used to spend about 750 m in that band and finish across a charted 1 ft sounding, and the report gave two distances for the same point (39 m to water charted at least 6 ft, and 372 m to water that leads out). There is now one distance. The route stops on the near side of the bar, about 1.7 nm short of the pin, and does not draw a line across the 1 ft sounding. At 4 ft the same trip still runs in, which matches the chart: the bar's soundings are not under a 4 ft safe depth the way the 1 ft sounding is under 6 ft.

No pack rebuild.

## 2026-11-02: the checks lead, and the chart keeps one depth key (in app 0.1.185)

The report and the chart:

- **The plan no longer opens with "saved".** The first line is how many legs to check. Under it: shoal water, bridges, cables, and restricted areas, then "Not for navigation", then where the GPX file was saved, then the leg table. Shoal rows are dark red, bridges blue, cables purple, restricted areas purple, and the check count a deep orange.
- **Entry restricted and entry prohibited are checks.** The leg is orange on the chart and the GPX point is named "check". Anchoring, fishing and no-wake rules stay information. A security zone was already a check.
- **The GPX description carries the same warning lines**, plus the start, via points and destination. **Use as trip** on a file this app wrote puts the via points back and shows those lines. A file from another app still uses the first and last point.
- **Route data no longer paints depth in red and amber.** The chart's blue ramp is the only depth key. The overlay leaves depth areas clear and keeps bright red for a hazard, black for a pier or structure, and a purple hatch for entry prohibited. A bridge that is too low stays maroon. A cable that is too low is navy, and both are labeled vertical clearance, so orange on the route line is only a leg to check.
- **On the chart, each leg has its number.** Tap the leg and the note from the report is shown under the chart.

No pack rebuild.

## 2026-11-02: air draft, and Move ends stops at a low cable (in app 0.1.185)

Planning:
- **Move ends does not draw across a bridge or cable the boat cannot clear.** With Move ends on, a straight line from your pin to the water the route can use used to cross a charted overhead cable, and the land beyond it, and the note called that line "not checked" in the same sentence as the cable. Maryland Marina to the upper Bush River pin did this for a 55 ft air draft and a 25 ft air draft: the checked water stopped south of a charted 20 ft cable, and the last leg ran across that cable and then across land. The route now stops on the near side of the lowest cable or bridge the air draft cannot clear, names that charted clearance, and does not cross the structure or the land beyond it. A 10 ft air draft still goes under the 20 ft cable. With Move ends off, those taller boats are still refused.
- **A shallower safe depth is suggested only when a route at that depth joins the two ends.** The old line looked only at whether water near the point reached the edge of a 4 km window. For the 25 ft boat that window opened at 1 ft, and the refusal said to try 1 ft, but the cable still blocked the river at 1 ft. The suggestion is kept when the whole trip plans at that depth (Maryland Marina at 6 ft with a 10 ft air draft still says 5 ft works). When the wall is the cable, no depth is offered.
- **Air draft is no longer an empty field you can plan past without noticing.** The hint is no longer "optional". Plan route asks for a height in feet. **Don't check bridges and cables** is a separate checkbox, off by default, and it is the only way to plan with the field empty. That choice is the first line of the report and a sentence in the GPX description. Bridge and cable notes on that route are checks, with the orange and green markers. A height you type is used even if the checkbox is on.

No pack rebuild. The Termux script has no Move ends option. Its air draft question already says that Enter skips the bridge and cable checks.

## 2026-11-02: Pensacola to Mobile stays in the GIWW (in app 0.1.185)

Planning:
- **On a long trip's coarse overview, the steep shore rise no longer applies beside a depth area that merely straddles the safe depth.** The 20 m grid is unchanged: that band still gets the same rise as land, so the route passes east of the 0-6 ft knoll north of the Bay Bridge, clear of the 120 m check. The overview was using that steep rise too. At 60 m it covers about 300 m, so a lagoon charted deep enough cost several times the open Gulf. Pensacola to Mobile at 4 ft left the GIWW through Pensacola Pass, ran the Gulf south of the beach at about 30.22 N, and came back in at the Mobile Bay entrance: 75.8 nm, outside the 58-70 nm range. The overview now uses only the gentler rise, and only as the gap to the deepest water within about 300 m through the same water, not across a beach or island. Where the strip is already the deepest water there, it keeps its ordinary cost. Pensacola to Mobile is 63.1 nm on the GIWW, through Big Lagoon, the Gulf Shores canal and the east side of Mobile Bay. No pack rebuild. The straight-leg rule is unchanged: a long leg still may not cut a shoal the path went around, a hazard buffer the path did not use, or water charted entirely under the safe depth.

## 2026-11-02: narrow channels keep to the middle (in app 0.1.185)

Planning:
- **A narrow dredged channel or fairway is followed down the middle.** The cost was flat across a dredged area, so the route took whichever edge made a bend shortest, and straightening then joined those edge points. In a channel no wider than about 120 m the cost now rises toward the edge. The middle keeps the old cost, so a long trip still prefers the ICW over going outside. A straightened leg in that channel has to stay at least a quarter of the local width off the edge, and no more than 30 m is required. A one-cell channel is already its own middle, so those legs still straighten. Wider dredged areas, wide fairways and open water are unchanged. The pack has no narrow flag, and the builder's narrow-strip rule (an ordinary depth area at most two cells wide) does not mark dredged areas, so the width is measured on the grid when the route is planned. No pack rebuild.
- The dredged cut south of Middle River (about 39.23 N, 76.396 W, on the way from Maryland Marina to Ego Alley) is two or three 20 m cells wide, about 60 to 80 m, so it counts as narrow. Where that cut is wide enough to have a middle, about two thirds of the route is now at least 25 m from the edge (the middle cell is about 30 m out). The 2-cell pinches are still about 10 m from the edge, because that is the middle of the cut there. Maryland Marina to Ego Alley at 5 ft with Stay inside and Move ends stays 26.5 nm, 36 points, planned in about 1 s. The Termux script is unchanged.

## 2026-11-02: east of the Bay Bridge knoll (in app 0.1.185)

Planning:
- **A long leg does not cut a shoal the path only passed beside.** Straightening a 7.9 nm leg from Maryland Marina to Ego Alley at 5 ft clipped the west edge of the 0-6 ft knoll about a mile north of the Bay Bridge (chart US5MD1ND, about 39.012 N, 76.386 W). The planned path was in safe water just west of the knoll. The straight line was allowed to cross the 0-6 ft cells because they touched that safe water. A long leg may now cross 0-6 ft water only when it touches 0-6 ft water the path already used. The one-cell pass that joins a staircase may still graze 0-6 ft water touching safe water the path used (a channel corner, or the edge the path is following). Neither pass may enter a hazard buffer the path did not use, or water charted entirely under the safe depth.
- **Open water keeps off a depth area that straddles your safe depth.** The same knoll is a small 0-6 ft patch with 12 ft and deeper water to its east. The route now passes east of it, in that deeper water, instead of running along the 6-12 ft side and clipping the shoal. On the 20 m grid that keep-off is the same rise used beside land. On a long trip's overview only the gentler rise applies, because the steep rise there sent a lagoon trip out to sea (see the Pensacola note above). It does not apply inside a dredged channel or fairway, so the ICW is unchanged. The 4.9 ft sounding on the knoll is 1.5 m, which the chart labels 5 ft, so at a 5 ft safe depth it is not a hazard and has no buffer. The depth area is what the leg stays out of.
- Maryland Marina to Ego Alley at 5 ft is 26.5 nm with Stay inside and Move ends on, and the leg at 39.012 N is about 220 m east of the knoll with no shallow-water note. Reference routes are unchanged within their ranges, including Ego Alley at 3 and 5 ft, Coinjock, Kitty Hawk, Bogue Sound, Boot Key, and the routes that are supposed to be refused.

## 2026-11-02: sounding labels, narrow channels, and straight legs (in app 0.1.185)

Planning:
- **A sounding blocks only when the chart labels it shallower than your safe depth.** Whole feet, the same rounding as the chart. 1.5 m prints as 5, so at a 5 ft safe depth it is not a hazard and gets no 60 m buffer. A sounding the chart labels 4 ft still does. A label equal to your depth is not a wall, including one stored a little under the exact meter value but still printed as that foot (1.4 m is 4.6 ft and prints as 5). Depth areas are unchanged and still use the 5 cm slack, so a 1.8 m area stays the 6 ft band. The previous rule treated a label equal to your safe depth as a hazard, and that closed the Maryland Marina slip at 5 ft on a charted 5 ft sounding in the 0-6 ft exit.
- **A one-cell channel and a 6-12 ft edge straighten.** See the Bay Bridge knoll note above. A long leg may cross 0-6 ft water only when it touches 0-6 ft water the path already used. The one-cell pass may also cross 0-6 ft water that touches safe water the path used: the corner of a dredged channel one cell wide, or the shoal side of a 6-12 ft edge the route is following. A shoal the path went around is not cut off. A hazard buffer the path did not use, and water charted entirely under your safe depth, stay forbidden.

Routing and safety:
- **A narrow deeper strip keeps its depth.** The shallow edge of a depth area is still stored when it covers more than about a quarter of a 20 m cell, so a route does not clip a shoal on the rim of a wide deep area (the 0-6 ft knoll west of the Bay Bridge). A deeper strip only a cell or two wide, at least three cells long, keeps the deeper depth at the cell center. That is the 6-12 ft ICW in Bogue Sound, charted as an ordinary depth area between 0-6 ft shoals: storing the shoal in those cells sent a 5 ft route off the track. Dredged areas, fairways and recommended tracks already kept the deeper center.

## 2026-11-01: chart crossings on the route report (in app 0.1.185)

Planning:
- **A leg is checked against the chart's depth areas, not only the 20 m grid.** After planning, each leg is compared with the depth areas (DEPARE and DRGARE) in a downloaded region chart, or the bundled Middle River chart when that is the one on the phone. A leg that crosses an area whose shoal limit is under your safe depth is flagged, with the meters crossed. When the area's deep limit is under your safe depth too, the note says the water is charted entirely under it. A leg outside every chart on the phone is unchanged. This catches a shoal that sits in the fringe of a grid cell whose center is deeper water: the line on the chart runs through the shoal and the grid never saw it.
- **One shallow cell is enough to flag a leg.** A single 20 m cell of water that may be under your safe depth, or a single cell of a hazard buffer, used to be dropped as grid noise. It is reported now.
- **Straightening.** See 2026-11-02. A one-cell channel and a 6-12 ft edge do not stay a staircase. A long leg does not cut a shoal the path only passed beside, and it does not enter a hazard buffer the path did not use. Water charted entirely under the safe depth stays forbidden.
- **Soundings use the chart's whole-foot label, not the 5 cm slack used for depth areas.** See 2026-11-02: only a label shallower than your safe depth is a hazard. Depth areas still use the 5 cm slack, so a 1.8 m area stays the 6 ft band.

Chart display:
- **The chart legend names the three blues.** Strong blue is charted entirely under your safe depth, lighter blue is a range that straddles it, and pale blue is at or over it. Olive still dries.

## 2026-11-01: route packs (in app 0.1.185)

Routing and safety:
- **Shallow edges of depth areas are stored.** Depth was recorded at the center of each 20 m cell, and where two areas overlapped the deeper one won the cell. A route at a 5 ft safe depth clipped the west edge of a 0-6 ft knoll (chart US5MD1ND, about 39.012 N, 76.386 W): the knoll covers about 8 m of the next cells, which stayed 6-12 ft. Where a shallower ordinary depth area covers more than about a quarter of a cell, the pack now stores that shallower range. Dredged areas and charted fairways still keep the deeper depth at the cell center, so a channel one cell wide stays open. The pack format is unchanged, so the current app reads the new packs.
- **A broken chart outline no longer stops the pack build.** A restricted area whose outline contains a coordinate that is not a number (a security zone, 33 CFR 165.1183, on an overview chart in region 19a) used to abort that region, and the failure cancelled every other region. The bad coordinates are left out of the note. If the outline still cannot be simplified, the note keeps the unsimplified shape. If nothing valid is left, the note is skipped and the build log says so.

## 2026-11-01: Region 04 chart on View map (in app 0.1.185)

Chart display:
- **Download a region's chart.** **View map**, **View on map**, and each region card under **Regions** can download the in-app chart for any region the vector catalog lists a `tiles` entry for. The chart on screen is the downloaded one whose bounds cover the route, or the middle of the map once you pan. The button shows the progress, the file is checked, and a later checksum is **Update**. The file stays in the app, works offline, and is not an OsmAnd map. Today that catalog entry is Region 04 (about 32 MB, zooms 8 to 15). A weekly build that publishes another region's chart shows up without a new app.
- Until a downloaded chart covers the area on screen, the bundled Middle River chart is shown.
- **Route data** draws on whichever of those charts is showing, above the chart and under the route line. Turning the chart from Middle River to Region 04 after the download keeps the overlay on.

## 2026-10-31: route data on the chart (in app 0.1.185)

Chart display:
- **Route data.** On the chart (**View map** and **View on map**), a **Route data** checkbox, off by default and remembered, draws what the route planner sees from the route pack already on the phone. Red is too shallow or drying, amber straddles your safe depth, faint blue is deep enough, teal is dredged, indigo is a fairway or track, bright red is a hazard, black is a pier or structure, a purple hatch is entry prohibited, maroon is a bridge that is too low, orange is a cable that is too low, cyan is the wrong side of a channel marker, and brown is a pile or snag. Land stays clear so the chart shows through. **View map** uses the depth and air draft on the form (3 ft if the depth field is empty). **View on map** uses the depth and air draft the route was planned with. With no air draft, span and cable clearances are not marked. If no downloaded pack covers the area on screen, the checkbox says so. The bright red cell is the hazard itself; the keep-off buffer around it is not drawn. The layer is drawn on the phone and works offline. It is the planner's grid, not a chart to steer by. It sits above the chart and under the route line.

## 2026-10-30: in-app chart, proof of concept (in app 0.1.185)

Chart display:
- **Light colours.** A green light (Fl G) was drawn as the same red dot as a red light, then as gray: the updated chart was the same size as the copy already stored, so the app kept the old file and the new style found no colour. An update now replaces that copy when the chart bytes change. The light is a small dot beside the buoy or daybeacon, which keeps its own green or red.
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
