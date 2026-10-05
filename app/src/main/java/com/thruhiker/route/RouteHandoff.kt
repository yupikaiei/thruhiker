package com.thruhiker.route

import com.thruhiker.core.model.Track

/**
 * Hands a route from one screen to another.
 *
 * The alternative — passing a whole track through a navigation argument — means serialising
 * it, and a planned route is tens of thousands of coordinates. The alternative — saving it
 * first — would fill the library with routes the user only wanted to look at.
 *
 * This is deliberately a plain holding cell rather than shared app state. The producer writes
 * it immediately before navigating and the consumer clears it on arrival, so it is empty
 * except across a single navigation.
 */
object RouteHandoff {

  /** A route waiting to be opened, or null when there is none. */
  var pending: Track? = null

  /** A name to give the handed-off route, for the screens that show one. */
  var pendingName: String? = null

  fun offer(track: Track, name: String?) {
    pending = track
    pendingName = name
  }

  /** Takes the waiting route and clears the cell, so it is consumed exactly once. */
  fun take(): Track? {
    val track = pending
    pending = null
    return track
  }

  fun takeName(): String? {
    val name = pendingName
    pendingName = null
    return name
  }
}
