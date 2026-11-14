# Drives

What real drives showed, and what changes because of them. Newest first. Each entry lists what
the owner reported, what the debug logs show, the causes, the changes with their stage, what the
owner checks on the phone, and what the next drive tests. Stages are those of
[ROADMAP.md](ROADMAP.md), where the next build is stage 1.1; the camera design is in
[CAMERA.md](CAMERA.md).

Drive logs never go into this repository. These notes leave out addresses, street and place names,
coordinates and times of day. Places are described ("a big-box store"), positions are distances,
and times are durations from Start or End.

## Next: the car screen after step 1d (stage 2)

What the next drives with the car screen check, on the Desktop Head Unit with the debug APK or in
the car with the build from Google Play's internal testing ([README](../README.md), On the car
screen). Screenshots in the car come from a passenger or a parked car, never from the driver. The
lines named are in the debug log; every car line has `screen` `car`. Checks 1 to 6 are step 1's,
with day and night now following the phone; 7 to 13 are step 1b's
([STAGE-2.md](STAGE-2.md), Step 1b); 14 to 18 are step 1c's ([STAGE-2.md](STAGE-2.md), Step 1c).
Step 1d's are checks 77 to 82 of the next drive's list below
([What the next drive tests](#what-the-next-drive-tests)): the car's frames with the phone locked, a
hot phone, the card's lanes two miles before an exit and the measuring's whole views.
Since step 1c the phone shows the list in place of its map during a drive with the car: for checks
7 to 13, which look at the phone's map too, press **Map** on the list first.

1. **The app in Android Auto's launcher.** Custom Maps is among the navigation apps; it opens with
   the map, or first with a message while the phone has no token or no precise location (the setup
   is done on the phone, parked). `car_session` lines: `ON_CREATE` with the Android Auto app's
   package and its Car API level, then `gate` `READY` and `map` `build`.
2. **The map, with the logo and the attribution, in the phone's look.** The same map as the
   phone's, the Mapbox logo and "© Mapbox © OpenStreetMap" in the bottom left corner, clear of the
   buttons and the routing card, and not moving when they come and go. The car's map shows the
   phone's look: night on the phone, night in the car, whatever the car says; Day or Night in the
   phone's Settings changes both screens at once (`car_look` with `cause` `APP` and the map's
   `look`). The car's own day and night (the DHU's `day` and `night`) change only the routing
   card's arrows and lanes (`car_look` with `cause` `HOST`): note how they read on the card the car
   draws behind them.
3. **A drive started on the phone, shown in the car.** Start on the phone with the car connected:
   the car shows the routing card at once, with the phone's turn, arrow, distance and road, the
   lanes to take (on a freeway's run-up the same row the phone's card shows, lit lanes and all), the
   turn after it exactly when the phone shows **Then**, and the arrival time, time and distance left.
   The card follows every second; a new turn shows at once. Leaving the route: the card shows that
   it is loading until the new route's first turn. The car's map shows the route and the car
   moving. `car_nav` `navigationStarted` with `cause` `drive`.
4. **End in the car.** End in the car ends the drive on both screens, as the phone's End does: one
   trip in Settings → Debug, a `ui_end` line with `screen` `car`, `car_nav` `navigationEnded`. At the
   destination the car says "You have arrived" until Done on the phone.
5. **The cable pulled during a drive, then plugged in.** The phone goes on guiding, with its
   notification; the trip session log keeps one entry for the drive. `car_session` `ON_DESTROY`, a
   `car_nav` `gone` line, no stop of the drive. Plugged in again: note whether Android Auto opens
   Custom Maps by itself or shows its launcher (not known yet); once Custom Maps is open, the routing
   card and the route show at once (`navigationStarted` again).
6. **Another navigation app, and back.** During a drive, start another navigation app in the car (or
   the DHU's `focus nav off`): `car_nav` `onStopNavigation` with the lifecycle and the connection,
   the phone goes on guiding, no error line. Open Custom Maps in the car again: the routing card is
   back, `navigationStarted` with `cause` `intent` or `start`. Note whether the other app stopped.
7. **Frames with the phone app in front.** A simulated drive with the car connected and the phone
   app in front, for a minute: the car's map moves smoothly. The car's camera lines, a few seconds
   into the drive: `render.drawnFps` about 30 (27 to 33), `maxFps` 30 with `capFrom` `display` on a
   phone at 60 Hz, 15 (`display`) or about 19 (`drawn`) on one at 120 Hz, `clock.vsyncTicks` about
   60; the phone's: `capped` true and `renderFps` about 60; a `car_surface` line with `event` `fps`
   only where the frames drawn moved the cap, with `drawnFps`.
8. **The phone locked during a drive.** Lock the phone for a few minutes, then unlock it: the car's
   map and its car icon keep moving. The `display_state` lines (OFF or DOZE while locked?), every
   `frame_clock` line (`source` `timer`, then `vsync` again, with `quietMs` and `timerTicks`), and
   the car's camera lines of those minutes: `clock.timerTicks`, `camWrites` about 60, `puckUpdates`,
   a small `puckLagM`, and `render.frames` and `drawn`, which say whether Mapbox still drew.
9. **The app left during a drive.** Home on the phone for a few minutes: the car's map keeps moving;
   the same lines.
10. **The zoom buttons with the phone locked:** the map zooms.
11. **Heat.** Ten minutes with the car and the phone app in front: the `thermal` lines and the
    `device` lines' `thermal`, against a drive with the phone alone.
12. **The cable pulled and plugged in during a drive:** `car_surface` `detach` and `attach`; the
    phone's camera line loses `capped` while the car is gone and has it again after.
13. **Before a drive, with the phone locked:** note whether the car's map shows the position
    (`car_location` says whether positions arrive). With location while in use it may not, until
    "Allow all the time" comes in a later step.

14. **The list during a drive with the car.** A simulated drive started on the phone with the car
    connected and the phone app in front: the phone shows the list at once, a black page by day too,
    with light icons in the bars: the card at the top, the turns that follow with their distances
    from the car going down each second, as many as fit, the replay slider, the guidance bar with
    **Map** and **End**, and "© Mapbox © OpenStreetMap" at the bottom. Nothing on it moves between
    updates. Lines: `phone_screen` with `screen` `list` and `cause` `drive`; the phone's camera lines
    go on with `away` true and `render.frames` 0. **End** on the list ends the drive as End always
    has: `ui_end`, one trip, and `phone_screen` `map`, `end`.
15. **Map and List.** **Map** on the list: the phone's map is back within a second, in the app's
    look of the moment, with the car where it is, the route as driven and the camera following the
    car; `phone_screen` `map`, `button`, and the camera lines without `away` and with frames again.
    The guidance bar has **List** then: it goes back to the list, `phone_screen` `list`, `button`.
    Note how long the map takes to show the car and whether a frame of the old place shows first.
    Then, on the list, swipe Custom Maps away from the recent apps and open it again from the trip
    notification while the drive goes on in the car: the list again, and no phone camera line;
    **Map** makes the phone's map then (`map_style_loaded`, as at the app's start) and its camera
    lines start.
