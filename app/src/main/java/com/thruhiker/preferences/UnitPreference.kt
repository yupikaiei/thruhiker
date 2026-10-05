package com.thruhiker.preferences

import android.content.Context
import androidx.core.content.edit
import com.thruhiker.ui.components.DistanceUnit

/**
 * Remembers which units the app is in.
 *
 * [android.content.SharedPreferences] rather than a file of the app's own: this is a single
 * value, read once at startup, and the platform already does it correctly. The route library
 * writes real files because those are documents the user owns; this is a setting.
 *
 * An unrecognised stored value falls back to metric rather than throwing — a downgrade, or a
 * hand-edited preference, should not stop the app opening.
 */
class UnitPreference(context: Context) {

  private val preferences =
    context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

  fun load(): DistanceUnit {
    val stored = preferences.getString(KEY_UNIT, null) ?: return DEFAULT
    return DistanceUnit.entries.firstOrNull { it.name == stored } ?: DEFAULT
  }

  fun save(unit: DistanceUnit) {
    preferences.edit { putString(KEY_UNIT, unit.name) }
  }

  companion object {
    private const val NAME = "thruhiker.preferences"
    private const val KEY_UNIT = "distanceUnit"

    /**
     * Metric, which is what the app's own data is in and what most of the world hikes in.
     * An app that guessed wrong and hid the toggle would be worse than one that guessed.
     */
    val DEFAULT = DistanceUnit.KILOMETERS
  }
}
