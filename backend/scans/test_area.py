from django.test import SimpleTestCase
from rest_framework.test import APIRequestFactory

from rest_framework.request import Request

from .area import Circle, Polygon, parse_area, parse_polygon

# A rough square around Berlin's Alexanderplatz, ~400 m across.
SQUARE = "52.5200,13.4100;52.5200,13.4160;52.5240,13.4160;52.5240,13.4100"


def request(**params):
    return Request(APIRequestFactory().get("/", params))


class AreaTests(SimpleTestCase):
    def test_circle_from_the_three_parameters(self):
        area = parse_area(request(area_lat="52.52", area_lng="13.41", area_radius_m="250"))
        self.assertIsInstance(area, Circle)
        self.assertTrue(area.contains(52.521, 13.411))
        self.assertFalse(area.contains(52.53, 13.41))
        self.assertEqual(area.describe(), "250 m around 52.52000, 13.41000")

    def test_half_specified_or_bad_circle_means_no_filter(self):
        self.assertIsNone(parse_area(request(area_lat="52.52", area_lng="13.41")))
        self.assertIsNone(parse_area(request(area_lat="52.52", area_lng="13.41", area_radius_m="0")))
        self.assertIsNone(parse_area(request(area_lat="92", area_lng="13.41", area_radius_m="10")))
        self.assertIsNone(parse_area(request(area_lat="x", area_lng="13.41", area_radius_m="10")))
        self.assertIsNone(parse_area(request(area_lat="52.52", area_lng="13.41", area_radius_m="nan")))
        self.assertIsNone(parse_area(request(area_lat="inf", area_lng="13.41", area_radius_m="10")))
        self.assertIsNone(parse_area(request()))

    def test_polygon_contains_and_describes(self):
        area = parse_area(request(area_polygon=SQUARE))
        self.assertIsInstance(area, Polygon)
        self.assertTrue(area.contains(52.522, 13.413))
        self.assertTrue(area.contains(52.5200, 13.4100))  # a corner
        self.assertFalse(area.contains(52.522, 13.420))
        self.assertFalse(area.contains(52.530, 13.413))
        self.assertEqual(area.describe(), "polygon with 4 corners around 52.52200, 13.41300")

    def test_polygon_wins_over_a_circle_and_a_closed_ring_is_fine(self):
        area = parse_area(request(area_polygon=SQUARE + ";52.5200,13.4100", area_lat="0", area_lng="0", area_radius_m="1"))
        self.assertIsInstance(area, Polygon)
        self.assertEqual(len(area.points), 4)

    def test_bad_polygons_are_refused(self):
        self.assertIsNone(parse_polygon(""))
        self.assertIsNone(parse_polygon("52.52,13.41;52.53,13.41"))  # two corners
        self.assertIsNone(parse_polygon("52.52,13.41;52.53;52.53,13.42"))
        self.assertIsNone(parse_polygon("52.52,13.41;95,13.41;52.53,13.42"))
        self.assertIsNone(parse_polygon(";".join(f"52.{i:03d},13.41" for i in range(201))))
        self.assertIsNotNone(parse_polygon(";".join(f"52.{i:03d},13.41" for i in range(200))))
        # A bad polygon does not fall back to a stray circle either way round:
        self.assertIsInstance(parse_area(request(area_polygon="junk", area_lat="52.52", area_lng="13.41", area_radius_m="5")), Circle)

    def test_concave_polygon(self):
        # An L shape: the notch at the top right is outside.
        l_shape = Polygon([(0, 0), (0, 2), (1, 2), (1, 1), (2, 1), (2, 0)])
        self.assertTrue(l_shape.contains(0.5, 1.5))
        self.assertTrue(l_shape.contains(1.5, 0.5))
        self.assertFalse(l_shape.contains(1.5, 1.5))
