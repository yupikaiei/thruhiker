package com.thruhiker.core.geo

import com.thruhiker.core.model.Track

/** Computes [TrackStats] for a track in a single pass over its points. */
object TrackStatsCalculator {

  fun compute(
    track: Track,
    options: TrackStatsOptions = TrackStatsOptions(),
  ): TrackStats {
    val points = track.points
    if (points.isEmpty()) return TrackStats.Empty

    var distanceMeters = 0.0
    var movingMillis = 0L
    var hasTimingData = false

    // Segment by segment: step 1 of the outer loop is the first point of a new
    // segment, and there is no leg joining it to the last point of the previous
    // one, because the hiker's path across that gap is unknown.
    for (segment in track.segments) {
      val points = segment.points

      for (index in 1 until points.size) {
        val previous = points[index - 1]
        val current = points[index]

        val segmentDistance = Geodesic.distanceMeters(previous.position, current.position)
        distanceMeters += segmentDistance

        val previousTime = previous.timeMillis
        val currentTime = current.timeMillis
        if (previousTime != null && currentTime != null) {
          val delta = currentTime - previousTime
          if (delta > 0L && delta <= options.maximumGapMillis) {
            hasTimingData = true
            val speed = segmentDistance / (delta / 1000.0)
            if (speed >= options.movingSpeedThresholdMetersPerSecond) {
              movingMillis += delta
            }
          }
        }
      }
    }

    val elevations = track.elevations()
    val gainLoss = ElevationCalculator.gainLoss(elevations, options.elevationThresholdMeters)

    val startTime = points.firstOrNull { it.timeMillis != null }?.timeMillis
    val endTime = points.lastOrNull { it.timeMillis != null }?.timeMillis
    val elapsedMillis = if (startTime != null && endTime != null && endTime > startTime) {
      endTime - startTime
    } else {
      null
    }

    val averageSpeed = elapsedMillis
      ?.takeIf { it > 0L }
      ?.let { distanceMeters / (it / 1000.0) }

    val movingSpeed = if (hasTimingData && movingMillis > 0L) {
      distanceMeters / (movingMillis / 1000.0)
    } else {
      null
    }

    return TrackStats(
      pointCount = points.size,
      distanceMeters = distanceMeters,
      elevationGainMeters = gainLoss.gainMeters,
      elevationLossMeters = gainLoss.lossMeters,
      minimumElevationMeters = ElevationCalculator.minimum(elevations),
      maximumElevationMeters = ElevationCalculator.maximum(elevations),
      startTimeMillis = startTime,
      endTimeMillis = endTime,
      elapsedMillis = elapsedMillis,
      movingMillis = if (hasTimingData) movingMillis else null,
      averageSpeedMetersPerSecond = averageSpeed,
      movingSpeedMetersPerSecond = movingSpeed,
    )
  }
}
