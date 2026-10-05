package com.thruhiker.ui.planner

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.thruhiker.R
import com.thruhiker.core.dem.TrackElevationFiller
import com.thruhiker.core.geo.ElevationProfile
import com.thruhiker.core.geo.Geodesic
import com.thruhiker.core.geo.LoopGenerator
import com.thruhiker.core.geo.RouteAnalysis
import com.thruhiker.core.geo.Splits
import com.thruhiker.core.geo.SplitsCalculator
import com.thruhiker.core.geo.TrackOperations
import com.thruhiker.core.gpx.GpxParser
import com.thruhiker.core.gpx.GpxWriter
import com.thruhiker.core.mapping.GeoJsonEncoder
import com.thruhiker.core.mapping.MapController
import com.thruhiker.core.mapping.TerrariumTileSource
import com.thruhiker.core.mapping.ThruHikerMap
import com.thruhiker.core.mapping.TrackFraming
import com.thruhiker.core.model.CameraOptions
import com.thruhiker.core.model.LatLng
import com.thruhiker.core.model.Track
import com.thruhiker.core.model.TrackPoint
import com.thruhiker.route.RouteHandoff
import com.thruhiker.route.RouteLibrary
import com.thruhiker.ui.components.DistanceUnit
import com.thruhiker.ui.components.ElevationProfileChart
import com.thruhiker.ui.components.GradientLegend
import com.thruhiker.ui.components.LocalDistanceUnits
import com.thruhiker.ui.components.SplitsList
import com.thruhiker.ui.components.StatChip
import com.thruhiker.ui.components.formatDifficulty
import com.thruhiker.ui.components.formatDistance
import com.thruhiker.ui.components.formatDuration
import com.thruhiker.ui.components.formatElevation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

/** Mont Blanc again: a valley, a summit and 4,800 m of relief inside one screen at this zoom. */
private val DEFAULT_PLAN_CAMERA = CameraOptions(
  target = LatLng(45.8326, 6.8652),
  zoom = 12.0,
  tilt = CameraOptions.CINEMATIC_TILT,
  bearing = 0.0,
)

/** Spacing the planner densifies tapped waypoints to, so the route can be measured and flown. */
private const val WAYPOINT_SPACING_METERS = 25.0

/**
 * Spacing at which a generated loop is converted back into waypoints.
 *
 * The loop is a real track, but the planner's model is waypoints, so the loop has to be
 * expressed in that model to stay editable. A hundred and twenty metres is fine enough that
 * re-drawing straight legs between the points does not visibly cut the loop's corners.
 */
private const val LOOP_WAYPOINT_SPACING_METERS = 120.0

/**
 * Spacing an imported route is thinned to.
 *
 * A recording can carry a sample a second for eight hours, and the planner does not need
 * that resolution to let someone move a corner. Forty metres keeps the shape and the whole
 * vertical profile within a metre or two, at a few thousand waypoints for a long day out.
 */
private const val IMPORT_WAYPOINT_SPACING_METERS = 40.0

/**
 * How long the planner waits before going looking for elevations.
 *
 * Every tap on the map restarts this wait, so a user sketching a route out of a dozen taps
 * does not start a dozen fetches; only the shape they stop on is looked up. Short enough to
 * be invisible when it does fire.
 */
private const val ELEVATION_DEBOUNCE_MILLIS = 400L

private const val MIN_LOOP_KM = 3.0
private const val MAX_LOOP_KM = 40.0
private const val DEFAULT_LOOP_KM = 10.0

