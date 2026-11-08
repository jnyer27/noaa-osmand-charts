---
title: Safety and limits
nav_order: 9
---

# Safety and limits

{: .warning }
> NOAA Route is a planning aid, not a certified navigation system. You are responsible for the safe navigation of your vessel.

## What the planner cannot know

- **Tide and water level.** Routes are planned on depths charted at MLLW (Low Water Datum on the Great Lakes) with no tide added. The app shows NOAA's tide predictions in the report, on flagged shallow legs and on the chart, but they are predictions: wind setdown (a northwest wind on the Chesapeake), high pressure and river flow can move the water feet from them, and below chart datum. A route planned on the tide could lead onto water that is not there, so the planner never uses it. **Predicted depth** on the chart is a display aid, not observed water.
- **The real bottom.** Soundings are sparse, and shoals move, especially in inlets and the ICW. Charts lag behind the water, even with Notices to Mariners.
- **Small things.** Single piles, crab pots, floating debris and uncharted obstructions are not on the grid.
- **Exact lines.** The grid is 20 m. A narrow dredged channel is drawn toward its middle, but a line through a bridge opening can still sit to one side. Line up on the marks by eye.
- **Bridge schedules and real clearances.** Opening bridges have schedules; fixed clearances change with the water level. Read the clearance boards.
- **Rules of the road, traffic, weather.**

## What to check yourself

Before you go, read the [route report](report.html) and look at each flagged leg on a current chart:

- legs where the charted depth may be under your safe depth, including "crosses N m of a depth area that straddles your depth" from the chart in the app: check the tide and the shallowest sounding (tap the leg on the chart for the day's tide and the times it is high enough, then allow for the wind),
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
