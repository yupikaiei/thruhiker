# Sample files

## `chamonix-test-loop.gpx`

**Synthetic test data.** The terrain is real; the path is not. It traces a plausible
ascent from Chamonix towards Lac Blanc and back, with elevations and timestamps,
but nobody should try to walk it. Do not treat it as a route.

It is useful for one thing: exercising the GPX importer end to end. It has two
`<trkseg>` elements with a deliberate nine-hundred-metre gap between them, where a
recording was "paused". That gap is the case the importer has to get right:

- the distance between the segments must **not** be counted, or the loop reports
  about a kilometre more walking than happened;
- the elevation change across the gap must **not** be counted as ascent;
- the map must draw two lines, not one line that spans the gap.

Import it from the Flyover tab to check all three at once.
