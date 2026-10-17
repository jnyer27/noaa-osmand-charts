---
title: Chart regions and updates
nav_order: 7
---

# Chart regions and updates

Route data comes in NOAA's numbered chart regions (the same regions as NOAA's chart maps). Every US region with harbor or approach charts is available: Maine to Texas, the Great Lakes, the West Coast, Alaska, Hawaii, Puerto Rico and the Virgin Islands, and the Pacific islands. The [README](https://github.com/jnyer27/noaa-osmand-charts#chart-regions) lists what each region covers.

## The Regions screen

Tap **Regions** for a map of NOAA's regions on a built-in coastline that works offline.

| Color | Meaning |
|---|---|
| Green | Downloaded and up to date |
| Amber | An update is available |
| Dashed blue | Available to download |
| Faint gray | No route data yet |

Tap a region to open its card: **Download route data**, **Update route data** or **Delete route data**, plus chart maps and vector charts for OsmAnd. Pinch to zoom, drag to pan, double-tap to zoom in. The list below the map shows the same information.

## Weekly updates

The route data is rebuilt every week from NOAA's current charts, with Notices to Mariners corrections applied, and every build is tested before it is published (see [Safety and limits](safety.html#how-the-route-data-is-checked)). The main screen shows how old your data is, and **Regions** shows the number of updates available.

**Download and update regions automatically on Wi-Fi** (on the Regions screen): when on, a daily background job fetches new weekly data for your regions on Wi-Fi only, and a trip that needs another region gets it without asking. On mobile data the app always asks first.

## Trips across regions

A trip can cross several regions; they join seamlessly. Once Start and Destination are filled in, a banner above **Plan route** names any region the trip needs that you have not downloaded ("This trip needs ..."), with its size and a **Download** button. It also warns when part of the trip is outside every region.

## Great Lakes depths

Great Lakes charts (for example region 15, Lake Erie) are charted to Low Water Datum, not MLLW, and the report and GPX say which datum a route was planned on. Lake levels are usually above that datum, but they change with the season and the wind.
