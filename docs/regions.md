---
title: Chart regions and updates
nav_order: 7
---

# Chart regions and updates

Route data comes in NOAA's numbered chart regions (the same regions as NOAA's chart maps). Every US region with harbor or approach charts is available: Maine to Texas, the Great Lakes, the West Coast, Alaska, Hawaii, Puerto Rico and the Virgin Islands, and the Pacific islands. The [README](https://github.com/jnyer27/noaa-osmand-charts#chart-regions) lists what each region covers.

## On the main screen

Under **Charts** the main screen lists the regions you have and the date of their NOAA data ("Charts: Region 04, Region 05 · NOAA data from 2026-10-16"). The button below says how many updates are waiting, for example **Regions (13 updates)**.

While a region downloads, the line under **Plan route** shows the progress ("Downloading Region 06: 1.2 of 3.6 MB") and then "Region 06 ready." (or "Chart download failed: ..." with the reason).

## The Regions screen

<img src="regions-screen.jpg" alt="The Regions screen: regions 03, 04 and 05 on the map with You, Start and Destination markers, the automatic Wi-Fi download checkbox, and the region list with update status" width="300">

Tap **Regions** on the main screen. **Close** (top right) returns to the main screen.

### The map

NOAA's regions on a built-in coastline that works offline. Pinch to zoom, drag to pan, double-tap to zoom in, and tap a region to open its [region card](#the-region-card).

| On the map | Meaning |
|---|---|
| Green box (shaded) | Route data downloaded and up to date |
| Amber box (shaded) | Downloaded, and an update is available |
| Dashed blue box | Available to download |
| Faint gray box | No route data yet |
| "chart map" under a region's number | That region's chart map for OsmAnd is ready |
| Red dots **Start** and **Destination** | The points filled in on the main screen |
| Red dot **You** | Your last known position |

The map opens zoomed to your trip if Start or Destination is filled in, otherwise to the regions you have, otherwise to all regions.

### Automatic downloads

**Download and update regions automatically on Wi-Fi**: when checked, a daily background job fetches the new weekly data for your regions on Wi-Fi only, and a trip that needs another region gets it without asking. On mobile data the app always asks first. The app asks about this once, the first time you download a region.

### The list

Below the map, every region with its status. Tap a row to open its region card.

| Status | Meaning |
|---|---|
| not downloaded, about N MB | Available; the route data is N MB |
| UPDATE available (date) | You have it; a newer weekly build from that date is out |
| up to date, NOAA data from date | You have the latest |
| replaced by ... | An old area replaced by newer regions (for example the old Chesapeake Bay area by 04 and 05); removed once those are downloaded |

Further down are **Chart maps for OsmAnd** (each chart map you have, with its status; **Combine chart maps for OsmAnd** appears once two or more are ready) and **Vector charts for OsmAnd** (each region's vector charts and their status). Tap one to open its card.

## The region card

<img src="region-card.jpg" alt="Region 05 card: route planning data up to date with Delete route data, the chart map for OsmAnd with its sizes and two download buttons, and vector charts ready with an update available and buttons to update, open in OsmAnd, show in Files and delete" width="300">

The card has three parts. **Close** at the bottom returns to the Regions screen.

### Route planning data

The data the planner uses, a few MB. The status says whether it is downloaded and how old it is, followed by what the region covers.

| Button | What it does |
|---|---|
| **Download route data** | Downloads the region (shown when you do not have it) |
| **Update route data** | Downloads the newer weekly build (shown when one is out) |
| **Delete route data** | Removes the region from the app |

### Chart map for OsmAnd

NOAA's paper-chart style map of the region (NCDS), converted for OsmAnd. Before you download, the card shows the sizes, for example "560 MB download, about 952 MB for OsmAnd, 1568 MB free space needed while converting". See [Chart maps for OsmAnd](osmand.html#chart-maps-for-osmand-paper-chart-style).

| State | Buttons |
|---|---|
| Not downloaded | **Download for OsmAnd** (download and convert), **Download the .mbtiles only** (NOAA's file as it is, for OpenCPN and other apps) |
| Downloading ("downloading: 120 of 560 MB (waits for Wi-Fi unless mobile data was allowed)") | **Cancel download** |
| Converting for OsmAnd... | None; it finishes in the background |
| Ready ("ready: Download/noaa-charts/ncds_05.sqlitedb (952 MB)") | **Open in OsmAnd**, **Show in Files**, **Add to combined map ...** (when you have a combined map without this region), **Delete chart map** |
| Failed ("failed: reason") | **Try again**, **Remove** |

A **combined chart map** has its own card (tap it in the Regions list): **Open in OsmAnd**, **Show in Files**, **Add regions...** and **Delete**.

### Vector charts for OsmAnd

NOAA's vector charts as two OsmAnd maps: a depth map and a seamarks map. Before you download, the card shows their size and NOAA data date. See [Vector charts for OsmAnd](osmand.html#vector-charts-for-osmand).

| State | Buttons |
|---|---|
| Not downloaded | **Download vector charts** (then pick **On Wi-Fi only** or **Use mobile data too**) |
| Downloading, or saving to Download/noaa-charts... | **Cancel download** while downloading |
| Ready ("ready: Download/noaa-charts/Ncds_05_depth.depth.obf, Ncds_05_seamarks.obf (25 MB, NOAA data from 2026-10-16)", plus "UPDATE available" when a newer build is out) | **Update vector charts** (when an update is out), **Open depth map in OsmAnd**, **Open seamarks map in OsmAnd**, **Show in Files**, **Delete vector charts** |
| Failed | **Try again**, **Remove** |

If there is not enough free space, the app says how much is needed before it starts.

{: .note }
> Deleting a chart map or vector charts in NOAA Route removes the files in `Download/noaa-charts`, not OsmAnd's own imported copy. Delete that in OsmAnd under **Maps & Resources > Local**.

## Weekly updates

The route data is rebuilt every week from NOAA's current charts, with Notices to Mariners corrections applied, and every build is tested before it is published (see [Safety and limits](safety.html#how-the-route-data-is-checked)). Chart maps follow NOAA's own publishing, and vector charts are rebuilt weekly.

## Trips across regions

A trip can cross several regions; they join seamlessly. Once Start and Destination are filled in, a banner above **Plan route** names any region the trip needs that you have not downloaded ("This trip needs ..."), with its size and a **Download** button. It also warns when part of the trip is outside every region.

## Great Lakes depths

Great Lakes charts (for example region 15, Lake Erie) are charted to Low Water Datum, not MLLW, and the report and GPX say which datum a route was planned on. Lake levels are usually above that datum, but they change with the season and the wind.
