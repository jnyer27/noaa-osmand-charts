"""Coverage rule for shallow depth-area fringes in the route pack builder.

A DEPARE shallower than the cell center is stored when it covers more than about
a quarter of the 20 m cell (2x2 supersamples: a straight edge wider than about
5 m). A 3 m sliver does not. Dredged areas and fairway cells keep the deeper
center value, so a one-cell channel stays open.

    python3 -m unittest tools/test_depth_coverage.py
"""

import importlib.util
import os
import shutil
import tempfile
import unittest

import numpy as np
from osgeo import ogr, osr

spec = importlib.util.spec_from_file_location(
    "build_route_pack",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "build_route_pack.py"))
bp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bp)


def rect(west, south, east, north):
    ring = ogr.Geometry(ogr.wkbLinearRing)
    ring.AddPoint_2D(west, south)
    ring.AddPoint_2D(east, south)
    ring.AddPoint_2D(east, north)
    ring.AddPoint_2D(west, north)
    ring.AddPoint_2D(west, south)
    poly = ogr.Geometry(ogr.wkbPolygon)
    poly.AddGeometry(ring)
    return poly


def cell_box(grid, r, c):
    north = grid.north - r * grid.dlat
    south = north - grid.dlat
    west = grid.west + c * grid.dlon
    east = west + grid.dlon
    return west, south, east, north


class ReduceSamplesTest(unittest.TestCase):
    def rank(self):
        # index 1 is the deeper range, index 2 the shallower one: the result must
        # follow depth, not the smaller index
        table = [None, [1.8, 3.6, 0], [0.0, 1.8, 0]]
        return bp.depth_rank_array(table)

    def test_two_of_four_shallow_samples_win(self):
        # West column shallow, east column deep: a straight edge past the quarter line
        fine = np.array([[2, 1], [2, 1]], np.uint8)
        out = bp.reduce_samples(fine, self.rank(), sub=2, need=2)
        self.assertEqual(out.tolist(), [[2]])

    def test_one_shallow_sample_does_not_win(self):
        fine = np.array([[2, 1], [1, 1]], np.uint8)
        out = bp.reduce_samples(fine, self.rank(), sub=2, need=2)
        self.assertEqual(int(out[0, 0]), 1)

    def test_empty_samples_stay_empty(self):
        fine = np.zeros((2, 2), np.uint8)
        out = bp.reduce_samples(fine, self.rank(), sub=2, need=2)
        self.assertEqual(int(out[0, 0]), 0)

    def test_need_is_strictly_more_than_a_quarter(self):
        self.assertEqual(bp.coverage_need(2, 0.25), 2)  # more than 1 of 4
        self.assertEqual(bp.coverage_need(4, 0.25), 5)  # more than 4 of 16


