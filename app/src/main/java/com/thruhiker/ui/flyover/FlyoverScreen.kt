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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.thruhiker.R
import com.thruhiker.core.flyover.FlyoverRig
import com.thruhiker.core.geo.ToblerEstimator
import com.thruhiker.core.geo.TrackStats
import com.thruhiker.core.geo.TrackStatsCalculator
import com.thruhiker.core.gpx.GpxParser
import com.thruhiker.core.gpx.GpxWaypoint
import com.thruhiker.core.mapping.GeoJsonEncoder
import com.thruhiker.core.mapping.MapController
import com.thruhiker.core.mapping.ThruHikerMap
import com.thruhiker.core.mapping.TrackFraming
import com.thruhiker.core.model.CameraOptions
import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.route.RouteHandoff
import com.thruhiker.ui.components.LocalDistanceUnits
import com.thruhiker.ui.components.formatDistance
import com.thruhiker.ui.components.formatDuration
import com.thruhiker.ui.components.formatElevation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/**
 * Where pressing play begins the flight.
 *
 * A flight that is over restarts from the top rather than sitting at the end. Anything
 * else resumes where it stopped. The caller applies the frame at this offset before
 * starting, which is what keeps the drawn route in step with the flight: the map still
 * holds the whole-route preview from the import, and the intro deliberately reveals
 * nothing, so without the reset the preview would linger and then snap away.
 */
internal fun playbackStartMillis(finished: Boolean, flightMillis: Long, durationMillis: Long): Long =
  if (finished || flightMillis >= durationMillis) 0L else flightMillis

