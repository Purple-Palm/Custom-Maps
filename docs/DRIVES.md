# Drives

What real drives showed, and what changes because of them. Newest first. Each entry lists what
the owner reported, what the debug logs show, the causes, the changes with their stage, what the
owner checks on the phone, and what the next drive tests. Stages are those of
[ROADMAP.md](ROADMAP.md), where the next build is stage 1.1; the camera design is in
[CAMERA.md](CAMERA.md).

Drive logs never go into this repository. These notes leave out addresses, street and place names,
coordinates and times of day. Places are described ("a big-box store"), positions are distances,
and times are durations from Start or End.

## 2026-11-03, two drives

Both drives: the owner's Samsung phone with Android 15, a stage 1 build.

- **First drive:** about 11 minutes of guidance to a big-box store, searched by its street address.
- **Second drive:** about 7 minutes of guidance to a post office, found by its name.

### Summary

| Finding | Cause | Change | Stage |
|---|---|---|---|
| The car and the map moved in steps, once a second | Android's animations were off; the SDK camera also moves in 1 s linear pieces | Camera styles with a new Smooth follow; the owner checks system animations | Built in stage 1.1 |
| The view switched abruptly near turns | The SDK's framing flips the tilt within 180 m of most turns and again at each step change | The tilt goes down and back up gradually | Built in stage 1.1 |
| Lanes too small before a turn | The following camera stops at zoom 16.35 | A Lane zoom setting: with Large the view goes close onto the junction | Built in stage 1.1 |
| The camera turned into a gentle curve well before the car (liked) | The SDK aims the camera at the far end of the road ahead | Kept | Built in stage 1.1, in every style |
| The first drive's routes all ended behind the store | The route went to the middle of the building, and the router took the nearest road | Routes end at Mapbox's stop point for the place, and the map shows the place as a pin | Built in stage 1.1 |
| No internet from 37 s into the first drive; 2337 map tile errors | The app had no network once home Wi-Fi faded; nothing was saved ahead | Map and road data saved ahead, an offline chip, network lines in the log | Built in stage 1.1 |
| The app restarted once before the first drive | Unknown: the log records no crash and no exit reason | Crash and exit-reason lines | Built in stage 1.1 |
| A reroute right after Start, on both drives | First drive: the car was not yet matched to a road. Second drive: the navigator began with an out-of-date position | None needed: a reroute inside a trip is free. Start refreshes or replans old routes inside the trip | Built in stage 1.1 |

### What the owner reported

After the first drive:

- The car and the camera move "frame by frame".
- The camera "switches views very abruptly".
- Before a turn the camera does not zoom in far enough to read the lanes.
- Liked: on a gently curving road the camera turned into the curve well before the car did. Keep it.
- The route led behind the store instead of to its front.
- Google Maps worked during the same drive.

### What the logs show

| | First drive | Second drive |
|---|---|---|
| Destination | A big-box store, searched by its street address | A post office, found by its name |
| Guidance | About 11 minutes; ended with End, parked in front of the store | About 7 minutes; ended with an arrival about 5 m from the route's end |
| Network | Lost 37 s after Start, at the edge of home Wi-Fi; no internet for the rest of the drive | Worked: no map errors, and a traffic refresh came through after about 5 minutes |
| Reroutes | 4 s after Start (online, free), and one near the end from the onboard router | 0.4 s after Start (online, free): the navigator began with an out-of-date position |
| Also | One app restart before the drive; routes 8 minutes old at Start | A short test start about 5 minutes before the drive, ended after about 10 seconds |

#### First drive: guidance and camera

- Route progress came once a second for the whole drive: median 1.000 s, worst 1.07 s. All 688
  updates were fresh.
- The camera stayed in following mode until about 22 s before End. It then went idle while the car
  was parked, most likely after a map touch: any camera move that is not the navigation camera's
  own, such as a pan or a pinch, switches it to idle (`NavigationBasicGesturesHandler`,
  ui-maps 3.34.1). Touches are not logged yet.
- The camera's three mode transitions finished 6 to 28 ms after they began. With Android's
  animations running they take at least 673 ms. Android ends every animation on its first frame
  when the animator duration scale is 0.
- The view switched 10 times in 11 minutes. The tilt dropped from 45° to flat within 180 m of a
  turn (seen at 160 to 179 m, at 27 to 47 mph) and came back at the next step. With animations off,
  each switch was a one-frame cut.
- The maneuver card changed height at six step changes, as its lanes row came and went, and each
  change reframed the view. These times are approximate: the log keeps only the first banner of
  each step.
- The early turn: rebuilt from the logged positions, the camera was already turned about 13° into
  the curve 12 to 24 s before the car began to turn. The range depends on how far ahead the SDK
  framed the road, which comes from junction data that is not logged, so this is an estimate.
- The log has no zoom, tilt, bearing or frame times. The freeway zoom-out (to about 13 to 14.5) and
  town frames sitting at the zoom cap are estimates, not measurements.

#### First drive: destination

- The search result's point lay in the middle of the store. How exact Search rated it (rooftop,
  parcel, interpolated) is not in the log.
- The Directions API moved it to the nearest drivable road: the service road along the back wall,
  71 m away. The aisle along the storefront was 75 m away, only 4 m farther.
- The preview, the online reroute and the onboard reroute all ended at that point, with "Your
  destination is on the left". The driver turned toward the front, parked there and pressed End.
- Whether Mapbox Search had a stop point in front of the store is not in the log: the app neither
  reads nor logs it.

#### First drive: network

- 37 s after Start, 100 to 134 m from the start of the drive at 18 to 25 mph, 53 map tile requests
  failed with `ERR_ADDRESS_UNREACHABLE` while home Wi-Fi faded.
- From about 560 m from the start on, every tile request failed with `ERR_INTERNET_DISCONNECTED`:
  2284 lines over about 9 minutes, through End, with no recovery. The same day's log, shared after
  the second drive, shows tile errors until about 12 minutes after End.
- A 109 s stretch without errors in between was not a reconnection. The car was still near home,
  where tiles were cached, and waited at a traffic light.
- In all, 2337 `map_error` lines: 52.5% of the log's bytes, up to 80 a second. By source: the
  basemap (`composite`) 1995, `3dbuildings` 171, `mapbox-3d-events` 171.
- So the map could show only what was cached. The street-level view was probably blank or blurry,
  without 3D buildings.
- Guidance kept working on data already on the phone. Map matching held (match probability 1.0
  offline, after 0 in the first 3 s at home), with road names and speed limits from the
  navigator's own road data.
- In the store's parking lot the car left the route. 1.2 s later the onboard router returned a
  374 m route through the lot aisles, without traffic data, to the same point behind the store.
  Guidance followed it for 52 s until End.
- No traffic refresh came after the first reroute. The SDK refreshes every 5 minutes, which needs a
  network.
- Nothing on screen said the app was offline.

#### First drive: restart and Start

- The app restarted once, about 10 minutes before Start: 38 s after precise location was allowed
  and before the drive's routes were planned. Nothing was logged in those 38 s.
- The routes were planned on home Wi-Fi 8 min 12 s before Start. At Start the car had moved 22.5 m
  and was 4.6 m from the route's start.
- 3.9 s after Start the navigator declared the car off route: it had not yet placed the car on a
  road (match probability 0, no road, 0.76 m/s). The online reroute came 0.19 s later, inside the
  trip and free, on the same streets.
- That reroute brought fresh traffic: the arrival estimate fell from 770 s to 696 s, and the
  moderate congestion was gone.

#### Second drive

- The arrival worked. The route ended about 11 m from the post office's point, and the arrival came
  about 5 m from the route's end.
- The network worked: no map errors during guidance, and a traffic refresh came through after about
  5 minutes, the SDK's default interval.
- Animations were still off: the camera's mode transitions around this drive also finished within
  5 to 21 ms.
- The route was recomputed 0.4 s after Start, online and inside the trip, and the new route was the
  same. The navigator's first position after Start was out of date: the last fix of the short test
  start, about 5 minutes old and about 950 m from the car. The next fix, a fraction of a second
  later, was at the route's start.
- The short test start still began a trip session, so it counts in **Settings → Debug**. The free
  tier covers 1,000 trips a month.

### Causes

#### Motion in steps: Android's animations were off

- Only an animator duration scale of 0 fits the log. At 0.5x the 1800 ms overview move would still
  have taken about 900 ms.
- On the phone the scale is 0 when **Developer options → Animator duration scale** is off, or when
  **Accessibility → Remove animations** is on. Power saving is unlikely: Android 15's stock battery
  saver does not turn animations off by default, and nothing in the log shows a phone maker's
  change to that.
- So the drawn car (`NavigationLocationProvider`, ui-maps 3.34.1, normally a 1000 ms glide per
  update) and the camera (`NavigationCamera`, normally 1000 ms per update) jumped once a second.
  Every change of tilt, zoom or centring became a one-frame cut.
- Which setting it was is for the owner to confirm.

#### Steps even with animations on

- The navigator updates once a second. On each camera update `NavigationCamera` cancels its running
  moves and starts five new 1000 ms linear ones (centre, zoom, bearing, tilt and padding). On each
  navigator update the drawn car starts a new 1000 ms glide.
- A move ends exactly when the next update is due. When an update is a few ms late, the car and the
  camera stand still for a frame, and their speed and turning rate change abruptly every second.
- `PhoneMap` computes the camera frame twice per update: on the new location, then a few ms later
  on the new progress. The first set of moves is cancelled before it has moved.

#### Abrupt view switches near turns

The default framing of `MapboxNavigationViewportDataSource` (ui-maps 3.34.1) changes the whole
view at three points, while the camera stays in following mode:

- `pitchNearManeuvers`: within 180 m of a maneuver the tilt drops from 45° to flat. Continue,
  merge, ramps and forks are excluded.
- `maximizeViewableGeometryWhenPitchZero`: at the same moment the frame stops holding the car at
  the bottom and centres on the rest of the step plus 100 m after the turn. The car jumps up the
  screen.
- At the step change the tilt returns to 45°, the car returns to the bottom, the zoom is recomputed
  for the new step, and the map rotates toward the new road, by up to 45° from the heading. The
  rotation comes here, not at the 180 m flip.
- Secondary: the camera's top padding follows the maneuver card's live height. The card grows by
  56 dp when lanes show (a 12 dp spacer and a 44 dp lane row), so lanes coming and going reframed
  the view.

#### No zoom into the lanes

- The following camera stops at zoom 16.35, the default `maxZoom`. Town frames probably already sit
  at that cap, so there is no zoom left to add at a turn. The flat frame near a turn would need
  about 16.6 to 17.6 and is clamped too.
- At zoom 16.35 a 3.7 m lane is about 5 dp wide on screen.
- The map itself drew no lane markings (HD roads were off; see the watch list in ROADMAP.md), so
  the lanes came only from the maneuver card. Since stage 1.1 a switch in Settings turns lane
  markings on where Mapbox has them.

#### The early turn the owner liked

- `bearingSmoothing` (`maxBearingAngleDiff` 45°) points the camera from the car toward the far end
  of the framed road of the current step instead of along the car's heading, at most 45° away from
  the heading.
- The framed road reaches far ahead (`intersectionDensityCalculation`: 7 times the average spacing
  of junctions), so the camera starts rotating into a curve long before the car does.
- Both stay as they are. Every camera change near turns has to keep this bearing.

#### Routes ended behind the store