class CoverageRuleTest(unittest.TestCase):
    """Polygons on the real grid: eastern fringe of a deep cell, like the knoll's west edge."""

    @classmethod
    def setUpClass(cls):
        cls.grid = bp.Grid(-76.40, 39.00, -76.39, 39.01)
        cls.table = [None, [1.8, 3.6, 0], [0.0, 1.8, 0], [3.6, None, 1]]
        cls.deep, cls.shallow, cls.dredged = 1, 2, 3
        cls.r = 40 if cls.grid.h > 50 else 10
        cls.c0 = 40 if cls.grid.w > 50 else 10

    def covered(self, geoms):
        feats = [(g, v) for g, v in geoms]
        return bp.depare_coverage(self.grid, feats, self.table)

    def eastern_fringe(self, frac, r, c):
        """Shallow polygon covering the eastern `frac` of cell (r, c), full height."""
        w, s, e, n = cell_box(self.grid, r, c)
        return rect(e - frac * (e - w), s, e, n)

    def whole(self, r, c):
        w, s, e, n = cell_box(self.grid, r, c)
        return rect(w, s, e, n)

    def test_eight_metre_fringe_is_stored_shallow(self):
        # 8 m of a 20 m cell is 0.40, past the 5 m quarter line, so both eastern samples hit
        r, c = self.r, self.c0
        covered = self.covered([
            (self.whole(r, c), self.deep),
            (self.eastern_fringe(8 / 20, r, c), self.shallow),
        ])
        self.assertEqual(int(covered[r, c]), self.shallow)

    def test_six_metre_fringe_is_stored_shallow(self):
        r, c = self.r, self.c0 + 1
        covered = self.covered([
            (self.whole(r, c), self.deep),
            (self.eastern_fringe(6 / 20, r, c), self.shallow),
        ])
        self.assertEqual(int(covered[r, c]), self.shallow)

    def test_three_metre_sliver_stays_deep(self):
        r, c = self.r, self.c0 + 2
        covered = self.covered([
            (self.whole(r, c), self.deep),
            (self.eastern_fringe(3 / 20, r, c), self.shallow),
        ])
        self.assertEqual(int(covered[r, c]), self.deep)

    def test_corner_under_a_quarter_stays_deep(self):
        # NE 8 m x 8 m is 16% of the cell and hits one sample
        r, c = self.r, self.c0 + 3
        w, s, e, n = cell_box(self.grid, r, c)
        corner = rect(e - 0.40 * (e - w), n - 0.40 * (n - s), e, n)
        covered = self.covered([(self.whole(r, c), self.deep), (corner, self.shallow)])
        self.assertEqual(int(covered[r, c]), self.deep)

    def test_apply_replaces_only_the_shallower_unprotected_cell(self):
        r, c = self.r, self.c0 + 4
        center = np.zeros((self.grid.h, self.grid.w), np.uint8)
        center[r, c] = self.deep
        center[r, c + 1] = self.deep
        center[r, c + 2] = self.dredged
        covered = np.zeros_like(center)
        covered[r, c] = self.shallow
        covered[r, c + 1] = self.shallow
        covered[r, c + 2] = self.shallow
        protect = np.zeros(center.shape, bool)
        protect[r, c + 1] = True  # fairway
        protect[r, c + 2] = True  # dredged channel
        n = bp.apply_shallow_fringe(center, covered, self.table, protect)
        self.assertEqual(n, 1)
        self.assertEqual(int(center[r, c]), self.shallow)
        self.assertEqual(int(center[r, c + 1]), self.deep)
        self.assertEqual(int(center[r, c + 2]), self.dredged)

    def test_same_range_is_not_a_shallower_fairway(self):
        # A fairway entry is the same depths as its depth area. It must not lose the
        # fairway kind because the plain entry was created first and sorts earlier.
        table = [None, [0.0, 1.8, 0], [0.0, 1.8, 2], [-0.1, None, 0]]
        rank = bp.depth_rank_array(table)
        self.assertEqual(int(rank[1]), int(rank[2]))
        self.assertLess(int(rank[3]), int(rank[1]))
        center = np.zeros((1, 1), np.uint8)
        center[0, 0] = 2  # fairway 0-1.8
        covered = np.array([[1]], np.uint8)  # plain 0-1.8, same range
        n = bp.apply_shallow_fringe(center, covered, table, np.zeros((1, 1), bool))
        self.assertEqual(n, 0)
        self.assertEqual(int(center[0, 0]), 2)
        # A truly shallower area still replaces an unprotected cell, and a protected
        # fairway cell keeps its depth.
        covered[0, 0] = 3
        fair = center.copy()
        n = bp.apply_shallow_fringe(center, covered, table, np.zeros((1, 1), bool))
        self.assertEqual(n, 1)
        self.assertEqual(int(center[0, 0]), 3)
        n = bp.apply_shallow_fringe(fair, covered, table, np.ones((1, 1), bool))
        self.assertEqual(n, 0)
        self.assertEqual(int(fair[0, 0]), 2)

    def test_apply_does_not_deepen_a_shallow_center(self):
        center = np.zeros((2, 2), np.uint8)
        center[0, 0] = self.shallow
        covered = np.zeros_like(center)
        covered[0, 0] = self.deep  # deep area also covers most of the cell
        n = bp.apply_shallow_fringe(center, covered, self.table, np.zeros((2, 2), bool))
        self.assertEqual(n, 0)
        self.assertEqual(int(center[0, 0]), self.shallow)


