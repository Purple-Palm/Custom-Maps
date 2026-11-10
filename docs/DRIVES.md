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
| The car and the map moved in steps, once a second | Android's animations were off; the SDK camera also moves in 1 s linear pieces | Camera styles with a new Smooth follow; the owner checks system animations | Next build |
| The view switched abruptly near turns | The SDK's framing flips the tilt within 180 m of most turns and again at each step change | The tilt goes down and back up gradually | Next build |
| Lanes too small before a turn | The following camera stops at zoom 16.35 | More zoom at turns | Next build; full junction zoom in stage 4 |
| The camera turned into a gentle curve well before the car (liked) | The SDK aims the camera at the far end of the road ahead | Kept | Next build |
| The first drive's routes all ended behind the store | The route went to the middle of the building, and the router took the nearest road | Routes end at Mapbox's stop point for the place | Next build |
| No internet from 37 s into the first drive; 2337 map tile errors | The app had no network once home Wi-Fi faded; nothing was saved ahead | Map and road data saved ahead, an offline chip, network lines in the log | Next build |
| The app restarted once before the first drive | Unknown: the log records no crash and no exit reason | Crash and exit-reason lines | Next build |
| A reroute right after Start, on both drives | First drive: the car was not yet matched to a road. Second drive: the navigator began with an out-of-date position | None needed: a reroute inside a trip is free. Start refreshes traffic on old routes | Next build |

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
- The map itself draws no lane markings (HD roads are off; see the watch list in ROADMAP.md), so
  the lanes come only from the maneuver card.

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

The next build is stage 1.1 in [ROADMAP.md](ROADMAP.md). At the time of writing it is designed,
not built. Stage 2 is the Android Auto stage.

#### Camera

The reasons and numbers behind each style are in [CAMERA.md](CAMERA.md).

