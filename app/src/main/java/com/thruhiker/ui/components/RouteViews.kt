package com.thruhiker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thruhiker.core.geo.GradientBand
import com.thruhiker.core.geo.Split
import com.thruhiker.core.geo.Splits

/** A labelled number: the shape every summary in this app uses. */
@Composable
fun StatChip(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
  Column(modifier) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.titleMedium,
      color = valueColor,
    )
  }
}

/** The blue-to-red ramp, so the map's colours and the chart's colours can be read. */
@Composable
fun GradientLegend(modifier: Modifier = Modifier) {
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    LEGEND_BANDS.forEach { band ->
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
          modifier = Modifier
            .width(26.dp)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(bandColor(band)),
        )
        Text(
          text = legendLabel(band),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * The split table: one row per [SplitsCalculator] interval.
 *
 * This is the "splits" of the name, and the reason it is worth computing properly: the
 * per-split times come from Tobler over the elevation actually crossed in that interval, so a
 * kilometre of climbing is shown as slower than the kilometre that follows it downhill.
 *
 * When [startTimeMillis] is given the last column stops being elapsed time and becomes the
 * clock time that split is reached. Both say the same thing; which one is wanted depends on
 * whether the reader is reviewing a route or standing at the trailhead working out whether
 * they will make the hut before dark.
 */
@Composable
fun SplitsList(
  splits: Splits,
  modifier: Modifier = Modifier,
  startTimeMillis: Long? = null,
) {
  val unit = LocalDistanceUnits.current.unit

  if (splits.splits.isEmpty()) {
    Text(
      text = "Build a route with at least two points to see its splits.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = modifier.padding(16.dp),
    )
    return
  }

  LazyColumn(modifier = modifier) {
    item {
      SplitRow(
        label = "Split",
        distance = "Dist",
        gain = "Up",
        gradient = "Grade",
        time = "Time",
        cumulative = if (startTimeMillis == null) "Total" else "Clock",
        emphasise = false,
        gradientColor = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    items(splits.splits, key = { it.index }) { split ->
      SplitRow(
        label = if (split.isPartial) "${split.index} · part" else "${split.index}",
        distance = formatDistance(split.distanceMeters, unit),
        gain = formatElevation(split.gainMeters, unit),
        gradient = formatGradient(split.averageGradientPercent),
        time = formatDuration(split.seconds),
        cumulative = if (startTimeMillis == null) {
          formatDuration(split.cumulativeSeconds)
        } else {
          formatClockTime(startTimeMillis + (split.cumulativeSeconds * 1000.0).toLong())
        },
        emphasise = split.index == splits.fastestSplit?.index,
        gradientColor = bandColor(split.band),
      )
    }
  }
}

/** The colour band a split's net gradient falls in. */
private val Split.band: GradientBand
  get() = GradientBand.of(averageGradientPercent)

@Composable
private fun SplitRow(
  label: String,
  distance: String,
  gain: String,
  gradient: String,
  time: String,
  cumulative: String,
  emphasise: Boolean,
  gradientColor: Color,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = if (emphasise) FontWeight.Bold else FontWeight.Normal,
      color = if (emphasise) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.width(64.dp),
    )
    Text(distance, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    Text(
      text = gain,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = gradient,
      style = MaterialTheme.typography.bodySmall,
      fontWeight = FontWeight.Medium,
      color = gradientColor,
      modifier = Modifier.weight(1f),
    )
    Text(time, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    Text(
      text = cumulative,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
  }
}

/** Only the bands a route can actually be in; unknown is not a colour, it is the absence. */
private val LEGEND_BANDS = listOf(
  GradientBand.STEEP_DESCENT,
  GradientBand.DESCENT,
  GradientBand.GENTLE_DESCENT,
  GradientBand.FLAT,
  GradientBand.GENTLE_CLIMB,
  GradientBand.CLIMB,
  GradientBand.STEEP_CLIMB,
)

private fun legendLabel(band: GradientBand): String = when (band) {
  GradientBand.STEEP_DESCENT -> "15%"
  GradientBand.DESCENT -> "7%"
  GradientBand.GENTLE_DESCENT -> "3%"
  GradientBand.FLAT -> "0"
  GradientBand.GENTLE_CLIMB -> "3%"
  GradientBand.CLIMB -> "7%"
  GradientBand.STEEP_CLIMB -> "15%"
  GradientBand.UNKNOWN -> "?"
}
