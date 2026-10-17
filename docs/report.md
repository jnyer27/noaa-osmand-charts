---
title: Reading the route report
nav_order: 5
---

# Reading the route report

After **Plan route**, the report appears under the button. You can select and copy its text.

## The header

```
Annapolis trip: 26.5 nm, 38 points
depth 5 ft (MLLW), air draft 10 ft, hazard buffer 60 m, grid 20 m
```

- The total distance and number of route points.
- Your start, via points and destination.
- The settings it was planned for, and what depths are charted to (MLLW, or Low Water Datum on the Great Lakes).
- **grid 20 m**: the chart detail the route was planned on. Long trips add "(long trip, guided by a 320 m overview)": the trip was sketched on a coarse grid first, then planned at 20 m stretch by stretch.
- The charts (regions) used and when it was planned.
- Messages, such as "Point 1 moved 35 m to the nearest usable water".

## The legs

```
Leg Course  Dist nm  Notes
  1   357T    0.05  95 m in 0-6 ft, shallowest sounding within 100 m 3.9 ft
  2   136T    1.00
  3    84T    1.06  passes near charted shoal soundings or obstructions: check
```

**Course** is true (T), not magnetic. **Dist** is nautical miles. A leg with no notes stays in water charted at least your safe depth, clear of everything below.

## What each note means

| Note | Meaning | What to do |
|---|---|---|
| **N m in 0-6 ft, shallowest sounding within 100 m X ft** | That much of the leg is in a charted depth band that spans your safe depth (usually NOAA's 0-6 ft band). The shallowest charted sounding near it is X ft; "no soundings within 100 m" means there are none charted. | Check the chart and the tide. Go slowly and watch the depth sounder. |
| **passes near charted shoal soundings or obstructions: check** | The leg uses the safety buffer (60 m) around a shallow sounding, rock, wreck or obstruction, because there was no other way. Over water charted deep enough, the hazard itself is always avoided. | Find the hazard on the chart and give it room. |
| **passes over or on the wrong side of a channel marker: check** | The leg crosses the wrong side of a lateral buoy or daybeacon. The planner does this only when there is no other way. | Check the marks on the chart; keep red right returning. |
| **runs along or across charted piers or seawalls: check** | The leg runs along or crosses a charted pier, seawall, jetty or breakwater line, for example into a slip. | Steer by eye; the chart line may not match the dock. |
| **passes charted piles or snags: check** | The leg passes old pilings or snags of unknown depth. | Look for them and stay clear. |
| **connects your point to usable water: not checked, use your eyes** | The short leg from your point to the nearest usable water. It may cross a dock or the shore. | Steer it by eye. |
| **Bridge name: swing (or bascule, lift) bridge, may need an opening (closed clearance N ft)** | An opening bridge on the route. | Check its opening schedule and call ahead if needed. |
| **Bridge name span (clearance N ft)** | A fixed bridge span you clear by your air draft. | Check the clearance board; water levels vary. |
| **Cable name (clearance N ft)** or **(clearance not charted)** | An overhead cable. | Make sure your air draft clears it with margin. |
| **Area name: rules (CFR)** | A restricted area: no wake, anchoring rules, entry restricted, or an area to be avoided by large ships. | Read the rule; the route is not blocked by it. |

## In the GPX file

Each route point in the GPX file carries a short description of the next leg, which OsmAnd shows when you tap the point:

```
Next: 357 T, 0.05 nm, 95 m in 0-6 ft charted water (shallowest sounding nearby 3.9 ft)
```

Points are named **Start**, **WP01**, **WP02** ... **End**. The file description says the total distance, the depth it was planned for, and "Not for navigation".