class BuildChannelTest(unittest.TestCase):
    """build() on a tiny chart: the knoll fringe changes, a dredged cell and a fairway do not."""

    def test_fringe_and_channels(self):
        grid = bp.Grid(-76.40, 39.00, -76.39, 39.01)
        r = 40 if grid.h > 50 else 10
        c_sliver, c_fringe, c_dredged, c_fair = 12, 14, 16, 18
        work = tempfile.mkdtemp(prefix="depth-cov-")
        try:
            path = os.path.join(work, "US5TEST.gpkg")
            self.write_chart(path, grid, r, c_sliver, c_fringe, c_dredged, c_fair)
            planes, table, _, _ = bp.build({"US5TEST": path}, grid, work, quiet=True)
        finally:
            shutil.rmtree(work, ignore_errors=True)
        depth = planes[bp.DEPTH]

        def key(c):
            v = int(depth[r, c])
            self.assertGreater(v, 0, f"cell col {c} has no depth")
            return table[v]

        self.assertEqual(key(c_sliver), [1.8, 3.6, 0])   # 3 m sliver, center wins
        self.assertEqual(key(c_fringe), [0.0, 1.8, 0])   # 8 m fringe stored shallow
        self.assertEqual(key(c_dredged), [3.6, None, 1])  # one-cell dredged channel stays
        self.assertEqual(key(c_fair), [1.8, 3.6, 2])      # fairway keeps the deeper range

    def test_later_band_does_not_strip_a_fairway_outside_its_coverage(self):
        # A more detailed chart's depth area can spill a few metres past its coverage.
        # That must not turn a fairway cell the detailed chart does not cover back into
        # a plain depth area, even when the spill is shallower.
        grid = bp.Grid(-76.40, 39.00, -76.39, 39.01)
        r = 40 if grid.h > 50 else 10
        c_fair, c_other = 20, 30
        work = tempfile.mkdtemp(prefix="depth-cov-band-")
        try:
            self.write_fairway_chart(os.path.join(work, "US4TEST.gpkg"), grid, r, c_fair)
            self.write_spill_chart(os.path.join(work, "US5TEST.gpkg"), grid, r, c_fair, c_other)
            planes, table, _, _ = bp.build(
                {"US4TEST": os.path.join(work, "US4TEST.gpkg"),
                 "US5TEST": os.path.join(work, "US5TEST.gpkg")}, grid, work, quiet=True)
        finally:
            shutil.rmtree(work, ignore_errors=True)
        v = int(planes[bp.DEPTH, r, c_fair])
        self.assertEqual(table[v], [0.0, 1.8, 2])

    def write_fairway_chart(self, path, grid, r, c):
        self._gpkg(path, grid, lambda depare, drgare, fair, cov, add, whole, east: (
            add(cov, rect(grid.west, grid.north - grid.h * grid.dlat,
                          grid.west + grid.w * grid.dlon, grid.north), CATCOV=1),
            add(depare, whole(r, c), DRVAL1=0.0, DRVAL2=1.8),
            add(fair, whole(r, c)),
        ))

    def write_spill_chart(self, path, grid, r, c_fair, c_other):
        def fill(depare, drgare, fair, cov, add, whole, east):
            # Coverage is another cell, so the fairway cell is not this chart's
            add(cov, whole(r, c_other), CATCOV=1)
            add(depare, whole(r, c_other), DRVAL1=1.8, DRVAL2=3.6)
            # Drying ground covering 8 m of the fairway cell, outside this coverage
            add(depare, east(r, c_fair, 8 / 20), DRVAL1=-0.5, DRVAL2=0.0)
        self._gpkg(path, grid, fill)

    def _gpkg(self, path, grid, fill):
        srs = osr.SpatialReference()
        srs.ImportFromEPSG(4326)
        srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
        ds = ogr.GetDriverByName("GPKG").CreateDataSource(path)

        def add_layer(name, fields):
            lyr = ds.CreateLayer(name, srs, ogr.wkbPolygon)
            for fname, ftype in fields:
                lyr.CreateField(ogr.FieldDefn(fname, ftype))
            return lyr

        def add(lyr, geom, **attrs):
            feat = ogr.Feature(lyr.GetLayerDefn())
            feat.SetGeometry(geom)
            for k, v in attrs.items():
                feat.SetField(k, v)
            lyr.CreateFeature(feat)

        def whole(rr, c):
            return rect(*cell_box(grid, rr, c))

        def east(rr, c, frac):
            w, s, e, n = cell_box(grid, rr, c)
            return rect(e - frac * (e - w), s, e, n)

        dsid = ds.CreateLayer("DSID", srs, ogr.wkbNone)
        dsid.CreateField(ogr.FieldDefn("DSPM_DUNI", ogr.OFTInteger))
        dsid.CreateField(ogr.FieldDefn("DSPM_SDAT", ogr.OFTInteger))
        feat = ogr.Feature(dsid.GetLayerDefn())
        feat.SetField("DSPM_DUNI", 1)
        feat.SetField("DSPM_SDAT", 12)
        dsid.CreateFeature(feat)
        cov = add_layer("M_COVR", [("CATCOV", ogr.OFTInteger)])
        depare = add_layer("DEPARE", [("DRVAL1", ogr.OFTReal), ("DRVAL2", ogr.OFTReal)])
        drgare = add_layer("DRGARE", [("DRVAL1", ogr.OFTReal), ("DRVAL2", ogr.OFTReal)])
        fair = add_layer("FAIRWY", [])
        fill(depare, drgare, fair, cov, add, whole, east)
        ds.FlushCache()

    def write_chart(self, path, grid, r, c_sliver, c_fringe, c_dredged, c_fair):
        srs = osr.SpatialReference()
        srs.ImportFromEPSG(4326)
        srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
        ds = ogr.GetDriverByName("GPKG").CreateDataSource(path)

        def add_layer(name, fields):
            lyr = ds.CreateLayer(name, srs, ogr.wkbPolygon)
            for fname, ftype in fields:
                lyr.CreateField(ogr.FieldDefn(fname, ftype))
            return lyr

        def add(lyr, geom, **attrs):
            feat = ogr.Feature(lyr.GetLayerDefn())
            feat.SetGeometry(geom)
            for k, v in attrs.items():
                feat.SetField(k, v)
            lyr.CreateFeature(feat)

        def whole(c):
            return rect(*cell_box(grid, r, c))

        def east(c, frac):
            w, s, e, n = cell_box(grid, r, c)
            return rect(e - frac * (e - w), s, e, n)

        dsid = ds.CreateLayer("DSID", srs, ogr.wkbNone)
        dsid.CreateField(ogr.FieldDefn("DSPM_DUNI", ogr.OFTInteger))
        dsid.CreateField(ogr.FieldDefn("DSPM_SDAT", ogr.OFTInteger))
        feat = ogr.Feature(dsid.GetLayerDefn())
        feat.SetField("DSPM_DUNI", 1)
        feat.SetField("DSPM_SDAT", 12)
        dsid.CreateFeature(feat)

        cov = add_layer("M_COVR", [("CATCOV", ogr.OFTInteger)])
        add(cov, rect(grid.west, grid.north - grid.h * grid.dlat, grid.west + grid.w * grid.dlon, grid.north),
            CATCOV=1)
        depare = add_layer("DEPARE", [("DRVAL1", ogr.OFTReal), ("DRVAL2", ogr.OFTReal)])
        drgare = add_layer("DRGARE", [("DRVAL1", ogr.OFTReal), ("DRVAL2", ogr.OFTReal)])
        fair = add_layer("FAIRWY", [])
        for c, frac in ((c_sliver, 3 / 20), (c_fringe, 8 / 20), (c_dredged, 8 / 20), (c_fair, 8 / 20)):
            add(depare, whole(c), DRVAL1=1.8, DRVAL2=3.6)
            add(depare, east(c, frac), DRVAL1=0.0, DRVAL2=1.8)
        add(drgare, whole(c_dredged), DRVAL1=3.6, DRVAL2=5.0)
        add(fair, whole(c_fair))
        ds.FlushCache()
        ds = None


if __name__ == "__main__":
    unittest.main()
