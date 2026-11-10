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
| No internet from 37 s into the first drive; 2337 map tile errors | The app had no network once home Wi-Fi faded; nothing was saved ahead | Map and road data saved ahead, an offline chip, network lines in the log | Next build; the network lines and the folded map errors are built |
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
diagnostics, the camera, the destination and the start below are built, and so are the Then chip
the owner asked for ([DECISIONS.md](DECISIONS.md), 37) and the Lane zoom setting with lane details
on the map (38, 39); the rest is designed and not built yet. Stage 2 is the Android Auto stage.

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

- **One tile store (next build).** The map and the navigator share one `TileStore`, set in
  `MapboxMapsOptions.tileStore` before the first map. Predictive caching needs it. It stays at the
  default location, so nothing cached so far is lost.
- **Predictive caching during guidance (next build).** `PredictiveCacheController` (ui-maps 3.34.1)
  keeps data along the drive while any network is up
  ([offline guide](https://docs.mapbox.com/android/navigation/guides/advanced/offline/)). Mapbox's
  defaults are 20 km around the car, 5 km along the route and 50 km around the destination; these
  are smaller:

  | Data | Around the car | Along the route | Around the destination |
  |---|---|---|---|
  | Road data: map matching, speed limits, offline reroutes | 10 km | 3 km | 10 km |
  | Standard map, zoom 15 to 16 | 2 km | 1 km | 2 km |
  | Standard map, zoom 11 to 14 | 10 km | 5 km | 10 km |

- **Street map along the routes (next build).** When routes appear in the preview, the Standard
  map at zoom 11 to 16 along every previewed route is downloaded, 3D sources included, plus the
  Standard style pack, so the map can also open offline. It is one tile region that the next
  preview replaces, not the owner-chosen offline regions on the future list in ROADMAP.md. On the
  first drive this would have used the 8 minutes on home Wi-Fi. Each download is logged with its
  size and time.
- **Cost.** No extra Mapbox charges: Navigation SDK requests during guidance are part of the trip,
  and map tiles are covered by the Maps SDK's monthly user. The cost is mobile data: tens of MB for
  a new area, reused on later drives. Data outside tile regions is capped at 1 GB by default.
  The preview download also runs on mobile data when routes are planned on the road; it could be
  limited to Wi-Fi.
- **Offline chip (next build).** 3 s after the app loses internet, a chip under the search bar or
  the maneuver card says so. The delay hides a normal handover from Wi-Fi to mobile data. The chip
  sits outside the measured panels, so the camera does not move when it appears.
  - No network: "No internet. The map shows only areas saved on the phone; guidance continues."
  - A network without internet: "Connected, but no internet. The map shows only saved areas."
  - Android blocks the app: "Android is not letting Custom Maps use this network." A **Settings**
    button next to it opens the app's data usage page.
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
| Start reason | On Android 15 and newer: why the app started (launcher, Recents, a service), cold, warm or hot, and whether it had been force-stopped | The restart |
| Device state | On screen or not; screen on, off, unlocked; charging; Power saving and Doze; heat; memory pressure. Once per start: battery limits, background restriction, standby bucket, Data saver, location power saving. During guidance, one line a minute: battery level and temperature, thermal status and headroom, Power saving, memory | Phone conditions |
| Connectivity | Every change of the app's network: Wi-Fi or mobile, internet reached or not, blocked or not, metered, roaming, suspended, and which networks the app can see. At start, on every return to the app and after each change, the settings that can cut off only this app: Data saver, the Mobile data switch, the SIM, background restriction, battery limits | The missing internet |
| Animations | Whether Android's animations run and the animator duration scale, when the first screen opens, when guidance starts and whenever the scale changes. Logged only; the app never changes the setting | Steps |
| Camera | Once a second during guidance while the map is on screen (the `activity` and `screen` lines show when it was not): zoom, tilt and bearing against the navigation camera's target, how far the camera trails the drawn car and the car trails the latest fix, how often the camera and the car moved and their longest pauses, the padding, the map's frame rate, render times and missed frames, and whether Android's animations run. Also how wide a lane is on the screen, what sets the zoom with the Large lane zoom, and whether lane details are on. A line on every camera mode change with how long the transition took, and when a touch takes over the camera or Recenter or Overview is tapped | Steps, switches, lanes, a heavy style |
| Lane choices | Once for each point of the route with lanes, when it comes as near as the close view would start: its lanes, how many can be used and how many leave the route, whether the road is a freeway, and the reason when the rule passes it over. Each route also lists, step by step, its intersections with lanes | How often Large goes close on a freeway, and how often a looser rule would |
| Map errors | The first error of each kind in full, then one line a minute with the counts and the map sources | The flood |
| Search results | How exact the point is (rooftop, parcel, interpolated, street), the stop points with their distance from it and the one the app took, the result's types, categories and Mapbox id | Behind the store |
| Route requests | Every route request the app sends: where it starts, and how old, how fast and in which direction that position was; the stop point, the arrival target and the direction sent with it; whether it was sent inside a trip. And every reroute request of the navigator: how many points and arrival targets it has, whether the destination's target is set, and how far its end is from where the trip goes | Behind the store; what a drive costs |
| Start | The plan at Start, with the age of the routes and how far the car moved. Then the result of the refresh and of the replan, whether a cancelled refresh is asked for once more, and the reason whenever fresh routes were not set | Old routes at Start |
| Routes | How far the Directions API moved each requested point: about 71 m for the store. The lines that describe whole routes are put together off the main thread, so they are written a moment after the lines that follow them | Behind the store |
| Fix age | Every matched location carries the age of its fix in ms and the number of points the drawn car glides through | GPS delay; old fixes, as at the second drive's Start |

- Folded, the first drive's 2337 map error lines become at most a few dozen, and the file about
  half as large.
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
- **Freeways:** the long view ahead stays.
- **Old routes at Start:** start at once and refresh inside the trip.
- **Arriving on the right side:** no.

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
3. **Offline.** If the network drops, the offline chip should appear within about 3 s, and the map
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
   close only about 25 s before an exit or a split where lanes leave the route. Note every exit
   where it went close and should not have, or did not and should have, and whether the road moves
   too fast when close. Compare one stretch in Default, and one in Mapbox tuned with Large. Changing
   Lane zoom while moving should glide, never cut.
7. **Lane details on the map.** Turn the switch on. Where Mapbox has them (major highways and some
   cities) lane lines and painted arrows should appear close in, without fading out and in when
   the map opens. Do the painted arrows match the card's? Is the route line ever lost on or under a
   bridge, and where does the car's icon sit there? The answers decide whether the switch can be
   on for everyone.
8. **Lanes in the card on a freeway.** Before a split or an exit with two exit lanes that the
   route passes going straight on, the card's lane row should show that point's lanes with its
   distance, and go back to the next maneuver's lanes after it.

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
| Offline downloads | Size and time of the route download |
| Map errors | A few lines a minute instead of thousands |
| Fix age | Whether GPS fixes arrive late, or old as at the second drive's Start |
| `start_plan` | The plan, the age of the routes in seconds and the meters moved; `movedMeters` and `position` are null when the map had shown no position |
| `start_refresh`, `start_replan` | After a plan other than `AS_PREVIEWED`: one closing line each, never before the drive's first `progress` line. `start_replan` with `set` is followed by `routes_updated`; any other result names its reason |
| `start_refresh` with `cancelled`, next to `routes_updated` | Whether the navigator changes its alternatives in the first seconds of a trip and so cancels the start's refresh, and whether the refresh asked for again went through |
| `progress`: `fixAgeMs` in the first lines of a drive | Whether the trip opened on an old position. The plan then waits: a `start_refresh` or `start_replan` line with a result from the navigator comes only after a `progress` line with an age of 30,000 or less |
| `session_state` | One `ActiveGuidance` session id for the whole drive, also across a `start_replan` |
| Progress: `after`, `thenShown`, `stepMetersLeft`, `stepSecondsLeft` | Where the Then chip showed, and for which pairs of maneuvers, to tune its distances |
| Camera: `laneDp`, `zoomPlan`, `laneFocus`, `centerLeadS` | Whether Large reached about 28 dp at junctions and 14 dp before lane choices, and how much of the freeway stayed in the long view |
| `lane_choice`, with and without `skipped` | Which exits and splits the rule counted and which it passed over, with their lane counts, to tune its two thresholds |
| Camera: `laneDetails`, `hdRoads`, `hdRoadsReapplied`; `lane_details` | Whether lane details stayed on once set |

Reading the connectivity lines:

| The log shows | Meaning |
|---|---|
| No network, no other network available, and no SIM or Mobile data off | The phone had no mobile data |
| No network, but a mobile network available | Mobile data existed, but Android kept it from this app, for example Wi-Fi only |
| Mobile, blocked | Data saver, background data or battery limits |
| Not validated | A network without internet |
