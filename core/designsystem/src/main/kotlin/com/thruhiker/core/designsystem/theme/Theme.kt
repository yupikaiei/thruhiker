package com.thruhiker.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
  primary = PineGreen,
  onPrimary = androidx.compose.ui.graphics.Color.White,
  primaryContainer = PineContainer,
  onPrimaryContainer = OnPineContainer,
  secondary = Granite,
  onSecondary = androidx.compose.ui.graphics.Color.White,
  secondaryContainer = GraniteContainer,
  onSecondaryContainer = OnPineContainer,
  tertiary = Sunrise,
  onTertiary = androidx.compose.ui.graphics.Color.White,
  tertiaryContainer = SunriseContainer,
  onTertiaryContainer = OnPineContainer,
  error = BlazeRed,
  surface = MistLight,
  surfaceVariant = MistLightVariant,
  background = MistLight,
  outline = OutlineLight,
)

private val DarkColors = darkColorScheme(
  primary = PineGreenDark,
  onPrimary = PineContainerDark,
  primaryContainer = PineContainerDark,
  onPrimaryContainer = OnPineContainerDark,
  secondary = GraniteDark,
  secondaryContainer = GraniteContainerDark,
  tertiary = SunriseDark,
  tertiaryContainer = SunriseContainerDark,
  error = BlazeRedDark,
  surface = MistDark,
  surfaceVariant = MistDarkVariant,
  background = MistDark,
  outline = OutlineDark,
)

/**
 * The app-wide Material 3 theme.
 *
 * Dark mode is not cosmetic here: a thru-hiker checks mileage at 4am in a shelter
 * and does not want a white screen. Dark colours are honoured from the system
 * setting.
 */
@Composable
fun ThruHikerTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }

    darkTheme -> DarkColors
    else -> LightColors
  }

  MaterialTheme(colorScheme = colorScheme, content = content)
}
