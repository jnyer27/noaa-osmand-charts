"""A charted outline with a NaN vertex must not abort the pack build.

Region 19a died in note() on a restricted area (33 CFR 165.1183, band 2):
GEOS raised "orientationIndex encountered NaN/Inf" from SimplifyPreserveTopology.
GetEnvelope of that outline was still finite, which is what a NaN vertex does.

    python3 -m unittest tools/test_note_geom.py
"""

import importlib.util
import math
import os
import unittest

from osgeo import ogr

spec = importlib.util.spec_from_file_location(
    "build_route_pack",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "build_route_pack.py"))
bp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bp)


def polygon(points):
    ring = ogr.Geometry(ogr.wkbLinearRing)
    for x, y in points:
        ring.AddPoint_2D(x, y)
    if points[0] != points[-1]:
        ring.AddPoint_2D(*points[0])
    poly = ogr.Geometry(ogr.wkbPolygon)
    poly.AddGeometry(ring)
    return poly


# The 37.71 N security zone's box, with one vertex replaced by NaN, as GEOS saw it.
ZONE = [(-122.65, 37.59), (-122.48, 37.70), (-122.50, 37.82), (-122.64, 37.75)]


class NoteGeomTest(unittest.TestCase):
    def test_nan_vertex_is_dropped_and_the_note_is_kept(self):
        broken = polygon([ZONE[0], (float("nan"), 37.70), ZONE[1], ZONE[2], ZONE[3]])
        # The finite envelope is what the build logged just before the crash.
        self.assertTrue(all(math.isfinite(v) for v in broken.GetEnvelope()))
        with self.assertRaises(RuntimeError):
            broken.Clone().SimplifyPreserveTopology(0.00005)
        got = bp.note("resare", 2, broken, name="security zone", rules=["security zone, entry may be prohibited: check"])
        self.assertIsNotNone(got)
        self.assertEqual(got["kind"], "resare")
        self.assertEqual(got["name"], "security zone")
        self.assertTrue(all(math.isfinite(v) for v in got["bbox"]))
        self.assertLess(got["bbox"][0], -122.6)
        self.assertGreater(got["bbox"][3], 37.7)
        flat = [c for ring in got["coords"] for c in ring]
        self.assertTrue(all(math.isfinite(v) for xy in flat for v in xy))

    def test_infinite_vertex_is_dropped(self):
        broken = polygon([ZONE[0], (float("inf"), 37.7), ZONE[1], ZONE[2], ZONE[3]])
        got = bp.note("resare", 2, broken, name="security zone")
        self.assertIsNotNone(got)
        self.assertTrue(all(math.isfinite(v) for v in got["bbox"]))

    def test_all_nan_outline_is_skipped(self):
        broken = polygon([(float("nan"), float("nan")), (float("nan"), 1.0), (float("nan"), 2.0)])
        self.assertIsNone(bp.note("resare", 2, broken, name="security zone"))

    def test_ordinary_outline_is_unchanged_in_kind(self):
        whole = polygon(ZONE)
        got = bp.note("resare", 2, whole, name="security zone", rules=["entry restricted"])
        self.assertEqual(got["type"], "Polygon")
        self.assertEqual(got["rules"], ["entry restricted"])
        self.assertGreater(len(got["coords"][0]), 3)


if __name__ == "__main__":
    unittest.main()
