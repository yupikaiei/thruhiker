package com.thruhiker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thruhiker.core.geo.ElevationProfile
import com.thruhiker.core.geo.GradientBand
import kotlin.math.roundToInt

/**
 * The elevation profile, painted by gradient band.
 *
 * This is the picture that makes a route legible before it is walked: the shape says how hard
 * the day is, and the colour says where it will hurt. Both come from the same distance-sampled
 * profile the statistics do, so the chart cannot disagree with the numbers beside it.
 *
 * Drag or tap anywhere on it to read a point off the route.
 */
@Composable
fun ElevationProfileChart(
  profile: ElevationProfile,
  modifier: Modifier = Modifier,
  scrubFraction: Float? = null,
  onScrub: ((Float) -> Unit)? = null,
  onScrubFinished: (() -> Unit)? = null,
  emptyLabel: String = "No elevation data",
) {
  val colors = MaterialTheme.colorScheme
  val gridColor = colors.outline.copy(alpha = 0.30f)
  val labelColor = colors.onSurfaceVariant
  val textMeasurer = rememberTextMeasurer()

  val samples = profile.samples
  val drawable = samples.size >= 2 && profile.hasElevation && profile.totalDistanceMeters > 0.0
  val elevations = remember(profile) { filledElevations(profile) }
  val scrubIndex = remember(profile, scrubFraction) {
    if (!drawable || scrubFraction == null) null else indexAt(profile, scrubFraction)
  }

  Box(
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .background(colors.surfaceVariant.copy(alpha = 0.45f))
      // Horizontal drags only. `detectDragGestures` would claim the gesture in every
      // direction, and the chart sits inside a scrollable panel, so a user dragging up to see
      // the rest of the panel would scrub the profile instead of scrolling. Waiting for
      // horizontal movement is what lets the two gestures coexist.
      .pointerInput(onScrub) {
        detectHorizontalDragGestures(
          onDragStart = { offset -> onScrub?.invoke(fractionOf(offset.x, size.width)) },
          onHorizontalDrag = { change, _ ->
            onScrub?.invoke(fractionOf(change.position.x, size.width))
          },
          onDragEnd = { onScrubFinished?.invoke() },
        )
      }
      .pointerInput(onScrub, onScrubFinished) {
        detectTapGestures { offset ->
          onScrub?.invoke(fractionOf(offset.x, size.width))
          onScrubFinished?.invoke()
        }
      },
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val topPad = 10.dp.toPx()
      val bottomPad = 8.dp.toPx()
      val plotHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)

      if (!drawable) {
        val baseline = size.height / 2f
        drawLine(
          color = gridColor,
          start = Offset(0f, baseline),
          end = Offset(size.width, baseline),
          strokeWidth = 2f,
          pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
        )
        val caption = textMeasurer.measure(
          AnnotatedString(emptyLabel),
          style = TextStyle(color = labelColor, fontSize = 12.sp),
        )
        drawText(
          textLayoutResult = caption,
          topLeft = Offset((size.width - caption.size.width) / 2f, baseline + 8.dp.toPx()),
        )
        return@Canvas
      }

      val minimum = profile.minimumElevationMeters ?: return@Canvas
      val maximum = profile.maximumElevationMeters ?: return@Canvas
      val span = (maximum - minimum).coerceAtLeast(1.0)
      val totalDistance = profile.totalDistanceMeters
      val baseline = size.height - bottomPad

      fun xOf(distanceMeters: Double): Float =
        (distanceMeters / totalDistance).toFloat() * size.width

      fun yOf(elevationMeters: Double): Float =
        topPad + (1.0 - ((elevationMeters - minimum) / span)).toFloat() * plotHeight

      for (fraction in listOf(0.0f, 0.5f, 1.0f)) {
        val y = topPad + fraction * plotHeight
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
      }

      fun drawRun(fromSegment: Int, toSegment: Int) {
        val band = samples[toSegment].band
        val color = bandColor(band)

        val path = Path()
        val firstX = xOf(samples[fromSegment - 1].distanceMeters)
        path.moveTo(firstX, yOf(elevations[fromSegment - 1]))
        for (index in fromSegment..toSegment) {
          path.lineTo(xOf(samples[index].distanceMeters), yOf(elevations[index]))
        }

        val lastX = xOf(samples[toSegment].distanceMeters)
        drawPath(
          path = filledPath(path, firstX, lastX, baseline),
          color = color.copy(alpha = 0.30f),
        )
        drawPath(
          path = path,
          color = color,
          style = Stroke(
            width = 2.5.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
          ),
        )
      }

      // A run is a maximal stretch of consecutive legs in one band. Drawing band by band
      // rather than segment by segment keeps the number of fill paths proportional to the
      // number of colour changes instead of the number of points.
      var runStart = 1
      for (segment in 2 until samples.size) {
        if (samples[segment].band != samples[segment - 1].band) {
          drawRun(runStart, segment - 1)
          runStart = segment
        }
      }
      drawRun(runStart, samples.size - 1)

      if (scrubIndex != null) {
        val x = xOf(samples[scrubIndex].distanceMeters)
        val y = yOf(elevations[scrubIndex])
        drawLine(
          color = colors.onSurface.copy(alpha = 0.55f),
          start = Offset(x, topPad),
          end = Offset(x, baseline),
          strokeWidth = 2f,
        )
        drawCircle(color = colors.onSurface, radius = 4.dp.toPx(), center = Offset(x, y))
      }
    }

    if (scrubIndex != null) {
      val sample = samples[scrubIndex]
      val unit = LocalDistanceUnits.current.unit
      Text(
        text = "${formatDistance(sample.distanceMeters, unit)} · " +
          "${formatElevation(sample.elevationMeters, unit)} · ${formatBand(sample.band)}",
        style = MaterialTheme.typography.labelSmall,
        color = labelColor,
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(start = 8.dp, top = 4.dp),
      )
    }
  }
}