16. **The car unplugged and plugged in on the list.** Pull the cable during a drive on the list: the
    phone's map comes back as of that moment (`phone_screen` `map`, `car`); plug it in and open
    Custom Maps in the car: the list again (`list`, `car`). Then **Map**, and pull and plug the cable
    again: the map stays, with no `phone_screen` line, and **List** shows only while the car shows
    the map. A new drive starts with the list again. On the list, open another app on the car's
    screen for a minute and come back: note whether the phone stays on the list meanwhile (the
    `car_surface` and `phone_screen` lines say whether the car let its map's surface go).
17. **Heat on the list.** Ten minutes of a drive with the car and the phone app in front, on the list:
    the `thermal` lines and the `device` lines' `thermal`, against check 11's ten minutes with both
    maps on step 1b's build.
18. **30 against 60 on the car.** **Settings → Debug → Car map frames** before the drive (Settings
    opens before Start and after the arrival): a few minutes of a simulated drive at 60, then the
    same at 30. The car's camera lines: `targetFps`, `maxFps` and `render.drawnFps`, about 30 at 30
    and about 60, or as many as the phone draws, at 60; a `car_surface` line with `targetFps` at the
    surface's attach. Say whether 60 looks smoother on the car's screen than 30 (the car may show
    fewer frames than the map draws), and share the `thermal` lines of both.

Anything wrong on the car screen writes `car_error` or `car_template` with the call and the class of
what failed; the phone's guidance must go on through all of it. Share the debug log after the drive.

## The first car test, a simulated drive (stage 2, step 1)

The owner's Samsung phone with Android 15, build 0.1.34 from Google Play's internal testing, a
simulated drive with the car screen.

- **What the owner reported:** few frames a second on the car screen, freezing and lag; the car's
  map in the day look while the phone showed the night; the car's map stopping when the app was left
  on the phone.
- **What the log shows:**
  - With the phone app in the background, the car's camera stood still for many seconds: no camera
    update and no write, while positions and progress kept coming and the car's session stayed
    resumed. The drawn car stood still too and fell further behind with every second. In earlier
    stretches in the background the car's map kept moving. No screen on or off broadcast was logged
    during the car session, though the phone was locked and unlocked.
  - With the phone app in front the phone's map drew at the phone's full display rate, and the car
    map's render thread was called far more often than its cap of 30 a second suggests, while the
    car's `renderFps` read 30 throughout. The phone grew hot quickly, much sooner than on drives with
    the phone alone.
  - The phone's look was the night's (Auto, by the sun); the car's first `car_look` line said `dark`
    false, and the car's map loaded in the day look and stayed in it.
  - After the drive, with the phone app in the background, the car's map got no position
    (`car_location`, `fixes` 0).
- **Causes:** the car's camera and its car icon moved on the main thread's display frames, through
  Choreographer and Android's animators, and those stopped with the app in the background; Mapbox
  paced the car's surface against a believed 60 Hz while its render thread was called at the phone's
  own, higher display rate, so its cap of 30 drew far more than 30 frames a second (60 on a phone at
  120 Hz); and the car's map followed its host's day and night by design (DECISIONS 36). The missing
  position is the known limit before Start (STAGE-2, Location before Start).
- **Changes (stage 2, step 1b):** a frame clock of the car's own, with a timer while the display sends
  the main thread no frames, for its Smooth follow camera, its zoom buttons and its car icon; a real
  30 frames a second on the car and at most 60 on the phone while the car shows the map; the phone's
  look on the car (DECISIONS 50); `display_state` and `frame_clock` lines and the camera line's new
  frame fields ([STAGE-2.md](STAGE-2.md), Step 1b).
- **Not changed:** the position before a drive with the app in the background, until "Allow all the
  time".
- **What the next drive tests:** checks 7 to 13 above.

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
diagnostics, the camera, the destination, the start and the offline data below are built, and so are
the Then chip the owner asked for ([DECISIONS.md](DECISIONS.md), 37), the Lane zoom setting and
Lanes on the map (38, 39), with the lanes the app draws itself (52, 53). The trip history and the
prediction of ROADMAP.md are designed and not built yet. Stage 2 is the Android Auto stage.

#### Camera

The reasons and numbers behind each style are in [CAMERA.md](CAMERA.md).

| Change | For | Stage |
|---|---|---|
| Camera styles in Settings: **Mapbox standard** (the camera of the first drives, for comparison), **Mapbox tuned** (the same camera with calmer settings) and **Smooth follow** (new, the default) | Steps and abrupt switches | Built in stage 1.1 |
| Smooth follow moves the camera on every display frame and glides after the drawn car. Its own motion does not depend on Android's animation setting; the drawn car's glide does | Steps | Built in stage 1.1 |
| The tilt goes down onto the junction gradually before a turn and back up after it | Abrupt switches | Built in stage 1.1 |
| **Lane zoom** in Settings: **Default** (today's zoom, what a phone starts with) and **Large**. With Large the view goes close onto the junction before every turn, until a lane is about 4.4 mm wide, and on freeways before a point where lanes leave the route, also where the route goes straight on; it comes back out after each. Mapbox standard keeps its own zoom | Small lanes | Built in stage 1.1 |
| **Lanes on the map** in Settings, Off unless chosen: **Mapbox's lane details**, lane markings, painted arrows and crosswalks where Mapbox has them, or **Drawn by the app**, lanes the app draws itself, on freeways and on streets where the route's data allows, with the route line in the lane the card names. The car is shown on the road, not in a lane | Small lanes | Built in stage 1.1 |
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

- **The lane the car is in:** the owner would like to see which lane the car is in and when it changes
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
  earlier exit, is not lit before the road either. The card's run-up row and the app's own lanes
  read it ([DECISIONS.md](DECISIONS.md), 53; checks 47 and 63).
- **The app's drawing as the lanes a phone starts with:** yes, once the owner has looked at the previews,
  at a simulated drive and at a real drive with the measuring switch on (checks 67 to 69),
  with Mapbox's lane details one tap away. Until then Lanes on the map starts at Off and Drawn by the
  app is a choice.
- **The car over drawn lanes in a close view:** yes to a blue bar across the road at its place along
  it, which claims no lane; elsewhere the usual dot (check 60).
- **Mapbox's electronic horizon, for lane counts between the points:** the owner wants it on, against
  the design's proposal to wait for Mapbox's written yes. **Settings → Guidance → Lane counts
  between lane points** turns it on, and is on unless turned off, marked as a Mapbox beta whose
  free use Mapbox may end. With Drawn by the app, the app's own lanes take its counts between lane
  points that agree with them; nothing else reads them ([DECISIONS.md](DECISIONS.md), 54; checks
  70 to 76).
- **What comes first:** the lanes. The app's own lanes, on freeways and then on streets, come before
  the other steps still waiting in stage 1.1, the trip history among them.

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
7. **Mapbox's lane details.** In **Settings → Guidance → Lanes on the map**, choose **Mapbox's lane
   details**. Where Mapbox has them (major highways and some cities) lane lines and painted arrows
   should appear close in, without fading out and in when the map opens. Do the painted arrows match
   the card's? Is the route line ever lost on or under a bridge, and where does the car's icon sit
   there? The answers decide whether they can be on for everyone. The lanes the app draws itself
   are checks 54 to 69.
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
25. **An exit with Large in Smooth follow.** On a freeway with exits, Lane zoom on Large. Before the
    route's exit the view should go close once, from a minute of driving (at least 1.5 km) plus 25 s
    before it where a lane on the exit's side leaves the route on the way, else about 25 s before
    it, or earlier where the lanes narrow early (check 62), and stay close to the gore and about 2 s
    past it, or through the ramp to a split soon after it (check 61): never out and in again between
    the points on the way. Close in, the view tilts up to 55° and shows the road well ahead, about
    12 s of it; over a junction it still goes straight down. The card shows one lane row, with no
    distance on it: the next point where a lane on the exit's side leaves, with one lane lit and
    words such as "3rd from right" or none, then the exit's own lanes with "Right lane", "2 right"
    or the like. The words never count down, a lane for car pools or buses is dim and never "the
    left lane", and the card does not change height when the words come and go. Before the run-up
    the card is as before. With Off or Mapbox's lane details, note at each exit
    **whether you followed the route line or the card**: there the lane the line lies in means
    nothing, and the card's words are the instruction. With Drawn by the app the line is in the lane
    the card names (check 56).
26. **Exits close together.** Where the route takes an exit right after an earlier one, the view
    should still go close once, a minute plus 25 s before the route's exit, with the earlier exit's
    lanes first ("2nd from right" and the like), and stay close past the earlier exit.
27. **A turn right after an exit.** Where the ramp ends in a turn within about 20 s of driving after
    the gore, the close view should stay until the junction view frames the turn, with no step out
    and back in before it. A turn farther on: the view goes out about 2 s after the gore, as before,
    unless the ramp splits within about 15 s of the gore where the lane plan names a lane for the
    split (check 61).
28. **Default.** With Lane zoom on Default the camera should be as before on the same exits: no
    tilt, no close view. The card's run-up row and its words come all the same.
29. **Mapbox tuned with Large.** One approach for each exit, as in check 25, with the SDK's tilt and
    lanes a third to a half narrower at freeway speed than in Smooth follow.
30. **The camera's frame rate and the data with the tilt.** With Smooth follow and Large, compare
    the camera line's `render` and `renderFps` on a run-up with the open road, and note the app's
    mobile data use for the drive (check 2): the tilt draws more of the map ahead.
31. **The new lines.** After the drive: `lane_row` lines at each change of the card's row, with
    `odd` where a run-up's row had no words (`composed`, `pieces`, `unknown_branch`, `opens_ahead`,
    `unknown_ahead`, `not_named`, `no_fit`) and
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
35. **Mapbox's lane details, by day,** on a road that has them: white markings on a dark asphalt,
    the amber route on top, and where the details end, the asphalt's end (the owner chose this
    asphalt over Mapbox's light road).
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
which lanes work at each point and which one lane to light where the card shows lanes; the card's
run-up row and its words read it, with every choice of Lanes on the map, and so does the app's own
drawing ([DECISIONS.md](DECISIONS.md), 53). It writes the plan down and compares it with the card:

46. **Whether the plan agreed with the card.** One `lane_plan` line right after each `lane_evidence`
    line, and a `lane_agree` line each time the card begins to show a lane row at a point with lane
    data: a run-up's row, a lane choice's, and on a freeway the exit's or the fork's own. A run-up's
    row reads the plan, outlines and words included, so a `lane_agree` line of a run-up's row should
    say `agree` true, but for `why` `no_lane` where no lane can be named on the approach (only a
    car-pool lane leads on, or the lanes to take lie beyond one: check 59), which is right there.
    Any other `why` of a run-up's row (`lit`, `amber`, `not_named`, `row`, `no_point` or `words`) is
    a fault worth a note with the exit. A lane choice's row, or the exit's own row before the
    run-up, is still the route's own: a `why` of `lit` with `unknownAhead` true can be right there,
    since before a road without lane data on the exit's side the plan keeps clear of the lane that
    will be at the edge where that road leaves. Any other `lit` or `amber` is a place where the card
    and the plan light different lanes: note the exit. There should be no `error` line with `where`
    `lane_plan` or `lane_agree`, and Start and a reroute should feel as before.
47. **How often a road without lane data dropped the hold.** A run-up's `unknownExits` in `lane_plan`
    counts the roads without lane data on the exit's side before it, and `unknownExitDropped` says
    where the plan lit the edge lane before such a road all the same, because it came less than about
    12 s of driving before the exit (the answer above); the card's words waited there until the road
    was passed (`lane_row` `odd` `unknown_ahead`).
48. **How much earlier the run-up began.** A run-up's `seam` in `lane_plan`: `latchM`, where the
    latch would begin the run-up on its own, in metres from the gore (negative before it), and
    `earlierM`, how much earlier it begins now at the plan's seam, 6 s of driving before the plan's
    lanes first narrow for a point within reach; the `lane_row` lines of such a run-up say `start`
    `seam`.
    And `ms`, how long the phone took to read the route and make its plan, against the design's
    budget of 50 ms.

The app's own route line, with **Settings → Debug → App's own route line** turned on before the
drive and **Lanes on the map** at Off or Mapbox's lane details (with Drawn by the app the line is the
app's anyway); a simulated drive will do for most of it; screenshots from a passenger or a simulated
drive, never from the driver. With the switch off everything is as before:

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

The app's own lanes, with **Settings → Guidance → Lanes on the map → Drawn by the app** chosen before
the drive (it draws the app's own route line too; a simulated drive through a freeway exit with lane
data will do for most of it; screenshots from a passenger or a simulated drive, never from the
driver). With the other two choices the map is as before. At each freeway exit or fork with lane
data ([CAMERA.md](CAMERA.md), The app's own lanes):

54. **The lanes under the line.** On a freeway with lane data, from a few kilometres before an exit
    and close in: grey asphalt a little wider than the lanes, white broken lane lines, solid edge
    lines (yellow on the left), dotted lines beside a lane that only exits, arrows in the lanes
    before lane points, and at the gore the road's other lanes going on for a short stub and
    tapering away. Lane lines only where the route has lane counts: where the data is missing inside
    a run-up, asphalt without lines, and a lane on the exit's side that may already be there drawn
    without a line between it and the next, never lit. Nothing over the Mapbox logo or the (i),
    labels and buildings above it. By day and at night, switching with the look; note the asphalt's
    shade where the drawing begins and ends against Mapbox's road. With Default, or zoomed out, the
    drawing is small, under the line. The log: one `lane_drawing` line for each window, `reason`
    `route` for the first one and `ahead` for the next, `budget` true; how many of the drive's exits
    had lane data, from `lane_evidence` (check 43), against those that got a drawing.
55. **The lit lane.** In the run-up to the exit (from where the card lights a lane) the lane to take
    is amber, edged in the route's casing colour, and the lanes that still work beside it are tinted,
    each tint ending in a wedge toward it where its lane stops working; read at 60 mph. The lit lane
    begins with the run-up and moves only toward the exit, never back; at the gore it follows the
    exit lane onto the ramp, with no line across the gore. The tints behind the car go piece by piece,
    and the amber ends at the car: behind it the lane is plain asphalt. With traffic the lit lane
    takes the traffic's colour; note how a jam reads on the dark asphalt by day.
56. **The card and the map name the same lane.** The card's lit lane is the lane lit on the map, and
    its words name it. At an exit with an exit-only lane that the point before it does not list: the
    card shows that lane hollow, as an outline, with the lane beside it lit and no words until the
    exit-only lane is there; then the words ("2nd from right" and the like), whole, and the card's
    height does not change. The map draws that lane without a line beside it until then, and never
    lights it. The `lane_row` lines: `~` on the outline lane, `odd` `opens_ahead` while the words
    waited; the `lane_agree` lines `agree` true.
57. **The swap, with Large in Smooth follow.** When the close view has settled for the lanes, the
    route line over the lit lane goes at once and the amber lane is the route there; when the view
    lets go, the line is back at once. Never a fade, a half-transparent line or a blink while the
    view rests. At a peak speed of about 34 m/s (76 mph) and over, in Mapbox tuned and with Default
    the line stays, opaque, in the lit lane. The camera lines' `routeLine`: `swapped` true while the
    close view holds with lanes wide enough, `swaps` 1 in the second that changes, 0 else. It is the
    camera's state and shows only over a lit lane: a junction view on a street sets it too.
58. **The arrow at the gore.** The white maneuver arrow of the exit runs along the lit lane from just
    before the gore onto the ramp, not across the lanes; at a split on the ramp the same. With another
    choice of Lanes on the map it is Mapbox's arrow as before.
59. **An exit no lane open to every car leads to** (its lane lies beyond a car-pool lane). No lane
    is lit before the gore and the card has no words (`odd` `not_named`; its `lane_agree` lines say
    `why` `no_lane`, as they should); the line keeps to the road's line to the gore and across it,
    over asphalt without any line of the app's from where it turns off until it is in the ramp's
    lane, and the arrow there is Mapbox's. On the ramp the lit lane begins where the line moves into
    its lane.
60. **The car.** Over the drawn lanes in a close view, once the lit lane shows in the route line's
    place: a blue bar with a white rim across all the drawn lanes and a small forward tick on each
    outer apron, lying flat on the road and reading as paint across it, not standing up or floating;
    its width follows the road's, so a lane added or dropped widens or narrows it. Everywhere else
    Mapbox's dot with its bearing arrow, as before, by day and at night. The bar and the dot glide
    with the car, with no stutter. If the car freezes and Mapbox's puck comes back after about a
    second, a `car_layer` line says `carLayerFallback` `no_position`: the puck's position did not
    come while it was invisible, and the bar needs another source. A camera style or lane zoom
    change during the drive, and Settings opened and closed: the car stays as it is, with no flash
    of the puck. The log: `car_layer` `own` `start` after Start and `sdk` `guidance_end` at the end,
    no `fallback`. In the car the car's map does the same on its own (`car_layer` with `screen` `car`).
61. **An exit whose ramp splits soon after the gore.** The close view stays through the ramp to the
    split, the card shows the split's lanes from the gore on, the lit lane stays on the ramp's lane
    for it, and the view lets go about 2 s after the split. On the ramp the `lane_row` lines have
    `runUp` the exit whose gore was passed and `key` the split's row, and the camera lines' `runUp`
    the exit's key until past the split. A split farther than about 15 s of driving after the gore:
    the view goes out about 2 s after the gore, as before.
62. **Where the run-up begins.** Where the lanes narrow well before the exit, the close view, the
    card's row and the lit lane come in together, earlier than before, and stay to the gore with no
    in and out between. `lane_row` with `start` `seam`; the route's `lane_plan` with the run-up's
    `seam` (check 48).
63. **An exit with a road without lane data before it on its side.** The lit lane stays one lane in
    from the edge until that road is passed, then moves to the edge once; the card has no words
    until it is passed (`odd` `unknown_ahead`).
64. **A reroute in the run-up, a look switch, the setting.** A reroute: Mapbox's line and no lit lane
    until the new route's path and lanes have loaded (`route_line` `loaded`, `lane_drawing` `route`),
    never the old route's lanes lit; the close view and the card's row stay, the card may dim for a
    moment, and its words may wait a moment for the new plan. A change of Day and night in Settings
    during the run-up: the lanes take the new look's colours at once, nothing else moves (a change by
    the sun waits for the close view to end, as before). Lanes on the map changed to another choice
    during a drive, parked or by a passenger: the lanes and the bar go at once, Mapbox's puck is back
    (`car_layer` `sdk` `switch`), and the next `map_layers` line has no `lanes`.
65. **Bridges and trees.** Where a road crosses over the route, note whether the drawing lies on top
    of the bridge; at the tilt, whether trees stand over the paint.
66. **The cost.** The camera lines against a drive with Off: `render` (`p95Ms`, `missedVsync`),
    `renderFps` and, with Smooth follow, `camMaxGapMs` no worse. `lane_drawing`: `buildMs` and
    `loadMs` a few tens of ms at most, `bytes` under 100,000. Any `route_line` or `car_layer`
    `fallback` is worth a note with where it happened. The measuring drive below measures it
    properly.

The measuring drive, with **Settings → Debug → Measure the drawn lanes** on as well: a real drive with
Large and Smooth follow, with several freeway exits and turns, by day or at night, on the phone alone
([CAMERA.md](CAMERA.md), Measuring the drawn lanes). Only the phone's map measures, and only while it
is on screen: while the car's screen shows the map the phone shows the list and measures nothing,
and with **Map** pressed on the phone its map draws at most 60 frames a second, which would mix two
kinds of frames into the gate.

67. **Every other close view drawn.** The first close view of the drive with the lanes, the next
    without them (the app's route line in its lane, no lanes and no lit lane), and so on, run-ups and
    junction views counted together. One `lane_measure` line at the end of each: `view` counting from
    1, `drawn` true on the odd ones, `kind`, `look`, `seconds`, `ms` (how long the view lasted),
    `frames`, `p95Ms`, `maxMs`, `missedVsync`, `uploadMaxMs`, `end` mostly `let_go`, and `counts` true
    only on a whole view: one its close view let go (`end` `let_go`) after 3 s or more (`ms` 3000 or
    more), with frames, begun 5 s or more after the drawing was shown or hidden. A view that the
    drive's end, Settings, the background, the list, the switch, Lanes on the map, the camera style or
    the map made again cut short says `counts` false however long it was, and so does a shorter one.
    The first run-up's line has `programsMs`, the time spent making graphics programs: whether the
    drawing's are made there.
68. **Mapbox's lane details under the drawing,** where Mapbox has lane details: one `lane_measure`
    line with `check` `details_under_drawing` at the start and a `lane_details` line `setting` with
    `wanted` true. Do their markings draw below the app's asphalt where the app draws, and show
    where it does not? How do they meet the drawing at its ends, the seams? The answer decides
    whether Drawn by the app keeps them on.
69. **The gate.** After the drive, the `trip_summary` line's `laneGate`: `gate` `pass`, `fail` or
    `too_few_views`, with each look's counts and medians. Six counted views of each kind in one look
    take about a dozen close views; with fewer it says `too_few_views`, and another measuring drive
    is needed. The app's drawing becomes what a phone starts with only after a pass and the owner's
    look ([DECISIONS.md](DECISIONS.md), 39 and 52).

Lane counts from Mapbox's electronic horizon, with **Settings → Guidance → Lane counts between lane
points** on, as it is unless turned off, on a drive with freeways and Drawn by the app
([DECISIONS.md](DECISIONS.md), 54; [CAMERA.md](CAMERA.md), The app's own lanes):

70. **Under which reading the counts agree.** One `horizon_lanes` line for each lane point ahead,
    each point once: `convention` `all` where the horizon's `count` is every lane the point lists
    (`lanes`), `general` where it is the lanes open to every car (`general`), `none` where it is
    neither; `matched` false where no edge of the horizon ends within 20 m of the point, and on
    every line `endM`, how far the nearest edge end lies past the point. At the end the
    `trip_summary` line's `horizon`: `points`, and of them `agreeAll`, `agreeGeneral`, `disagree`
    and `noCount`.
71. **Car-pool lanes and one direction.** At a point that lists a lane for car pools, whether the
    line says `all` or `general`: whether the horizon counts that lane. On a divided freeway,
    whether `count` is about the point's `lanes`, with `oneWay` true, or more: whether it counts
    one direction or both.
72. **How much more of the road gets lines.** The `lane_drawing` lines: `horizonM`, the metres of
    each window that the horizon's counts drew, and `horizonZones`; a window that went with the
    route's path built anew has `reason` `horizon`. Against the stretches without lane data in
    `lane_evidence` (check 43). On the map: lane lines between two lane points where there were
    none, asphalt without lines where the count changes, and a lane added on the side away from an
    exit opening where the count rises (never one kept for car pools on a count of the lanes open
    to every car). Between two lane points far apart the lines begin 40 m past the window on screen
    at the send whose update first reaches past the second point; what that window held stays as
    it was. Never a lane lit, tinted or named from them: the card and its words as with the switch
    off.
73. **Nothing moves where the driver looks.** When a window with `reason` `horizon` loads, the line
    and the lanes on screen stay as they were: no jump of the route line and no lane coming or
    going near the car, and Mapbox's line never shows for it. After a seek of a simulated drive the
    window at the car goes as before, `reason` `ahead`, and the counts come with the one after it.
74. **The data use with and without.** Two like drives, one with the switch off: `trip_summary`
    `receivedBytes`, with `horizon` `on` true and false; `updates` about one a second while the app
    listened (`listenedS`), and `edges`, the edges whose details were read; `stretchesUsed` and
    `windows`, what the app's lanes took from it, counted on each map that drew them, the phone's
    and the car's.
75. **The switch during a drive,** parked or by a passenger. Off: no more `horizon_lanes` lines and
    no `ehLanes`, `switched` 1 in the summary, and the windows after it take nothing new, while what
    was drawn stays until the next route. On again: `ehLanes` within a second or two, and
    `horizon_lanes` for the points not compared yet.
76. **The car's edge, and what the horizon costs the phone.** On freeways the `progress` lines'
    `motion` has `ehLanes`, the horizon's count of lanes on the edge the car is on; nothing on the
    screen uses it, as nothing shows the car's lane ([DECISIONS.md](DECISIONS.md), 39). With
    **Measure the drawn lanes** on, the `progress` lines have `horizon`: `updates`, `ms` and
    `maxMs`, the app's observer on the main thread since the line before, a few milliseconds at
    most. A failure: one `error` line with `where` `horizon`, `failed` true in the summary, and the
    drive goes on without the horizon.

Stage 2's step 1d ([STAGE-2.md](STAGE-2.md), Step 1d (built)): the car's frames and its lighter map
while the phone is hot, with the car screen (every car line has `screen` `car`); the card's lanes two
miles before an exit and the measuring's whole views, on the phone too.

77. **The car's frames with the phone locked.** A drive with the car, real or simulated, and the
    phone locked, its screen off, for ten minutes or more: while the car moves, the car's camera
    lines draw the target, `render.drawnFps` about 30 at the target 30, with `maxFps` 30 and
    `capFrom` `display` on a phone at 60 Hz, and `car_surface` lines with `event` `fps` are rare:
    none after the first seconds of a plain drive, and any that comes has `drawnFps`, the frames
    drawn that moved the cap. Then a few minutes with the phone's screen on: the same. Share the
    car's camera lines and every `car_surface` line.
78. **A phone at 120 Hz,** if there is one: `maxFps` 15 with `capFrom` `display` before the drive;
    within a few seconds of guidance about 19 with `capFrom` `drawn` and one `car_surface` `fps` line
    with `drawnFps` under nine tenths of the target; the camera lines' `render.drawnFps` about 30 from
    then on.
79. **A hot phone.** On the charger in the sun, or a long drive with the car: when a `thermal` line
    says `MODERATE` or worse, one `car_light` line with `on` true and that `thermal`, the car's map
    without its 3D buildings and trees, and the car's camera lines with `light` true; the phone's own
    map keeps them. Once the phone has cooled (a `thermal` line `LIGHT` or `NONE`), two minutes later
    a `car_light` line with `on` false and the 3D objects back on the car. Say whether the car's
    `render.drawnFps` and `render.p95Ms` were better while light. While light, Day or Night chosen by
    hand before the drive, or Auto at dusk: the car's map stays without its 3D objects.
80. **Lighter car map when hot.** **Settings → Debug** shows the switch on; off and on again write
    `settings` lines with `car_light_when_hot`. Turned off while the car's map is light, the full map
    at once and a `car_light` line with `on` false; while off, a hot phone writes no `car_light` line
    with `on` true. A drive without the car: no `car_light` line and no `light` in the phone's camera
    line.
81. **The card's lanes two miles before an exit.** A drive whose freeway exit comes more than two
    miles after the maneuver before it (a simulated drive will do): while the exit is farther than two
    miles, the phone's card and the car's routing card show no lanes, and one `lane_row` line says
    `source` `none` with `far` true and `metersAhead`; at 2.0 mi on the card the exit's own lanes come
    on both screens at once, with a `lane_row` line `source` `maneuver` and `metersAhead` about 3219.
    A lane choice's row on the way and the run-up's row show as before. The same at a turn more than
    two miles after the maneuver before it.
82. **Only whole views count.** On the measuring drive (checks 67 to 69): every `lane_measure` line
    has `ms`; a view that ended `let_go` after 3 s or more, with frames and begun 5 s or more after
    the drawing was shown or hidden, says `counts` true; a shorter one, and one that the drive's end,
    Settings, the background or the list cut short, says `counts` false. The trip summary's `laneGate`
    `counted` is the number of lines with `counts` true.

The side roads' fields, with **Settings → Debug → Side-road fields on routes** on (off unless turned
on): the route requests the app makes anyway, and the reroutes inside a drive, then also ask Mapbox
for the access (whether a road is one-way, and which way) and the form of way of every road at each
intersection of the routes, two more parameters on the same requests and never a request of their
own. Nothing on the screen uses them: they are measured first. The measuring drive is a real drive
through streets of ten minutes or more, with one reroute:

83. **Asked and served.** Turn the switch on before planning the route (a `settings` line with
    `side_road_fields` true), then plan it: its `route_request` line says `linkAccess` and
    `linkFormOfWay` true, and after its `route_response` line comes one `side_roads` line for each
    route of the answer, `reason` `preview`. Press Start: one more for the route the drive starts
    on, `reason` `start`, after its `lane_evidence` and `lane_plan` lines. On each, `withAccess` and
    `withFormOfWay` close to `intersections` mean that Mapbox serves the fields; 0 with `linkAccess`
    true means it does not. No request is added: one `route_request` for each Directions press and
    at most one with `inTrip` in a drive, as before, and one trip more in **Settings → Debug** after
    the drive.
84. **Kept by a reroute.** Leave the route once, where it is safe to: the `reroute_options` line
    says whether the navigator's request for the new route still asked for the fields (`linkAccess`
    and `linkFormOfWay` true: kept) and `linkAdded`, how many of the two the app added to it (0
    where the navigator kept them, 2 where it lost both). The new route's `side_roads` line,
    `reason` `reroute`, says whether the reroute's answer brought them (`withAccess`).
85. **What they cost.** On each `side_roads` line, `jsonBytes` is the route as the app holds it, in
    bytes of uncompressed JSON, and `fieldsBytes` the part of it that is the two fields: `jsonBytes`
    less `fieldsBytes` is the same route without the switch, and `growthPct` how much larger the
    switch makes it, in percent. Start and the reroute should feel as before, and there should be no
    `error` line with `where` `side_roads`.
86. **What they say, and the switch off.** `streetOwnRoad` counts the road the route leaves each
    street intersection on, `oneWay` or `twoWay` by its access, to hold against the streets driven;
    `streetManeuvers`, of the turns at street junctions (`total`), those whose approach had an
    access value (`withAccess`); `access` and `formOfWay` every road at every intersection by its
    value. Then turn the switch off: from the next request on, `route_request` says `linkAccess`
    false, no `side_roads` line comes, and a reroute says `linkAdded` 0 (a reroute of a route that
    asked for them may still ask: the app never takes a field away). Turned on during a drive, the
    switch reaches the next reroute.

The answers decide whether the app's own lanes on streets (checks 87 to 96) may take one-way and
two-way from these fields; until then they take it from the electronic horizon's one-way flag alone,
while its switch is on.

The app's own lanes on streets, with **Drawn by the app** and **Lane counts between lane points** on,
on a real drive through streets with turns at signals, or a simulated one: today the electronic
horizon's one-way flag is the only source that lets a street's lanes be drawn, and only from the
second drawing window of a route on ([DECISIONS.md](DECISIONS.md), 52; [CAMERA.md](CAMERA.md), The
app's own lanes). Screenshots from a passenger or a simulated drive, never from the driver:

87. **A two-way street.** Past the route's first 3 km, at a street with lane data: the route's lanes
    to the right of the road's line, plain asphalt over the oncoming half, no centre line, no line of
    the oncoming lanes, no double line, no crosswalk. Closing on a turn with Large in Smooth follow:
    the junction view, the lane to take lit, the swap at about 100 to 130 m before the turn (on the
    street's own lane width: the amber about as wide as the line it takes over, not visibly
    thinner), the car a bar across the route's lanes only with its tick on the outer side, and the
    card's row lighting the same lane as the map. By day and at night. That window's `lane_drawing`
    line: `streetM` above 0; the turn's `lane_agree` line: `source` `maneuver`, `agree` true.
88. **A one-way street.** The lanes about the road's line with an edge line on each side, and the
    bar with a tick on each side. Note whether the street is one-way where the drawing has it so.
    Where the horizon tells one street of a road otherwise than the road's first, that street is
    left to the map (`streetMixed` above 0): note where. Where a street goes on onto a freeway
    without a turn, the freeway's lanes about its line with both edge lines, and a two-way street
    before it left to the map.
89. **A turn pocket.** Before a turn whose lane list adds a pocket: the pocket drawn from where it
    may begin, with no line beside it until it is known, at full width before the stop line, and the
    lit lane moving into it along its taper, never back.
90. **A stop line at a signal.** A white line across the route's lanes 10 m before each junction
    where the route's flags give a traffic signal or a stop sign, none where they give neither. At a
    stop sign, whether it stands on the route's own approach: the flag is the junction's, and which
    approach it is for is not checked.
91. **A lane for bicycles.** A narrow strip at the edge beside a solid line, never lit or tinted; in
    the card's row dim, never lit.
92. **A U-turn.** The lit lane up to the junction, the app's line on into the junction and back where
    Mapbox's maneuver arrow turns, not short of it, and no lane lit on the road back.
93. **A roundabout.** Nothing of the app's inside it, also where the route lists lanes on the way
    round or a ramp leads in, the approach asphalt without lane lines, the road out drawn from past
    the way out, and the card's row the navigation SDK's.
94. **A junction behind the car.** By day, past a junction the lit lane crossed or turned through: no
    dark band left on the map's road there, and no amber left behind the car for more than a moment.
95. **A street without a one-way source.** In the route's first 3 km, or with **Lane counts between
    lane points** off before the route is planned: nothing of the app's on streets, Mapbox's road and
    the usual line, the card's row as before. Each `lane_drawing` line: `streetM` 0, and where the
    window holds streets with lane data, `streetNoOneWay` above 0 with their metres in
    `streetNoOneWayM`. On a drive through streets with the switch on, how those counts fall after the
    first window says how much of the streets the horizon lets the app draw.
96. **The horizon switched off mid-drive,** parked or by a passenger. The streets drawn stay to the
    end of the window on screen; the next window draws no street past it (`streetM` falls,
    `streetNoOneWay` rises), and nothing moves on the screen as it loads.

Then share the debug log. What to read in it:

| Lines | Question |
|---|---|
| Exit reasons and start reason, at the first start | Why the app restarted before the first drive |
| Camera | Frame rate and worst gaps for each style; whether animations ran |
| Device state | Power saving, heat, charging, display rate |
| Connectivity | Which case cut the app off, see below |
| Search results and routes | Stop points by address and by name; how far each point was moved |
| `search_result`: `stopPoint` | Which routable point the app took for each search, its name and its distance from the place; null when it took none |
| `route_request`: `stopPoint`, `targets`, `bearings`, `originFix`, `inTrip`, `linkAccess`, `linkFormOfWay` | Whether routes went to the stop point with the place as target; whether a direction was sent and from what speed; one request for each Directions press, and at most one with `inTrip` in a drive; whether it asked for the side roads' access and form of way, true only with **Settings → Debug → Side-road fields on routes** on |
| `reroute_options` | Whether reroutes keep the arrival target: `targets` null with `targetWanted` true means a reroute lost it. `endMeters` near 0 means it still ends at the same point. `linkAccess` and `linkFormOfWay`: whether the navigator's request for the new route asked for the side roads' fields; `linkAdded`, how many of the two the app added to it, only with the Debug switch on; the app never takes one away and changes nothing else |
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
| `lane_row` | What the card's lane row drew and when it changed: `source` (with `far` true on a row of `none` while the next maneuver's own lanes are held back, more than two miles ahead, and `metersAhead` its distance), the lanes (`row`, `lit`, `des`; `~` in `row` for a lane drawn as an outline, never lit), `words`, `need`, the run-up (`runUp`, `start`, which is `seam` where the lane plan's seam began it) and, where a run-up's row had no words, why (`odd`: `composed`, `pieces`, `unknown_branch`, `opens_ahead`, `unknown_ahead`, `not_named` or `no_fit`), or that a row lit only lanes for car pools or buses (`odd` `designated_only`). On a ramp the run-up holds through, `runUp` is the exit whose gore was passed and `key` the split's row. One to four lines for each run-up; a distance alone writes none |
| `missed_maneuver`, `diverge_taken` | One line for each place the road divided: the step change past an exit, fork or on ramp, or a point passed on the way (`kind`); missed when the navigator found the car off the route within 30 s or 1,000 m. How sure the navigator was (`probAt`, `probMin`, `probMax`, `probLast`, `uncertainS`), how far the phone's positions lay to the side (`sideAt`, `sideMin`, `sideMax`) and what the card showed on the way (`card`, `words`, `runUp`): whether a rule could find a missed exit sooner than the navigator without false alarms |
| `progress`, `off_route`: `match.raw` | Where the phone's own position lay against the matched one: `sideM` to the side (right positive), `aheadM` along the way (about minus the speed, since the navigator puts the car a second ahead), `accM`, `ageMs`, `bearingDiff` |
| `route_response`, `routes_updated`: a step's `lanes` | The lanes of each step's last banner that has them, the one shown near its maneuver |
| Camera: `laneDetails`, `hdRoads`, `hdRoadsReapplied`; `lane_details` | Whether lane details stayed on once set |
| `map_layers` | `arrowAboveLine` true at every style load: the maneuver arrow is drawn above the route line. `middle` lists the order of the route's layers. While the app draws its own route line, `ownLine` true: its casing and line lie above Mapbox's route line and below the arrow. With **Lanes on the map** at **Drawn by the app**, `lanes` true: the drawing's layers lie in their order below Mapbox's route line, and the lane arrows above the app's route line and below the maneuver arrow |
| Camera: `bendM`, `bendDeg`, `extraPts`; step fields `bendM`, `bendDeg`, `bearingBefore`, `bearingAfter` | Where the road bends past each maneuver point, and whether the junction frame held it: how often a step begins well before its bend, and at what angle, to check the rule's 20 degrees and 4 m |
| Progress: `card` | `pending` only in the first second or so after a new route, never for long; `kept` for a second now and then |
| `trip_session_reset` | One after each drive's end: the navigator forgot where the drive left the car. `ms` is how long that took |
| Progress: `staleStart` | An update at the start of a drive that still carried where the drive before ended; nothing on the screen and no start plan acted on it. None are expected after a `trip_session_reset`. After a simulated drive, the `match` of such a line says `replayed` true |
| `export_error` | Only in a shared file: a part of that day could not be read to its end (`error` says why), and the lines before it are what could be read of it. The other days are complete |
| `trip_summary` | One for each drive: `trip` (the session id of `session_state`), `simulated`, `ending`, `seconds`, `meters`, `reroutes`, `offRouteEpisodes`, `firstProgressS`, `longestGapS`, `mapErrors`, `offlineSeconds`, `staleStart`, `staleUpdates`, `receivedBytes`, `batteryUsedPercent`, and `horizon`, what the drive read from Mapbox's electronic horizon (its own row). With `ending` `process_death` the numbers are those of the last minute before Android closed the app. On a drive the measuring switch measured close views of, `laneGate`: `gate` (`pass`, `fail` or `too_few_views`), `views` and `counted`, and for each look that had a view (`DAY`, `NIGHT`) its own `gate`, the counted views with the drawing and without it (`drawn`, `notDrawn`), the medians of their render p95 (`drawnP95Ms`, `notDrawnP95Ms`) and of their display frames missed a second (`drawnMissedPerS`, `notDrawnMissedPerS`), and the longest upload in a view with the drawing (`uploadMaxMs`) |
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
| `settings` with `lanes_on_map`, `own_route_line`, `measure_own_lanes`, `side_road_fields`, `lane_counts_horizon` | Each change of Lanes on the map (`off`, `mapbox` or `app`), also the one carry-over of the earlier switch Lane details on the map, of the three Debug switches App's own route line, Measure the drawn lanes and Side-road fields on routes, and of Lane counts between lane points |
| `lane_evidence` | One for each route a drive uses: right after `routes_committed`, and after each `routes_updated` that brings a new route (a reroute, another route taken, the start's replan), never after a refresh. Numbers only, about 700 to 800 bytes: `routeId`; `lengthM`, along the steps' shapes; `steps`; `lanePoints`, the intersections with lane data by the road that leads to them (`freeway`, `ramp`, `street`); `freewayM`, `rampM` and `streetM`, the whole metres of each kind of road whose lanes are `known` (from a point with lane data back to the intersection before it, at most 1,000 m on freeways and ramps and 250 m on streets), `bridged` (between two points of one carriageway with the same lanes, across roads that join or leave on the exit's side; on streets only up to 400 m apart) or `none`; `gaps` without lane data between two points of one carriageway that are not bridged: `count`, `m`, `inWindow` and `inWindowM` (those reaching into a card window, a freeway exit's run-up or the 180 m before a street maneuver, and their metres inside it), `shortOutside` (at most 600 m and outside every window); `unexplained`, changes of the lane count between two points that nothing on the way explains; `exitSideAdded`, the lanes a point lists that the point before it does not (`count`), and of them `sideUnknown`, those whose arrows do not tell their side; `unknownExits`, roads without lane data that leave on the exit's side inside a bridge; `windows`, the run-ups (`runUps`) and street maneuvers (`street`); `streetTurns`, the turns at junctions off freeways, `withLanes` and `withoutLanes`; `designated`, the lanes the points list for each kind (`hov` for car pools, `bus`, `taxi`, `motorcycle`, `moped`, `bicycle`, `other`); `noLanes`, true for a route without any lane data. Then, from the route's lane plan (left out where it could not be made): `zones`, changes of the lane count that nothing on the way explains, drawn without lane lines; `zonesBridged`, stretches without lane data between two drawn ones, drawn the same way; `possibleLanes`, lanes on an exit's side that may already be there before the point that first lists them; `aligned`, changes of the lane count whose side a road, the arrows or the car-pool lanes told; `noneM`, the whole metres left without anything of the app's own lanes. Together: how much of a route the app's own lanes could draw, and how often a gap falls where the card names a lane |
| `lane_plan` | One for each route a drive uses, right after its `lane_evidence` line: the lanes the app takes, in numbers only; the card's run-up row, the run-up's start and the ramp it holds through read them, and with Drawn by the app the drawing. `routeId`; `ms`, how long reading the route and making its plan took; `windows`, in route order, the stretches where the plan names a lane and holds lane data, and the approaches where no lane can be named: `kind` (`run_up`; `ramp`, from a gore along the ramp to a split or point with lane data no more than 15 s of driving after it, which the plan names a lane for; `street`; or `approach`, an exit no lane open to every car leads to), `key` (the card's own key of its exit or point, as in `lane_row` and `lane_choice`) and `fromM`, where it begins, in metres from its end (the gore, the split or the junction, which every distance of the entry counts from, negative before it); a run-up's `seam`: `latchM`, where the latch would begin the run-up on its own, `narrowingM`, where the plan's lanes first narrow for a point within reach, and `earlierM`, how much earlier the run-up begins at the seam, where it begins now; `points`, the lane points in it (a ramp's after its gore): `atM`, `lane`, the lane the plan names there counted from the edge of the exit or fork ahead (0 at the edge, lanes that may already be there counted), and `amber`, how many lanes still work there; `moves` of the named lane: `atM` before the point it is made for, `from` and `to` counted from the edge, and `back` where it moves away from the edge, which the plan does only where the road leaves no other way (the named lane leaves the lanes that work, and no lane of them could get there otherwise); `tightM` and `conflictM`, how long the lanes that work allow only the latest lane changes, or none; `unknownExits`, the roads without lane data on the exit's side before it, and `unknownExitDropped`, where the plan lit the edge lane before them all the same; `conflict` (`designatedOnly`: only a car-pool lane leads on; `designatedBetween`: the lanes to take lie beyond one) or `roundabout`, where no lane is named; `possibleLanes` and `zones` in it. After a change of route: `replanMoves`, 1 where the lit lane would move once the new plan takes over, 2.5 s of driving ahead of the car, and `replanKept`, true where the old count from the edge was kept because both routes run on the same carriageway at the car. `odd`, over the route: `activeOutsideValid`, lanes the route prefers but says cannot be used, and `arrowContradicts`, lanes it can be followed from whose one arrow points away from the exit. About 400 to 650 bytes for a route with one freeway exit |
| `side_roads` | Only with **Settings → Debug → Side-road fields on routes** on: one for each route of a preview's answer (`reason` `preview`, after `route_response`), and one for each route a drive uses, after its `lane_evidence` and `lane_plan` lines: `start`, `reroute` (the new route of a reroute) or `new_route` (the start's replan, another route taken); never for a refresh. `routeId`; `linkAccess` and `linkFormOfWay`, whether the route's own request asked for each field (null where the route holds no request); `intersections`, and of them `withAccess` and `withFormOfWay`, how many came with each field; `access`, every road at every intersection by its value: `none` (0), `forward` (-1), `backward` (1), `both` (2), `other` (a value the Directions models do not list) or `missing` (which way `forward` is, Mapbox does not document); `formOfWay`, the same by its word (`freeway`, `multiple_carriageway`, `single_carriageway`, `roundabout`, `ramp`, `service_road`, `pedestrian_zone`, `unknown`, `other` or `missing`); `streetManeuvers`, the turns at junctions the camera frames as a street junction (`total`), and of them those whose approach road had an access value (`withAccess`); `streetOwnRoad`, at each intersection a street leaves, the road the route leaves on: `oneWay` (forward or backward), `twoWay` (both), `none`, `other` or `missing`; `jsonBytes`, the route as the app holds it, in bytes of uncompressed JSON (null where it could not be measured, with an `error` line, `where` `side_roads`); `fieldsBytes`, the part of it that is the two fields, so that the same route without the switch is `jsonBytes` less `fieldsBytes`; `growthPct`, how much larger they make the route, in percent of it without them, to one decimal. Counts, sizes and kinds only, about 700 bytes, a few a drive |
| `lane_agree` | One each time the card begins to show a lane row at a point with lane data, once the plan of its route is there: `source` (`run_up`, `choice` or `maneuver`: a maneuver's own row at an exit or a fork on a freeway, or at a maneuver on a street, where the row is read from the plan), `key`, `aheadM`, how far ahead of the car the point is; `lit`, the lanes the card lights, from the left (on a run-up the row read from the plan, outlines never lit); `planLit`, the lane the plan names there, and `amber`, the lanes that still work there; `agree`, and where they do not, `why`: `lit` (the card lights one lane, the plan another), `amber` (the card lights several lanes, or the plan names none there, and they are not the lanes that work), `not_named` (one lit lane where the plan names none and more work), `no_lane` (no lane can be named on this approach: only a car-pool lane leads on, the lanes to take lie beyond one, or a roundabout), `row` (the row's lanes are not the point's), `no_point` (not a point of the plan) or `words` (the card names the lanes in words where the plan would wait); then what the words depend on: `opensAhead`, a lane on the exit's side opens or may begin between the car and the point, or the lit lane's count from the edge changes; `unknownAhead`, a road without lane data on the exit's side still lies ahead on the step; `exact`, the card's own count leaves no doubt; and `outline`, the point's lanes the app's drawing would not show at the car yet. About 250 to 300 bytes; one to four for each run-up |
| `progress`: `motion` | Only while guidance runs on a freeway (the intersection the car passed last leads onto a motorway or a trunk road, as for the lane choices), on every `progress` line, held ones too; off freeways the field is left out. The phone's motion since the line before, in numbers only: `n`, the gyroscope's samples (about 25 a second), and `ms`, from the first of them to the last by their own times; `yaw`, the mean turn rate about the vertical in degrees a second, a right turn positive (the way a bearing grows), and `yawSd`, its standard deviation over the samples; `fwd` and `side`, the mean acceleration in the level plane with gravity taken out, in m/s², along the reference direction and 90° clockwise of it, to the right positive like `match.raw`'s `sideM`; `ref`, the reference direction: `back`, the level direction of the phone's back (its camera side) while the back points at most 45° above or below the level, as in a windshield or vent mount upright or turned sideways, else `top`, that of its top edge, for a phone lying about flat; `tilt`, how far that axis points above the level, in whole degrees, negative below (a phone leaning back 30° in its mount: `back`, `tilt` -30). With the back facing the road ahead, `fwd` is the car's acceleration along its way and `side` its sideways acceleration, within the mount's own small angle. A field is null when a sensor it needs gave nothing in the window: the first line on a freeway has `n` 0 and nulls. In a simulated drive the phone is at rest. About 100 bytes a line, 0.36 MB an hour on freeways. For research only (the owner's answers above, the lane the car is in): nothing on the screen uses it |
| `route_line` | Only while the app draws the route line, with **Settings → Debug → App's own route line** on or **Lanes on the map** at **Drawn by the app**: one at each change of who draws the route line. `drawer`, `sdk` (Mapbox's line) or `own` (the app's); `reason`: `start` (the own line begins with the drive's route: at Start, on a map made anew, or with the switch turned on during a drive), `route_change` (a reroute or another route taken), `loaded` (the own path has loaded and replaced Mapbox's line), `style_load` (the map's style loaded again), `switch` (the switch or Lanes on the map changed during a drive), `fallback` or `guidance_end`; `route`, which of the drive's routes it is, counted from 1. With `loaded`: `swapMs`, from the change to the own line drawn, `buildMs`, how long making its path took off the main thread, `loadMs`, from handing the path to the map until the map had loaded it, `points`, the path's points, and `version`. With `fallback`: `ownLineFallback`, `source`, `exception` or `no_data`. Numbers and words only, about 90 to 160 bytes |
| Camera: `routeLine` | Only while the app draws the route line: `own`, whether the app's line is drawn; `trims`, its trims that second; `trimLagM`, the largest distance along the route between its trimmed end and Mapbox's vanishing point (0 while it follows Mapbox's point, more where it went on by its own projection of the puck); `trimMaxMs`, the longest the app's part of a puck update took on the main thread. About 70 bytes more a second, 0.25 MB an hour. With **Drawn by the app** also `swapped`, whether the lit lane takes the route line's place over the lit stretches (the camera's state: a close view holds and a drawn lane is wide enough, also where no lane is lit), and `swaps`, how often that changed in the second |
| `lane_drawing` | Only with **Lanes on the map** at **Drawn by the app**: one for each window of the app's own lanes that loaded. `route`, which of the drive's routes, counted from 1; `window`, counted from 1; `reason`: `route` (the first window, with a route's path), `ahead` (the next one as the car drives on), `horizon` (the next one, sent with the route's path built anew with the lane counts of Mapbox's electronic horizon past the window on screen) or `style_load` (sent again after the map's style loaded); `lengthM`, the window's length along the route; `features`, `coordinates` and `bytes`, the GeoJSON handed to the map; `buildMs`, how long making it took off the main thread; `loadMs`, from handing it to the map until the map had loaded it; `budget`, true within 600 KB and 20,000 coordinates; `horizonM`, the metres of the window whose lines or zones come from the electronic horizon's counts, and `horizonZones`, its zones there; `streetM`, the metres of streets whose lanes it drew, `streetNoOneWay`, the stretches of streets with lane data it left to the map for want of a source that tells whether they are one-way, with their metres in it in `streetNoOneWayM`, and `streetMixed`, those left so because a source told otherwise of them than of their road's first street. An empty window after an empty one loads, and gets a line, only where it holds such stretches. Numbers and words only, never a shape or a place; about 250 bytes, a few a drive |
| `horizon_lanes` | Only with **Settings → Guidance → Lane counts between lane points** on, during guidance: once for each lane point of the route that an update of Mapbox's electronic horizon covers, from the car on, once it reaches 20 m past the point. `key`, the lane choices' key of the point, null where it has none; `toGoreM`, metres to where the route next turns as the lane plan counts it; `aheadM`, from the car; `road` (`freeway`, `ramp` or `street`), the road that leads to the point; `lanes`, the lanes it lists, and `general`, those of them open to every car; `matched`, whether an edge of the horizon ends within 20 m of it, and `endM`, where the nearest edge end lies past it (before it below zero), to 0.1 m; of the matched edge `count`, the horizon's lanes (null where it gives none), `motorway`, `ramp`, `oneWay` and `roadClass`; `convention`, the reading under which the count agrees with the list: `all`, `general` or `none`. Counts, metres and words only, never an edge's id, a shape or a road's name |
| `trip_summary`: `horizon` | Every drive: `on`, the switch Lane counts between lane points as the trip began; `switched`, how often it changed in the trip; `listenedS`, how long the app listened to the electronic horizon; `updates` of its position, and of them `notAvailable` (off the road, or unsure where the car is); `edges`, how many edges' details were read; the lane `points` compared, of which the count agreed with every listed lane (`agreeAll`), with the lanes open to every car (`agreeGeneral`), with neither (`disagree`), or had none to compare (`noCount`); `stretchesUsed`, the horizon's stretches between two lane points it agreed with that a path of the app's lanes took past the window on screen, each pair of points once a route; `windows`, the drawing windows that loaded with lines or zones from its counts, on each map that drew them; `failed`, whether reading it failed |
| `progress`: `motion.ehLanes`, `horizon` | `ehLanes` in the motion object, on freeways while the app listens to the electronic horizon: its count of lanes on the edge the car is on, null where none is known; nothing on the screen uses it. With **Measure the drawn lanes** on, `horizon`: the app's observer of the horizon on the main thread since the line before, `updates`, `ms` in all and `maxMs` the longest |
| `error` with `where` `horizon` | Reading the electronic horizon failed: the kind of error and its stack, never its message, and the rest of the drive goes on without it. Where a drawing with its counts could not be made, an `error` line with `where` `ownRouteLine`, and the route's windows go on without them |
| `car_layer` | Only with **Lanes on the map** at **Drawn by the app**: one at each change of who draws the car. `drawer`, `own` (the app's own layer: the bar over drawn lanes in a close view, else the dot) or `sdk` (Mapbox's puck); `reason`: `start` (guidance began on a map that was there before it), `scene` (a map made during a drive, as the car's surface or the phone's map made again), `switch` (Lanes on the map changed during a drive), `guidance_end` or `fallback`; with `fallback` also `carLayerFallback`: `no_position` (a fix the puck was given had no position after it for a second while the watchdog counted) or `exception` (the style refused a call; with an `error` line, `where` `carLayer`). After a fallback the drive goes on with Mapbox's puck; the next drive tries again. Words only, about 100 bytes, a few a drive |
| `car_light` | Only with the car screen, `screen` `car`: one at each change of the car's lighter map, `on` (whether it draws without its 3D objects) and `thermal` (the phone's thermal status by Android's name, null while none was read). From `MODERATE` on it is light, after two minutes in a row of `LIGHT` or better full again; the car's camera line has `light` beside it. With **Settings → Debug → Lighter car map when hot** off (`settings` `car_light_when_hot` false) it is never light. Words only, a few a drive at most |
| `lane_measure` | Only with **Settings → Debug → Measure the drawn lanes** on and Drawn by the app, from the phone's map: one as the measuring begins, `check` `details_under_drawing` with `on` true (Mapbox's lane details under the drawing for the drive), then one at the end of each close view. `view`, counted from 1 in the drive; `drawn`, whether the drawing showed (the odd views); `kind`, `run_up` or `junction`; `look`, `DAY` or `NIGHT`; `seconds` and `frames`; `ms`, how long the view lasted, from the update it began on to its end; `p95Ms` and `maxMs`, over every frame time of the view; `missedVsync` and `missedPerS`; `uploadMaxMs`, the longest upload of a frame in Mapbox's statistics; `programsMs` and `programs`, the time spent making graphics programs and how many there are, as Mapbox's statistics give them (whether a sample counts since its own start or the map's is not verified); `windows`, `windowLoadMaxMs` and `windowBytesMax`, the drawing windows that loaded in the view; `carPositions`, the positions handed to the car's layer; `hdRoads`, what the style said of Mapbox's lane details as the view began; `end`: `let_go`, `guidance_end`, `switch`, `setting`, `background`, `covered`, `away`, `camera_style` or `released`; `counts`, true only for a whole view, one with frames, begun 5 s or more after the drawing was shown or hidden, that lasted 3 s or more and ended `let_go`, and false for every other. Numbers and words only, about 390 bytes, one a close view |
| `motion_sensors` | One each time the sensors are asked to start, as a drive comes onto a freeway: whether the phone has a gyroscope (`gyroscope`), a gravity sensor (`gravity`) and a linear acceleration sensor (`linearAcceleration`), the period asked for (`periodUs`, 40000: 25 samples a second, each delivered at once) and whether listening `started`. They stop at the first update off the freeway and when the drive ends. Without a gyroscope or a gravity sensor nothing is registered: one line a drive with `started` false, and no `motion`. Without linear acceleration, `fwd` and `side` stay null |

Reading the connectivity lines:

| The log shows | Meaning |
|---|---|
| No network, no other network available, and no SIM or Mobile data off | The phone had no mobile data |
| No network, but a mobile network available | Mobile data existed, but Android kept it from this app, for example Wi-Fi only |
| Mobile, blocked | Data saver, background data or battery limits |
| Blocked only while the `activity` lines show the app stopped, and unblocked when it starts again | Android's limit for apps in the background (Android 15, Data saver, Battery saver); no fault, and the chip leaves it out |
| Not validated | A network without internet |
