---
title: How the planner chooses a route
nav_order: 8
---

# How the planner chooses a route

The app turns NOAA's charts into a grid of 20 m cells and finds the cheapest path from start to destination. "Cheap" means deep, clear, well-marked water, away from the shore. This page explains the rules in plain words; the [README](https://github.com/jnyer27/noaa-osmand-charts#how-the-planner-decides-where-you-can-go) has the full details. On the chart screen, **Route data** draws these classes on the map. See [Route data](planning.html#route-data).

## Depth

| Charted | What the planner does |
|---|---|
| Depth area at least your safe depth | Used freely |
| Depth area that spans your safe depth (NOAA's usual 0-6 ft band) | Used only when needed, and flagged with the length in it and the shallowest sounding nearby. Soundings shallower than your depth are treated as hazards. |
| Land, drying, unsurveyed or uncharted water | Never used |

On the 20 m grid, a shallower depth area that covers more than about a quarter of a cell is counted, so the route sees the edge of a shoal and not only the depth at the cell's center. A dredged channel or fairway keeps the deeper depth, so a narrow channel stays open.

## Hazards

Rocks, wrecks and obstructions shallower than your depth plus 1 ft (or of unknown depth), and soundings shallower than your depth, get a 60 m safety buffer. Over water charted deep enough (such as a dredged channel), only the hazard itself is a wall and the buffer is crossed only when there is no other way, with a "check" note. In water that may be shallower than your depth, the whole buffer is a wall. Single charted piles are not avoided (a 20 m cell would close narrow channels), so keep a lookout.

## Channels

Dredged channels and charted fairways deep enough for you are preferred, so long trips take the ICW or the C&D Canal instead of going outside. Where the ICW is charted only as a fairway or a recommended track (along Oak Island, in Bogue Sound), those count as the channel too. Being near the edge of a maintained channel is normal, so the keep-off-the-shore cost there is much lower.

## Channel markers

Lateral buoys and daybeacons are never passed on the wrong side, in either direction: "red right returning" and "green right going out" both hold. Each mark gets a short line pointing away from its channel; the route crosses one only when there is no other way, with a "check" note.

## Bridges and power lines

With an air draft set, the route goes only under spans with a charted clearance that covers it. Opening bridges are passed and reported with their closed clearance. Bridge piers are avoided. Where a bridge crosses a dredged channel, the route takes the channel span.

## Keeping off the shore

The cost of a cell rises within about 100 m of land, shallow water, piers and hazards, so the route only goes close where it has to, and keeps toward the middle of gaps under 600 m wide. In 0-6 ft water near the bank the cost is 10 times higher again, so the route does not take a narrow marsh gut as a shortcut.

## Stay inside

With **Stay inside**, open ocean is never used. Open ocean is the water reached from 12 nm offshore without crossing land or a COLREGS demarcation line (the lines across inlets and bay mouths). The Chesapeake, Delaware Bay and the North Carolina sounds are inside; the ocean along the beaches is not.

## Move ends to navigable water

A point in a pocket of water walled in only by land or structures (a marina basin behind a pier too thin for the grid) moves up to 250 m to connected water. With **Move ends to navigable water**, a point in water charted under your safe depth or in a hazard buffer moves to the nearest water, along the water within 0.5 nm, charted at least that deep and clear of buffers, piers and markers; and a point in a pocket closed by anything (water shallower than your safe depth, a hazard buffer, a bridge or cable too low for your air draft, land) moves to the nearest water within 0.5 nm that leads to the rest of the trip. The leg between is never planned, only marked to check.

## Restricted areas

Entry-prohibited areas are never used. Coast Guard security zones (33 CFR 165) are used, but every leg through one is a check with check markers in the GPX, because the charts do not tell a zone that is always closed (the Cove Point LNG terminal, a nuclear plant, a Navy base) from one enforced only around particular vessels or when announced. Read the regulation and the Local Notice to Mariners, and if the zone applies, tap [Try to avoid checks](planning.html#try-to-avoid-checks) or add a via point to go around it. Other restricted areas (no wake, anchoring rules, entry restricted, areas to be avoided by large ships) are used but reported with their rule.

## Long trips

Long trips are worked out at full 20 m detail within 20 km of each end first, to settle which inlet and which creek. The middle is sketched on a coarse overview, then planned at full detail stretch by stretch between guide points, and finally re-planned around each guide point so the joins take the best line.