- The app routes to the search result's display point (`SearchResult.coordinate`, Search 2.34.1).
  For this street address it lay in the middle of the building.
- The Directions API moves such a point to the nearest drivable road. Here that was the service
  road behind the store.
- A Search Box result can also carry routable points (`SearchResult.routablePoints`): where a car
  should stop for the place, kept apart from the display point
  ([Search Box API](https://docs.mapbox.com/api/search/search-box/)). `MapboxSearchProvider` never
  reads them, and the route request sends no `waypoint_targets`. So the app had no way to stop in
  front.
- Reroutes are built from the original request, so every reroute, online or onboard, aimed at the
  same point. Fixing the request fixes the reroutes too.
- At the post office the display point worked: the route ended about 11 m from it. The display
  point goes wrong when the nearest road is not the one the place is reached from, as at the store.

#### No internet on the first drive

- The tile errors are Chromium network errors (Cronet). Cronet goes offline only when Android
  reports that the app's default network is lost. A network that Android blocks for the app still
  counts as online and fails with name-resolution errors instead. So 9 minutes of
  `ERR_INTERNET_DISCONNECTED` mean Android gave the app no network at all.
- Two explanations fit, and the log cannot tell them apart:
  - The phone had no mobile data at that moment: no SIM or data plan, Mobile data off, a data limit
    reached, or no coverage.
  - Samsung's **Allowed networks for apps** was set to Wi-Fi only for Custom Maps. It is the one
    Samsung setting that keeps an app off mobile data while it is on screen.
- Unlikely: Data saver, background data off and battery limits. They act on apps in the
  background, and Android reports them as blocked. The app was on screen until End.
- The second drive had a working network on the road, so the app is not kept off mobile data for
  good. Either the phone had no mobile data during the first drive, or a setting changed between
  the drives.
- Google Maps working proves little: it keeps guiding a route it already has without internet, and
  it may have run on another screen or phone.
- Nothing was saved ahead. The app never asks Mapbox to keep data for a drive: no tile store is set
  for the map, there is no predictive caching and no tile region. The routes sat on home Wi-Fi
  for 8 minutes before Start, and that time went unused. Predictive caching even refuses map data
  until `MapboxMapsOptions.tileStore` (Maps 11.34.1) is set.
- The log flooded because `PhoneMap` writes one line per failed tile, and offline every missing
  tile fails again whenever the camera needs it. The messages carry no tile id.
- The screen said nothing: the map reports only a refused token, and the connection is mentioned
  only after a route or search request has failed.

#### The restart before the first drive

- Nothing records a crash or how the previous process ended. There is no uncaught-exception
  handler, the app never reads Android's exit records (`ApplicationExitInfo`, Android 11 and
  newer), it logs no lifecycle events, and the log writer works in the background, so a dying
  process loses its last lines.
- So 38 s of silence looks the same for a Kotlin crash, a crash in Mapbox's native code, an ANR, a
  swipe from Recents, a kill after a permission or setting change, and a system kill. Android keeps
  a record of each; the app does not read it.

#### Reroutes right after Start

- **First drive:** the navigator declared the car off route before it had placed the car on a road.
  Until a car is matched to a road, it counts as off route and the SDK reroutes. This is an
  inference: the map matcher is closed source.
- **Second drive:** the navigator began with the out-of-date position left by the short test start
  and declared the car off route on it. Why it began with an old fix is not known. The fix age that
  the next build logs (see Diagnostics below) should show such a fix directly.
- Both early reroutes were online, inside the trip and free, and kept the same streets.
- On the first drive the age of the routes did not cause the reroute, but their traffic was 8
  minutes old. Without the early reroute, the old arrival estimate would have stayed until the
  first planned refresh, 5 minutes after Start.

#### What the log could not tell

- Frame rate and frame times, so whether night Standard with 3D buildings is too heavy for the
  phone. With animations off the map redrew mostly once a second, so drawing load does not explain
  the steps. Many 3D buildings were probably missing anyway, since their tile sources failed
  offline.
- The camera's zoom, tilt and bearing, its target, and what took it over: a touch, Recenter or
  Overview.
- The age of each GPS fix, and the path the drawn car glides along.
- Phone conditions: screen, charging, Power saving, heat, display rate. The phone's adaptive 120 Hz
  display drops to 60 Hz in Power saving, and a hot phone slows down, for example while charging in
  a mount.

#### Main-thread waste, not a cause

- The route line is fed on every frame of the drawn car's glide, but `MapboxRouteLineApi` (ui-maps
  3.34.1) accepts a point only every 62.5 ms. Once animations run, 45 to 105 calls a second fail,
  and each failure reads the style through a regex and logs an error.
- Each tile error also ran three regexes and built JSON on the main thread, a few ms a second at
  worst.
- Progress still came once a second, so neither delayed guidance.

### What changes

The next build is stage 1.1 in [ROADMAP.md](ROADMAP.md). It is being built in steps: the
diagnostics, the camera, the destination, the start and the offline data below are built, and so
are the Then chip the owner asked for ([DECISIONS.md](DECISIONS.md), 37) and the Lane zoom setting
with lane details on the map (38, 39). The trip history and the prediction of ROADMAP.md are
designed and not built yet. Stage 2 is the Android Auto stage.

#### Camera

The reasons and numbers behind each style are in [CAMERA.md](CAMERA.md).

