package com.thruhiker.core.geo

import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import com.thruhiker.core.model.TrackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSamplerTest {

  private val origin = LatLng(46.0, 7.0)

  private fun point(latitude: Double, longitude: Double, elevation: Double? = null) =
    TrackPoint(LatLng(latitude, longitude), elevationMeters = elevation)

  private fun north(from: LatLng, distanceMeters: Double): LatLng =
    Geodesic.destination(from, 0.0, distanceMeters)

  /**
   * A due-north line built by projecting an exact distance leg by leg.
   *
   * Adding a latitude offset would be easier and would quietly break these tests: a
   * degree of latitude is 111,132 m at the equator and roughly 111,151 m at 46
   * degrees north, so the legs would not be the length the assertions assume.
   */
  private fun northTrack(count: Int, spacingMeters: Double): Track {
    var position = origin
    val points = ArrayList<TrackPoint>(count)
    for (index in 0 until count) {
      if (index > 0) position = north(position, spacingMeters)
      points.add(TrackPoint(position, elevationMeters = 1000.0 + index * 10.0))
    }
    return Track.of(points)
  }

  @Test
  fun `an empty track has no sampler`() {
    assertNull(TrackSampler.of(Track.Empty))
  }

  @Test
  fun `a track of only empty segments has no sampler`() {
    assertNull(TrackSampler.of(Track(listOf(TrackSegment(emptyList())))))
  }

  @Test
  fun `total distance matches the geodesic path length`() {
    val track = northTrack(count = 10, spacingMeters = 500.0)
    val sampler = TrackSampler.of(track)!!

    val expected = track.segments.sumOf { segment ->
      Geodesic.pathLengthMeters(segment.points.map { it.position })
    }

    assertEquals(expected, sampler.totalDistanceMeters, 1e-6)
    assertEquals(4_500.0, sampler.totalDistanceMeters, 1e-6)
  }

  @Test
  fun `sampling the start and end returns the endpoints`() {
    val sampler = TrackSampler.of(northTrack(count = 5, spacingMeters = 100.0))!!

    val start = sampler.sampleAt(0.0)
    val end = sampler.sampleAt(sampler.totalDistanceMeters)

    assertEquals(0.0, Geodesic.distanceMeters(origin, start.position), 1e-6)
    assertEquals(400.0, Geodesic.distanceMeters(origin, end.position), 1e-6)
    assertEquals(0.0, start.distanceMeters, 1e-9)
  }

  @Test
  fun `sampling between points interpolates along the leg`() {
    val sampler = TrackSampler.of(northTrack(count = 3, spacingMeters = 1_000.0))!!

    val sample = sampler.sampleAt(500.0)

    // Tolerance is a centimetre rather than a micron: position is interpolated on
    // the sphere, which sits a fraction of a millimetre from the ellipsoidal
    // midpoint over a kilometre. A centimetre is still far tighter than any GPS.
    assertEquals(500.0, Geodesic.distanceMeters(origin, sample.position), 0.01)
    // A geodesic heading due north holds its longitude.
    assertEquals(7.0, sample.position.longitude, 1e-9)
  }

  @Test
  fun `elevation is interpolated between samples`() {
    val track = Track.of(
      listOf(
        TrackPoint(origin, elevationMeters = 1000.0),
        TrackPoint(north(origin, 1_000.0), elevationMeters = 1200.0),
      ),
    )
    val sampler = TrackSampler.of(track)!!

    assertEquals(1000.0, sampler.sampleAt(0.0).elevationMeters!!, 1e-6)
    assertEquals(1100.0, sampler.sampleAt(500.0).elevationMeters!!, 1e-6)
    assertEquals(1200.0, sampler.sampleAt(1_000.0).elevationMeters!!, 1e-6)
  }

  @Test
  fun `elevation is carried through when only one end has it`() {
    val track = Track.of(
      listOf(
        TrackPoint(origin, elevationMeters = 1000.0),
        TrackPoint(north(origin, 1_000.0)),
      ),
    )
    val sampler = TrackSampler.of(track)!!

    assertEquals(1000.0, sampler.sampleAt(500.0).elevationMeters!!, 1e-6)
  }

  @Test
  fun `elevation is null when neither end has it`() {
    val track = Track.of(listOf(TrackPoint(origin), TrackPoint(north(origin, 1_000.0))))
    val sampler = TrackSampler.of(track)!!

    assertNull(sampler.sampleAt(500.0).elevationMeters)
  }

  @Test
  fun `bearing follows the leg direction`() {
    val sampler = TrackSampler.of(northTrack(count = 4, spacingMeters = 500.0))!!

    assertEquals(0.0, sampler.sampleAt(100.0).bearingDegrees, 1e-6)
    assertEquals(0.0, sampler.sampleAt(1_200.0).bearingDegrees, 1e-6)
  }

  @Test
  fun `distance beyond the end is clamped`() {
    val sampler = TrackSampler.of(northTrack(count = 4, spacingMeters = 500.0))!!

    val past = sampler.sampleAt(1_000_000.0)
    val end = sampler.sampleAt(sampler.totalDistanceMeters)

    assertEquals(sampler.totalDistanceMeters, past.distanceMeters, 1e-9)
    assertEquals(end.position, past.position)
  }

  @Test
  fun `distance before the start is clamped`() {
    val sampler = TrackSampler.of(northTrack(count = 4, spacingMeters = 500.0))!!

    val before = sampler.sampleAt(-5_000.0)

    assertEquals(0.0, before.distanceMeters, 1e-9)
    assertEquals(0.0, Geodesic.distanceMeters(origin, before.position), 1e-6)
  }

  @Test
  fun `a fractional sample walks distance rather than point count`() {
    val sampler = TrackSampler.of(northTrack(count = 5, spacingMeters = 1_000.0))!!

    assertEquals(
      sampler.sampleAt(sampler.totalDistanceMeters / 2).position.latitude,
      sampler.sampleAtFraction(0.5).position.latitude,
      1e-9,
    )
  }

  /**
   * The distance of a gap belongs to nobody. Sampling past the boundary must
   * resolve to the later segment's geometry rather than leaping across the void.
   */
  @Test
  fun `sampling after a gap stays in the later segment`() {
    val firstLeg = listOf(point(46.0, 7.0, 0.0), point(46.01, 7.0, 100.0))
    val secondLeg = listOf(point(47.0, 8.0, 900.0), point(47.01, 8.0, 1000.0))
    val track = Track(listOf(TrackSegment(firstLeg), TrackSegment(secondLeg)))

    val sampler = TrackSampler.of(track)!!
    val firstLegLength = Geodesic.distanceMeters(firstLeg[0].position, firstLeg[1].position)

    val justIntoSecond = sampler.sampleAt(firstLegLength + 100.0)

    assertEquals(1, justIntoSecond.segmentIndex)
    assertEquals(47.0, justIntoSecond.position.latitude, 0.01)
    assertEquals(8.0, justIntoSecond.position.longitude, 0.01)
  }

  /**
   * Two hundred-metre recordings a hundred metres apart, standing in for a walk whose
   * recording stopped and started again.
   */
  private fun gappedTrack(): Track = Track(
    listOf(
      TrackSegment(listOf(point(46.0, 7.0, 0.0), point(46.0018, 7.0, 100.0))),
      TrackSegment(listOf(point(46.0027, 7.0, 200.0), point(46.0045, 7.0, 300.0))),
    ),
  )

  private fun recordedLeg(): Double = Geodesic.distanceMeters(
    point(46.0, 7.0).position,
    point(46.0018, 7.0).position,
  )

  private fun recordedGap(): Double = Geodesic.distanceMeters(
    point(46.0018, 7.0).position,
    point(46.0027, 7.0).position,
  )

  /**
   * The flight axis counts the unrecorded stretch between two recordings as ground a
   * camera still has to cross. It exists because the walking axis, which must never
   * cross a gap, makes a camera teleport over one.
   */
  @Test
  fun `flight distance counts the gap between recordings`() {
    val sampler = TrackSampler.of(gappedTrack())!!

    assertEquals(recordedLeg() * 2, sampler.totalDistanceMeters, 0.5)
    assertEquals(recordedLeg() * 2 + recordedGap(), sampler.totalFlightDistanceMeters, 0.5)
  }

  @Test
  fun `a flight sample crosses a gap instead of jumping it`() {
    val sampler = TrackSampler.of(gappedTrack())!!
    val leg = recordedLeg()
    val gap = recordedGap()

    val halfWayAcross = sampler.sampleAtFlight(leg + gap / 2)

    // Half way over the gap is half way between where the recording stopped and where the
    // next one began, with elevation interpolated between the same two ends.
    assertEquals(46.00225, halfWayAcross.position.latitude, 0.00002)
    assertEquals(7.0, halfWayAcross.position.longitude, 1e-9)
    assertEquals(150.0, halfWayAcross.elevationMeters!!, 2.0)

    // The walk, though, has not moved: a gap is nobody's mileage.
    assertEquals(leg, halfWayAcross.distanceMeters, 0.5)
    assertEquals(0, halfWayAcross.segmentIndex)
  }

  @Test
  fun `the walk holds still while the camera crosses a gap`() {
    val sampler = TrackSampler.of(gappedTrack())!!
    val leg = recordedLeg()
    val gap = recordedGap()

    assertEquals(leg - 50.0, sampler.walkedDistanceAtFlight(leg - 50.0), 0.5)
    assertEquals(leg, sampler.walkedDistanceAtFlight(leg + gap * 0.1), 0.5)
    assertEquals(leg, sampler.walkedDistanceAtFlight(leg + gap), 0.5)
    assertEquals(leg + 50.0, sampler.walkedDistanceAtFlight(leg + gap + 50.0), 0.5)
  }

  /** The invariant the flyover camera leans on: no single step ever jumps a gap. */
  @Test
  fun `flight sampling never steps further than the step`() {
    val sampler = TrackSampler.of(gappedTrack())!!
    val step = 5.0

    var previous = sampler.sampleAtFlight(0.0)
    var distance = step
    while (distance <= sampler.totalFlightDistanceMeters) {
      val sample = sampler.sampleAtFlight(distance)
      val moved = Geodesic.distanceMeters(previous.position, sample.position)
      assertTrue("flight moved $moved m in a $step m step at $distance m", moved <= step * 1.5)
      previous = sample
      distance += step
    }
  }

  @Test
  fun `flight and walking sampling agree inside a recorded stretch`() {
    val sampler = TrackSampler.of(gappedTrack())!!
    val leg = recordedLeg()

    var distance = 0.0
    while (distance < leg) {
      assertEquals(
        sampler.sampleAt(distance).position.latitude,
        sampler.sampleAtFlight(distance).position.latitude,
        1e-12,
      )
      assertEquals(distance, sampler.walkedDistanceAtFlight(distance), 1e-9)
      distance += 25.0
    }
  }

  @Test
  fun `the segment index advances with distance`() {
    val track = Track(
      listOf(
        TrackSegment(listOf(point(46.0, 7.0), point(46.01, 7.0))),
        TrackSegment(listOf(point(47.0, 8.0), point(47.01, 8.0))),
        TrackSegment(listOf(point(48.0, 9.0), point(48.01, 9.0))),
      ),
    )
    val sampler = TrackSampler.of(track)!!
    val total = sampler.totalDistanceMeters

    assertEquals(0, sampler.sampleAt(0.0).segmentIndex)
    assertEquals(0, sampler.sampleAt(total * 0.30).segmentIndex)
    assertEquals(1, sampler.sampleAt(total * 0.40).segmentIndex)
    assertEquals(2, sampler.sampleAt(total * 0.90).segmentIndex)
  }

  @Test
  fun `duplicate consecutive points do not produce a broken sample`() {
    val track = Track.of(
      listOf(
        TrackPoint(origin, elevationMeters = 100.0),
        TrackPoint(origin, elevationMeters = 100.0),
        TrackPoint(north(origin, 1_000.0), elevationMeters = 200.0),
      ),
    )
    val sampler = TrackSampler.of(track)!!

    val middle = sampler.sampleAt(500.0)

    assertTrue(middle.position.latitude.isFinite())
    // Spherical interpolation sits a fraction of a millimetre from the
    // ellipsoidal midpoint over a kilometre, so a centimetre is the honest
    // tolerance, and still far tighter than any GPS.
    assertEquals(500.0, Geodesic.distanceMeters(origin, middle.position), 0.01)
    assertEquals(150.0, middle.elevationMeters!!, 1e-6)
  }

  @Test
  fun `a track with no length samples without dividing by zero`() {
    val sampler = TrackSampler.of(
      Track.of(listOf(TrackPoint(origin, elevationMeters = 100.0), TrackPoint(origin, elevationMeters = 100.0))),
    )!!

    val sample = sampler.sampleAt(0.0)

    assertEquals(0.0, sampler.totalDistanceMeters, 1e-9)
    assertEquals(0.0, Geodesic.distanceMeters(origin, sample.position), 1e-6)
  }

  @Test
  fun `a single point track is walkable at zero distance`() {
    val sampler = TrackSampler.of(Track.of(listOf(TrackPoint(origin, elevationMeters = 100.0))))!!

    assertEquals(0.0, sampler.totalDistanceMeters, 1e-9)
    assertEquals(1, sampler.segmentCount)
    assertEquals(0.0, Geodesic.distanceMeters(origin, sampler.sampleAt(0.0).position), 1e-6)
  }
}
