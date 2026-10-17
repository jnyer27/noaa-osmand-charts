---
title: Getting started
nav_order: 3
---

# Getting started

Everything happens on one main screen: your chart regions at the top, then the trip (Start, Destination, Via points), your boat's settings (safe depth, air draft, Stay inside), and **Plan route**. Each part is explained in [Planning a route](planning.html).

<img src="main-screen.jpg" alt="NOAA Route main screen: Charts with the installed regions and Regions (13 updates), Start and Destination filled in, safe depth 2 ft, air draft 10 ft, Stay inside checked, Plan route, the status line Region 06 ready, and Manage places" width="300">

## 1. Download your chart region

On first launch the app asks **Where do you boat?** Route planning needs NOAA chart data for your waters, a few MB per region.

- **Use my location** finds your region from your position ("You're in ..."), or
- **Pick on map** opens the Regions map: tap your region, then **Download route data**.

Most regions are 2-7 MB; the largest (Arctic Alaska) is 14 MB. Download at home on Wi-Fi. After that, planning works with no signal.

The first time you download a region, the app asks once about **Automatic chart downloads**: yes means it keeps your regions up to date on Wi-Fi, and fetches a region a trip needs without asking. You can change this later on the Regions screen.

<img src="app-regions.jpg" alt="The Regions screen: NOAA chart regions on a map, with the regions list below" width="300">

## 2. Plan your first route

1. **Start**: tap **Here** for your GPS position, **Places** for a saved spot, or type `LAT, LON` (for example `39.32371, -76.39998` or `39.25 N, 76.48 W`).
2. **Destination**: the same, with **Places** or typed coordinates.
3. **Safe operating depth (ft)**: the shallowest charted depth you are willing to use, which is your draft plus a margin. A boat with a 1.8 ft draft might use 3 ft.
4. **Air draft (ft)**: the height of your boat above the water, so the route avoids low bridges and power lines. Leave it empty only if you never go under anything.
5. Check **Stay inside (avoid open ocean)** if you want bays, sounds, rivers and the ICW only.
6. Tap **Plan route**.

The app shows "Planned in 0.8 s. Saved to Download/noaa-charts/routes/..." and the route report below it: one line per leg, with the course, distance and anything to check. See [Reading the route report](report.html).

## 3. Follow it in OsmAnd

Tap **Open in OsmAnd**. OsmAnd shows the route; start navigation or just follow the line. If OsmAnd does not open it, tap **Show routes folder in Files** and tap the `.gpx` file there. See [Using routes in OsmAnd](osmand.html).

{: .note }
> Before you leave, read the report. Every note that ends in "check" is a place to look at the chart, and at the water, yourself.
