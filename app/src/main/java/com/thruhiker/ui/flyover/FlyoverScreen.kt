package com.thruhiker.ui.flyover

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.thruhiker.R
import com.thruhiker.core.flyover.FlyoverRig
import com.thruhiker.core.geo.ToblerEstimator
import com.thruhiker.core.geo.TrackStats
import com.thruhiker.core.geo.TrackStatsCalculator
import com.thruhiker.core.gpx.GpxParser
import com.thruhiker.core.mapping.GeoJsonEncoder
import com.thruhiker.core.mapping.MapController
import com.thruhiker.core.mapping.ThruHikerMap
import com.thruhiker.core.mapping.TrackFraming
import com.thruhiker.core.model.CameraOptions
import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/**
 * Mont Blanc massif: the reference view when no route is loaded.
 *
 * It is a good test case because it has everything at once: a deep valley, a
 * glaciated summit and a 4,800 m relief range within one screen at this zoom, so
 * terrain that looks right here will look right anywhere.
 */
private val MONT_BLANC = LatLng(45.8326, 6.8652)

private val DEFAULT_CAMERA = CameraOptions(
  target = MONT_BLANC,
  zoom = 12.0,
  tilt = CameraOptions.CINEMATIC_TILT,
  bearing = 20.0,
)

/**
 * How often the on-screen readout is refreshed.
 *
 * The camera is driven imperatively through the map controller, so it does not go
 * through Compose at all. Rebuilding a text label sixty times a second would
 * recompose the whole screen for no visible gain, so the readout lags at human
 * speed on purpose.
 */
private const val HUD_INTERVAL_NANOS = 100_000_000L

/**
 * Reveal granularity, which is a bandwidth decision rather than a visual one.
 *
 * Every step re-encodes the geometry walked so far and uploads it to the map, so
 * the cost of a flight is roughly the number of steps times the average revealed
 * length. A hundred-thousand-point route revealed in three hundred steps would
 * move gigabytes of JSON; twenty steps still looks continuous.
 */
private fun revealStepFor(pointCount: Int): Double = when {
  pointCount > 50_000 -> 0.02
  pointCount > 10_000 -> 0.01
  else -> 1.0 / 300.0
}

/** A route the user imported, with everything derived from it computed once, off the main thread. */
private data class ImportedRoute(
  val name: String?,
  val track: Track,
  val geoJson: String,
  val stats: TrackStats,
  val walkingSeconds: Double,
  val camera: CameraOptions,
  val rig: FlyoverRig?,
)

/** The bit of flight state worth recomposing for. */
private data class FlightHud(
  val progress: Double,
  val elevationMeters: Double?,
  val distanceAlongTrackMeters: Double,
)

/**
 * The 3D cinematic screen.
 *
 * Import a GPX file, frame it over real terrain, then fly it: the camera follows
 * the route while the route draws itself behind the camera. Scrubbing the flight is
 * the same code path as playing it, one frame at a time.
 */