| Change | For | Stage |
|---|---|---|
| Camera styles in Settings: **Mapbox standard** (today's camera, unchanged, for comparison), **Mapbox tuned** (the same camera with calmer settings) and **Smooth follow** (new, the default) | Steps and abrupt switches | Next build |
| Smooth follow moves the camera on every display frame and glides after the drawn car. Its own motion does not depend on Android's animation setting; the drawn car's glide does | Steps | Next build |
| The tilt goes down onto the junction gradually before a turn and back up after it | Abrupt switches | Next build |
| More zoom at turns, so the junction and its lanes are larger | Small lanes | Next build; the full junction zoom stays in stage 4 |
| The early turn into curves stays | The owner liked it | Next build |
| Proposed: during guidance the top padding only grows, so the lanes row no longer reframes the view. In Smooth follow padding changes glide anyway | Secondary switches | Next build |
| The route line is updated only when the SDK accepts the point | Main-thread waste | Next build |

#### Destination

- **Stop point (next build).** `Place` in `:core-nav` gets a stop point: where a car should stop
  for the place, when Search gives one. The place's own point stays its pin.
- **Which point (next build).** `MapboxSearchProvider` takes the first routable point meant for
  cars (`POI`, `Address` or `default`). It skips points named `entrance`: in Mapbox's data they
  mark a building door for people on foot, not where a car stops. It ignores any point more than
  1 km from the place as bad data.
- **Route request (next build).** Routes go to the stop point, with the place as the arrival
  target (`RouteOptions.waypointTargetsList`, directions-models 7.10.1; `waypoint_targets` in the
  [Directions API](https://docs.mapbox.com/api/navigation/directions/)), so the arrival says which
  side the building is on. Results without a stop point work as today. There are no extra search
  requests: routable points come in the same response.
- **Reroutes (next build).** Every reroute, online and onboard, aims at the same stop, because
  reroutes are built from the request. Whether the onboard router reads `waypoint_targets` is
  unverified; offline, only the side named at arrival could differ.
- **Preview (next build).** The map shows the place as a pin next to the route's end. Without a
  stop point, when the route ends more than 50 m from the place, the route sheet says so, for
  example "Route ends 230 ft from the place". 50 m is a starting value.
- **Curb side (unchanged).** Arriving with the place always on the right stays off: it can add a
  loop around the block. The owner chose to keep it off (2026-11-10).
- **Shared links (stage 2).** A Google Maps, Apple Maps or Waze link, or a `geo:` address, gives a
  point, often the middle of a building, and no stop point. The link's point is looked up once in
  Search Box, and that result's stop point is used when it lies within about 150 m. The link's own
  point stays the place. This costs one extra Search Box request per link.
- **Still unknown:** whether this store gives a stop point in front, searched by address or by
  name. The next drive shows it.

#### Start

Next build. On Start the app checks how old the routes are and how far the phone has moved since
they were planned:

| Situation | What Start does | Cost |
|---|---|---|
| Routes up to 5 minutes old, phone within 100 m | Starts with the routes as they are | Nothing extra |
| Routes 5 to 30 minutes old, phone within 100 m | Starts with them, then refreshes traffic and times inside the trip | Free, part of the trip |
| Phone moved more than 100 m, or routes older than 30 minutes | Starts with them, then gets routes that begin where the car is, inside the trip | Free, part of the trip |

- The owner chose to start at once (2026-11-10): Start never waits, and fresh routes replace the old
  ones inside the trip a moment later. The alternative was to wait up to 4 s for fresh routes before
  the trip.
- The first drive would have got the refresh: 8 minutes old, 22.5 m moved.
- The new routes keep the chosen route when they have the same main roads. If the request fails,
  the old routes go on and the SDK reroutes inside the trip by itself, as on both drives.
- Nothing is swapped before the trip's first progress update, so the new routes always land inside
  the same billed trip.
- A simulated drive always starts with the routes as they are.
- The choice is logged as `start_plan`.
- The refresh uses `RouteRefreshController.requestImmediateRouteRefresh` (Navigation 3.34.1), an
  experimental API. When the SDK reroutes first, as on both drives, the reroute already brings
  fresh traffic.
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

Next build. New lines in the debug log:

| What | Recorded | For |
|---|---|---|
| Crash | A Kotlin or Java crash on any thread, written to the file at once, before Android shows "keeps stopping": thread, exception, stack and causes, guidance state, and whether the app was on screen | The restart |
| Exit reasons | At every start, each earlier end of the app not logged yet: when, Android's reason (a swipe from Recents shows as `USER_REQUESTED`, "remove task"), status, importance, memory, the app's saved state summary, and for an ANR the main thread's stack | The restart |
| Start reason | On Android 15 and newer: why the app started (launcher, Recents, a service), cold, warm or hot, and whether it had been force-stopped | The restart |
| Device state | On screen or not; screen on, off, unlocked; charging; Power saving and Doze; heat; memory pressure. Once per start: battery limits, background restriction, Data saver. During guidance, one line a minute: battery level and temperature, thermal headroom, Power saving, memory | Phone conditions |
| Connectivity | Every change of the app's network: Wi-Fi or mobile, internet reached or not, blocked or not, and which networks the app can see. Also the settings that can cut off only this app: Data saver, the Mobile data switch, the SIM, battery limits | The missing internet |
| Camera | Once a second during guidance: the active camera style, zoom, tilt and bearing against their targets, how far the camera trails the drawn car, the frame rate and the worst gap between frames, and whether Android's animations run. A line on every camera mode change, and when a touch takes over the camera or Recenter or Overview is tapped | Steps, switches, lanes, a heavy style |
| Map errors | The first error of each kind in full, then one line a minute with the counts | The flood |
| Search results | How exact the point is (rooftop, parcel, interpolated, street), the stop points with their distance from it, the result's types and categories | Behind the store |
| Routes | How far the Directions API moved each requested point: about 71 m for the store | Behind the store |
| Fix age | Every matched location carries the age of its fix in ms and the number of points the drawn car glides through | GPS delay; old fixes, as at the second drive's Start |

- Folded, the first drive's 2337 map error lines become at most a few dozen, and the file about
  half as large.
- Android keeps exit records across app updates. Installed over the current build, the next
  build's first start should log the record behind the first drive's restart, as long as it is
  still among the last 16 that Android keeps.
- A crash in native code or an ANR never reaches a Kotlin handler; the exit records cover those.
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
   where the route ends next to the store's pin. Then search it by its name and do the same. Drive
   one of them and note where the arrival is and which side it names.
2. **Camera styles.** Compare Mapbox standard, Mapbox tuned and Smooth follow on the same kinds of
   road: a gently curving road (the early turn must stay), ordinary turns, and a freeway entry and
   exit. Note smoothness, how the tilt changes before and after turns, whether the lanes are large
   enough, and whether the freeway view zooms out too far. Change the style only while stopped.
3. **Offline.** If the network drops, the offline chip should appear within about 3 s, and the map
   along the route should stay complete.
4. **Old routes (optional).** Leave the routes open for more than 5 minutes before Start; the log
   should show a refresh right after Start.

Then share the debug log. What to read in it:

| Lines | Question |
|---|---|
| Exit reasons and start reason, at the first start | Why the app restarted before the first drive |
| Camera | Frame rate and worst gaps for each style; whether animations ran |
| Device state | Power saving, heat, charging, display rate |
| Connectivity | Which case cut the app off, see below |
| Search results and routes | Stop points by address and by name; how far each point was moved |
| Offline downloads | Size and time of the route download |
| Map errors | A few lines a minute instead of thousands |
| Fix age | Whether GPS fixes arrive late, or old as at the second drive's Start |
| Start plan | What Start did with the routes |

Reading the connectivity lines:

| The log shows | Meaning |
|---|---|
| No network, no other network available, and no SIM or Mobile data off | The phone had no mobile data |
| No network, but a mobile network available | Mobile data existed, but Android kept it from this app, for example Wi-Fi only |
| Mobile, blocked | Data saver, background data or battery limits |
| Not validated | A network without internet |
