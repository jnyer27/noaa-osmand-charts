---
title: Getting started
nav_order: 3
---

# Getting started

Everything happens on one main screen: your chart regions at the top, then the trip (Start, Destination, Via points), your boat's settings (safe depth, air draft, Stay inside), and **Plan route**. Each part is explained in [Planning a route](planning.html).

<img src="main-screen.jpg" alt="NOAA Route main screen: Charts with the installed regions and NOAA data date, Regions, Start at Maryland Marina and a destination in North Carolina, Safe operating depth 5 ft, air draft 10 ft, Stay inside and Check markers in the GPX checked with their hints, Name, Plan route and Manage places" width="300">

## 1. Download your chart region

On first launch the app asks **Where do you boat?** Route planning needs NOAA chart data for your waters, a few MB per region.

- **Use my location** finds your region from your position ("You're in ..."), or
- **Pick on map** opens the Regions map: tap your region, then **Download route data**.

Most regions are 2-7 MB; the largest (Arctic Alaska) is 14 MB. Download at home on Wi-Fi. After that, planning works with no signal.

The first time you download a region, the app asks once about **Automatic chart downloads**: yes means it keeps your regions up to date on Wi-Fi, and fetches a region a trip needs without asking. You can change this later on the Regions screen.

<img src="regions-screen.jpg" alt="The Regions screen: regions 03, 04 and 05 on the map with You, Start and Destination markers, the automatic Wi-Fi download checkbox, and the region list with update status" width="300">

## 2. Plan your first route

1. **Start**: tap **Here** for your GPS position, **Places** for a saved spot, or type `LAT, LON` (for example `39.32371, -76.39998` or `39.25 N, 76.48 W`).
2. **Destination**: the same, with **Places** or typed coordinates.
3. **Safe operating depth (ft)**: the shallowest charted depth you are willing to use, which is your draft plus a margin. A boat with a 1.8 ft draft might use 3 ft.
4. **Air draft (ft)**: the height of your boat above the water, so the route avoids low bridges and power lines. Leave it empty only if you never go under anything.
5. Check **Stay inside (avoid open ocean)** if you want bays, sounds, rivers and the ICW only. Check **Move ends to navigable water** if your dock is in water too shallow for your safe depth.
6. Tap **Plan route**.

The app shows "Planned in 0.8 s. Saved to Download/noaa-charts/routes/..." and the route report below it: one line per leg, with the course, distance and anything to check. See [Reading the route report](report.html).

## 3. Follow it in OsmAnd

Tap **Open in OsmAnd**. OsmAnd shows the route; start navigation or just follow the line. If OsmAnd does not open it, tap **Show routes folder in Files** and tap the `.gpx` file there. See [Using routes in OsmAnd](osmand.html).

**View map**, under **Regions**, opens that chart of Middle River with no route on it. **View on map**, next to **Open in OsmAnd**, draws the route you just planned on the same chart. **Route data** on that screen, off until you turn it on, draws what the planner sees from a region you have downloaded. It is a proof of concept for that one area (the marina creeks out to the bay), it works offline, and it does not replace OsmAnd. See [View on map](planning.html#view-on-map).

## 4. Share places from other map apps

Instead of typing coordinates, share a spot from another app to NOAA Route; it asks whether to use it as **Start**, **Destination** or a **Via point**, or to save it as a place.

- **OsmAnd**: tap a spot on the map, then **Share > NOAA Route** (see [Sharing points from OsmAnd](osmand.html#sharing-points-from-osmand)).
- **Google Maps**: tap a place or press and hold to drop a pin, then **Share > NOAA Route**. Google sends a short link (`maps.app.goo.gl/...`) with no coordinates in it, so NOAA Route follows it to the full Google Maps link and reads the position there (the place's own pin, named as Google names it). This one step needs the internet; without it the app says "Reading a Google Maps link needs the internet." If Google gives only a place's name and no coordinates, the app says so: drop a pin on the spot and share the pin instead.
- **Other apps**: NOAA Route reads `geo:` links, map links that contain coordinates (OpenStreetMap, OsmAnd, Google Maps) and plain coordinates, shared as text. It also takes GPX files. For apps whose links it cannot read (Apple Maps, Waze, HERE WeGo and others), convert the link with [osm2gmaps](https://f-droid.org/packages/net.retiolus.osm2gmaps) (free, from F-Droid): share the place to osm2gmaps, then share or copy its coordinates to NOAA Route.

{: .note }
> Before you leave, read the report. Every note that ends in "check" is a place to look at the chart, and at the water, yourself.