/** A copy of [path] closed along the baseline, so the area under the curve can be filled. */
private fun filledPath(path: Path, startX: Float, endX: Float, baseline: Float): Path =
  Path().apply {
    addPath(path)
    lineTo(endX, baseline)
    lineTo(startX, baseline)
    close()
  }

/**
 * The profile's heights with the holes filled in.
 *
 * A track can carry elevation for one part of a route and not another, and a chart with a
 * hole in it has no honest way to draw the hole. Carrying the last known height across it
 * keeps the curve continuous; the band colour for those legs is still [GradientBand.UNKNOWN],
 * so the stretch is grey rather than pretending to be flat.
 */
private fun filledElevations(profile: ElevationProfile): List<Double> {
  val samples = profile.samples
  val result = MutableList(samples.size) { 0.0 }
  var last: Double? = null

  for (index in samples.indices) {
    samples[index].elevationMeters?.let { last = it }
    result[index] = last ?: 0.0
  }

  var next: Double? = null
  for (index in samples.indices.reversed()) {
    val elevation = samples[index].elevationMeters
    if (elevation != null) next = elevation
    if (elevation == null && next != null) result[index] = next
  }

  return result
}

/** The sample index under a horizontal position, given the even distance sampling. */
private fun indexAt(profile: ElevationProfile, fraction: Float): Int {
  val lastIndex = profile.samples.size - 1
  return (fraction.coerceIn(0f, 1f) * lastIndex).roundToInt().coerceIn(0, lastIndex)
}

private fun fractionOf(x: Float, width: Int): Float =
  if (width <= 0) 0f else (x / width).coerceIn(0f, 1f)

/** Turns a band's `#RRGGBB` into a Compose colour. */
internal fun bandColor(band: GradientBand): Color {
  val value = band.colorHex.removePrefix("#").toLongOrNull(16) ?: return Color.Gray
  return Color(0xFF000000L or value)
}