/**
 * The planner: tap the map, get a route.
 *
 * This is the screen the app is named after a website for. Everything a route planning tool
 * has to do is here — build the line, measure it, colour it by gradient, split it, keep it or
 * export it — and everything is computed on the device, because there is no server and on a
 * long trail there is no signal.
 *
 * What it deliberately does not do is follow trails. Snapping a leg to the path network needs
 * a routing graph, which is a download and a service. Straight legs between taps are honest
 * about being straight, and the user is one drag from the shape they wanted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(
  modifier: Modifier = Modifier,
  onFlyRoute: () -> Unit = {},
) {
  val context = LocalContext.current
  val appContext = remember(context) { context.applicationContext }
  val scope = rememberCoroutineScope()
  val library = remember(appContext) { RouteLibrary(appContext) }
  val unit = LocalDistanceUnits.current.unit
  val selectUnit = LocalDistanceUnits.current.select

  // Strings are resolved during composition rather than read off a Context later: a message
  // built inside a coroutine would otherwise need a Context captured for the lifetime of the
  // screen, and would not follow a locale change.
  val labelExported = stringResource(R.string.planner_exported)
  val labelExportFailed = stringResource(R.string.planner_export_failed)
  val labelSaveFailed = stringResource(R.string.planner_save_failed)
  val labelLoopGenerated = stringResource(R.string.planner_loop_generated)
  val labelUnreadable = stringResource(R.string.route_error_unreadable)
  val labelNoTrack = stringResource(R.string.route_error_no_track)
  val defaultRouteName = stringResource(R.string.planner_default_name)
  val templateSaved = stringResource(R.string.planner_saved)
  // Counted strings have to be picked at runtime, so the resources object is what is kept.
  val resources = LocalResources.current

  var waypoints by remember { mutableStateOf<List<TrackPoint>>(emptyList()) }
  var plan by remember { mutableStateOf<Plan?>(null) }
  var addingPoints by remember { mutableStateOf(true) }
  var loopKilometers by remember { mutableDoubleStateOf(DEFAULT_LOOP_KM) }
  var routeName by remember { mutableStateOf("") }
  var message by remember { mutableStateOf<String?>(null) }
  var controller by remember { mutableStateOf<MapController?>(null) }
  var camera by remember { mutableStateOf(DEFAULT_PLAN_CAMERA) }
  var scrubFraction by remember { mutableStateOf<Float?>(null) }
  var showSplits by remember { mutableStateOf(false) }
  var expanded by remember { mutableStateOf(true) }
  var panelHeightPx by remember { mutableIntStateOf(0) }
  var samplingElevation by remember { mutableStateOf(false) }
  var startTimeMillis by remember { mutableStateOf<Long?>(null) }

  /**
   * Which elevation pass the user is still waiting for.
   *
   * A pass reads a tile at a time over the network, and its coroutine cannot be interrupted
   * out of a blocking request. So a pass is not stopped — it is made stale, by a newer pass
   * taking the number, which the older one notices before its next tile. Anything it fetched
   * before that is already in the shared cache, so the work is not even lost.
   */
  val elevationPass = remember { AtomicInteger(0) }

  /**
   * A framing request, as a pair of counters.
   *
   * `fitRequested` is bumped whenever something asks for the route to be framed; `fitDone`
   * records the last request that was honoured. They are held in `remember` rather than being
   * a local of the effect so that a request made in the same frame as the waypoints change —
   * which is exactly what a generated loop or an import does — cannot be missed: the effect
   * keys on both counters, and compares them once the new plan actually exists.
   */
  var fitRequested by remember { mutableIntStateOf(0) }
  var fitDone by remember { mutableIntStateOf(0) }

  // Everything derived from the waypoints: the track, its statistics, its profile, its
  // splits and its geometry. A forty-kilometre route is thousands of geodesic solves and a
  // JSON document of similar size, so it all happens off the main thread and the screen
  // shows the previous result until it lands.
  LaunchedEffect(waypoints, fitRequested) {
    if (waypoints.size < 2) {
      plan = null
      samplingElevation = false
      return@LaunchedEffect
    }

    val shouldFit = fitRequested != fitDone
    val pass = elevationPass.incrementAndGet()

    val track = withContext(Dispatchers.Default) {
      TrackOperations.fromWaypoints(waypoints, WAYPOINT_SPACING_METERS)
    }

    // Drawn first without a profile, so the route appears the moment the map is tapped
    // rather than waiting on the network.
    val drawn = withContext(Dispatchers.Default) { planFor(track) }
    plan = drawn
    if (shouldFit) {
      fitDone = fitRequested
      framingFor(track)?.let { camera = it }
    }

    // A route from taps carries no heights, which leaves the gradient colours, the profile,
    // the climb and the difficulty all switched off. They are looked up from the same terrain
    // tiles the map is already drawing relief from. A route that brought its own elevation —
    // a recording, an imported GPX — is already finished and is left alone.
    if (track.points.all { it.hasElevation }) {
      samplingElevation = false
      return@LaunchedEffect
    }

    samplingElevation = true
    delay(ELEVATION_DEBOUNCE_MILLIS)

    val elevation = withContext(Dispatchers.IO) {
      val source = TerrariumTileSource(appContext, isActive = { elevationPass.get() == pass })
      TrackElevationFiller(source).fill(track)
    }

    samplingElevation = false
    if (elevation != null) {
      plan = withContext(Dispatchers.Default) { planFor(elevation.track) }
    }
  }

  // Splits are a view of the track at a chosen interval rather than part of the plan itself,
  // so changing the unit re-cuts them without rebuilding the route or looking up the terrain
  // again.
  val splits = remember(plan?.track, unit) {
    plan?.track?.let { SplitsCalculator.compute(it, unit.splitLengthMeters) } ?: Splits.Empty
  }

  val waypointsGeoJson = remember(waypoints) {
    GeoJsonEncoder.encodeWaypoints(waypoints.map { it.position })
  }

  // The panel covers the bottom of the map, so the map is told to frame what is left.
  val panelMarginPx = with(LocalDensity.current) { PANEL_MARGIN.roundToPx() }
  LaunchedEffect(controller, panelHeightPx, panelMarginPx) {
    if (panelHeightPx > 0) {
      controller?.setViewportPadding(bottom = panelHeightPx + panelMarginPx)
    }
  }

  val importer = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenMultipleDocuments(),
  ) { uris ->
    if (uris.isEmpty()) return@rememberLauncherForActivityResult

    scope.launch {
      val unreadable = labelUnreadable
      val noTrack = labelNoTrack

      val outcome = withContext(Dispatchers.IO) {
        runCatching {
          val documents = uris.map { uri ->
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
              GpxParser.parse(stream)
            } ?: error(unreadable)
          }
          require(documents.all { !it.track.isEmpty }) { noTrack }
          documents
        }
      }

      outcome.fold(
        onSuccess = { documents ->
          // Several files are joined into one route: a long trail is usually published as a
          // file per stage, and planning it means planning the whole thing. A recording's own
          // pauses cannot be expressed as waypoints, so a multi-segment import is joined too —
          // the right trade for a planner, whose output is a clean route rather than an archive
          // of the recording. The flyover keeps the gaps.
          val imported = thinSamples(
            TrackOperations.merge(documents.map { it.track }).points,
            IMPORT_WAYPOINT_SPACING_METERS,
          )
          waypoints = imported
          documents.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) }
            ?.let { routeName = it }
          addingPoints = false
          message = if (documents.size > 1) {
            resources.getQuantityString(R.plurals.planner_merged, documents.size, documents.size) +
              " — " +
              resources.getQuantityString(R.plurals.planner_imported, imported.size, imported.size)
          } else {
            resources.getQuantityString(R.plurals.planner_imported, imported.size, imported.size)
          }
          fitRequested++
        },
        onFailure = { failure -> message = failure.message ?: unreadable },
      )
    }
  }

  val exporter = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/gpx+xml"),
  ) { uri ->
    val track = plan?.track
    if (uri == null || track == null) return@rememberLauncherForActivityResult

    scope.launch {
      val saved = withContext(Dispatchers.IO) {
        runCatching {
          appContext.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(GpxWriter.write(track, routeName.ifBlank { null }).toByteArray())
          } ?: error("no output stream")
        }.isSuccess
      }
      message = if (saved) labelExported else labelExportFailed
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    ThruHikerMap(
      camera = camera,
      trackGeoJson = plan?.trackGeoJson ?: GeoJsonEncoder.EMPTY_COLLECTION,
      gradientGeoJson = plan?.gradientGeoJson ?: GeoJsonEncoder.EMPTY_COLLECTION,
      waypointsGeoJson = waypointsGeoJson,
      onMapClick = if (addingPoints) {
        { position -> waypoints = waypoints + TrackPoint(position) }
      } else {
        null
      },
      onMapReady = { controller = it },
      modifier = Modifier.fillMaxSize(),
    )

    Row(
      modifier = Modifier
        .align(Alignment.TopCenter)
        .padding(TOP_OVERLAY_MARGIN),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilterChip(
        selected = addingPoints,
        onClick = { addingPoints = !addingPoints },
        label = { Text(stringResource(R.string.planner_add_points)) },
        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
      )
      if (waypoints.isNotEmpty()) {
        TextButton(onClick = { plan?.track?.let { framingFor(it)?.let { framed -> camera = framed } } }) {
          Text(stringResource(R.string.planner_fit))
        }
      }
    }

    PlannerPanel(
      plan = plan,
      samplingElevation = samplingElevation,
      waypointCount = waypoints.size,
      addingPoints = addingPoints,
      loopKilometers = loopKilometers,
      routeName = routeName,
      message = message,
      expanded = expanded,
      scrubFraction = scrubFraction,
      onToggleExpanded = { expanded = !expanded },
      onLoopKilometers = { loopKilometers = it },
      onRouteName = { routeName = it },
      onUndo = { waypoints = waypoints.dropLast(1) },
      onClear = {
        waypoints = emptyList()
        message = null
      },
      onReverse = { waypoints = waypoints.reversed() },
      onGenerateLoop = {
        val start = waypoints.firstOrNull()?.position ?: controller?.camera()?.target ?: camera.target
        scope.launch {
          val loop = withContext(Dispatchers.Default) {
            LoopGenerator.generate(start, loopKilometers * 1000.0)
          }
          waypoints = thinSamples(loop.points, LOOP_WAYPOINT_SPACING_METERS)
          addingPoints = false
          message = labelLoopGenerated
          fitRequested++
        }
      },
      onImport = { importer.launch(GPX_MIME_TYPES) },
      onScrub = { scrubFraction = it },
      onScrubFinished = { scrubFraction = null },
      onShowSplits = { showSplits = true },
      onSave = {
        val track = plan?.track ?: return@PlannerPanel
        scope.launch {
          val name = routeName.ifBlank { defaultRouteName }
          val outcome = runCatching {
            withContext(Dispatchers.IO) { library.save(track, name) }
          }
          message = outcome.fold(
            onSuccess = { templateSaved.format(it.name) },
            onFailure = { labelSaveFailed },
          )
        }
      },
      onExport = {
        exporter.launch(EXPORT_FILE_NAME)
      },
      onFly = {
        val track = plan?.track ?: return@PlannerPanel
        RouteHandoff.offer(track, routeName.ifBlank { null })
        onFlyRoute()
      },
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(PANEL_MARGIN)
        .onSizeChanged { panelHeightPx = it.height },
    )
  }

  if (showSplits) {
    ModalBottomSheet(onDismissRequest = { showSplits = false }) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.planner_splits_title),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        UnitToggle(unit = unit, onSelect = selectUnit)
      }

      Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // A start time turns the split table from "how long each leg takes" into "when will
        // I be there", which is the question people actually stand at a trailhead asking.
        TextButton(
          onClick = {
            val start = startTimeMillis ?: System.currentTimeMillis()
            val calendar = Calendar.getInstance().apply { timeInMillis = start }
            TimePickerDialog(
              context,
              { _, hour, minute -> startTimeMillis = nextOccurrenceOf(hour, minute) },
              calendar.get(Calendar.HOUR_OF_DAY),
              calendar.get(Calendar.MINUTE),
              DateFormat.is24HourFormat(context),
            ).show()
          },
        ) {
          Text(
            stringResource(
              if (startTimeMillis == null) {
                R.string.planner_add_start_time
              } else {
                R.string.planner_change_start_time
              },
            ),
          )
        }
        if (startTimeMillis != null) {
          TextButton(onClick = { startTimeMillis = null }) {
            Text(stringResource(R.string.planner_clear_start_time))
          }
        }
      }

      SplitsList(
        splits = splits,
        startTimeMillis = startTimeMillis,
        modifier = Modifier.heightIn(max = 420.dp),
      )
      Spacer(Modifier.height(24.dp))
    }
  }
}