@Composable
fun FlyoverScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var route by remember { mutableStateOf<ImportedRoute?>(null) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var controller by remember { mutableStateOf<MapController?>(null) }
  var isPlaying by remember { mutableStateOf(false) }
  var flightMillis by remember { mutableStateOf(0L) }
  var hud by remember { mutableStateOf<FlightHud?>(null) }
  var finished by remember { mutableStateOf(false) }

  val unreadable = stringResource(R.string.route_error_unreadable)
  val noTrack = stringResource(R.string.route_error_no_track)

  /** Applies a single frame. Playback and scrubbing both go through here. */
  val applyFrame: (Long) -> Unit = { millis ->
    val active = route
    val rig = active?.rig
    if (active != null && rig != null) {
      val clamped = millis.coerceIn(0L, rig.durationMillis)
      val frame = rig.frameAt(clamped)
      flightMillis = clamped
      controller?.moveCamera(frame.camera)
      controller?.updateTrack(GeoJsonEncoder.encode(active.track, frame.revealedFraction))
      hud = FlightHud(frame.progress, frame.elevationMeters, frame.distanceAlongTrackMeters)
      finished = clamped >= rig.durationMillis
    }
  }

  val picker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult

    scope.launch {
      // Parsing, statistics, the walking-time estimate, the GeoJSON encoding and the
      // flight plan all walk the whole track, and a trail guide is a real file.
      val outcome: Result<ImportedRoute> = withContext(Dispatchers.IO) {
        runCatching {
          val document = context.contentResolver.openInputStream(uri)?.use { stream ->
            GpxParser.parse(stream)
          } ?: throw IllegalStateException(unreadable)

          // A waypoint-only file is valid GPX and useless here.
          require(!document.track.isEmpty) { noTrack }

          val track = document.track
          ImportedRoute(
            name = document.name,
            track = track,
            geoJson = GeoJsonEncoder.encode(track),
            stats = TrackStatsCalculator.compute(track),
            walkingSeconds = ToblerEstimator.hikingSeconds(track),
            camera = TrackFraming.cameraFor(track) ?: DEFAULT_CAMERA,
            rig = FlyoverRig.of(track),
          )
        }
      }

      outcome
        .onSuccess { imported ->
          route = imported
          errorMessage = null
          isPlaying = false
          finished = false
          flightMillis = 0L
          hud = null
        }
        .onFailure { failure ->
          errorMessage = failure.message ?: unreadable
        }
    }
  }

  LaunchedEffect(isPlaying, route) {
    val active = route ?: return@LaunchedEffect
    val rig = active.rig ?: return@LaunchedEffect
    if (!isPlaying) return@LaunchedEffect

    val revealStep = revealStepFor(active.track.size)
    var elapsed = flightMillis
    var previousFrameNanos = withFrameNanos { it }
    var previousHudNanos = previousFrameNanos
    var appliedReveal = rig.frameAt(elapsed).revealedFraction

    while (true) {
      val now = withFrameNanos { it }
      elapsed += (now - previousFrameNanos) / 1_000_000L
      previousFrameNanos = now

      if (elapsed >= rig.durationMillis) {
        val last = rig.frameAt(rig.durationMillis)
        controller?.moveCamera(last.camera)
        controller?.updateTrack(GeoJsonEncoder.encode(active.track, 1.0))
        flightMillis = rig.durationMillis
        hud = FlightHud(1.0, last.elevationMeters, last.distanceAlongTrackMeters)
        finished = true
        isPlaying = false
        break
      }

      val frame = rig.frameAt(elapsed)
      controller?.moveCamera(frame.camera)

      if (abs(frame.revealedFraction - appliedReveal) >= revealStep) {
        appliedReveal = frame.revealedFraction
        controller?.updateTrack(GeoJsonEncoder.encode(active.track, frame.revealedFraction))
      }

      if (now - previousHudNanos >= HUD_INTERVAL_NANOS) {
        previousHudNanos = now
        flightMillis = elapsed
        hud = FlightHud(frame.progress, frame.elevationMeters, frame.distanceAlongTrackMeters)
      }
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    ThruHikerMap(
      camera = route?.camera ?: DEFAULT_CAMERA,
      trackGeoJson = route?.geoJson,
      onMapReady = { controller = it },
      modifier = Modifier.fillMaxSize(),
    )

    RouteCard(
      route = route,
      hud = hud,
      isPlaying = isPlaying,
      finished = finished,
      errorMessage = errorMessage,
      onImportClick = { picker.launch(GPX_MIME_TYPES) },
      onPlayToggle = {
        val rig = route?.rig
        if (isPlaying) {
          isPlaying = false
        } else if (rig != null) {
          // A finished flight restarts rather than sitting at the end.
          if (finished || flightMillis >= rig.durationMillis) applyFrame(0L)
          isPlaying = true
        }
      },
      onScrub = { fraction ->
        val rig = route?.rig
        if (rig != null) {
          isPlaying = false
          applyFrame((fraction * rig.durationMillis).toLong())
        }
      },
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(12.dp),
    )
  }
}

/**
 * GPX has no registered MIME type that every file provider agrees on, so the picker
 * is opened to everything and the contents are validated on read instead.
 */
private val GPX_MIME_TYPES = arrayOf("*/*")

@Composable
private fun RouteCard(
  route: ImportedRoute?,
  hud: FlightHud?,
  isPlaying: Boolean,
  finished: Boolean,
  errorMessage: String?,
  onImportClick: () -> Unit,
  onPlayToggle: () -> Unit,
  onScrub: (Double) -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ),
  ) {
    Column(Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = route?.name ?: stringResource(R.string.route_none_title),
          style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onImportClick) {
          Text(
            stringResource(
              if (route == null) R.string.import_gpx else R.string.import_gpx_another,
            ),
          )
        }
      }

      if (errorMessage != null) {
        Text(
          text = errorMessage,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }

      if (route == null) {
        if (errorMessage == null) {
          Text(
            text = stringResource(R.string.route_none_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        return@Column
      }

      Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Statistic(stringResource(R.string.stats_distance), formatDistance(route.stats.distanceMeters))
        Statistic(stringResource(R.string.stats_ascent), formatElevation(route.stats.elevationGainMeters))
        Statistic(stringResource(R.string.stats_walking_time), formatDuration(route.walkingSeconds))
      }

      if (route.rig == null) return@Column

      val progress = hud?.progress ?: 0.0

      Slider(
        value = progress.toFloat(),
        onValueChange = { onScrub(it.toDouble()) },
        modifier = Modifier.fillMaxWidth(),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Button(onClick = onPlayToggle) {
          Text(
            stringResource(
              when {
                isPlaying -> R.string.flyover_pause
                finished -> R.string.flyover_replay
                else -> R.string.flyover_play
              },
            ),
          )
        }

        Column(horizontalAlignment = Alignment.End) {
          Text(
            text = stringResource(R.string.flyover_progress, (progress * 100).toInt()),
            style = MaterialTheme.typography.labelMedium,
          )
          Text(
            text = formatElevation(hud?.elevationMeters),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun Statistic(label: String, value: String) {
  Column {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = value, style = MaterialTheme.typography.titleMedium)
  }
}

private fun formatDistance(meters: Double): String =
  String.format(Locale.US, "%,.1f km", meters / 1000.0)

private fun formatElevation(meters: Double?): String =
  if (meters == null) "—" else String.format(Locale.US, "%,.0f m", meters)

private fun formatDuration(seconds: Double?): String {
  if (seconds == null || !seconds.isFinite()) return "—"
  val totalMinutes = (seconds / 60.0).toLong()
  val hours = totalMinutes / 60
  val minutes = totalMinutes % 60
  return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
