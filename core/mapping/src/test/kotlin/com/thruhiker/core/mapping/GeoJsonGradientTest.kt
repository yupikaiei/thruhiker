package com.thruhiker.core.mapping

import com.thruhiker.core.geo.Geodesic
import com.thruhiker.core.geo.GradientBand
import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import com.thruhiker.core.model.TrackSegment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoJsonGradientTest {

  private fun features(geoJson: String): List<JSONObject> {
    val array = JSONObject(geoJson).getJSONArray("features")
    return (0 until array.length()).map { array.getJSONObject(it) }
  }

  /**
   * A run of points due north, [spacingMeters] apart, climbing by [risePerLegMeters] on each
   * leg. Position comes from the geodesic forward formula so the leg length is known and the
   * expected band can be worked out exactly.
   */
  private fun run(
    legs: Int,
    spacingMeters: Double = 100.0,
    risePerLegMeters: Double? = 10.0,
    start: LatLng = LatLng(46.0, 7.0),
  ): List<TrackPoint> {
    val points = ArrayList<TrackPoint>(legs + 1)
    var position = start
    points.add(TrackPoint(position, risePerLegMeters?.let { 0.0 }))

    for (leg in 1..legs) {
      position = Geodesic.destination(position, 0.0, spacingMeters)
      points.add(TrackPoint(position, risePerLegMeters?.let { it * leg }))
    }

    return points
  }

  @Test
  fun `the route is drawn as one feature per leg`() {
    val track = Track.of(run(legs = 4))

    val drawn = features(GeoJsonEncoder.encodeGradient(track, minimumSpacingMeters = 50.0))

    assertEquals(4, drawn.size)
    for (feature in drawn) {
      assertEquals("Feature", feature.getString("type"))
      val geometry = feature.getJSONObject("geometry")
      assertEquals("LineString", geometry.getString("type"))
      assertEquals(2, geometry.getJSONArray("coordinates").length())
    }
  }

  @Test
  fun `each leg carries the colour of its own band`() {
    // Ten metres of rise per hundred of ground is a ten percent climb: the CLIMB band.
    val track = Track.of(run(legs = 6, spacingMeters = 100.0, risePerLegMeters = 10.0))

    val drawn = features(GeoJsonEncoder.encodeGradient(track, minimumSpacingMeters = 50.0))

    for (feature in drawn) {
      val properties = feature.getJSONObject("properties")
      assertEquals(GradientBand.CLIMB.name, properties.getString("band"))
      assertEquals(GradientBand.CLIMB.colorHex, properties.getString("color"))
    }
  }

  @Test
  fun `a steep climb is red and a descent is blue`() {
    val steep = Track.of(run(legs = 4, spacingMeters = 100.0, risePerLegMeters = 25.0))
    val down = Track.of(run(legs = 4, spacingMeters = 100.0, risePerLegMeters = -20.0))

    val steepBands = features(GeoJsonEncoder.encodeGradient(steep, 50.0))
      .map { it.getJSONObject("properties").getString("band") }
    val downBands = features(GeoJsonEncoder.encodeGradient(down, 50.0))
      .map { it.getJSONObject("properties").getString("band") }

    assertTrue(steepBands.all { it == GradientBand.STEEP_CLIMB.name })
    assertTrue(downBands.all { it == GradientBand.STEEP_DESCENT.name })
  }

  @Test
  fun `a flat route is neutral`() {
    val track = Track.of(run(legs = 3, risePerLegMeters = 0.0))

    val bands = features(GeoJsonEncoder.encodeGradient(track, 50.0))
      .map { it.getJSONObject("properties").getString("band") }

    assertTrue(bands.all { it == GradientBand.FLAT.name })
  }

  @Test
  fun `a route without elevation is unknown rather than flat`() {
    val track = Track.of(run(legs = 3, risePerLegMeters = null))

    val drawn = features(GeoJsonEncoder.encodeGradient(track, 50.0))

    assertEquals(3, drawn.size)
    for (feature in drawn) {
      val properties = feature.getJSONObject("properties")
      assertEquals(GradientBand.UNKNOWN.name, properties.getString("band"))
      assertEquals(GradientBand.UNKNOWN.colorHex, properties.getString("color"))
    }
  }

  /** The same transposition trap as in the plain encoder, in a second code path. */
  @Test
  fun `gradient coordinates are longitude first`() {
    val track = Track.of(
      listOf(
        TrackPoint(LatLng(46.5, 7.9), 100.0),
        TrackPoint(LatLng(46.6, 8.0), 110.0),
      ),
    )

    val coordinate = features(GeoJsonEncoder.encodeGradient(track))
      .first()
      .getJSONObject("geometry")
      .getJSONArray("coordinates")
      .getJSONArray(0)

    assertEquals(7.9, coordinate.getDouble(0), 1e-9)
    assertEquals(46.5, coordinate.getDouble(1), 1e-9)
  }

  @Test
  fun `a long recording is thinned to the display spacing`() {
    // A thousand one-metre legs: a kilometre of route, which must not become a thousand
    // two-point features.
    val track = Track.of(run(legs = 1_000, spacingMeters = 1.0))

    val drawn = features(GeoJsonEncoder.encodeGradient(track, minimumSpacingMeters = 30.0))

    assertTrue("expected roughly a kilometre over thirty metres, got ${drawn.size}", drawn.size in 30..36)
  }

  @Test
  fun `thinning keeps both ends of the route`() {
    val points = run(legs = 1_000, spacingMeters = 1.0)
    val track = Track.of(points)

    val drawn = features(GeoJsonEncoder.encodeGradient(track, minimumSpacingMeters = 30.0))

    val first = drawn.first().getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
    val last = drawn.last().getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(1)

    assertEquals(points.first().position.longitude, first.getDouble(0), 1e-5)
    assertEquals(points.first().position.latitude, first.getDouble(1), 1e-5)
    assertEquals(points.last().position.longitude, last.getDouble(0), 1e-5)
    assertEquals(points.last().position.latitude, last.getDouble(1), 1e-5)
  }

  @Test
  fun `no feature is drawn across a recording gap`() {
    val first = run(legs = 3, spacingMeters = 100.0)
    val second = run(legs = 3, spacingMeters = 100.0, start = LatLng(46.5, 7.0))
    val track = Track(listOf(TrackSegment(first), TrackSegment(second)))

    val drawn = features(GeoJsonEncoder.encodeGradient(track, minimumSpacingMeters = 50.0))

    assertEquals(6, drawn.size)
    for (feature in drawn) {
      val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
      val start = coordinates.getJSONArray(0)
      val end = coordinates.getJSONArray(1)
      val legLength = Geodesic.distanceMeters(
        LatLng(start.getDouble(1), start.getDouble(0)),
        LatLng(end.getDouble(1), end.getDouble(0)),
      )
      assertTrue("a feature spanned $legLength m across a gap", legLength < 200.0)
    }
  }

  @Test
  fun `an empty track produces an empty collection`() {
    val encoded = GeoJsonEncoder.encodeGradient(Track.Empty)

    assertEquals("FeatureCollection", JSONObject(encoded).getString("type"))
    assertTrue(features(encoded).isEmpty())
  }

  @Test
  fun `a rejected spacing is not silently accepted`() {
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
      GeoJsonEncoder.encodeGradient(Track.Empty, minimumSpacingMeters = 0.0)
    }
  }

  @Test
  fun `markers are numbered from one`() {
    val encoded = GeoJsonEncoder.encodeWaypoints(
      listOf(
        GeoJsonEncoder.WaypointMarker(LatLng(46.0, 7.0)),
        GeoJsonEncoder.WaypointMarker(LatLng(46.1, 7.1)),
        GeoJsonEncoder.WaypointMarker(LatLng(46.2, 7.2)),
      ),
    )

    val drawn = features(encoded)

    assertEquals(3, drawn.size)
    assertEquals(
      listOf(1, 2, 3),
      drawn.map { it.getJSONObject("properties").getInt("index") },
    )
    assertEquals(
      listOf("1", "2", "3"),
      drawn.map { it.getJSONObject("properties").getString("label") },
    )
  }

  @Test
  fun `a marker with a name is labelled with it`() {
    val encoded = GeoJsonEncoder.encodeWaypoints(
      listOf(
        GeoJsonEncoder.WaypointMarker(LatLng(46.0, 7.0), label = "Water"),
        GeoJsonEncoder.WaypointMarker(LatLng(46.1, 7.1)),
      ),
    )

    val labels = features(encoded).map { it.getJSONObject("properties").getString("label") }

    assertEquals(listOf("Water", "2"), labels)
  }

  @Test
  fun `marker geometry is a longitude-first point`() {
    val encoded = GeoJsonEncoder.encodeWaypoints(listOf(LatLng(46.5, 7.9)))

    val geometry = features(encoded).first().getJSONObject("geometry")

    assertEquals("Point", geometry.getString("type"))
    assertEquals(7.9, geometry.getJSONArray("coordinates").getDouble(0), 1e-9)
    assertEquals(46.5, geometry.getJSONArray("coordinates").getDouble(1), 1e-9)
  }

  @Test
  fun `no markers is an empty collection, not a malformed one`() {
    val encoded = GeoJsonEncoder.encodeWaypoints(emptyList<GeoJsonEncoder.WaypointMarker>())

    assertEquals("FeatureCollection", JSONObject(encoded).getString("type"))
    assertTrue(features(encoded).isEmpty())
    // Same document as the constant by content, compared key by key because org.json
    // serialises from an unordered map and two identical documents need not be byte-identical.
    assertEquals(
      JSONObject(GeoJsonEncoder.EMPTY_COLLECTION).keys().asSequence().sorted().toList(),
      JSONObject(encoded).keys().asSequence().sorted().toList(),
    )
  }
}
