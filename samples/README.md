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
- the map must draw two lines, not one line that spans the gap;
- the flyover camera must **cross** the gap smoothly, at the same ground speed as the
  rest of the route, while the mileage readout stands still and the drawn route holds
  at the end of the first recording.

Import it from the Flyover tab to check all four at once. `SampleRouteFlyoverTest`
asserts the last two frame by frame.
