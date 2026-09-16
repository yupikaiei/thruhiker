package com.thruhiker.core.mapping

import com.thruhiker.core.geo.Geodesic
import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import com.thruhiker.core.model.TrackSegment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reveal is what makes the flyover look like a route drawing itself. Its two
 * failure modes are drawing too much and drawing across a gap, so every test here
 * measures the geometry that actually came out rather than checking that something
 * came out.
 */
class GeoJsonRevealTest {

  private val origin = LatLng(46.0, 7.0)

  private fun north(from: LatLng, distanceMeters: Double): LatLng =
    Geodesic.destination(from, 0.0, distanceMeters)

  /** A straight ten-kilometre line in one-kilometre legs. */
  private fun straightTrack(): Track {
    var position = origin
    val points = ArrayList<TrackPoint>()
    for (index in 0..10) {
      if (index > 0) position = north(position, 1_000.0)
      points.add(TrackPoint(position, elevationMeters = 1000.0 + index * 50.0))
    }
    return Track.of(points)
  }

  /** Two short legs a hundred kilometres apart. */
  private fun gappedTrack(): Track = Track(
    listOf(
      TrackSegment(listOf(TrackPoint(LatLng(46.0, 7.0)), TrackPoint(LatLng(46.01, 7.0)))),
      TrackSegment(listOf(TrackPoint(LatLng(47.0, 8.0)), TrackPoint(LatLng(47.01, 8.0)))),
    ),
  )

  private fun positions(geoJson: String): List<List<LatLng>> {
    val geometry = JSONObject(geoJson).getJSONObject("geometry")
    val coordinates = geometry.getJSONArray("coordinates")

    return when (geometry.getString("type")) {
      "LineString" -> listOf(line(coordinates))
      else -> (0 until coordinates.length()).map { line(coordinates.getJSONArray(it)) }
    }
  }

  private fun line(coordinates: JSONArray): List<LatLng> =
    (0 until coordinates.length()).map { index ->
      val coordinate = coordinates.getJSONArray(index)
      LatLng(coordinate.getDouble(1), coordinate.getDouble(0))
    }

  private fun drawnLengthMeters(geoJson: String): Double =
    positions(geoJson).sumOf { path -> Geodesic.pathLengthMeters(path) }

  @Test
  fun `nothing is drawn before the flight starts`() {
    val geoJson = GeoJsonEncoder.encode(straightTrack(), revealedFraction = 0.0)

    assertEquals(emptyList<List<LatLng>>(), positions(geoJson))
    assertEquals(0.0, drawnLengthMeters(geoJson), 1e-9)
  }

  @Test
  fun `the whole route is drawn at full reveal`() {
    val track = straightTrack()

    assertEquals(
      GeoJsonEncoder.encode(track),
      GeoJsonEncoder.encode(track, revealedFraction = 1.0),
    )
  }

  /**
   * Tolerance is a couple of metres, not centimetres, because the measurement is
   * taken back off geometry whose coordinates were rounded to six decimal places —
   * about 11 cm each. Ten rounded points can accumulate a metre of apparent length.
   * Two metres still rules out the failure that matters, which is cutting a whole
   * kilometre leg in the wrong place.
   */
  @Test
  fun `half the route is drawn at half reveal`() {
    val geoJson = GeoJsonEncoder.encode(straightTrack(), revealedFraction = 0.5)

    assertEquals(5_000.0, drawnLengthMeters(geoJson), 2.0)
  }

  @Test
  fun `the cut lands at the requested distance`() {
    val revealed = positions(GeoJsonEncoder.encode(straightTrack(), revealedFraction = 0.37))
    val lastPoint = revealed.last().last()

    assertEquals(3_700.0, Geodesic.distanceMeters(origin, lastPoint), 0.05)
  }

  @Test
  fun `the reveal is monotonic in the fraction`() {
    val track = straightTrack()

    val quarters = listOf(0.25, 0.5, 0.75, 1.0)
      .map { drawnLengthMeters(GeoJsonEncoder.encode(track, it)) }

    assertEquals(2_500.0, quarters[0], 2.0)
    assertEquals(5_000.0, quarters[1], 2.0)
    assertEquals(7_500.0, quarters[2], 2.0)
    assertEquals(10_000.0, quarters[3], 2.0)
  }

  @Test
  fun `fractions outside the unit interval are clamped`() {
    val track = straightTrack()

    assertEquals(
      GeoJsonEncoder.encode(track, revealedFraction = 0.0),
      GeoJsonEncoder.encode(track, revealedFraction = -3.0),
    )
    assertEquals(
      GeoJsonEncoder.encode(track, revealedFraction = 1.0),
      GeoJsonEncoder.encode(track, revealedFraction = 9.0),
    )
  }

  /**
   * The reveal must stop at a gap rather than reaching across it. Both legs are
   * about 1.1 km, so a quarter of the way through draws the first leg only.
   */
  @Test
  fun `a partial reveal stays inside the first segment`() {
    val revealed = positions(GeoJsonEncoder.encode(gappedTrack(), revealedFraction = 0.25))

    assertEquals(1, revealed.size)
    assertTrue("the drawn line must stay near 46 degrees north", revealed[0][0].latitude < 47.0)
  }

  @Test
  fun `a reveal past a gap draws both segments as separate lines`() {
    val revealed = positions(GeoJsonEncoder.encode(gappedTrack(), revealedFraction = 0.75))

    assertEquals(2, revealed.size)
    // Each line stays in its own segment: no line spans the hundred kilometres.
    assertTrue(revealed[0].all { it.latitude < 47.0 })
    assertTrue(revealed[1].all { it.latitude > 46.5 })
  }

  @Test
  fun `a reveal never draws a line longer than the route`() {
    val geoJson = GeoJsonEncoder.encode(gappedTrack(), revealedFraction = 0.9)

    // Both legs together are about 2.2 km; the gap is 100 km and must be absent.
    assertTrue(
      "drawn ${drawnLengthMeters(geoJson)} m, which means the gap was crossed",
      drawnLengthMeters(geoJson) < 3_000.0,
    )
  }

  @Test
  fun `an empty track reveals to nothing at any fraction`() {
    assertEquals(emptyList<List<LatLng>>(), positions(GeoJsonEncoder.encode(Track.Empty, 0.5)))
    assertEquals(emptyList<List<LatLng>>(), positions(GeoJsonEncoder.encode(Track.Empty, 1.0)))
  }

  /** A track whose points are all in one place has no length to reveal. */
  @Test
  fun `a track with no length reveals to nothing`() {
    val track = Track.of(
      listOf(TrackPoint(origin, elevationMeters = 0.0), TrackPoint(origin, elevationMeters = 1.0)),
    )

    assertEquals(emptyList<List<LatLng>>(), positions(GeoJsonEncoder.encode(track, 0.5)))
  }
}
