package com.thruhiker.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.thruhiker.R
import com.thruhiker.navigation.FlyoverRoute
import com.thruhiker.navigation.PlanRoute
import com.thruhiker.navigation.RecordRoute
import com.thruhiker.navigation.RoutesRoute
import com.thruhiker.preferences.UnitPreference
import com.thruhiker.ui.components.DistanceUnits
import com.thruhiker.ui.components.LocalDistanceUnits
import com.thruhiker.ui.components.PlaceholderContent
import com.thruhiker.ui.flyover.FlyoverScreen
import com.thruhiker.ui.planner.PlannerScreen
import com.thruhiker.ui.routes.RoutesScreen

/**
 * The four things the app can be doing.
 *
 * The planner leads because planning is what the app is for: the flyover is a way of checking
 * a route, not a reason to open the app.
 */
private enum class TopLevelDestination(
  val route: NavKey,
  @param:StringRes val labelRes: Int,
  val icon: ImageVector,
) {
  Plan(PlanRoute, R.string.destination_plan, Icons.Filled.Place),
  Routes(RoutesRoute, R.string.destination_routes, Icons.AutoMirrored.Filled.List),
  Flyover(FlyoverRoute, R.string.destination_flyover, Icons.Filled.PlayArrow),
  Record(RecordRoute, R.string.destination_record, Icons.Filled.Add),
}

/**
 * Application shell: a single back stack driving a bottom navigation bar.
 *
 * Selecting a top-level destination resets the stack rather than growing it, so
 * the back gesture never walks a trail of tabs.
 */
@Composable
fun ThruHikerApp() {
  val backStack = rememberNavBackStack(PlanRoute)
  val currentRoute = backStack.lastOrNull()

  val goTo: (NavKey) -> Unit = { destination ->
    backStack.clear()
    backStack.add(destination)
  }

  // Units are chosen once and remembered, because they are not a per-screen choice: someone
  // who plans in miles reads the flyover in miles too.
  val context = LocalContext.current.applicationContext
  val preference = remember { UnitPreference(context) }
  var unit by remember { mutableStateOf(preference.load()) }

  val units = DistanceUnits(unit) { chosen ->
    unit = chosen
    preference.save(chosen)
  }

  CompositionLocalProvider(LocalDistanceUnits provides units) {
    Scaffold(
      bottomBar = {
        NavigationBar {
          TopLevelDestination.entries.forEach { destination ->
            val selected = currentRoute == destination.route
            NavigationBarItem(
              selected = selected,
              onClick = {
                if (!selected) {
                  goTo(destination.route)
                }
              },
              icon = { Icon(imageVector = destination.icon, contentDescription = null) },
              label = { Text(stringResource(destination.labelRes)) },
            )
          }
        }
      },
    ) { innerPadding ->
      NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
        entryProvider = entryProvider {
          entry<PlanRoute> {
            PlannerScreen(onFlyRoute = { goTo(FlyoverRoute) })
          }
          entry<RoutesRoute> {
            RoutesScreen(onOpenInFlyover = { goTo(FlyoverRoute) })
          }
          entry<FlyoverRoute> { FlyoverScreen() }
          entry<RecordRoute> {
            PlaceholderContent(
              title = stringResource(R.string.destination_record),
              description = stringResource(R.string.placeholder_record),
            )
          }
        },
      )
    }
  }
}