/**
 * The next time the clock reads [hour]:[minute].
 *
 * Tomorrow's six o'clock rather than today's if today's has already gone, because a route is
 * planned to be walked, and a start time in the past makes the arrival column read backwards.
 */
private fun nextOccurrenceOf(hour: Int, minute: Int): Long {
  val calendar = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, hour)
    set(Calendar.MINUTE, minute)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    if (timeInMillis <= System.currentTimeMillis()) {
      add(Calendar.DAY_OF_YEAR, 1)
    }
  }
  return calendar.timeInMillis
}

/** Metric or imperial, in one control, wherever a distance is being read. */
@Composable
private fun UnitToggle(unit: DistanceUnit, onSelect: (DistanceUnit) -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    DistanceUnit.entries.forEach { candidate ->
      FilterChip(
        selected = candidate == unit,
        onClick = { onSelect(candidate) },
        label = { Text(candidate.distanceLabel) },
      )
    }
  }
}

/** Everything the planner derives from its waypoints, computed together. */
private data class Plan(
  val track: Track,
  val trackGeoJson: String,
  val profile: ElevationProfile,
  val analysis: RouteAnalysis,
  val gradientGeoJson: String,
)

/**
 * Derives everything downstream of a track, in one place.
 *
 * It is a function rather than a block inside the effect because a route is built twice: once
 * the moment it is drawn, and again once the terrain has answered.
 */
