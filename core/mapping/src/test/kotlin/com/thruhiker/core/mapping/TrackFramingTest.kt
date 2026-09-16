package com.thruhiker.core.mapping

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import com.thruhiker.core.model.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFramingTest {

  private fun point(latitude: Double, longitude: Double) =
    TrackPoint(LatLng(latitude, longitude))

  @Test
  fun `an empty track has no camera`() {
    assertNull(TrackFraming.cameraFor(Track.Empty))
  }

  @Test
  fun `the centroid of symmetric points is between them`() {
    val centroid = TrackFraming.centroid(
      listOf(LatLng(10.0, 0.0), LatLng(20.0, 0.0), LatLng(15.0, 0.0)),
    )

    assertEquals(15.0, centroid.latitude, 1e-6)
    assertEquals(0.0, centroid.longitude, 1e-6)
  }

  /**
   * A naive mean of longitudes puts a track spanning the antimeridian near
   * Greenwich, which is the wrong hemisphere and roughly 20,000 km away.
   */
  @Test
  fun `the centroid is correct across the antimeridian`() {
    val centroid = TrackFraming.centroid(
      listOf(LatLng(0.0, 179.9), LatLng(0.0, -179.9)),
    )

    assertEquals(0.0, centroid.latitude, 1e-6)
    assertEquals(180.0, kotlin.math.abs(centroid.longitude), 1e-6)
  }

  @Test
  fun `zoom falls as the route grows`() {
    val short = TrackFraming.zoomFor(5.0)
    val medium = TrackFraming.zoomFor(50.0)
    val long = TrackFraming.zoomFor(500.0)
    val continental = TrackFraming.zoomFor(4_000.0)

    assertTrue(short > medium)
    assertTrue(medium > long)
    assertTrue(long > continental)
  }

  @Test
  fun `zoom is clamped at both ends`() {
    assertEquals(15.0, TrackFraming.zoomFor(0.0), 1e-9)
    assertEquals(15.0, TrackFraming.zoomFor(0.001), 1e-9)
    assertEquals(3.0, TrackFraming.zoomFor(200_000.0), 1e-9)
  }

  /**
   * Sanity check against the web-Mercator geometry the formula is derived from.
   *
   * A 2,000 km span needs about 400 screen pixels at zoom 5, which is a snug fit
   * for a phone held portrait. The tilted camera foreshortens the far distance
   * further, so this is a starting point for the user to pinch from, not a
   * guaranteed exact fit.
   */
  @Test
  fun `a two thousand kilometre route lands in a continental zoom range`() {
    val zoom = TrackFraming.zoomFor(2_000.0)
    assertTrue("expected z4-z6 but was $zoom", zoom in 4.0..6.0)
  }

  @Test
  fun `the camera frames the whole route`() {
    val track = Track.of(
      listOf(
        point(34.6, -84.2), // Springer Mountain, Georgia
        point(45.9, -68.9), // Mount Katahdin, Maine
      ),
    )

    val camera = TrackFraming.cameraFor(track)

    assertTrue(camera != null)
    assertEquals(CameraOptions.CINEMATIC_TILT, camera!!.tilt, 1e-9)
    // The Appalachian Trail spans roughly 20 degrees of latitude, so the centre
    // sits around 40 degrees north and up the eastern seaboard.
    assertEquals(40.0, camera.target.latitude, 1.0)
    assertTrue("expected an overview zoom but was ${camera.zoom}", camera.zoom in 3.0..8.0)
  }

  @Test
  fun `a single point track zooms all the way in`() {
    val camera = TrackFraming.cameraFor(Track.of(listOf(point(46.0, 7.0))))

    assertEquals(15.0, camera!!.zoom, 1e-9)
    assertEquals(46.0, camera.target.latitude, 1e-9)
  }

  @Test
  fun `the diagonal spans the track rather than its width`() {
    // Two points 1,000 km apart on the same meridian.
    val positions = listOf(LatLng(0.0, 0.0), LatLng(9.0, 0.0))
    val diagonal = TrackFraming.diagonalKilometers(positions)

    // One degree of latitude is about 111 km, so nine degrees is about 999 km.
    assertEquals(1_000.0, diagonal, 20.0)
  }

  @Test
  fun `segments do not distort the frame`() {
    val track = Track(
      listOf(
        TrackSegment(listOf(point(0.0, 0.0), point(1.0, 0.0))),
        TrackSegment(listOf(point(2.0, 0.0), point(3.0, 0.0))),
      ),
    )

    val camera = TrackFraming.cameraFor(track)

    assertEquals(1.5, camera!!.target.latitude, 0.01)
  }

  @Test
  fun `a centroid of no positions is rejected`() {
    val failure = runCatching { TrackFraming.centroid(emptyList()) }
    assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
  }
}
