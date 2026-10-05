package com.thruhiker.ui.routes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thruhiker.R
import com.thruhiker.core.geo.TrackOperations
import com.thruhiker.route.RouteHandoff
import com.thruhiker.route.RouteLibrary
import com.thruhiker.route.SavedRoute
import com.thruhiker.ui.components.LocalDistanceUnits
import com.thruhiker.ui.components.StatChip
import com.thruhiker.ui.components.formatDate
import com.thruhiker.ui.components.formatDifficulty
import com.thruhiker.ui.components.formatDistance
import com.thruhiker.ui.components.formatDuration
import com.thruhiker.ui.components.formatElevation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The route library: everything the user has kept.
 *
 * Each entry opens in the flyover, where the 3D flight already lives; the rest of the actions
 * are the ones a planner needs after the fact — reverse it, export it, or get rid of it.
 */
@Composable
fun RoutesScreen(
  modifier: Modifier = Modifier,
  onOpenInFlyover: () -> Unit = {},
) {
  val context = LocalContext.current
  val appContext = remember(context) { context.applicationContext }
  val scope = rememberCoroutineScope()
  val library = remember(appContext) { RouteLibrary(appContext) }

  var routes by remember { mutableStateOf<List<SavedRoute>>(emptyList()) }
  var refreshToken by remember { mutableIntStateOf(0) }
  var message by remember { mutableStateOf<String?>(null) }
  var menuFor by remember { mutableStateOf<String?>(null) }
  var exportId by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(refreshToken) {
    routes = withContext(Dispatchers.IO) { library.list() }
  }

  val exporter = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/gpx+xml"),
  ) { uri ->
    val id = exportId
    if (uri == null || id == null) return@rememberLauncherForActivityResult

    scope.launch {
      val saved = withContext(Dispatchers.IO) {
        runCatching {
          val gpx = library.gpx(id) ?: error("missing")
          appContext.contentResolver.openOutputStream(uri)?.use { it.write(gpx.toByteArray()) }
            ?: error("no output stream")
        }.isSuccess
      }
      message = appContext.getString(
        if (saved) R.string.routes_exported else R.string.routes_export_failed,
      )
      exportId = null
    }
  }

  fun openInFlyover(route: SavedRoute) {
    scope.launch {
      val track = withContext(Dispatchers.IO) { library.load(route.id) }
      if (track == null) {
        message = appContext.getString(R.string.routes_missing)
      } else {
        RouteHandoff.offer(track, route.name)
        onOpenInFlyover()
      }
    }
  }

  fun reverse(route: SavedRoute) {
    scope.launch {
      val reversed = withContext(Dispatchers.IO) {
        runCatching {
          val track = library.load(route.id) ?: error("missing")
          library.save(TrackOperations.reverse(track), "${route.name} reversed")
        }
      }
      message = reversed.fold(
        onSuccess = { appContext.getString(R.string.routes_reversed, it.name) },
        onFailure = { appContext.getString(R.string.routes_reverse_failed) },
      )
      refreshToken++
    }
  }

  fun delete(route: SavedRoute) {
    scope.launch {
      withContext(Dispatchers.IO) { library.delete(route.id) }
      message = appContext.getString(R.string.routes_deleted, route.name)
      refreshToken++
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    if (routes.isEmpty()) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = stringResource(R.string.routes_empty_title),
          style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
          text = stringResource(R.string.routes_empty_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      return@Box
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (message != null) {
        item {
          Text(
            text = message!!,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
          )
        }
      }

      items(routes, key = { it.id }) { route ->
        RouteRow(
          route = route,
          menuOpen = menuFor == route.id,
          onOpen = { openInFlyover(route) },
          onMenuToggle = { menuFor = if (menuFor == route.id) null else route.id },
          onExport = {
            exportId = route.id
            menuFor = null
            exporter.launch("${route.name.take(40)}.gpx")
          },
          onReverse = {
            menuFor = null
            reverse(route)
          },
          onDelete = {
            menuFor = null
            delete(route)
          },
        )
      }
    }
  }
}

@Composable
private fun RouteRow(
  route: SavedRoute,
  menuOpen: Boolean,
  onOpen: () -> Unit,
  onMenuToggle: () -> Unit,
  onExport: () -> Unit,
  onReverse: () -> Unit,
  onDelete: () -> Unit,
) {
  val unit = LocalDistanceUnits.current.unit
  Card(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpen)
        .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(
          text = route.name,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = formatDate(route.createdAtMillis),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          StatChip(stringResource(R.string.stats_distance), formatDistance(route.distanceMeters, unit))
          StatChip(stringResource(R.string.stats_ascent), formatElevation(route.elevationGainMeters, unit))
          StatChip(stringResource(R.string.stats_difficulty), formatDifficulty(route.difficulty))
          StatChip(stringResource(R.string.stats_walking_time), formatDuration(route.walkingSeconds))
        }
      }

      Box {
        IconButton(onClick = onMenuToggle) {
          Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.routes_menu))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuToggle) {
          DropdownMenuItem(
            text = { Text(stringResource(R.string.routes_export)) },
            onClick = onExport,
            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
          )
          DropdownMenuItem(
            text = { Text(stringResource(R.string.routes_reverse)) },
            onClick = onReverse,
            leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
          )
          DropdownMenuItem(
            text = { Text(stringResource(R.string.routes_delete)) },
            onClick = onDelete,
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
          )
        }
      }
    }
  }
}
