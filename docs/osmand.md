---
title: Using routes in OsmAnd
nav_order: 6
---

# Using routes in OsmAnd

NOAA Route plans; OsmAnd+ (or free OsmAnd) shows the route on a map and follows it with GPS. Both work offline once everything is downloaded, so do the downloads at home on Wi-Fi.

## Following a route

1. After planning, tap **Open in OsmAnd**. OsmAnd opens the GPX route.
2. Follow the line, or use OsmAnd's navigation along the track. Tap a route point to see the next leg's course, distance and notes.
3. If OsmAnd does not open it, tap **Show routes folder in Files** and tap the `.gpx` file there, then choose OsmAnd.

The route line is a guide. On the 20 m grid, a line through a narrow bridge opening or a channel can run along one side instead of down the middle, so line up by eye.

## Sharing points from OsmAnd

In OsmAnd, tap a spot on the map, then **Share > NOAA Route**. NOAA Route opens a dialog titled with the shared spot's name and coordinates (OsmAnd sends "No address determined" for a spot without an address):

<img src="share-dialog.jpg" alt="Dialog for a position shared from OsmAnd, titled No address determined 39.08780, -76.30120, with the choices Start, Destination, Via point, Save as place and Cancel" width="300">

| Choice | What it does |
|---|---|
| **Start** | Puts the position in Start ("Start set: ...") |
| **Destination** | Puts it in Destination |
| **Via point** | Adds it to the end of Via points |
| **Save as place...** | Opens **Add place** with the name and position filled in; edit the name and tap **Save** |
| **Cancel** | Does nothing |

When what was shared holds several positions, the app first asks **Which position?** and lists them. Map links, `geo:` links and plain coordinates from other apps work the same way, and so do Google Maps short links when there is an internet connection (see [Share places from other map apps](getting-started.html#4-share-places-from-other-map-apps)).

Sharing a **GPX file** offers:

- a waypoint from the file, as one point,
- **Use as trip**: a route or track's first and last points as Start and Destination,
- **Save all waypoints** as places.

## Chart maps for OsmAnd (paper-chart style)

The app can download NOAA's paper-chart style map of a region (NCDS) and convert it for OsmAnd:

1. Tap **Regions**, then your region, then **Download for OsmAnd**. Choose Wi-Fi only or mobile data too. Android downloads it in the background (it resumes after interruptions and keeps going with the app closed), and the app converts it to `Download/noaa-charts/ncds_XX.sqlitedb`.
2. Tap **Open in OsmAnd** on the region card. OsmAnd imports it as an offline map.
3. In OsmAnd, select it as the **overlay** map (the Online maps plugin must be on). With OsmAnd's Boat profile, set it while that profile is active: each profile keeps its own map settings.

A chart map is about 600 MB per region, and converting needs about 2.8 times that in free space; the app checks first.

<img src="osmand-chart-map.jpg" alt="OsmAnd showing the region 05 chart map as the overlay off Middle River and Back River, Chesapeake Bay: NOAA's paper chart with tan land, blue shallow water, dense soundings in feet, magenta light teardrops with labels such as Fl G 2.5s 15ft 4M, and two routes past Hart-Miller Island" width="360">

*The chart map in OsmAnd: NOAA's paper chart as an overlay, off Middle River and Hart-Miller Island.*

**Combine chart maps for OsmAnd** merges two or more regions into one map, because OsmAnd shows one overlay at a time. A combined map can **Add regions** later.

**Download the .mbtiles only** keeps NOAA's file as it is, for OpenCPN and other apps.

### Reading the paper-chart style

- Depths are in feet.
- A magenta teardrop is a light, whatever its color: read the color from the label (`Fl G 4s` is green, `Fl R 4s` is red).
- A black dot with a height ("15ft") is a fixed beacon on a pile, not a buoy.
- Even numbers are red, odd numbers green ("red right returning").

## Vector charts for OsmAnd

Also on the region card: NOAA's vector charts as two OsmAnd maps, drawn by OsmAnd itself, sharp at every zoom and only tens of MB:

- **Depth map**: depth contours, depth areas and soundings. OsmAnd lists it under Nautical maps (the free Nautical plugin) and it follows the Nautical depth switch.
- **Seamarks map**: buoys and beacons, lights, wrecks, rocks and obstructions, piers, bridges, cables, fairways and dredged channels, and restricted areas. OsmAnd lists it under Standard maps, so you can turn it off on its own.

Tap **Download vector charts**, then **Open depth map in OsmAnd** and **Open seamarks map in OsmAnd**. Use OsmAnd's Nautical map style. Some buoys show twice, because OpenStreetMap's seamarks also come with OsmAnd's own maps. Daymarks and light sectors are not drawn yet.

OsmAnd's current release (5.4) draws only its standard contour classes, so NOAA's shallow contours (3, 6, 12 and 18 ft) all draw alike (each labeled with its depth) and depth areas are not shaded. OsmAnd's development version adds new depth rules: shallow (2 m) contours drawn dashed, darker contour lines and depth area shading ([OsmAnd-resources #1478](https://github.com/osmandapp/OsmAnd-resources/pull/1478), [depth areas](https://github.com/osmandapp/OsmAnd-resources/commit/09f91397c78435e67f66676351ebef9b699f1340)). The depth maps already carry the depth area tags, so shading and the darker lines will appear once an OsmAnd release includes the new rules. The shallow contours are written in the 5 m class for now so that today's OsmAnd draws them; after that release, the weekly build will write them in the new 2 m class.

<img src="osmand-vector-charts.jpg" alt="The same spot in OsmAnd with the depth and seamarks maps and the Nautical map style instead of the chart overlay: OsmAnd's own land and roads, blue water with depth contours and soundings, buoys in their real colors and shapes, a green fairway line and hatched restricted areas" width="360">

*The same spot with the vector charts: OsmAnd draws the depth and seamarks maps itself, over its own land and roads.*

## Updating maps in OsmAnd

OsmAnd keeps its own copy of every imported map. After an update (an **Update** button appears on the region card when NOAA publishes a newer build), delete the old map in OsmAnd (**Maps & Resources > Local**) and open the new one from the app.