| Change | For | Stage |
|---|---|---|
| Camera styles in Settings: **Mapbox standard** (the camera of the first drives, for comparison), **Mapbox tuned** (the same camera with calmer settings) and **Smooth follow** (new, the default) | Steps and abrupt switches | Built in stage 1.1 |
| Smooth follow moves the camera on every display frame and glides after the drawn car. Its own motion does not depend on Android's animation setting; the drawn car's glide does | Steps | Built in stage 1.1 |
| The tilt goes down onto the junction gradually before a turn and back up after it | Abrupt switches | Built in stage 1.1 |
| **Lane zoom** in Settings: **Default** (today's zoom, what a phone starts with) and **Large**. With Large the view goes close onto the junction before every turn, until a lane is about 4.4 mm wide, and on freeways before a point where lanes leave the route, also where the route goes straight on; it comes back out after each. Mapbox standard keeps its own zoom | Small lanes | Built in stage 1.1 |
| **Lane details on the map** in Settings, off unless turned on: lane markings, painted arrows and crosswalks where Mapbox has them. The car is shown on the road, not in a lane | Small lanes | Built in stage 1.1 |
| At a lane choice where the route goes straight on, the card's lane row shows that point's lanes with its distance | Small lanes | Built in stage 1.1 |
| The early turn into curves stays | The owner liked it | Kept in every style |
| During guidance the top padding comes from the maneuver card alone and only grows, so the lanes row no longer reframes the view. It also holds a constant band under the card for the Then chip, which therefore never moves the view. In Smooth follow padding changes glide anyway | Secondary switches | Built in stage 1.1 |
| The route line is updated only when the SDK accepts the point | Main-thread waste | Built in stage 1.1 |
| Before Start the camera follows the car, from the phone's own location, until the driver moves the map and again after My location | A long camera move at Start | Built in stage 1.1 |

None of the camera changes has been on a drive yet.

#### Destination

Built in stage 1.1 ([DECISIONS.md](DECISIONS.md), 28), except where a line says otherwise.

- **Stop point.** `Place` in `:core-nav` has a stop point: where a car should stop for the place,
  when Search gives one. The place's own point stays its pin.
- **Which point.** `MapboxSearchProvider` takes the first routable point of the result whose name
  is not `entrance`, in any letter case, and that lies within 1 km of the place. Mapbox names these
  points in free text (`POI`, `Address`, `default` and others), so the app keeps no list of good
  names. `entrance` marks a building door for people on foot, not where a car stops. A point
  farther than 1 km counts as bad data and is passed over. With no such point the place has no
  stop point.
- **Route request.** Routes go to the stop point, with the place as the arrival target
  (`RouteOptions.waypointTargetsList`, directions-models 7.10.1; `waypoint_targets` in the
  [Directions API](https://docs.mapbox.com/api/navigation/directions/)), so the arrival says which
  side the building is on. Results without a stop point work as before. There are no extra search
  requests: routable points come in the same response.
- **The car's direction.** A request from a position no older than 30 s of a car moving at 3 m/s
  or more also carries the direction it drives in, so the routes start that way and not with a
  U-turn. A car that stands sends none, as before.
- **Reroutes.** The navigator builds a reroute's request itself. It ends at the same point; whether
  it keeps the arrival target cannot be read from the SDK. The app writes every reroute request to
  the debug log (`reroute_options`) and changes none. Offline, only the side named at arrival could
  differ.
- **Preview.** The map shows the place as an amber dot next to the route's end, from the moment the
  place is picked until its card is closed, guidance ends or the arrival is dismissed. Without a
  stop point, when the route ends more than 50 m from the place, the route sheet says so, for
  example "Route ends 250 ft from the place". 50 m is a starting value.
- **Curb side (unchanged).** Arriving with the place always on the right stays off: it can add a
  loop around the block. The owner chose to keep it off (2026-11-10).
- **Shared links (stage 2).** A Google Maps, Apple Maps or Waze link, or a `geo:` address, gives a
  point, often the middle of a building, and no stop point. The link's point is looked up once in
  Search Box, and that result's stop point is used when it lies within about 150 m. The link's own
  point stays the place. This costs one extra Search Box request per link.
- **Still unknown:** whether this store gives a stop point in front, searched by address or by
  name, and whether reroutes keep the arrival target. The next drive shows both. None of this has
  been on a drive yet.

#### Start

Built in stage 1.1 ([DECISIONS.md](DECISIONS.md), 40). Start hands the previewed routes to the
navigator at once and never waits. It looks at how old the routes are and how far the car is from
where they were requested, and picks a plan that is carried out inside the trip:

| Plan (`start_plan`) | Situation at Start | What follows inside the trip | Cost |
|---|---|---|---|
| `AS_PREVIEWED` | Routes up to 5 minutes old, car within 100 m | Nothing | Nothing extra |
| `REFRESH_IN_TRIP` | Routes older than 5 minutes, or the car moved more than 100 m | One refresh of traffic, times and closures on the same roads (`start_refresh`). If it fails, or the routes cannot be refreshed, the replan below follows | Part of the trip |
| `REPLAN_IN_TRIP` | Routes older than 30 minutes | One route request from where the car is to the same place; its routes replace the old ones (`start_replan`) | Part of the trip |

- The owner chose to start at once (2026-11-10). The alternative was to wait up to 4 s for fresh
  routes before the trip.
- The first drive would have got the refresh: 8 minutes old, 22.5 m moved.
- **The car's position at Start** is the map's puck. Without one nothing is measured, and only
  the age counts. The location service's last answer is no second source: the app asks for it only
  for a route request, so at Start it still is the position the routes were requested from.
- **A car that moved gets a refresh, not new routes.** If it has left the route, the navigator
  reroutes it by itself inside the trip, as on both drives. From the moment the navigator finds the
  car off the route, or sets routes of its own, the plan does nothing more: a refresh that then
  fails is not followed by a replan, and a request under way is cancelled.
- **The replan** asks what the preview asked: the same stop point and arrival target, from the
  navigator's position on the road. Of the fresh routes, the one with the most main roads of the
  route the driver started on goes first; with none in common the order of the service stays. If
  the request fails, the old routes go on.
- **Nothing happens before the trip's first progress update,** and fresh routes are set from inside
  a progress update. Routes that reach the navigator while it has no progress, or that end more
  than 100 m from where the trip goes, would start a second billed trip, and an empty list would
  drop the trip into free drive. So an empty answer, routes worked out on the phone without
  network and a destination that moved are never set. One rule in `:core-nav` (`InTripRefresh`)
  decides each time, and the engine only does what it answers.
- **The plan begins with a live position.** The navigator can open a trip on the phone's last
  known position, as on the second drive. The plan waits for the first progress update whose
  position is no older than 30 s, so that a replan asks for routes from where the car is. A replan
  after a failed refresh is skipped when the position is older (`no_position`). The wait trusts
  the age the navigator gives its positions, `fixAgeMs` in the `progress` lines.
- **Guidance that ends** cancels a request under way, and an answer that comes later changes
  nothing, also not in the next drive.
- A simulated drive always starts with the routes as they are.
- **The refresh** uses `RouteRefreshController.requestImmediateRouteRefresh` (Navigation 3.34.1),
  an experimental API. Its four results:
  - Success: done.
  - Failure: followed by the replan.
  - Not started, when the navigator has no route it can refresh, such as one worked out on the
    phone: also followed by the replan.
  - Cancelled: the navigator drops a refresh on every change of its routes that is not itself a
    refresh. After a reroute or the end of guidance nothing follows. It also cancels when only the
    alternatives changed: the car passed the point where one of them leaves the route, or the
    navigator found new ones. The trip is then still on its old route, and the navigator's own
    refresh starts its 5 minutes over. So a refresh cancelled while the trip is on the route it
    started on is asked for once more, at the next progress update. A second cancel is left alone.
    How often the navigator changes its alternatives in the first seconds of a trip cannot be read
    from its source; the next drive shows it.
- **In the log:** `start_plan` (plan, age in seconds, meters moved, simulated); `start_refresh`
  and `start_replan` (the result, and the reason when nothing was set; a cancelled refresh says
  with `again` whether it is asked for once more). Every route request is a `route_request` line
  with `inTrip`.
- The trip count in **Settings → Debug** follows the navigator's sessions and should show one trip
  for one drive. A second billed trip from swapped routes would not show there, only on Mapbox's
  statistics page; that is why the rule above is tested and not left to care.
- 5 minutes, 100 m and 30 minutes are first settings, and so are the 30 s of a live position and
  the single repeat of a cancelled refresh. None of this has been on a drive yet.
- Prices: [Navigation SDK pricing](https://docs.mapbox.com/android/navigation/guides/pricing/) and
  [mapbox.com/pricing](https://www.mapbox.com/pricing).

#### Offline

Built in stage 1.1 ([DECISIONS.md](DECISIONS.md), 41). None of it has run on a phone yet.

- **One tile store.** The map and the navigator share one `TileStore`. It is set in
  `MapboxMapsOptions.tileStore` before the first map exists, and in the options the navigator is
  made from. Predictive caching needs it. It is the store at the default location, so nothing
  cached so far is lost, and the map uses it as before (`READ_ONLY`).
- **Predictive caching during guidance.** `PredictiveCacheController` (ui-maps 3.34.1) keeps data
  ahead of the car while any network is up
  ([offline guide](https://docs.mapbox.com/android/navigation/guides/advanced/offline/)). It has
  something to load only during guidance: the navigator knows where the car is, and which route
  it drives, only in a trip. Only the route in use counts, not its alternatives. Mapbox's defaults
  are 20 km around the car, 5 km along the route and 50 km around the destination; these are
  smaller:

  | Data | Around the car | Along the route | Around the destination |
  |---|---|---|---|
  | Road data: map matching, speed limits, offline reroutes | 10 km | 3 km | 10 km |
  | Standard map, zoom 15 to 16 | 2 km | 1 km | 2 km |
  | Standard map, zoom 11 to 14 | 10 km | 5 km | 10 km |

- **Street map along the routes.** When routes appear in the preview, the Standard map at zoom 11
  to 16 along the first 100 km of every previewed route is downloaded, outward from where the
  routes start. The first preview after the app starts also loads the Standard style pack, so the
  map can open offline.
  - It is one tile region that each new preview replaces, not the owner-chosen offline regions on
    the future list in ROADMAP.md.
  - The download goes on after Start until it is done: a preview is often open for a few seconds
    only, and a download ended at Start rarely saved anything. It ends early only when a newer
    preview takes the region over, or when the drive ends first, by End or at the destination.
    It runs next to predictive caching, on the tile store's own threads. A preview closed without
    Start lets its download finish too.
  - Map tiles only; road data is loaded during guidance, as part of the trip. Tiles the phone
    already has are taken as they are.
  - Only the first 100 km of each route: Mapbox refuses a region whole when it needs more than 750
    tile packs, so a long trip's whole length would save nothing at all. During guidance
    predictive caching keeps the rest ahead.
  - On the first drive this would have used the 8 minutes on home Wi-Fi.
  - Whether Mapbox resolves the Standard style for a tile region, its 3D buildings included,
    cannot be read from the SDK's sources. The first download on the phone shows it; see the next
    drive's checks.
- **Cost.** No extra Mapbox charges: Navigation SDK requests during guidance are part of the trip,
  and map tiles are covered by the Maps SDK's monthly user. The cost is mobile data: by estimate
  tens of MB for a new area, reused on later drives. Each preview download, the tile region and
  the style pack, writes into the log how many bytes it really loaded. Predictive caching reports
  no such count; the `device` line, once a minute during guidance, has all the bytes the app has
  received, an upper bound for what guidance loaded. Data outside tile regions is capped at 1 GB
  by default.
  The preview download also runs on mobile data when routes are planned on the road. Whether it
  should wait for Wi-Fi is an open question to the owner. For the tile region it is one constant
  in the code; the style pack has no network option in the SDK, so Wi-Fi only would need a second
  change for it.
- **Offline chip.** 3 s after the app loses internet a chip says so, and it goes at once when the
  internet is back. The delay hides a normal handover from Wi-Fi to mobile data. A reason that
  changes while the chip is up changes what it says at once.
  - **Before a drive** it is the whole sentence, under the search bar:
    - No network: "No internet. The map shows only areas saved on the phone; guidance continues."
    - A network without internet: "Connected, but no internet. The map shows only saved areas."
    - Android blocks the app: "Android is not letting Custom Maps use this network." A
      **Settings** button next to it opens the app's data usage page, or the app's details page
      on a phone that has no such page.
  - **During guidance** it is a short tab with an icon and the word "Offline", at the end side of
    the band under the maneuver card, where the Then chip hangs at the other side. It takes at
    most 30% of the card's width, the Then chip keeps its 70%, and it has no button: nothing asks
    for a tap while the car moves.
  - In both forms the chip is outside the measured panels, so neither the panels nor the camera
    move when it comes or goes. It is not shown over an open list of suggestions and not in the
    route preview, where it would lie over the far end of the routes. The sentence is also left
    out where it would reach the strip of the Mapbox logo, as in a short window, or with a very
    large font and the keyboard up.
  - The compass keeps clear of it. During guidance it now sits under the band, for the whole
    drive; before a drive it moves below the sentence while that is up.
  - Which sentence shows follows what Android reports for the app's network. "Blocked" is only
    what Android itself marks as blocked for the app, and only a block that has lasted since the
    screen was last on: Android keeps apps in the background off the network and lets them back a
    moment after they return. How Samsung's Wi-Fi only setting for one
    app is reported is not known yet; see the next drive's checks.
- **Car map (stage 2).** The car map uses the same saved map data as the phone.
- **Trip-log upload (stage 2).** The first drive ended offline, so an upload at End would have
  failed. The upload waits for any network, in a background job that survives the app being
  closed.

#### Diagnostics

Built in stage 1.1. New lines in the debug log:

| What | Recorded | For |
|---|---|---|
| Crash | A Kotlin or Java crash on any thread, written to the file at once, after the events still queued and before Android shows "keeps stopping": thread, exception, stack and causes, guidance state, and whether the app was on screen | The restart |
| Exit reasons | At every start, on Android 11 and newer, each earlier end of the app not logged yet: when, Android's reason (a swipe from Recents shows as `USER_REQUESTED`, "remove task"), status, importance, memory, the state summary the app left with the process (guidance state, on screen, since when), and for an ANR the main thread's stack | The restart |
| Start reason | On Android 15 and newer, one line for every start: why the app started (launcher, Recents, a service), cold, warm or hot, whether it had been force-stopped, and how Android's record of it was found | The restart |
| Device state | On screen or not; screen on, off, unlocked; charging; Power saving and Doze; heat; memory pressure. Once per start: battery limits, background restriction, standby bucket, Data saver, location power saving. During guidance, one line a minute: battery level and temperature, thermal status and headroom, Power saving, memory, and all the bytes the app has received | Phone conditions; what a drive costs in data |
| Connectivity | Every change of the app's network: Wi-Fi or mobile, internet reached or not, blocked or not, metered, roaming, suspended, and which networks the app can see. At start, on every return to the app and after each change, the settings that can cut off only this app: Data saver, the Mobile data switch, the SIM, background restriction, battery limits | The missing internet |
| Animations | Whether Android's animations run and the animator duration scale, when the first screen opens, when guidance starts and whenever the scale changes. Logged only; the app never changes the setting | Steps |
| Camera | Once a second during guidance while the map is on screen (the `activity` and `screen` lines show when it was not): zoom, tilt and bearing against the navigation camera's target, how far the camera trails the drawn car and the car trails the latest fix, how often the camera and the car moved and their longest pauses, the padding, the map's frame rate, render times and missed frames, and whether Android's animations run. Also how wide a lane is on the screen, what sets the zoom with the Large lane zoom, and whether lane details are on. A line on every camera mode change with how long the transition took, and when a touch takes over the camera or Recenter or Overview is tapped | Steps, switches, lanes, a heavy style |
| Lane choices | Once for each point of the route with lanes, when it comes as near as the close view would start: its lanes, how many can be used and how many leave the route, whether the road is a freeway, and the reason when the rule passes it over. Each route also lists, step by step, its intersections with lanes | How often Large goes close on a freeway, and how often a looser rule would |
| Map errors | The first error of each kind in full, then one line a minute with the counts and the map sources | The flood |
| Offline data | For each navigator, whether it and the map share the tile store, and how far data is kept ahead. For each download of a preview's routes and of the map's style: each route's length and how far along it the region reached, how it ended (done, partial, replaced by a newer preview, stopped when the drive ended first, or failed and why) and whether it ended during a drive, how many resources it needed and has, their bytes on the phone, the bytes that came over the network for it, and the seconds it took. Errors of predictive caching, the first of each kind and then counts. Each time the offline chip comes, goes or changes its sentence, with the reason and whether a drive was running, and which system page its Settings button opened | Nothing saved ahead; what a preview download costs in data |
| Search results | How exact the point is (rooftop, parcel, interpolated, street), the stop points with their distance from it and the one the app took, the result's types, categories and Mapbox id | Behind the store |
| Route requests | Every route request the app sends: where it starts, and how old, how fast and in which direction that position was; the stop point, the arrival target and the direction sent with it; whether it was sent inside a trip. And every reroute request of the navigator: how many points and arrival targets it has, whether the destination's target is set, and how far its end is from where the trip goes | Behind the store; what a drive costs |
| Start | The plan at Start, with the age of the routes and how far the car moved. Then the result of the refresh and of the replan, whether a cancelled refresh is asked for once more, and the reason whenever fresh routes were not set | Old routes at Start |
| Routes | How far the Directions API moved each requested point: about 71 m for the store. The lines that describe whole routes are put together off the main thread, so they are written a moment after the lines that follow them | Behind the store |
| Fix age | Every matched location carries the age of its fix in ms and the number of points the drawn car glides through. A simulated drive's positions say `replayed` instead of an age: the replayer stamps them on a clock of its own | GPS delay; old fixes, as at the second drive's Start |
| Trip start and end | When a drive ends, the navigator is told to forget where it left the car, and a line says so. The first updates of the next drive that still carry the old position are marked, and nothing on the screen acts on them | A later drive that began where the one before ended |
| Trip summary | One line at the end of each drive: how it ended, how long and how far, reroutes, times off the route, the longest wait for an update, map errors by kind, the time without internet, the bytes and battery it took, and whether it began on an old position. Numbers only. A drive cut short when Android closed the app gets its line at the next start | Drives compared at a glance |
| Route counts | Each step of a route: its traffic signals, stop and yield signs, railway crossings and road class; each route: its runs of heavy and of severe traffic and their lengths; the speed limit whenever it changes | What the roads of a drive were like |

- Folded, the first drive's 2337 map error lines become at most a few dozen, and the file about
  half as large.
- Every time in the log is UTC with three digits of the second.
- Days that are over are kept compressed on the phone, so the 50 MB limit holds many more days of
  guidance. **Share debug log** still writes one plain text file per day.
- Android keeps exit records across app updates. Installed over the current build, the next
  build's first start should log the record behind the first drive's restart, as long as it is
  still among the last 16 that Android keeps.
- Android writes its exit records to storage only about every 30 minutes, so a reboot soon after
  an exit can lose that record.
- A crash in native code or an ANR never reaches a Kotlin handler; the exit records cover those.
- Debug builds have **Settings → Debug → Crash now**, which closes the app with an error as a crash
  would. The log then holds a `crash` line, and the next start a `process_exit` with reason
  `CRASH` and the state summary.
- None of this has run on a phone yet.

### What the owner checks on the phone

Samsung's menu names vary a little between versions.

1. **System animations.** Both drives fit only animations switched off.
   - **Settings → Accessibility → Vision enhancements → Remove animations** (older menus:
     Visibility enhancements; some versions say Reduce animations): off.
   - **Settings → Developer options → Animator duration scale**: 1x.
   - A sign they were off: after Start the map jumped straight into the driving view instead of
     flying in over 1 to 3 seconds.
   - With them off the drawn car jumps once a second and both Mapbox camera styles cut, so the
     styles cannot be compared fairly. If Remove animations is on by choice, for example because
     motion on screen is uncomfortable, say so: a car marker that stays smooth anyway would override
     an accessibility choice, so it can only be a separate choice the owner confirms, not a default.
2. **Mobile data for the app.**
   - Does the phone have a SIM with a data plan, and was Mobile data on? 5G or LTE shows in the
     status bar.
   - **Settings → Connections → Data usage → Allowed networks for apps → Custom Maps**: Mobile data
     or Wi-Fi. Wi-Fi only keeps the app off mobile data even while it is on screen.
   - **Settings → Connections → Data usage → Mobile data usage**: did Custom Maps use any mobile
     data on the day of the first drive, and did Google Maps? 0 B for Custom Maps while Google Maps
     used data means the app was kept off mobile data.
   - Quick test before the next drive: turn Wi-Fi off, open Custom Maps and move the map to a town
     it has never shown. Does it load?
3. **Data saver.** **Settings → Connections → Data usage → Data saver**: if it is on, add Custom
   Maps under **Allowed to use data while Data saver is on**. Also check **Billing cycle and data
   warning** for a data limit: once reached, it turns mobile data off for the whole phone.
4. **Background data.** **Settings → Apps → Custom Maps → Mobile data → Allow background data
   usage**: on.
5. **Battery.** **Settings → Apps → Custom Maps → Battery**: Unrestricted. **Settings → Battery →
   Background usage limits** (older menus: Battery and device care → Battery): Custom Maps must not
   be under Sleeping apps or Deep sleeping apps; add it to **Never sleeping apps**. These limits act
   only in the background, but guidance runs there too.
6. **The restart.** 38 s after precise location was allowed, before the first drive, the app
   restarted. Did it close by itself ("Custom Maps keeps stopping"), was it swiped away from
   Recents or force-stopped, or did a setting change then (a permission, mobile data, battery)?
   With a computer at hand, this prints Android's own record (without `.debug` for a non-debug
   build):

   ```bash
   adb shell dumpsys activity exit-info io.github.purplepalm.custommaps.debug
   ```

7. **Also:**
   - Any VPN, ad blocker or firewall? **Settings → Connections → More connection settings → VPN**.
   - **Settings → Display → Motion smoothness**: Adaptive (120 Hz) or Standard (60 Hz)?
   - During the drive, was the phone charging in a mount, warm, or in Power saving?
   - Where did Google Maps run: on this phone, on the car display, or on another phone? Did it show
     live traffic?
   - About 22 s before End the camera stopped following. Was the map touched then?

#### The owner's answers

Answered on 2026-11-10; all answers are in [STATUS.md](STATUS.md). Those about the drives:

- **System animations:** Reduce animations was on. The owner turned it off, and guidance became
  smooth. No override in the app.
- **Lane size at turns:** a Lane zoom setting, Default (today's zoom) and Large (the junction fills
  the view and the lanes are clearly visible; also on freeways where lanes split off while the route
  goes straight on).
- **Freeways:** the long view ahead stays. Since the run-up to a freeway exit it stays except on the
  run-up ([DECISIONS.md](DECISIONS.md), 46).
- **Old routes at Start:** start at once and refresh inside the trip.
- **Arriving on the right side:** no.

Answered on 2026-11-11, after the drive with the missed freeway exit:

- **A spoken lane hint:** no. The lanes to use are words on the card; nothing is said aloud.
- **The close view's tilt before an exit:** yes to the stronger tilt, 55° with Smooth follow and
  Large, with at least 12 s of road ahead.

Asked on 2026-11-11, with the answers about the app's own lanes:

- **The lane the car is in:** the owner would like to see which lane he is in and when he changes
  lanes. The phone's positions alone cannot tell lanes, so for now this is research and logging
  only: on freeways the log has the phone's own motion next to `match.raw` (checks 44 and 45). The
  question is whether lane changes can be counted from a lane the car is known to be in, such as the
  one it merges into from an on-ramp, by the turn rate less the road's own turn and by the sideways
  pull, checked against how far the phone's positions lay to the side. Nothing on the screen shows or
  claims a lane, and the car is never drawn in one ([DECISIONS.md](DECISIONS.md), 39) until the logs
  show that this works.
- **A road without lane data that leaves before the exit, on its side:** yes to keeping the lit lane
  one lane in from the edge until that road is passed, since the road may take the edge lane with
  it, and moving it to the edge once after it. Where such a road comes less than about 12 s of
  driving before the exit, the lane reaches the edge before the road, and the card's words wait
  until the road is passed. The app's lane plan does this, with the edge as it is where the road
  leaves: a lane that becomes the edge lane only on the way, because the lane beyond it leaves at an
  earlier exit, is not lit before the road either. For now it is only written to the log
  (`lane_plan`, checks 46 to 48), and nothing on the screen changes.

### Checking a turn at home

The simulated drive and its replay slider show a turn again and again without driving it:

1. Turn on **Settings → Debug → Simulate drive**, plan a route through the turn and press **Start
   simulation**. A short route is easier: the slider spans the whole drive, so on a long one a
   finger's width covers a lot of road.
2. Drag the white dot of the slider over the guidance bar to a little before the turn and let go.
   The car is put there at once, the map flies to it within about 2 s, and the card shows the turn
   with its distance. Not there yet: drag again. A release within 2 s of the car's place in the
   drive moves nothing.
3. Watch the turn: the card, the Then chip, the lanes, the arrow on the route line, and with Large
   the view going down and close. Then drag back to before the turn and watch it again, in another
   camera style or lane zoom if wanted; Settings can be changed during the drive.
4. Take screenshots of what looks wrong and share the debug log afterwards. Each release is one
   `replay_seek` line, and the `progress` lines after it show where the car landed.

The simulated car drives up to about 67 mph on straight stretches and slows down for turns; the
slider counts the time of that drive.

### What the next drive tests

Before the drive:

- Install the next build over the current one, not after uninstalling, so the token stays and the
  first start can log the old exit record.
- Animations on and mobile data allowed, as checked above.

On the drive:

1. **The store, twice.** Search the big-box store by its street address, press Directions, and note
   where the route ends next to the store's pin, the amber dot, and whether the route sheet says
   "Route ends ... from the place". Then search it by its name and do the same. Drive one of them
   and note where the arrival is and which side it names. Note the pin too: its size, how it looks
   at night and over a 3D building, and whether it is gone after End.
2. **Camera styles.** Compare Mapbox standard, Mapbox tuned and Smooth follow on the same kinds of
   road: a gently curving road (the early turn must stay), ordinary turns, and a freeway entry and
   exit. Note smoothness, how the tilt changes before and after turns, and whether the freeway
   view zooms out too far. Change the style only while stopped.
3. **Offline.** None of it could be run on a phone, so some checks come before the drive.
   - **The first download.** At home on Wi-Fi, open the routes to a place and press Start; the
     download goes on during the drive until it is done. The log then has an `offline_region`
     line. `required` above zero, with some MB in `completedBytes`, means
     Mapbox resolved the Standard style for a tile region. That line is the only place where
     this shows. `failed` with `tileset_descriptor` means it did not resolve. `failed` with
     `tile_count_exceeded` means that 100 km of each route is too much for the tilesets of
     Standard; `required` and `completedBytes` of a load that worked tell how far the limit could
     go.
   - **What a drive costs.** Note the figure for Custom Maps under **Mobile data usage** (check 2
     above) before and after the drive. Predictive caching has no byte count in the log; the
     `device` lines' `appRxBytes` give the app's whole traffic over the drive, Wi-Fi included.
   - **A preview that replaces one, and Start during a download.** On Wi-Fi, open the routes to a
     place, go back within a few seconds and open them again. The first load's `offline_region`
     line should say `replaced`, never `failed` with `canceled`. Then press Start while the second
     load still runs: its line should come later, during the drive, with `inTrip` true; if the
     drive ends first, it says `stopped` with `trip_end`.
   - **The chip, before a drive.** With the map open and no drive, turn on airplane mode. Within
     about 3 s the sentence "No internet. The map shows only areas saved on the phone; guidance
     continues." should appear under the search bar. It should go as soon as the phone is online
     again.
   - **The tab, during guidance.** On a drive, or on a simulated one (which counts as a trip),
     turn on airplane mode after Start. A tab with "Offline" should appear under the maneuver
     card's right end within about 3 s, and neither the card nor the map should move for it. The
     map along the route should stay complete, streets included, and guidance should go on. The
     compass now sits lower, under the band: it should be clear of the tab and of the Then chip.
   - **The blocked case.** Parked, with Wi-Fi off, set **Allowed networks for apps** to Wi-Fi
     only for Custom Maps (check 2 above). The chip should say "Android is not letting Custom Maps
     use this network." with a **Settings** button that opens the app's data usage page. If it
     says "No internet." without a button, Android reported no network at all instead of a
     blocked one. The log's `network` line then shows `transport` `none` with a mobile network
     under `available`, and the rule that picks the sentence has to learn that case.
   - **On the road.** If the network drops, the tab should appear within about 3 s, and the map
     along the route should stay complete.
4. **Old routes.** Start must go at once every time, with no pause and no spinner.
   - Leave the routes open for more than 5 minutes, parked, then press Start: the drive begins on
     the routes as shown, and the arrival time may correct itself within a few seconds.
   - If there is time, leave routes open for more than 30 minutes, then press Start: the route
     line may change once, a few seconds in, and the drive goes on.
   - After each drive, **Settings → Debug** must show one more trip, not two. Once the month's
     drives are done, compare the count with Mapbox's statistics page.
   - Leave the route once on purpose, so the log has a reroute.
5. **The Then chip.** Before a turn that is closely followed by another, a chip should slide out
   from under the maneuver card, about 400 m or 30 s before the first turn, and show the second
   one. Note whether it comes too early or too late, whether a chain of close turns keeps it with
   only the arrow or the road that changes fading (the same arrow again should not blink), whether
   it shows early enough before a freeway exit with a turn at the ramp's end, and whether it ever
   flashes or moves the map.

6. **Lane zoom, Large.** In Settings, Guidance, set Lane zoom to Large (Default is what the build
   starts with) and drive ordinary turns, two close turns and a freeway with exits. Before a turn
   the view should first go flat and then close, until the junction fills the map, and ease back
   out after it. Note whether the lanes are large enough or the view too close, and whether the
   car and the junction both stay on screen. On the freeway the view should stay far ahead and go
   close only before an exit (once for its whole run-up, check 25) or about 25 s before a split
   where lanes leave the route. Note every exit where it went close and should not have, or did not
   and should have, and whether the road moves too fast when close. Compare one stretch in Default,
   and one in Mapbox tuned with Large. Changing Lane zoom while moving should glide, never cut.
7. **Lane details on the map.** Turn the switch on. Where Mapbox has them (major highways and some
   cities) lane lines and painted arrows should appear close in, without fading out and in when
   the map opens. Do the painted arrows match the card's? Is the route line ever lost on or under a
   bridge, and where does the car's icon sit there? The answers decide whether the switch can be
   on for everyone.
8. **Lanes in the card on a freeway.** Before a split or an exit with two exit lanes that the
   route passes going straight on, the card's lane row should show that point's lanes with its
   distance, and go back to the next maneuver's lanes after it. On the run-up to the route's own
   exit the card shows the run-up's one row instead (check 25).
9. **The arrow on the route line.** Screenshots of the map at turns come from a passenger or from a
   simulated drive, never from the driver. Before any turn the white arrow should lie on top of the
   amber line, along the last 30 m before the turn and the first 30 m after it, with its head at
   the end; before, only the head showed at the sides of the line. Also after the app was closed
   and opened again during a drive. The log: one `map_layers` line each time the map loads its
   style, with `arrowAboveLine` true.
10. **A U-turn on a divided road.** On a route with a U-turn through a median opening (a simulated
    drive will do), with Large: the arrow should be one white U over the opening, while the amber
    line still shows its two corners behind it. A U-turn on an undivided road keeps a hairpin
    arrow. The log: the `progress` lines before it with `next.modifier` `uturn`.
11. **Other routes behind the car.** Start with other routes shown in the preview. During the drive
    the grey of another route should begin only where it leaves the amber route: never along the
    road already driven, never under the amber line. The log: the route ids in `routes_updated`.
12. **Streets against the ground.** The streets should be clearly lighter than the ground, the
    buildings only a little, and the amber route still the strongest thing on the map. Close in at
    a divided road, the median should read as a median, not as a block. On a freeway, note whether
    another route's grey line is still easy to see on the freeway's band. A river, a lake or a
    park should still show darker than the ground. Screenshots, as above.
13. **A turn whose road bends later.** With Large and Smooth follow, at a left turn where the turn
    lane or a split begins well before the road visibly turns (a simulated drive through such a
    turn will do): from about 20 ft before the turn the bend and some road past it should stay on
    the map between the card and the bottom panel, with the view a little farther out if it needs
    the room; after the turn the map should turn only a little toward the road ahead while the
    view is still down. An ordinary turn should look as before. The log: on the `camera` lines
    before the turn, `bendM` and `bendDeg` (where the road bends past the maneuver point) and
    `extraPts` above 0, and after the turn, back on the open road, `extraPts` 0 again; in
    `route_response` and `routes_updated`, each step's `bendM`, `bendDeg`, `bearingBefore` and
    `bearingAfter`. Where the road only leaves the maneuver point a few degrees off the way in and
    runs on straight, as at many name changes, the view should stay as close as at an ordinary
    turn, with no `bendM` on the `camera` line. Screenshots, as above.
14. **The card after a reroute.** Leave the route once, where it is safe to. Right after the new
    route appears the card may dim for a moment and show no distance; it must never show a
    maneuver of the old route next to the new route's line. The log: `progress` lines with `card`
    `pending` right after a `routes_updated` line, then `fresh`.
15. **A second drive from elsewhere.** With **Settings → Debug → Simulate drive** on, start a
    simulated drive, drag its slider far along the route and End it there; then, within a minute,
    turn the switch off, plan a real drive from where the phone is and start it (search its
    destination once beforehand, so it is among the recent ones). A second simulated drive will not
    do: its replay makes the navigator forget its position by itself as it starts, so it cannot show
    whether the reset after End worked. Do the same once after a real drive: End it, drive on a
    kilometre or more without guidance, and start the next. The second drive's card and car must
    appear where the car is, never first where the last drive ended, and no new route should come in
    its first seconds. The log: a `trip_session_reset` line after each End; the second drive's first
    `progress` lines without `staleStart`, its `trip_summary` with `staleStart` false, and no
    `off_route` or reroute in its first seconds. `staleStart` true on a line means the navigator
    still had the old position, and the app left that update alone; after a simulated drive the
    `match` of such a line says `replayed` true. In the real drive only such lines may say
    `replayed`, and only a few at its start: a line with `replayed` but without `staleStart` means
    the old position reached the screen, and many lines with both mean the phone's own positions
    carry the replay's mark and the drive showed nothing.
16. **The street map after a quick Start.** On Wi-Fi, open the routes to a place you have not shown
    on the map before and press Start within a few seconds. The `offline_region` line comes during
    the drive, with `inTrip` true and `done`. End a drive before its download is done: `stopped`
    with `trip_end`. Note whether guidance felt slower in its first minute.
17. **The month's count.** **Settings → Debug**: after a new installation in the middle of a
    month the count says "since installation on" and the day; after an update installed over the
    app it is the month's count as before.
18. **Why the app started.** On Android 15 or newer, every start of the app writes one
    `process_start` line: open it from the launcher, from Recents, after **Crash now**, and after
    swiping it away. Each line has `reason` and `matched` (`pid`, `bindTime` or `complete`);
    `found` false means Android had no record of that start.
19. **A summary of each drive, and a longer log.** After each drive one `trip_summary` line, with
    `ending` `arrived`, `end_in_app` or `end_in_notification`, and the drive's numbers. Compare
    `meters` and `seconds` with the drive, `receivedBytes` with the phone's data usage, and
    `batteryUsedPercent` with the battery. A drive whose app Android closed gets its line at the
    next start, with `ending` `process_death`. Days later, **Settings → Debug** should hold many
    more days for the same size, and **Share debug log** should still give one plain text file
    per day that opens as before, with no `export_error` line in it.
20. **The camera after a flight, and after a jump.** With Smooth follow: after Start, Recenter and
    Overview, a `camera_settled` line follows the `camera_state` line within about two seconds;
    `settled` false with gaps means the camera was still turning or tilting. Where the car's
    position jumps, as to a road alongside, the drawn car should go on moving from its new place,
    never stand still for a second; the `camera` line's `puckGapMs` stays small then.
21. **The log's own fixes.** In a simulated drive the positions say `replayed` true and have no
    `fixAgeMs`; `routes_committed` names the `routeId`; the Idle `session_state` line right after
    a simulated drive says `simulated` true; every time has three digits after the second.
22. **The roads in numbers.** In `route_response` and `routes_updated`: each step's
    `trafficSignals`, `stopSigns`, `yieldSigns`, `railwayCrossings` (null when the service sent
    nothing for that step) and `roadClass`; each route's `congestionRuns`; and on the `progress`
    lines, `speedLimit` where the limit changes. Compare a few with the road.
23. **A build with a temporary key.** A CI build made without the shared debug key is named
    `...-tempkey.apk`, its run shows a warning, and **Settings → Debug** in that build says that
    the next CI build needs an uninstall, which deletes the token, the settings and the log. Share
    the log before installing the next build.
24. **The replay slider.** In a simulated drive, and never in a real one, a thin slider sits over
    the guidance bar, clear of the card and of the Mapbox logo; the amber part grows and the dot
    glides without steps. Drag forward past a turn, then back to before it: each time the car and
    the map are at the new place at once (the map flies there within about 2 s), the card shows a
    maneuver ahead of the new place and never one jumped over, the Then chip comes only for turns
    ahead, no new route appears, and the drive goes on by itself. Drag to the very end: the drive
    arrives within a few seconds. With TalkBack the slider reads "Simulated drive position" and a
    percent. The log: one `replay_seek` line per release, `ok` true with a small `ms`; `progress`
    lines with `replaySeek` only in the moment after a seek; no `off_route` and no `routes_updated`
    with `reroute` after a seek; the drive's `trip_summary` with `simulated` true and `meters` close
    to the road actually replayed. Seek once in Mapbox tuned and once in Mapbox standard as well:
    the view should be at the new place at once, never sliding across the map or framing the turn
    the car left. Then let go of the slider and press End at once: the car should be drawn where the
    phone is, never left on the simulated route.
25. **An exit with Large in Smooth follow.** On a freeway with exits, Lane zoom on Large. Before
    the route's exit the view should go close once, from a minute of driving (at least 1.5 km) plus
    25 s before it where a lane on the exit's side leaves the route on the way, else about 25 s
    before it, and stay close to the gore and about 2 s past it: never out and in again between the
    points on the way. Close in, the view tilts up to 55° and shows the road well ahead, about 12 s
    of it; over a junction it still goes straight down. The card shows one lane row, with no
    distance on it: the next point where a lane on the exit's side leaves, with one lane lit and
    words such as "3rd from right" or none, then the exit's own lanes with "Right lane", "2 right" or
    the like. The words never count down, a lane for car pools or buses is dim and never "the left
    lane", and the card does not change height when the words come and go. Before the run-up the
    card is as before. Note at each exit **whether you followed the route line or the card**: the
    lane the line lies in means nothing until the app draws the lanes itself, and the card's words
    are the instruction.
26. **Exits close together.** Where the route takes an exit right after an earlier one, the view
    should still go close once, a minute plus 25 s before the route's exit, with the earlier exit's
    lanes first ("2nd from right" and the like), and stay close past the earlier exit.
27. **A turn right after an exit.** Where the ramp ends in a turn within about 20 s of driving after
    the gore, the close view should stay until the junction view frames the turn, with no step out
    and back in before it. A turn farther on: the view goes out about 2 s after the gore, as before.
28. **Default.** With Lane zoom on Default the camera should be as before on the same exits: no
    tilt, no close view. The card's run-up row and its words come all the same.
29. **Mapbox tuned with Large.** One approach for each exit, as in check 25, with the SDK's tilt and
    lanes a third to a half narrower at freeway speed than in Smooth follow.
30. **The camera's frame rate and the data with the tilt.** With Smooth follow and Large, compare
    the camera line's `render` and `renderFps` on a run-up with the open road, and note the app's
    mobile data use for the drive (check 2): the tilt draws more of the map ahead.
31. **The new lines.** After the drive: `lane_row` lines at each change of the card's row, with
    `odd` where a run-up's row had no words (`composed`, `pieces`, `unknown_branch`, `no_fit`) and
    `designated_only` where any row lit only lanes for car pools or buses; a `missed_maneuver` line
    for an exit missed, if one was, and `diverge_taken` lines for exits and points on the way where
    the route was kept; and on every `progress` and `off_route` line `match.raw`.

The day and night looks, at home, parked ([DECISIONS.md](DECISIONS.md), 48):

32. **A start by day, and one after dark.** By day: the graphite splash, then the light screen, with
    no frame of the dark map and no night roads. The `look` line with `cause` `START` has `place` and
    `startMs`. Then a cold start after dark: graphite all the way, no light frame at any moment.
33. **Settings, Day and night, three times each way,** with no route (Settings opens only from the
    search bar's gear for now). The map changes in place, with no reload and no black screen; the
    pin and the Mapbox logo stay where they are, and no `map_style_loaded` line follows a `look`
    line. The `look` lines say `errors` 0 and, by day, `reset` `null`, and give `writeMs` and
    `transitionMs`; `look_settled` gives `idleMs`, how long the map took. Note any moment of a blank
    or patchy map. By day the logo's top edge is clear of every panel's shadow, and the car's dot has
    its dark edge.
34. **A route preview across a change by the sun.** A minute before the time Settings shows under
    Auto, open a route preview and wait for the next minute: the route keeps its amber with its
    bronze edge by day, and the grey routes keep their dark edge.
35. **Lane details on, by day,** on a road that has them: white markings on a dark asphalt, the amber
    route on top, and where the details end, the asphalt's end (the owner chose this asphalt over
    Mapbox's light road).
36. **The phone's dark theme on and off, and Power saving on, by day:** nothing changes in the app.
    At night with the phone's light theme, tap (i): the dialog is dark.
37. **The app in the background across sunset, then back,** from Recents and from the launcher icon,
    after a day of use: the night look at once, with no light frame at any moment (a `look` line with
    `RESUME`). Recents shows a graphite tile for the app (Android 13 and newer).

On the road:

38. **A simulated drive across a change by the sun.** Start a simulated drive about five minutes
    before the time shown under Auto and let it run across it. The change comes at a quiet moment:
    `heldS` and `heldBy` in the `look` line, or at once at a stop, and never within 300 m of a turn.
    The arrow stays above the route line after it. The map does not stutter: the `camera` line's
    `camMaxGapMs` and `render.p95Ms` in the seconds around the `look` line. Then once more, with the
    screen turned off a few minutes before the time and on again just before a turn after it: the
    screen is dark at once (`themeDeferred` true), and `look_theme` comes after the turn.
39. **A day drive.** Frame times against a night drive (`render.p95Ms`, `missedVsync`). The card's
    numbers and the arrow in sunshine; the lanes to take found at one glance, in sun; the car's dot on
    major roads and on white streets; the compass. The places' icons in their own colours (the
    owner's choice), and whether the amber pin is ever taken for one of them. After a reroute, the
    dimmed card for a moment: its road still readable on the white card.
40. **A night drive:** today's look, with the dark snackbar, the lanes not to take plainly visible,
    the lanes to take found at one glance, the switches' tracks visible in Settings; through a jam on
    the lighter night roads (heavy and severe stand out from the road) and on a freeway (the route
    outlined by its casing).
41. **A real drive across dusk with Large on a freeway,** when one comes: a change due during a
    lane-choice close-up waits for it (`heldBy` `CLOSE_VIEW`).

The guidance bar with a large font, at home:

42. **A simulated drive with the largest font size** in Android's settings: the arrival time and the
    time and distance left are whole, in a row of their own above **Overview** and **End**, and the
    bar keeps its height from the first numbers to the end of the drive, also across **Overview** and
    **Recenter**. With a smaller font size they stand beside the two buttons, the time left alone
    where both do not fit; at no size is a number cut off.

The lane data of each route and the phone's motion on freeways, read in the log after a drive.
Nothing on the screen changes for them:

43. **How much of each route has lane data.** One `lane_evidence` line right after
    `routes_committed`, and one after each `routes_updated` that brings a new route, such as a
    reroute; none after a refresh. From it: how many of the route's exits and turns had lane data
    (`lanePoints`, `streetTurns`), how far along the route that data holds (`known` and `bridged`
    against `none` in `freewayM`, `rampM` and `streetM`), how many gaps in it fall where the card
    names a lane (`gaps`: `inWindow`, `inWindowM`), and which kinds of designated lanes occur
    (`designated`). There should be no `error` line with `where` `lane_evidence`, and Start and a
    reroute should feel as before.
44. **The phone's motion and lane changes.** With the phone in its usual mount, its back to the road
    ahead: on a freeway every `progress` line has a `motion` object, with `ref` `back` and `tilt` about
    the mount's lean, and each time the drive comes onto a freeway a `motion_sensors` line says
    `started` true. Where it is safe and the traffic allows, change lanes a few times on a straight
    stretch, for example once or twice to the left after the merge from an on-ramp, and remember
    roughly where. After the drive, in the seconds of each change, `yaw` less the road's own turn (the
    change of `match.location.bearing` from line to line) should swing one way and back, `side` pull
    to that side and back, and `match.raw`'s `sideM` move by about a lane's width. Whether a change
    can be told from the road's own curves this way is what these lines are for.
45. **The motion with the screen off, at home.** In a simulated drive on a route with a freeway, turn
    the screen off for a minute while the car is on the freeway, then on again. The `motion` objects of
    that minute should keep `n` at about 25 and `ms` at about 1,000, with the phone at rest: `yaw`,
    `fwd` and `side` near 0, and `replayed` true in `match`. Far fewer samples would mean that Android
    held the sensors back while the screen was off.

The lane plan of each route, read in the log after a drive. The app works out, for every route,
which lanes work at each point and which one lane it would light where the card shows lanes; it
writes that down and compares it with the card, and nothing on the screen changes for it:

46. **Whether the plan agreed with the card.** One `lane_plan` line right after each `lane_evidence`
    line, and a `lane_agree` line each time the card begins to show a lane row at a point with lane
    data: a run-up's row, a lane choice's, and on a freeway the exit's or the fork's own. At each
    freeway exit with lane data every `lane_agree` line should say `agree` true, or `why` `words` with
    `opensAhead` true where an exit-only lane opens ahead of the car (once the card reads the plan, it
    keeps its words until that lane is there). A `why` of `lit` with `unknownAhead` true can be right
    too: before a road without lane data on the exit's side the plan keeps clear of the lane that will
    be at the edge where that road leaves, which the card may still light, without words. Any other
    `lit` or `amber` is a place where the card and the plan light different lanes: note the exit.
    There should be no `error` line with `where` `lane_plan` or `lane_agree`, and Start and a reroute
    should feel as before.
47. **How often a road without lane data dropped the hold.** A run-up's `unknownExits` in `lane_plan`
    counts the roads without lane data on the exit's side before it, and `unknownExitDropped` says
    where the plan lit the edge lane before such a road all the same, because it came less than about
    12 s of driving before the exit (the answer above).
48. **How much earlier the plan would begin a run-up.** A run-up's `seam` in `lane_plan`: `latchM`,
    where the card's run-up begins today, in metres from the gore (negative before it), and
    `earlierM`, how much earlier the plan would begin it, 6 s of driving before its lanes first narrow
    for a point within reach.
    And `ms`, how long the phone took to read the route and make its plan, against the design's
    budget of 50 ms.

The app's own route line, with **Settings → Debug → App's own route line** turned on before the
drive (a simulated drive will do for most of it; screenshots from a passenger or a simulated drive,
never from the driver). With the switch off everything is as before:

49. **The line looks like Mapbox's away from lanes.** Along streets and on freeways without lane
    data the amber line, its casing, the traffic colours, the grey of other routes beneath it and the
    white maneuver arrow on top should look exactly as with the switch off, by day and at night, and
    switch with the look. On a freeway exit with lane data the line may lie a lane or two to the side
    of the road's middle on the run-up, moving there in one gentle S and never back, and at the gore
    it should follow its lane onto the ramp, not cut across the gore. Before an exit whose lanes lie
    beyond a lane kept for car pools or buses (no lane lit on the card), the line stays on the road's
    middle to the gore and, onto a ramp with lane data, until the ramp's lanes have joined it, never
    beside the ramp; where the exit's lane is named only shortly before the gore, the line goes on
    straight from where it is at the gore onto the ramp, with no swing over the gore and no jump
    where the app's drawing ends. The car's icon stays where it
    always is. The log: one `route_line` line with `drawer` `sdk` and `reason` `start` after Start,
    and after it one with `drawer` `own` and `reason` `loaded`, with `swapMs` (how long Mapbox's line showed
    first), `loadMs` and `buildMs`.
50. **A reroute.** Leave the route once on purpose. Mapbox's line should show on the new route at
    once and the app's take over a moment later, at most a second or two; the old route's line
    should never come back, not even for a frame. The log: `route_change`, and `loaded` after it, with
    `swapMs`.
51. **The switch during a drive, parked or by a passenger.** Off: Mapbox's line at once, with a
    `route_line` line `switch`, and the next `map_layers` line without `ownLine`. On again: Mapbox's
    line until the app's has loaded (`start`, and `loaded` after it).
52. **A fallback when something failed.** A `route_line` line with `reason` `fallback` names why in
    `ownLineFallback`: `source` (the map could not load the line's data), `exception` (with an
    `error` line, `where` `ownRouteLine`) or `no_data`. Mapbox's line must have shown at once and
    stayed until the next route. Any such line is worth a note with where it happened.
53. **The frame cost.** The camera lines' `render` (`p95Ms`, `missedVsync`), `renderFps` and, with
    Smooth follow, `camMaxGapMs` against a drive with the switch off: no worse (CAMERA.md: `camMaxGapMs`
    under 34 ms in 99 % of the seconds, `renderFps` at least 55). The `routeLine` object: `trims` up
    to 16 a second while moving, `trimLagM` 0 on most seconds, and `trimMaxMs` under 0.5. After the
    drive, and in the next route preview, the line is Mapbox's again (`guidance_end`).

The app's own lanes, with **Settings → Debug → Draw the app's lanes** turned on before the drive (it
draws the app's own route line too; a simulated drive through a freeway exit with lane data will do
for most of it; screenshots from a passenger or a simulated drive, never from the driver). With the
switch off everything is as before:

- **The lanes under the line.** On a freeway with lane data, from a few kilometres before an exit
  and close in: grey asphalt a little wider than the lanes, white broken lane lines, solid edge
  lines (yellow on the left), dotted lines beside a lane that only exits, arrows in the lanes
  before lane points, and at the gore the road's other lanes going on for a short stub and
  tapering away. Nothing over the Mapbox logo or the (i), labels and buildings above it. By day and
  at night, switching with the look. With Default, or zoomed out, the drawing is small, under the
  line. The log: one `lane_drawing` line for each window, `reason` `route` for the first one and
  `ahead` for the next, `budget` true.
- **The lit lane.** In the run-up to the exit (from where the card lights a lane) the lane to take
  is amber, edged in the route's casing colour, and the lanes that still work beside it are tinted;
  the tints behind the car go piece by piece, and the amber ends at the car: behind it the lane is
  plain asphalt. With traffic the lit lane takes the traffic's colour.
- **The swap, with Large in Smooth follow.** When the close view has settled for the lanes, the
  route line over the lit lane goes at once and the amber lane is the route there; when the view
  lets go, the line is back at once. Never a fade, a half-transparent line or a blink while the view
  rests. At a peak speed of about 34 m/s (76 mph) and over, in Mapbox tuned and with Default the
  line stays, opaque, in the lit lane. The camera lines' `routeLine`: `swapped` true while the close
  view holds with lanes wide enough, `swaps` 1 in the second that changes, 0 else. It is the
  camera's state and shows only over a lit lane: a junction view on a street sets it too.
- **The arrow at the gore.** The white maneuver arrow of the exit runs along the lit lane from just
  before the gore onto the ramp, not across the lanes; at a split on the ramp the same. With the
  switch off it is Mapbox's arrow as before.
- **A reroute in the run-up, a look switch, the switch.** A reroute: Mapbox's line and no lit lane
  until the new route's path and lanes have loaded (`route_line` `loaded`, `lane_drawing` `route`),
  never the old route's lanes lit. A change of Day and night in Settings during the run-up: the
  lanes take the new look's colours at once, nothing else moves (a change by the sun waits for the
  close view to end, as before). The switch off during a drive: the lanes go at once and the next
  `map_layers` line has no `lanes`.
- **The cost.** As in the frame cost above, against a drive with the switch off: no worse.
  `lane_drawing`: `buildMs` and `loadMs` a few tens of ms at most, `bytes` under 100,000. Any
  `route_line` `fallback` is worth a note with where it happened.

Then share the debug log. What to read in it:

| Lines | Question |
|---|---|
| Exit reasons and start reason, at the first start | Why the app restarted before the first drive |
| Camera | Frame rate and worst gaps for each style; whether animations ran |
| Device state | Power saving, heat, charging, display rate |
| Connectivity | Which case cut the app off, see below |
| Search results and routes | Stop points by address and by name; how far each point was moved |
| `search_result`: `stopPoint` | Which routable point the app took for each search, its name and its distance from the place; null when it took none |
| `route_request`: `stopPoint`, `targets`, `bearings`, `originFix`, `inTrip` | Whether routes went to the stop point with the place as target; whether a direction was sent and from what speed; one request for each Directions press, and at most one with `inTrip` in a drive |
| `reroute_options` | Whether reroutes keep the arrival target: `targets` null with `targetWanted` true means a reroute lost it. `endMeters` near 0 means it still ends at the same point |
| `tile_store` | `shared` true: the map and the navigator have the one tile store. `map` false would mean that predictive caching keeps no map data |
| `offline_region_start`, `offline_region` | One pair for each preview. `routeKm` is each route's length and `keptKm` how far along it the region reaches, up to about `maxKm`. `required` above zero: Mapbox Standard resolved for a tile region. `tile_count_exceeded`: the region was too large and saved nothing. `loadedBytes` is what the download cost in data, `completedBytes` what the phone holds for it, `seconds` how long it took. `replaced` (a newer preview) and `stopped` with `trip_end` (the drive ended before the download) are no faults; `partial` and `failed` name their reason. A start line with no line after it is a download that never got an answer, as without a connection |
| `offline_style_pack` | The size of the map's style, loaded once after the app starts, and again with the next preview if that failed |
| `device`: `appRxBytes` | All the bytes the app has received, Wi-Fi and mobile data alike. Its growth from the first to the last `device` line of a drive is the most that predictive caching can have loaded; Mapbox gives no count of its own for it |
| `offline_cache`, `offline_cache_error` | That predictive caching was set up, once for each navigator, with its distances, and what it could not do |
| `offline_chip` | When the chip came and went, with its reason and the guidance state. `shown` true should follow a `network` line that reports the app offline by about 3 s, and `shown` false should come with the line that reports it online again. A block that Android reports while the app is not on screen is left out, so it brings no chip lines. `shown` is about the reason: in a window too short for the sentence it is true while nothing is drawn |
| `offline_settings` | The system page the chip's Settings button opened; null when the phone has neither the data usage page nor the app's details page |
| Map errors | A few lines a minute instead of thousands |
| Fix age | Whether GPS fixes arrive late, or old as at the second drive's Start |
| `start_plan` | The plan, the age of the routes in seconds and the meters moved; `movedMeters` and `position` are null when the map had shown no position |
| `start_refresh`, `start_replan` | After a plan other than `AS_PREVIEWED`, one line closes the plan: `start_refresh` for a refresh, `start_replan` for a replan. A refresh that failed or could not start (`failure`, `not_started`) has both, and a `cancelled` one with `again` true is followed by one more `start_refresh` line. Only `skipped` with `rerouted` or `guidance_ended` can come before the drive's first `progress` line, or in a drive that has none (End at once, routes rejected): nothing was asked of the navigator then. Any other result before it would be a fault. `start_replan` with `set` is followed by `routes_updated`; any other `start_replan` result names its reason |
| `start_refresh` with `cancelled`, next to `routes_updated` | Whether the navigator changes its alternatives in the first seconds of a trip and so cancels the start's refresh, and whether the refresh asked for again went through |
| `progress`: `fixAgeMs` in the first lines of a drive | Whether the trip opened on an old position. The plan then waits: a `start_refresh` or `start_replan` line with a result from the navigator comes only after a `progress` line with an age of 30,000 or less |
| `session_state` | One `ActiveGuidance` session id for the whole drive, also across a `start_replan` |
| Progress: `after`, `thenShown`, `stepMetersLeft`, `stepSecondsLeft` | Where the Then chip showed, and for which pairs of maneuvers, to tune its distances |
| Camera: `laneDp`, `zoomPlan`, `laneFocus`, `centerLeadS` | Whether Large reached about 28 dp at junctions and 14 dp before lane choices, and how much of the freeway stayed in the long view |
| `lane_choice`, with and without `skipped` | Which exits and splits the rule counted and which it passed over, with their lane counts, to tune its two thresholds |
| Camera: `runUp`, `laneChoice.key`, `laneCapZoom`, `capSpeed`, `aheadM` | Which run-up held the close view and from where to where; the closest zoom the floor of 12 s allowed and the speed it was worked out for; the road the camera showed above the car, to check the 12 s and the model behind them against a screenshot |
| `lane_choice`: `key`, `fromLeft`, `edgeLeaves` | Every lane of each point as the route has it, from the left: which lanes the route marked where, which side's lanes left at each point on the way to an exit, and so which row the card should have shown |
| `lane_row` | What the card's lane row drew and when it changed: `source`, the lanes (`row`, `lit`, `des`), `words`, `need`, the run-up (`runUp`, `start`) and, where a run-up's row had no words, why (`odd`), or that a row lit only lanes for car pools or buses (`odd` `designated_only`). One to four lines for each run-up; a distance alone writes none |
| `missed_maneuver`, `diverge_taken` | One line for each place the road divided: the step change past an exit, fork or on ramp, or a point passed on the way (`kind`); missed when the navigator found the car off the route within 30 s or 1,000 m. How sure the navigator was (`probAt`, `probMin`, `probMax`, `probLast`, `uncertainS`), how far the phone's positions lay to the side (`sideAt`, `sideMin`, `sideMax`) and what the card showed on the way (`card`, `words`, `runUp`): whether a rule could find a missed exit sooner than the navigator without false alarms |
| `progress`, `off_route`: `match.raw` | Where the phone's own position lay against the matched one: `sideM` to the side (right positive), `aheadM` along the way (about minus the speed, since the navigator puts the car a second ahead), `accM`, `ageMs`, `bearingDiff` |
| `route_response`, `routes_updated`: a step's `lanes` | The lanes of each step's last banner that has them, the one shown near its maneuver |
| Camera: `laneDetails`, `hdRoads`, `hdRoadsReapplied`; `lane_details` | Whether lane details stayed on once set |
| `map_layers` | `arrowAboveLine` true at every style load: the maneuver arrow is drawn above the route line. `middle` lists the order of the route's layers. With the own route line's switch on, `ownLine` true: its casing and line lie above Mapbox's route line and below the arrow. With **Draw the app's lanes** on, `lanes` true: the drawing's layers lie in their order below Mapbox's route line, and the lane arrows above the app's route line and below the maneuver arrow |
| Camera: `bendM`, `bendDeg`, `extraPts`; step fields `bendM`, `bendDeg`, `bearingBefore`, `bearingAfter` | Where the road bends past each maneuver point, and whether the junction frame held it: how often a step begins well before its bend, and at what angle, to check the rule's 20 degrees and 4 m |
| Progress: `card` | `pending` only in the first second or so after a new route, never for long; `kept` for a second now and then |
| `trip_session_reset` | One after each drive's end: the navigator forgot where the drive left the car. `ms` is how long that took |
| Progress: `staleStart` | An update at the start of a drive that still carried where the drive before ended; nothing on the screen and no start plan acted on it. None are expected after a `trip_session_reset`. After a simulated drive, the `match` of such a line says `replayed` true |
| `export_error` | Only in a shared file: a part of that day could not be read to its end (`error` says why), and the lines before it are what could be read of it. The other days are complete |
| `trip_summary` | One for each drive: `trip` (the session id of `session_state`), `simulated`, `ending`, `seconds`, `meters`, `reroutes`, `offRouteEpisodes`, `firstProgressS`, `longestGapS`, `mapErrors`, `offlineSeconds`, `staleStart`, `staleUpdates`, `receivedBytes`, `batteryUsedPercent`. With `ending` `process_death` the numbers are those of the last minute before Android closed the app |
| `process_start`: `matched`, `found` | How the start's record was found: `pid`, `bindTime` or `complete` (handed over at the first frame). `found` false with `records`: Android had none for this start |
| `camera_settled` | With Smooth follow, how long after a `camera_state` line with `ms` the camera came to rest (`ms`). `settled` false: not within 5 s, with the gaps left |
| `offline_region`, `offline_style_pack`: `inTrip`, `why` | `inTrip` true: the download ended during a drive. `stopped` with `trip_end`: the drive ended first |
| Step fields `trafficSignals`, `stopSigns`, `yieldSigns`, `railwayCrossings`, `roadClass`; route `congestionRuns`; progress `speedLimit` | What the roads were like: signs and signals per step (null: the service sent nothing), the class of each step's road, the runs of heavy and of severe traffic with their meters, and the speed limit (value, unit, sign) whenever it changed |
| `routes_committed`: `routeId`; `session_state`: `simulated` | The route a drive started on. In the Idle state, `simulated` is that of the drive that just ended; it is left out before the first drive of a run |
| `replay_seek` | One for each release of the replay slider in a simulated drive: where the car was, `fromS` of `durationS` seconds of the replay and `fromM` metres along the route; `toS`, where it was sent (for a release that moved nothing, where the finger let go); `ok`, and with it `ms`, the time from the release until the replay played from there; else `why`: `near` (within 2 s of the car), `end` (already in the last stretch), `reroute` (a new route was on its way), `no_replay`, `replaced` (a newer release went instead), `ended` (the drive ended, its route changed, or the replay started over on an update of the routes first; in the last case the car goes on from where the replay started) or `refused` (the navigator or the replayer would not) |
| Progress: `replaySeek` | An update between a seek of a simulated drive and the car's first position at its new place: logged, and left alone by everything on the screen. One or two per seek; more would mean the navigator was slow to find the new place |
| `look` | Each change between the day and the night look: `cause` (`START`, `RESUME`, `CHOICE`, `SUN`, `PLACE`), `choice`, `place` (where the sun was worked out for: `FIX`, `LAST_KNOWN`, `TIME_ZONE`), `heldS` and `heldBy` when it waited, `guidance`, `themeDeferred`; the map's `keys`, `errors`, `writeMs`, `reset` by day (`null` expected), `transitionMs`, `routeLine`; on `START` `startMs`. A change by the sun shows only during guidance |
| `look_theme`, `look_settled` | When a theme left for a quiet moment went (`themeHeldS`, `heldBy`, `writeMs`), and how long the map took to draw every tile after a theme was written (`idleMs`, null after 10 s) |
| `map_style_loaded`: `look`, `keys`, `errors` | The look the map showed as its style loaded, with 16 settings written and 0 refused |
| `settings` with `day_night` | Each change of Day and night in Settings |
| `lane_evidence` | One for each route a drive uses: right after `routes_committed`, and after each `routes_updated` that brings a new route (a reroute, another route taken, the start's replan), never after a refresh. Numbers only, about 700 to 800 bytes: `routeId`; `lengthM`, along the steps' shapes; `steps`; `lanePoints`, the intersections with lane data by the road that leads to them (`freeway`, `ramp`, `street`); `freewayM`, `rampM` and `streetM`, the whole metres of each kind of road whose lanes are `known` (from a point with lane data back to the intersection before it, at most 1,000 m on freeways and ramps and 250 m on streets), `bridged` (between two points of one carriageway with the same lanes, across roads that join or leave on the exit's side; on streets only up to 400 m apart) or `none`; `gaps` without lane data between two points of one carriageway that are not bridged: `count`, `m`, `inWindow` and `inWindowM` (those reaching into a card window, a freeway exit's run-up or the 180 m before a street maneuver, and their metres inside it), `shortOutside` (at most 600 m and outside every window); `unexplained`, changes of the lane count between two points that nothing on the way explains; `exitSideAdded`, the lanes a point lists that the point before it does not (`count`), and of them `sideUnknown`, those whose arrows do not tell their side; `unknownExits`, roads without lane data that leave on the exit's side inside a bridge; `windows`, the run-ups (`runUps`) and street maneuvers (`street`); `streetTurns`, the turns at junctions off freeways, `withLanes` and `withoutLanes`; `designated`, the lanes the points list for each kind (`hov` for car pools, `bus`, `taxi`, `motorcycle`, `moped`, `bicycle`, `other`); `noLanes`, true for a route without any lane data. Then, from the route's lane plan (left out where it could not be made): `zones`, changes of the lane count that nothing on the way explains, drawn without lane lines; `zonesBridged`, stretches without lane data between two drawn ones, drawn the same way; `possibleLanes`, lanes on an exit's side that may already be there before the point that first lists them; `aligned`, changes of the lane count whose side a road, the arrows or the car-pool lanes told; `noneM`, the whole metres left without anything of the app's own lanes. Together: how much of a route the app's own lanes could draw, and how often a gap falls where the card names a lane |
| `lane_plan` | One for each route a drive uses, right after its `lane_evidence` line: the lanes the app would take, in numbers only; nothing on the screen shows them yet. `routeId`; `ms`, how long reading the route and making its plan took; `windows`, in route order, the stretches where the plan names a lane and holds lane data, and the approaches where no lane can be named: `kind` (`run_up`; `ramp`, from a gore along the ramp to a split or point with lane data no more than 15 s of driving after it, which the plan names a lane for; `street`; or `approach`, an exit no lane open to every car leads to), `key` (the card's own key of its exit or point, as in `lane_row` and `lane_choice`) and `fromM`, where it begins, in metres from its end (the gore, the split or the junction, which every distance of the entry counts from, negative before it); a run-up's `seam`: `latchM`, where the card's run-up begins today, `narrowingM`, where the plan's lanes first narrow for a point within reach, and `earlierM`, how much earlier the plan begins it; `points`, the lane points in it (a ramp's after its gore): `atM`, `lane`, the lane the plan names there counted from the edge of the exit or fork ahead (0 at the edge, lanes that may already be there counted), and `amber`, how many lanes still work there; `moves` of the named lane: `atM` before the point it is made for, `from` and `to` counted from the edge, and `back` where it moves away from the edge, which should never happen; `tightM` and `conflictM`, how long the lanes that work allow only the latest lane changes, or none; `unknownExits`, the roads without lane data on the exit's side before it, and `unknownExitDropped`, where the plan lit the edge lane before them all the same; `conflict` (`designatedOnly`: only a car-pool lane leads on; `designatedBetween`: the lanes to take lie beyond one) or `roundabout`, where no lane is named; `possibleLanes` and `zones` in it. After a change of route: `replanMoves`, 1 where the lit lane would move once the new plan takes over, 2.5 s of driving ahead of the car, and `replanKept`, true where the old count from the edge was kept because both routes run on the same carriageway at the car. `odd`, over the route: `activeOutsideValid`, lanes the route prefers but says cannot be used, and `arrowContradicts`, lanes it can be followed from whose one arrow points away from the exit. About 400 to 650 bytes for a route with one freeway exit |
| `lane_agree` | One each time the card begins to show a lane row at a point with lane data, once the plan of its route is there: `source` (`run_up`, `choice` or `maneuver`), `key`, `aheadM`, how far ahead of the car the point is; `lit`, the lanes the card lights, from the left; `planLit`, the lane the plan names there, and `amber`, the lanes that still work there; `agree`, and where they do not, `why`: `lit` (the card lights one lane, the plan another), `amber` (the card lights several lanes, or the plan names none there, and they are not the lanes that work), `not_named` (one lit lane where the plan names none and more work), `no_lane` (no lane can be named on this approach: only a car-pool lane leads on, the lanes to take lie beyond one, or a roundabout), `row` (the row's lanes are not the point's), `no_point` (not a point of the plan) or `words` (the card names the lanes in words where the plan would wait); then what the words depend on: `opensAhead`, a lane on the exit's side opens or may begin between the car and the point, or the lit lane's count from the edge changes; `unknownAhead`, a road without lane data on the exit's side still lies ahead on the step; `exact`, the card's own count leaves no doubt; and `outline`, the point's lanes the app's drawing would not show at the car yet. About 250 to 300 bytes; one to four for each run-up |
| `progress`: `motion` | Only while guidance runs on a freeway (the intersection the car passed last leads onto a motorway or a trunk road, as for the lane choices), on every `progress` line, held ones too; off freeways the field is left out. The phone's motion since the line before, in numbers only: `n`, the gyroscope's samples (about 25 a second), and `ms`, from the first of them to the last by their own times; `yaw`, the mean turn rate about the vertical in degrees a second, a right turn positive (the way a bearing grows), and `yawSd`, its standard deviation over the samples; `fwd` and `side`, the mean acceleration in the level plane with gravity taken out, in m/s², along the reference direction and 90° clockwise of it, to the right positive like `match.raw`'s `sideM`; `ref`, the reference direction: `back`, the level direction of the phone's back (its camera side) while the back points at most 45° above or below the level, as in a windshield or vent mount upright or turned sideways, else `top`, that of its top edge, for a phone lying about flat; `tilt`, how far that axis points above the level, in whole degrees, negative below (a phone leaning back 30° in its mount: `back`, `tilt` -30). With the back facing the road ahead, `fwd` is the car's acceleration along its way and `side` its sideways acceleration, within the mount's own small angle. A field is null when a sensor it needs gave nothing in the window: the first line on a freeway has `n` 0 and nulls. In a simulated drive the phone is at rest. About 100 bytes a line, 0.36 MB an hour on freeways. For research only (the owner's answers above, the lane the car is in): nothing on the screen uses it |
| `route_line` | Only with **Settings → Debug → App's own route line** or **Draw the app's lanes** on: one at each change of who draws the route line. `drawer`, `sdk` (Mapbox's line) or `own` (the app's); `reason`: `start` (the own line begins with the drive's route: at Start, on a map made anew, or with the switch turned on during a drive), `route_change` (a reroute or another route taken), `loaded` (the own path has loaded and replaced Mapbox's line), `style_load` (the map's style loaded again), `switch` (turned off during a drive), `fallback` or `guidance_end`; `route`, which of the drive's routes it is, counted from 1. With `loaded`: `swapMs`, from the change to the own line drawn, `buildMs`, how long making its path took off the main thread, `loadMs`, from handing the path to the map until the map had loaded it, `points`, the path's points, and `version`. With `fallback`: `ownLineFallback`, `source`, `exception` or `no_data`. Numbers and words only, about 90 to 160 bytes |
| Camera: `routeLine` | Only with the same switches: `own`, whether the app's line is drawn; `trims`, its trims that second; `trimLagM`, the largest distance along the route between its trimmed end and Mapbox's vanishing point (0 while it follows Mapbox's point, more where it went on by its own projection of the puck); `trimMaxMs`, the longest the app's part of a puck update took on the main thread. About 70 bytes more a second, 0.25 MB an hour. With **Draw the app's lanes** on also `swapped`, whether the lit lane takes the route line's place over the lit stretches (the camera's state: a close view holds and a drawn lane is wide enough, also where no lane is lit), and `swaps`, how often that changed in the second |
| `lane_drawing` | Only with **Settings → Debug → Draw the app's lanes** on: one for each window of the app's own lanes that loaded. `route`, which of the drive's routes, counted from 1; `window`, counted from 1; `reason`: `route` (the first window, with a route's path), `ahead` (the next one as the car drives on) or `style_load` (sent again after the map's style loaded); `lengthM`, the window's length along the route; `features`, `coordinates` and `bytes`, the GeoJSON handed to the map; `buildMs`, how long making it took off the main thread; `loadMs`, from handing it to the map until the map had loaded it; `budget`, true within 600 KB and 20,000 coordinates. Numbers and words only, never a shape or a place; about 160 bytes, a few a drive |
| `motion_sensors` | One each time the sensors are asked to start, as a drive comes onto a freeway: whether the phone has a gyroscope (`gyroscope`), a gravity sensor (`gravity`) and a linear acceleration sensor (`linearAcceleration`), the period asked for (`periodUs`, 40000: 25 samples a second, each delivered at once) and whether listening `started`. They stop at the first update off the freeway and when the drive ends. Without a gyroscope or a gravity sensor nothing is registered: one line a drive with `started` false, and no `motion`. Without linear acceleration, `fwd` and `side` stay null |

Reading the connectivity lines:

| The log shows | Meaning |
|---|---|
| No network, no other network available, and no SIM or Mobile data off | The phone had no mobile data |
| No network, but a mobile network available | Mobile data existed, but Android kept it from this app, for example Wi-Fi only |
| Mobile, blocked | Data saver, background data or battery limits |
| Blocked only while the `activity` lines show the app stopped, and unblocked when it starts again | Android's limit for apps in the background (Android 15, Data saver, Battery saver); no fault, and the chip leaves it out |
| Not validated | A network without internet |
