---
title: Planning a route
nav_order: 4
---

# Planning a route

The main screen, top to bottom.

## Charts

<img src="main-screen.jpg" alt="NOAA Route main screen: Charts with the installed regions and NOAA data date, Regions, Start at Maryland Marina and a destination in North Carolina, Safe operating depth 5 ft, air draft 10 ft, Stay inside and Check markers in the GPX checked with their hints, Name, Plan route and Manage places" width="300">

Shows the chart regions you have and how old their NOAA data is (for example "Charts: Region 04, Region 05 · NOAA data from 2026-10-16"). The **Regions** button says how many updates are waiting, for example **Regions (13 updates)**, and opens the Regions screen. See [Chart regions and updates](regions.html).

The line under **Plan route** shows what the app is doing: download progress ("Downloading Region 06: 1.2 of 3.6 MB", then "Region 06 ready."), where a shared point went ("Start set: ..."), or the result of planning.

## Start, Destination and Via points

Each point can be:

- typed as `LAT, LON` in decimal degrees (`39.25, -76.48`) or with hemispheres (`39.25 N, 76.48 W`),
- picked from **Places** (your saved spots),
- **Here** (Start only): your current GPS position,
- shared from OsmAnd or another app (see [Sharing points from OsmAnd](osmand.html#sharing-points-from-osmand)).

**Via points** are optional stops in order, separated by `;`. **Add** appends a saved place. Use a via point to force the route through a particular channel or to break up a very long trip.

A point on land or on a dock is moved to the nearest usable water, and the report says how far. The short leg between your point and that water is marked "connects your point to usable water: not checked, use your eyes", because it may cross the shore or a pier.

Once Start and Destination are filled in, a banner above **Plan route** names any chart region the trip needs that you have not downloaded, with a **Download** button.

## Safe operating depth (ft)

The shallowest **charted** depth you are willing to use: your draft plus a margin for tide, waves and chart error. The app plans so that every leg stays in water charted at least this deep wherever possible. Where it cannot (a creek, a marina entrance), it uses water that may be shallower only as briefly as possible and flags it in the report.

NOAA's depth areas usually step 0 / 6 / 12 ft, so at 3 ft most creeks fall into the "0-6 ft" band: usable, but flagged, with the shallowest nearby sounding. Depths are at MLLW (low water) with no tide added; on the Great Lakes, Low Water Datum.

Allowed values: 0 to 40 ft. The app remembers your last setting.

## Air draft (ft)

Your boat's height above the waterline. With it, the route goes only under bridge spans and power lines whose charted clearance covers it. Opening bridges (swing, bascule, lift) are passed and reported with their closed clearance, so you can check the opening schedule. Leave it empty and clearances are not checked at all.

## Stay inside (avoid open ocean)

Keeps the route on bays, sounds, rivers and the ICW. The note under the checkbox puts it as no water seaward of the line across inlets and bay mouths: the planner treats as open ocean the water reached from 12 nm offshore without crossing land or one of those lines. For example, Maryland to the Neuse River goes down the Chesapeake, through the ICW's Virginia Cut and the North Carolina sounds instead of around Cape Hatteras. If a point you entered is itself out in the ocean, the planner says so.

Without it, the planner takes the cheapest safe water, which can include the ocean along the beach or past a cape.

## Move ends to navigable water

Off by default. Normally, a point in water charted under your safe depth (a marina in a 0-6 ft creek at 3 ft) is routed out through that water, and those legs are flagged; a point in water your boat cannot get out of is refused with "No route found" and an explanation of what closes it in. With this checked, the route starts or ends instead at the nearest water charted at least your safe depth, clear of hazard buffers, piers and markers, up to 0.5 nm away along the water, that leads to the rest of the trip. The report then says "Point 1 is in water charted under your 3 ft or near charted hazards; with Move ends to navigable water on, the route uses the nearest water charted at least 3 ft, 180 m away. Check the way between on a chart." It is for a dock or a mooring up a creek shallower than your safe depth, or behind a bridge too low for your air draft, that you know how to get in and out of (on the tide, with the mast down).

For a boxed-in point, the report says which point moved, how far, and what closed it in, for example "Point 1: the water there is cut off by water shallower than your safe depth; with Move ends to navigable water on, the route uses the nearest water that leads out, 850 m away. Check the way between on a chart." The leg from your point to that water is marked "connects your point to usable water: not checked, use your eyes" and is a check, with check markers in the GPX. It is a straight line, not a route: it can cross shallows, a hazard, a bridge or the shore.

It applies to your start, destination and via points. A point on land or a dock is moved to the nearest usable water with or without it.

## Check markers in the GPX

On by default. The route's GPX file gets a waypoint where each stretch to check starts (orange, "Check: legs 16-18 (0.21 nm)") and where it ends (green, "Check ends: legs 16-18"), so OsmAnd and other chart apps show and, in OsmAnd, announce them. Off, the GPX has only the route, with each leg's notes in its points. The route and the report are the same either way. See [In the GPX file](report.html#in-the-gpx-file).

## Name

Optional. Routes are saved as `Download/noaa-charts/routes/<name>.gpx`, or `route_YYYYMMDD_HHMM.gpx` without a name. Planning again with the same name replaces that file.

## Plan route

Plans the route, shows the report and saves the GPX. Read the report's notes before you go: see [Check the route before you go](#check-the-route-before-you-go). Then:

- **Open in OsmAnd** sends the route to OsmAnd,
- **Show routes folder in Files** opens `Download/noaa-charts/routes` in Android's Files app,
- **Share GPX** sends the file to any app (email, messages, another navigation app).

Long trips (100+ nm, for example the Chesapeake down the ICW to the Carolinas) take longer: up to a minute on a phone for 400+ nm. Very long trips can cross several chart regions; download all of them first.

## Check the route before you go

{: .warning }
Every route needs checking. The planner keeps to water charted at least your safe operating depth wherever it can, but where it cannot (a creek, a marina entrance, a narrow channel) it goes on and flags the leg instead of stopping. A flagged leg is the planner telling you it could not prove that stretch safe.

After planning, read the [route report](report.html) leg by leg. Each leg with a note needs a look on a current chart before you leave:

- **shallow water**: "N m where charted depth may be under 5 ft" (your own safe depth in place of 5 ft): check the tide and the shallowest sounding named with it,
- **"check" notes**: a hazard the leg passes close to (named, with its depth and distance), a channel marker passed on the wrong side, piers or seawalls, piles or snags,
- **"connects your point to usable water"**: the legs at either end, which are not checked at all,
- **bridges and cables**: clearances and opening schedules,
- **restricted areas**: their rules.

[What each note means](report.html#what-each-note-means) explains every note and what to do about it. With **Check markers in the GPX** on, each stretch of flagged legs also has an orange start and a green end marker in OsmAnd, which announces them as you approach (see [In the GPX file](report.html#in-the-gpx-file)). Underway, follow the buoys rather than the line and watch the depth sounder where the report flags shallow water. See also [What to check yourself](safety.html#what-to-check-yourself).

### Try to avoid checks

When the route has legs to check, a **Try to avoid checks** button appears under the report. It replans each stretch of flagged legs on its own, this time treating the causes of the checks (shallow water, the hazard buffer, piers and seawalls, piles, security zones) as land, and keeps the new stretch only if:

- its checks are gone (or, failing that, its "check" notes are gone and what is left is shallow water no longer than before), and
- it is no more than 0.2 nm or 10% longer than the old stretch, whichever is more.

Your start, via points and destination never move, and the legs at either end that connect your points to usable water are left alone. The status line says what happened, for example *Avoid checks: 1 of 3 check stretches resolved (+0.12 nm), 2 unchanged (no way around within 0.2 nm or 10% of the stretch).* A stretch with no small way around (a creek or marina entrance that is shallow all the way across) stays as it was, still flagged.

If anything changed, the adjusted route is saved as a new file, `<name>_adjusted.gpx`, and the report, **Open in OsmAnd** and **Share GPX** switch to it; the original route file is unchanged. The adjusted route still needs checking: read its report the same way.

## When there is no route

If a point is boxed in, the app stops and says why: shallow water, land, a hazard buffer, or a bridge or power line too low for your air draft. It names the shallowest sounding or obstruction responsible, with its position. When depth is the limit, it suggests the deepest setting that gets out ("With a safe depth of 5 ft or less the water here leads out of this area"). Move the point into open water, or adjust the depth or air draft.

## Places

**Manage places** opens **Places (tap to edit or delete)**:

- **+ Add a place** opens **Add place**: a name and `LAT, LON` (filled in with Start), then **Save**.
- Tap a place to **Edit place**: change the name or position and **Save**, or **Delete** it.
- Saving under a name that already exists updates that place.

The list starts with six sample Chesapeake places (Maryland Marina, Hart-Miller Island beach, Bear Creek, Rock Hall Harbor, Annapolis Ego Alley, Chesapeake City dock), which you can edit or delete. The **Places** buttons next to Start and Destination, and **Add** next to Via points, pick from this list. You can also save a position shared from OsmAnd as a place, or save all waypoints of a GPX file at once.
