package com.thruhiker.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.thruhiker.R
import com.thruhiker.navigation.FlyoverRoute
import com.thruhiker.navigation.JournalRoute
import com.thruhiker.navigation.PlanRoute
import com.thruhiker.navigation.RecordRoute
import com.thruhiker.ui.components.PlaceholderContent
import com.thruhiker.ui.flyover.FlyoverScreen

private enum class TopLevelDestination(
  val route: NavKey,
  @param:StringRes val labelRes: Int,
  val icon: ImageVector,
) {
  Flyover(FlyoverRoute, R.string.destination_flyover, Icons.Filled.Place),
  Plan(PlanRoute, R.string.destination_plan, Icons.Filled.DateRange),
  Record(RecordRoute, R.string.destination_record, Icons.Filled.Add),
  Journal(JournalRoute, R.string.destination_journal, Icons.Filled.Edit),
}

/**
 * Application shell: a single back stack driving a bottom navigation bar.
 *
 * Selecting a top-level destination resets the stack rather than growing it, so
 * the back gesture never walks a trail of tabs.
 */
@Composable
fun ThruHikerApp() {
  val backStack = rememberNavBackStack(FlyoverRoute)
  val currentRoute = backStack.lastOrNull()

  Scaffold(
    bottomBar = {
      NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
          val selected = currentRoute == destination.route
          NavigationBarItem(
            selected = selected,
            onClick = {
              if (!selected) {
                backStack.clear()
                backStack.add(destination.route)
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
        entry<FlyoverRoute> { FlyoverScreen() }
        entry<PlanRoute> {
          PlaceholderContent(
            title = stringResource(R.string.destination_plan),
            description = stringResource(R.string.placeholder_plan),
          )
        }
        entry<RecordRoute> {
          PlaceholderContent(
            title = stringResource(R.string.destination_record),
            description = stringResource(R.string.placeholder_record),
          )
        }
        entry<JournalRoute> {
          PlaceholderContent(
            title = stringResource(R.string.destination_journal),
            description = stringResource(R.string.placeholder_journal),
          )
        }
      },
    )
  }
}
