---
title: Report a route problem
nav_order: 11
---

# Report a route problem

If a route takes a wrong or unsafe way, zig-zags, or is refused when it should not be, please report it with the **Route problem** form on GitHub:

[Report a route problem](https://github.com/jnyer27/noaa-osmand-charts/issues/new?template=route-problem.yml){: .btn .btn-primary }

The form asks for:

- **where** (lat, lon) the route looks wrong, and what you expected instead,
- the **start and destination**,
- your **safe depth, air draft and Stay inside** setting,
- the **regions installed** in the app (long trips can differ depending on which regions are loaded),
- the **app version** (Android Settings > Apps > NOAA Route),
- the route's **GPX file** (from `Download/noaa-charts/routes`) and a **screenshot**. GitHub accepts a `.gpx` file only zipped, or renamed to `.txt`.

Each fixed problem becomes a reference route that every future build must pass.
