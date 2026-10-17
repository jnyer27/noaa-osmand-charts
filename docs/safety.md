---
title: Safety and limits
nav_order: 9
---

# Safety and limits

{: .warning }
> NOAA Route is a planning aid, not a certified navigation system. You are responsible for the safe navigation of your vessel.

## What the planner cannot know

- **Tide and water level.** Depths are charted at MLLW (Low Water Datum on the Great Lakes) with no tide added. Wind and low tides can take water below chart datum.
- **The real bottom.** Soundings are sparse, and shoals move, especially in inlets and the ICW. Charts lag behind the water, even with Notices to Mariners.
- **Small things.** Single piles, crab pots, floating debris and uncharted obstructions are not on the grid.
- **Exact lines.** The grid is 20 m. A route line may run along one side of a narrow opening or channel; line up on the marks by eye.
- **Bridge schedules and real clearances.** Opening bridges have schedules; fixed clearances change with the water level. Read the clearance boards.
- **Rules of the road, traffic, weather.**

## What to check yourself

Before you go, read the [route report](report.html) and look at each flagged leg on a current chart:

- legs with time in **0-6 ft** water: check the tide and the shallowest sounding,
- **"check"** notes: hazards, channel markers, piers, piles,
- the **"connects your point to usable water"** leg at either end,
- **bridges and cables**: clearances and opening schedules,
- **restricted areas**: their rules.

Underway, keep your eyes on the water and the depth sounder, follow the buoys rather than the line, and slow down where the report flags shallow water.

## How the route data is checked

- The route data is rebuilt every week from NOAA's current charts (ENC cells with all update files applied). The build checks each chart's depth units and records its datum.
- Over 100 **reference routes** run through the app's own planner on every build and every planner change. Some must come out within a length range, some must be refused (a destination boxed in by shallow water), some must keep away from a spot (an inlet that should not be used, a drying patch), some must not zig-zag, some must stay inside. Nothing is published if one fails.
- Changes are tried in a test version first, and only data tested from the released code is published.
- Every route problem that is reported and fixed becomes a new reference route, so it cannot come back unnoticed. See [Report a route problem](report-a-problem.html).