private fun planFor(track: Track): Plan {
  val profile = ElevationProfile.of(track)
  return Plan(
    track = track,
    trackGeoJson = GeoJsonEncoder.encode(track),
    profile = profile,
    analysis = RouteAnalysis.of(track),
    // Without a vertical profile every leg would come out grey. Supplying nothing instead
    // lets the plain route line show through, which is the honest picture while the heights
    // are still being read: the shape is known, the steepness is not.
    gradientGeoJson = if (profile.hasElevation) {
      GeoJsonEncoder.encodeGradient(track)
    } else {
      GeoJsonEncoder.EMPTY_COLLECTION
    },
  )
}

@Composable
private fun PlannerPanel(
  plan: Plan?,
  samplingElevation: Boolean,
  waypointCount: Int,
  addingPoints: Boolean,
  loopKilometers: Double,
  routeName: String,
  message: String?,
  expanded: Boolean,
  scrubFraction: Float?,
  onToggleExpanded: () -> Unit,
  onLoopKilometers: (Double) -> Unit,
  onRouteName: (String) -> Unit,
  onUndo: () -> Unit,
  onClear: () -> Unit,
  onReverse: () -> Unit,
  onGenerateLoop: () -> Unit,
  onScrub: (Float) -> Unit,
  onScrubFinished: () -> Unit,
  onShowSplits: () -> Unit,
  onImport: () -> Unit,
  onSave: () -> Unit,
  onExport: () -> Unit,
  onFly: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val unit = LocalDistanceUnits.current.unit

  Card(
    // The cap has to be on the card, not on the scrolling column inside it. On the column it
    // becomes the *scroll viewport*, and the card — bottom-aligned in a Box — sizes to its own
    // content instead, which can be shorter. The difference is scroll range that exists but
    // cannot be reached: the last row of actions sat below the card's edge with no way to
    // scroll to it.
    modifier = modifier.fillMaxWidth().heightIn(max = PANEL_MAX_HEIGHT),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface.copy(alpha = PANEL_ALPHA),
    ),
  ) {
    Column(
      modifier = Modifier
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = plan?.analysis?.let { formatDistance(it.stats.distanceMeters, unit) }
            ?: stringResource(R.string.planner_title),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleExpanded) {
          Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            contentDescription = stringResource(
              if (expanded) R.string.flyover_hide_details else R.string.flyover_show_details,
            ),
          )
        }
      }

      if (!expanded) return@Column

      if (plan == null) {
        Text(
          text = stringResource(
            if (addingPoints) R.string.planner_empty_hint else R.string.planner_empty_hint_off,
          ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onImport) {
          Text(stringResource(R.string.planner_import))
        }
        Spacer(Modifier.height(4.dp))
        LoopControls(
          loopKilometers = loopKilometers,
          onLoopKilometers = onLoopKilometers,
          onGenerateLoop = onGenerateLoop,
        )
        return@Column
      }

      val analysis = plan.analysis

      // While the terrain is still being read, the climb and the difficulty are not zero —
      // they are unknown, and saying "0 m" and "Easy" about a route up a mountain because the
      // network has not answered yet would be a lie the user has no way to spot.
      val pending = samplingElevation && !plan.profile.hasElevation
      val unknown = stringResource(R.string.planner_unknown)
      Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        StatChip(
          stringResource(R.string.stats_distance),
          formatDistance(analysis.stats.distanceMeters, unit),
        )
        StatChip(
          stringResource(R.string.stats_ascent),
          if (pending) unknown else formatElevation(analysis.stats.elevationGainMeters, unit),
        )
        StatChip(
          stringResource(R.string.stats_difficulty),
          if (pending) unknown else formatDifficulty(analysis.difficulty),
        )
        StatChip(stringResource(R.string.stats_walking_time), formatDuration(analysis.walkingSeconds))
      }

      Spacer(Modifier.height(10.dp))

      ElevationProfileChart(
        profile = plan.profile,
        scrubFraction = scrubFraction,
        onScrub = onScrub,
        onScrubFinished = onScrubFinished,
        modifier = Modifier
          .fillMaxWidth()
          .height(108.dp),
      )

      Spacer(Modifier.height(6.dp))
      when {
        plan.profile.hasElevation -> GradientLegend(Modifier.fillMaxWidth())
        samplingElevation -> Text(
          text = stringResource(R.string.planner_sampling_elevation),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Text(
          text = stringResource(R.string.planner_no_elevation),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(Modifier.height(10.dp))
      HorizontalDivider()
      Spacer(Modifier.height(8.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onUndo, enabled = waypointCount > 0) {
          Text(stringResource(R.string.planner_undo))
        }
        OutlinedButton(onClick = onReverse, enabled = waypointCount > 1) {
          Text(stringResource(R.string.planner_reverse))
        }
        OutlinedButton(onClick = onClear, enabled = waypointCount > 0) {
          Text(stringResource(R.string.planner_clear))
        }
      }

      Spacer(Modifier.height(8.dp))
      LoopControls(
        loopKilometers = loopKilometers,
        onLoopKilometers = onLoopKilometers,
        onGenerateLoop = onGenerateLoop,
      )

      Spacer(Modifier.height(8.dp))
      OutlinedTextField(
        value = routeName,
        onValueChange = onRouteName,
        label = { Text(stringResource(R.string.planner_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )

      Spacer(Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        TextButton(onClick = onImport, modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.planner_import_short))
        }
        TextButton(onClick = onExport, modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.planner_export_short))
        }
        TextButton(onClick = onFly, modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.planner_fly))
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        TextButton(onClick = onSave, modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.planner_save))
        }
        TextButton(onClick = onShowSplits, modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.planner_splits))
        }
      }

      if (message != null) {
        Text(
          text = message,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@Composable
private fun LoopControls(
  loopKilometers: Double,
  onLoopKilometers: (Double) -> Unit,
  onGenerateLoop: () -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = stringResource(R.string.planner_loop_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = formatDistance(loopKilometers * 1000.0, LocalDistanceUnits.current.unit),
        style = MaterialTheme.typography.labelMedium,
      )
    }
    Slider(
      value = loopKilometers.toFloat(),
      onValueChange = { onLoopKilometers(it.toDouble()) },
      valueRange = MIN_LOOP_KM.toFloat()..MAX_LOOP_KM.toFloat(),
      modifier = Modifier.fillMaxWidth(),
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onGenerateLoop) {
        Text(stringResource(R.string.planner_generate_loop))
      }
      Text(
        text = stringResource(R.string.planner_loop_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

/**
 * Thins a track's samples down to waypoints, keeping each sample's elevation.
 *
 * The last position is kept so the loop still closes on itself after thinning.
 */
private fun thinSamples(samples: List<TrackPoint>, spacingMeters: Double): List<TrackPoint> {
  if (samples.size < 2) return samples

  val result = ArrayList<TrackPoint>()
  result.add(samples.first())
  var accumulated = 0.0

  for (index in 1 until samples.size) {
    accumulated += Geodesic.distanceMeters(samples[index - 1].position, samples[index].position)
    if (index == samples.size - 1 || accumulated >= spacingMeters) {
      result.add(samples[index])
      accumulated = 0.0
    }
  }

  return result
}

private fun framingFor(track: Track): CameraOptions? =
  TrackFraming.cameraFor(track, tilt = CameraOptions.CINEMATIC_TILT)

private const val PANEL_ALPHA = 0.90f
private val PANEL_MARGIN = 12.dp
private val PANEL_MAX_HEIGHT = 300.dp
private val TOP_OVERLAY_MARGIN = 12.dp
private const val EXPORT_FILE_NAME = "thruhiker-route.gpx"

/** GPX has no MIME type every file provider agrees on, so the picker opens to everything. */
private val GPX_MIME_TYPES = arrayOf("*/*")