/** A route the user imported, with everything derived from it computed once, off the main thread. */
private data class ImportedRoute(
  val name: String?,
  val track: Track,
  val geoJson: String,
  val waypointsGeoJson: String,
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
  var compact by remember { mutableStateOf(false) }
  var panelHeightPx by remember { mutableStateOf(0) }

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
      // The route is set before the camera moves, so that the frame the camera move
      // renders is the one with the new geometry already in it.
      controller?.updateTrack(GeoJsonEncoder.encode(active.track, frame.revealedFraction))
      controller?.moveCamera(frame.camera)
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

          analyse(document.name, document.track, document.waypoints)
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

  // A route handed over by the planner or the route library opens straight away, with no
  // file picker: the user already chose it by name.
  LaunchedEffect(Unit) {
    val handedOver = RouteHandoff.take() ?: return@LaunchedEffect
    val name = RouteHandoff.takeName()

    route = withContext(Dispatchers.Default) { analyse(name, handedOver) }
    errorMessage = null
    isPlaying = false
    finished = false
    flightMillis = 0L
    hud = null
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
        controller?.updateTrack(GeoJsonEncoder.encode(active.track, 1.0))
        controller?.moveCamera(last.camera)
        flightMillis = rig.durationMillis
        hud = FlightHud(1.0, last.elevationMeters, last.distanceAlongTrackMeters)
        finished = true
        isPlaying = false
        break
      }

      val frame = rig.frameAt(elapsed)

      if (abs(frame.revealedFraction - appliedReveal) >= revealStep) {
        appliedReveal = frame.revealedFraction
        controller?.updateTrack(GeoJsonEncoder.encode(active.track, frame.revealedFraction))
      }
      controller?.moveCamera(frame.camera)

      if (now - previousHudNanos >= HUD_INTERVAL_NANOS) {
        previousHudNanos = now
        flightMillis = elapsed
        hud = FlightHud(frame.progress, frame.elevationMeters, frame.distanceAlongTrackMeters)
      }
    }
  }

  // Playback takes the panel down to a strip. The flight is what there is to watch, and
  // nothing on the panel changes while it runs, so the statistics step aside for the view
  // and come back when the flight ends, which is when there is something to read again.
  // Collapsing by hand works at any time and holds until the next play or finish.
  LaunchedEffect(isPlaying, finished) {
    if (isPlaying) compact = true else if (finished) compact = false
  }

  // The panel covers the bottom of the map, so the camera is told to frame what is left.
  // Otherwise the middle of the screen is under the panel and the route sits low in the
  // part the user can actually see.
  val panelMarginPx = with(LocalDensity.current) { PANEL_MARGIN.roundToPx() }
  LaunchedEffect(controller, panelHeightPx, panelMarginPx) {
    if (panelHeightPx > 0) {
      controller?.setViewportPadding(bottom = panelHeightPx + panelMarginPx)
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    ThruHikerMap(
      camera = route?.camera ?: DEFAULT_CAMERA,
      trackGeoJson = route?.geoJson,
      waypointsGeoJson = route?.waypointsGeoJson ?: GeoJsonEncoder.EMPTY_COLLECTION,
      onMapReady = { controller = it },
      modifier = Modifier.fillMaxSize(),
    )

    RouteCard(
      route = route,
      hud = hud,
      isPlaying = isPlaying,
      finished = finished,
      errorMessage = errorMessage,
      compact = compact,
      onToggleCompact = { compact = !compact },
      onImportClick = { picker.launch(GPX_MIME_TYPES) },
      onPlayToggle = {
        val rig = route?.rig
        if (isPlaying) {
          isPlaying = false
        } else if (rig != null) {
          // Applying the frame first matters on the very first play: the map is still
          // showing the whole route from the import preview, and the intro holds the
          // reveal at zero, so without this the full route would sit there for the whole
          // intro and then snap away the moment travel begins.
          applyFrame(playbackStartMillis(finished, flightMillis, rig.durationMillis))
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
        .padding(PANEL_MARGIN)
        .onSizeChanged { panelHeightPx = it.height },
    )
  }
}

/**
 * How much basemap shows through the info panel, expanded and collapsed.
 *
 * The panel is a card over a map, so it reads as something laid on top of the view rather
 * than as a solid band cutting the view in half. It stops short of full transparency
 * because the readout has to stay legible over blown-out snow and over dark forest.
 */
private const val PANEL_ALPHA = 0.78f
private const val COMPACT_PANEL_ALPHA = 0.55f

/** Gap between the panel and the bottom of the screen, also reserved on the map. */
private val PANEL_MARGIN = 12.dp

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
  compact: Boolean,
  onToggleCompact: () -> Unit,
  onImportClick: () -> Unit,
  onPlayToggle: () -> Unit,
  onScrub: (Double) -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface.copy(
        alpha = if (compact) COMPACT_PANEL_ALPHA else PANEL_ALPHA,
      ),
    ),
  ) {
    val progress = hud?.progress ?: 0.0
    val rig = route?.rig
    val unit = LocalDistanceUnits.current.unit

    if (compact && rig != null) {
      FlightStrip(
        isPlaying = isPlaying,
        finished = finished,
        progress = progress,
        elevationMeters = hud?.elevationMeters,
        onPlayToggle = onPlayToggle,
        onScrub = onScrub,
        onToggleCompact = onToggleCompact,
      )
      return@Card
    }

    Column(Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = route?.name ?: stringResource(R.string.route_none_title),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onImportClick) {
          Text(
            stringResource(
              if (route == null) R.string.import_gpx else R.string.import_gpx_another,
            ),
          )
        }
        if (rig != null) {
          PanelToggle(compact = false, onClick = onToggleCompact)
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
        Statistic(stringResource(R.string.stats_distance), formatDistance(route.stats.distanceMeters, unit))
        Statistic(stringResource(R.string.stats_ascent), formatElevation(route.stats.elevationGainMeters, unit))
        Statistic(stringResource(R.string.stats_walking_time), formatDuration(route.walkingSeconds))
      }

      if (rig == null) return@Column

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
        PlayButton(isPlaying = isPlaying, finished = finished, onClick = onPlayToggle)

        Column(horizontalAlignment = Alignment.End) {
          Text(
            text = stringResource(R.string.flyover_progress, (progress * 100).toInt()),
            style = MaterialTheme.typography.labelMedium,
          )
          Text(
            text = formatElevation(hud?.elevationMeters, unit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

/**
 * The panel while the flight is running: playback controls on one line, nothing else.
 *
 * The statistics are fixed for a given route, so during a flight they are the least
 * useful thing on screen and the most expensive in pixels: this keeps the panel to a
 * strip along the bottom edge and leaves the terrain to the flight.
 */
@Composable
private fun FlightStrip(
  isPlaying: Boolean,
  finished: Boolean,
  progress: Double,
  elevationMeters: Double?,
  onPlayToggle: () -> Unit,
  onScrub: (Double) -> Unit,
  onToggleCompact: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PlayButton(isPlaying = isPlaying, finished = finished, onClick = onPlayToggle)

    Slider(
      value = progress.toFloat(),
      onValueChange = { onScrub(it.toDouble()) },
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 8.dp),
    )

    Text(
      text = stringResource(
        R.string.flyover_reading,
        (progress * 100).toInt(),
        formatElevation(elevationMeters, LocalDistanceUnits.current.unit),
      ),
      style = MaterialTheme.typography.labelMedium,
    )

    PanelToggle(compact = true, onClick = onToggleCompact)
  }
}

@Composable
private fun PlayButton(isPlaying: Boolean, finished: Boolean, onClick: () -> Unit) {
  Button(onClick = onClick) {
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
}

@Composable
private fun PanelToggle(compact: Boolean, onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    Icon(
      imageVector = if (compact) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
      contentDescription = stringResource(
        if (compact) R.string.flyover_show_details else R.string.flyover_hide_details,
      ),
    )
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

/**
 * Everything the flyover derives from a track, computed once.
 *
 * Falling back to [DEFAULT_CAMERA] matters for a route handed over from the planner: a short
 * loop can have a degenerate bounding box, and an unframeable route should still open
 * somewhere sensible rather than not at all.
 *
 * A file's waypoints ride along as markers. They are the water sources, huts and summits a
 * trail file is dotted with, and drawing them is most of what makes an imported route worth
 * looking at before you fly it.
 */
private fun analyse(
  name: String?,
  track: Track,
  waypoints: List<GpxWaypoint> = emptyList(),
): ImportedRoute = ImportedRoute(
  name = name,
  track = track,
  geoJson = GeoJsonEncoder.encode(track),
  waypointsGeoJson = GeoJsonEncoder.encodeWaypoints(
    waypoints.map { GeoJsonEncoder.WaypointMarker(it.point.position, it.name) },
  ),
  stats = TrackStatsCalculator.compute(track),
  walkingSeconds = ToblerEstimator.hikingSeconds(track),
  camera = TrackFraming.cameraFor(track) ?: DEFAULT_CAMERA,
  rig = FlyoverRig.of(track),
)

