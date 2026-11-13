# Stage 2: Android Auto

The design of the car screen as decided so far. It is built after the next phone build (stage 1.1
in [ROADMAP.md](ROADMAP.md)), which brings the Smooth follow camera, the stop point and the crash
diagnostics that this stage builds on. The owner's choices and their reasons are decisions 30 to 36
in [DECISIONS.md](DECISIONS.md); the car camera is in [CAMERA.md](CAMERA.md).

The stage is built in steps (DECISIONS 49). Step 1, the car screen, is built: the app's map and the
running drive in the car, with Start on the phone; [Step 1 (built)](#step-1-built) says what it has
and where it settled or changed what follows. Step 1b, the fixes after the owner's first test with
the car screen, is built too: the car map's own frame clock, its puck on that clock, real frame
rates, and the phone's look on the car ([Step 1b (built)](#step-1b-built)). Step 1c is built as well:
during a drive while the car shows the map, the phone shows the turns ahead as a list in place of its
own map, with the map one tap away ([Step 1c (built)](#step-1c-built)). The rest is the design as
decided so far.
**Unverified** marks behaviour that no document or library source settles; it is checked on the
Desktop Head Unit (DHU) or on the phone before code depends on it.

Versions: Car App Library 1.8.0-rc01; Mapbox Maps 11.34.1 with maps-androidauto 11.34.1, Navigation
3.34.1 and Search 2.34.1; WorkManager 2.12.0.

## In short

- A car app service in the navigation category, with screens on the navigation template, on Car
  App Library 1.8.0-rc01 for its host check fix.
- The car map is Mapbox's map surface (maps-androidauto) drawing the app's own scene: the same
  style, route line, puck and camera as the phone. Mapbox's ready-made car screens are not used.
- One drive across both screens. The drive belongs to the engine, not to a screen. When the car
  disconnects, guidance continues on the phone; when it connects again, it continues in the car,
  once Android Auto opens the app again (unverified). Another navigation app in the car does not
  stop it.
- Start pressed in the car with the phone locked can crash the app on Android 14 and 15. A start
  guard, location "Allow all the time" and caught exceptions prevent that. The first device test
  shows how much of the guard is needed.
- Turn-by-turn notifications in the car are Mapbox's trip notification, extended for the car.
- After a crash or a system kill mid-drive, the phone and the car offer to resume to the same
  destination.
- Google Maps, Apple Maps and Waze links and `geo:` addresses become places. A link that arrives
  during a drive offers to go there now.
- Each trip's debug log goes to a private GitHub repository.
- Every car check runs on the DHU. A real car runs only builds installed from Google Play.

## The owner's choices

| Choice | Where | Decision |
|---|---|---|
| The stage in steps, the car screen first: the map and the running drive in the car, Start on the phone | [Step 1 (built)](#step-1-built) | 49 |
| Car App Library 1.8.0-rc01 now, 1.8.0 when it is released | [Car App Library 1.8.0-rc01](#car-app-library-180-rc01) | 30 |
| Mapbox's map surface only, not its car components | [Modules and dependencies](#modules-and-dependencies) | 31 |
| The car disconnects mid-drive: guidance continues on the phone, and in the car again when it reconnects | [One drive, two screens](#one-drive-two-screens) | 32 |
| Another navigation app starts in the car: this app keeps guiding too | [onStopNavigation](#onstopnavigation) | 32 |
| A crash or system kill mid-drive: offer to resume to the same destination | [Resume after a crash](#resume-after-a-crash) | 32 |
| Location "Allow all the time", asked for after precise location | [The start problem](#the-start-problem-on-android-14-and-15) | 33 |
| Coordinates in links are used as they are; Google place pages are read for exact coordinates, on by default with a switch; the fallback is a Mapbox search near the link's place hint | [Shared links](#shared-links) | 34 |
| A link during a drive offers "Go there now", on the phone and in the car, which changes the running drive's destination after a confirmation | [A link during a drive](#a-link-during-a-drive) | 34 |
| Opening Custom Maps on the car screen again during a drive takes the car back from another navigation app | [onStopNavigation](#onstopnavigation) | 32 |
| The real car runs the build from Google Play's internal testing, which came forward from stage 3 on 2026-11-11, instead of Internal App Sharing | [The real car](#the-real-car) | 42 |
| Trip logs go to a private GitHub repository: a fine-grained token for that repository only, 366 days, a reminder two weeks before it expires, an upload after every trip and Upload now | [GitHub log upload](#github-log-upload) | 35 |
| A bright, colourful day map in the car, the existing dark map at night; since step 1b the car's map shows the phone's look, Auto by the sun or the driver's choice | [Day and night](#day-and-night) | 36, 48, 50 |
| No map on the phone while the car shows one during a drive: the list of the maneuvers ahead instead, the map one tap away | [Step 1c (built)](#step-1c-built) | 51 |
| Turn-by-turn notifications on the car screen while another app is in front | [Turn-by-turn notifications in the car](#turn-by-turn-notifications-in-the-car) | ROADMAP |
| Starting a drive by voice comes later | No `NAVIGATE` intent filter in this stage | ROADMAP |

## Step 1 (built)

The owner asked on 2026-11-12 for the car screen "just like on the phone but on the Android Auto
screen", with no separate screens for now. Step 1 is that screen.

### What it has

- **The car app.** `CustomMapsCarAppService` in `:app`, in the navigation category, with the
  library's sample allow-list as its host check in every build type; the `:car` module with
  `CarSession`, `CarHomeScreen`, `CarMessageScreen`, `CarGuidanceBridge`, the mappers
  (`ManeuverToCar`, `LanesToCar`, `DistanceToCar`, `EstimateToCar`) and the two image renderers
  (`ManeuverIconRenderer`, `LanesImageRenderer`). Two of the three artifacts of
  [Dependencies](#dependencies): car-app 1.8.0-rc01 and maps-androidauto 11.34.1. The
  [Manifest](#manifest) as designed, without `ACCESS_BACKGROUND_LOCATION` and without the link
  entry.
- **The setup gate.** The car waits for the token as the phone does: a loading message while the
  token store is read, a message to add the token or to allow precise location on the phone when
  parked, and the map once both are there; a new token builds the car map again.
- **The map.** `MapScene` (`:engine-mapbox`, `map/MapScene.kt`) is everything of the phone's map
  that is not tied to a view, as of stage 1.1 and the app's own lanes: the look and `MapPalette`,
  the lane zoom and lane details, the SDK route line and the app's own route line with its
  watchdog and swap, the drawing of the app's lanes with its eraser and arrow, the U-turn arrow, the
  place pin, the camera of the chosen style with a data source for each map, and the navigator
  observer. `PhoneMap` keeps the view parts. `CarNavigationMap` draws the same scene on the car's
  surface ([The car map](#the-car-map)), with the Mapbox logo and the text attribution in the bottom
  left of the stable area, the zoom limits 6 to 20 with steps of 0.5, the host's day and night in
  two stages, at most 30 frames a second, and one `GuidancePuck`, owned by the engine, for both
  maps. The Debug switches apply to both maps. Step 1b replaced the two stages with the phone's
  look and the cap with a real 30 frames a second, and gave the car's map a frame clock of its
  own ([Step 1b](#step-1b-built)).
- **The screens.** `CarHomeScreen` is the root, always a navigation template over the map. Before
  a drive and while the phone shows routes, its action is **Start on phone** (a phone icon), which
  opens a message: "Choose a destination and press Start on the phone when you are parked. The
  drive then shows here.", or in the route preview "Press Start on the phone when you are parked.",
  with Back. The routes the phone shows are on the car's map. During a drive: the routing card, the
  arrival estimate and **End**, which ends the drive as the phone's End does. At the arrival the card
  says "You have arrived" with the destination's name, until the phone's arrival card goes; then the
  idle screen. The map's action strip: Pan, zoom in, zoom out, and Recenter while the camera is idle,
  otherwise the switch between following and the overview. At most two templates are ever on the
  stack.
- **The routing card** ([below](#the-routing-card)) is made from the phone card's own content: the
  maneuver with the phone's arrow, its distance in feet and miles as the phone shows them, its
  words as the card has them, the card's lane row with the lanes to take, the Then step exactly
  while the phone's Then chip shows, and the arrival time, time and distance left. It loads while
  the car is off the route and until a new route has its first instruction. It and the trip the
  host gets change at once when the maneuver, the Then step, the lane row or loading changes, and
  otherwise at most once a second.
- **The bridge** ([below](#the-guidance-bridge-to-navigationmanager)) as designed, without
  AUTO_DRIVE: the callback first at the session's ON_CREATE, `navigationStarted()` for a drive and
  for a session made during one, trips while the bridge's own flag is set, `navigationEnded()` at
  the end of the drive, every call caught, `onStopNavigation` logged with the session's lifecycle
  and CarConnection's type while the drive goes on, and the car taken back when the driver opens the
  app on the car screen again.
- **One drive, two screens** as designed: the car session is navigator owner B, never detached by
  hand; a disconnect, the host's stop or another navigation app never ends the drive, and only End,
  End Navigation in the notification and the arrival do, through the existing stop sequence.
  Nothing in the car starts, stops or restarts a trip session or asks for anything of its own.
- **Logs**, with `screen` `car` and numbers and states only: `car_session` (the host's package
  and Car API level, the lifecycle, the gate, the map, the screens, the connection), `car_surface`,
  `car_look`, `car_nav`, `car_template`, `car_location`, `car_error`, and the camera line.

### Where it settled or changed the design

- **Start stays on the phone** in this step. The action strip's idle action is Start on phone, not
  Search.
- **Surface callback.** The car map registers `MapboxCarMap`'s callback itself, from
  `prepareSurfaceCallback`, inside one that catches every host call: car-app rethrows a surface
  callback's exception on the main thread, Mapbox's own map creation included. Still one callback
  for the app; `:car` never calls `setSurfaceCallback`.
- **The attribution** is a `BitmapWidget` the car map keeps, not a class of its own.
- **The camera on a new surface.** Of the camera mode, the car map keeps only an overview the
  driver asked for during the drive under way (`CarOverview` in `:core-nav`): a new surface shows it
  again until the driver presses Recenter, moves the map or zooms, and a drive that ends forgets it,
  so the next drive starts by following the car. Otherwise a new surface follows the car during a
  drive, also one the driver had moved the map on, and frames the routes in the preview.
- **Car theme:** amber and dark amber, platinum and graphite.
- **Maneuvers:** besides Mapbox's rules, a U-turn is a left U-turn also where the route calls it
  going on along the road, as the phone's card draws it; an off-ramp or a fork with no turn at all
  leaves on the near side, and a roundabout with no turn is still a roundabout.
- **The images.** One source of the arrows' shapes, `GlyphShapes` in `:core-nav`, which the phone
  draws with Compose and the car on a canvas, so an arrow added there reaches both. The lanes image
  is the card's row without its words or a lane choice's distance: a distance changes with every
  update and would draw the image again each time, and the host shows the image too small for
  words. The host draws the card behind the images in its own colour, dark on the Android Auto
  versions read: every arrow and bar is bright, amber for what the driver does now, grey for the
  other lanes, platinum for the Then step, with a thin dark edge that keeps it readable on a light
  card too. Unverified: the card's colour by day.
- **The trip** holds the routing card's steps, the Then step only while it shows, and the
  destination by its name.
- **The arrival** has no Done in the car: the phone's Done ends it on both screens.
- **The phone** does not watch `CarConnection` yet; the car session does, for its log.

### Still to come

Later steps, with clear seams left for them: `CarSearchScreen` and `CarRoutePreviewScreen` (pushed
on the home screen's navigation template), Start in the car with the start guard
(`TripStartPolicy`, `StartResult`, `StartBlocked`, `startProblem`; the phone and car hosts are
already split for it), "Allow all the time" and `ACCESS_BACKGROUND_LOCATION`, the debug switch to
start without the guard, the foreground service check (`trip_service`), the turn-by-turn
notification extender (`CarTurnNotification`, `setTripNotificationExtender`; it can reuse the
arrows' shapes and the bridge's routing content), AUTO_DRIVE, the reconnect fallback notification,
resume after a crash and the drive marker, shared links, the GitHub log upload and WorkManager, and
the DHU profiles. The next steps of the app's own lanes and arrows draw into `MapScene` and
`GlyphShapes`, so they reach the car with no change there.

### Unverified, for the DHU and the car

- Whether Android Auto opens the app again by itself when the car reconnects during a drive; the
  `car_session` lines show what happens.
- Whether `navigationStarted()` after the driver opened the app again makes it the active navigation
  app in the car, and whether the host then stops the other app.
- The colour the host draws the routing card in by day, behind the images.
- Whether the host sends a surface to a car map built again in one session (a token change).
- Whether the car's puck gets positions before Start with the phone locked and location only while
  in use (`car_location`); no request and no workaround in this step. The first car test: none
  with the phone app in the background after the drive ([Location before Start](#location-before-start)).
- The frame rate the host streams, and the camera's `camFps` with the phone locked. The first car
  test: with the phone app in the background the main thread got no display frames for many seconds
  and the car's map stood still; step 1b's frame clock is the answer.
- Whether the light preset's change animates.

## Step 1b (built)

The owner's first test with the car screen, a simulated drive on build 0.1.34, found the car's map
slow, freezing now and then, and in the day look while the phone showed the night. Step 1b fixes
both, from what the drive's debug log showed and from the Maps SDK 11.34.1 sources.

### What the first car test showed

- **A freeze.** With the phone app in the background, the car's camera stopped for many seconds: no
  camera update and no write, while positions and progress kept coming and the car's session stayed
  resumed. The drawn puck stood still and fell further behind the car with every second. The car
  map's render thread drew a few more frames and then none, with nothing left to draw. In earlier
  stretches in the background the car's map kept moving. No screen on or off broadcast came during
  the whole car session, though the phone was locked and unlocked, so whether its display was off is
  not known.
- **Two maps at the phone's display rate, and heat.** With the phone app in front the phone's map
  drew at the phone's full display rate, and the car map's render thread was called far more often
  than its cap of 30 a second suggests, while the car's `renderFps` read 30 throughout. The phone
  grew hot quickly, much sooner than on drives with the phone alone. Frame times on the car had long
  spikes now and then.
- **The day map at night.** The phone showed the night look (Auto, by the sun); the car's host said
  day, and the car's map loaded in the day look and stayed in it.
- **No position after the drive.** With the drive over and the phone app in the background, the car
  map's platform location brought no fix (`car_location`): the known limit of
  [Location before Start](#location-before-start), which "Allow all the time" lifts in a later step.

### Why

- **The main thread's frames.** Mapbox's map draws only on Choreographer callbacks of its render
  thread. The puck's glide is a `ValueAnimator` for each position on the main thread, and the
  Smooth follow camera asks the main thread's Choreographer for its next frame only while something
  moves. When the main thread got no display frames, the glide stayed at its first value, the camera
  saw nothing move and stopped asking, and the car's map stood still although its render thread
  could still draw. Whether the display was off, dozing or held back for another reason, the fix does
  not depend on it.
- **A believed 60 Hz.** Mapbox paces the car's surface against the refresh rate of the display its
  context names, car-app's own virtual display (60 Hz by Android's sources; Mapbox's fallback is 60
  too), while the surface's render thread is called at the phone's display rate: `setMaximumFps(30)`
  drew every second call, 60 frames a second at 120 Hz, and Mapbox's own estimate said 30 whatever
  the real rate.

### What it has

- **The car's frame clock.** `CarFrameClock` (`:core-nav`, its decisions) and `CarFrames`
  (`:engine-mapbox`, on Android): the main thread's display frames while they come; once none has
  come for 50 ms while a frame is wanted, a main-thread timer every 16 ms, until two display frames
  come less than 25 ms apart again; never two ticks within 15 ms, so at most about 60 a second, at
  120 Hz too. It runs only from a surface's attach to its detach, and only while something wants a
  frame ([CAMERA.md](CAMERA.md), The car map).
- **On the clock:** the car's Smooth follow camera (through a `FrameSource`; the phone's is the
  display's Choreographer, as before), the zoom buttons' ease, and the puck. `CarPuck`, the car's
  location provider, takes each update of the platform's location or of the guidance puck with its
  points and glide time, works out the puck's place and bearing for each tick (`PuckTrack`: one
  speed through the points, the short way round, a new update from where the puck is) and hands
  them to the location component with no duration, so nothing waits for an animator frame. The
  phone's camera and puck are as before.
- **Still on Mapbox's animators on the car,** so still standing still while the main thread gets no
  display frames: the two Mapbox camera styles and the double tap's zoom. The camera style chosen in
  Settings applies to the car too (DECISIONS 26); Smooth follow, the default, is the one that keeps
  moving.
- **Frame rates.** The car's surface draws a real 30 frames a second (`CarFrameCap`): its cap is
  `round(30 × 60 / rate)`, within 1 and 60, for the rate at which Mapbox's render thread ticks, as
  Mapbox counts its ticks: its calls and the display frames it counts as missed. That rate is not
  always the display's: on a phone at 120 Hz a frame that takes longer than one display frame to
  draw makes the next call wait for the display frame after next, which Mapbox counts as one tick,
  so the cap for the display's rate alone, 15, drew about 24 frames a second with frames of 9 to
  16 ms. During guidance 30 is held from the render thread's own ticks, which the camera line's
  window of its statistics counts each second (15 to 20 at 120 Hz): a window counts when the frame
  clock stayed on the display, the cap did not change in it and the render thread was called on at
  least half of the display's frames; the cap goes down at once when the map would draw more than a
  tenth over 30, and up only when two windows in a row say it would draw more than a tenth under.
  Before the first guidance it is the rate of the display's frames (measured from display callbacks
  asked for one right after the other), after it the last render thread's rate while the display's
  rate stays within a tenth of the one it was counted at, and 60 while the clock is on its timer.
  While a car surface shows the map, the phone's map draws at most 60 frames a second, and as
  before once the surface goes.
- **The phone's look on the car** (DECISIONS 50; [Day and night](#day-and-night)): the app's one
  look, which `LookController` decides for the process. The car's session (its part in the look,
  `CarLookSession`) starts it if no screen has (the car's host may have started the process), keeps
  it deciding while the session is started, one loop with the phone's activity when both are, and
  hands it to the car's map as the phone's screen hands it to its map, so a theme that waits for a
  quiet moment of the drive waits on both. The car's map is made once the first look is decided,
  with that look (`CarMapStep`). While the phone's activity is not started, the car map's close view
  holds a switch, and Settings over the phone's map lets nothing go early while the car's screen
  shows the map. The host's day and night colour only the routing card's images.
- **Lines**, numbers and states only:
  - `display_state`, from the app at its start and at every change of the phone's own display:
    `state` (ON, OFF, DOZE, DOZE_SUSPEND, VR, ON_SUSPEND or UNKNOWN) and `hz`, its refresh rate;
  - `frame_clock`, with `screen` `car`, at each switch of the car's clock: `source` (`timer` or
    `vsync`), `quietMs`, and on the way back `timerTicks`;
  - the camera line's `render` adds `drawn` and `drawnFps`, the frames the render thread drew
    beside the calls it got (`frames`, `fps`), on both maps; the car's camera line adds `clock`
    (`vsyncTicks`, `timerTicks` of the second), `maxFps`, `capFrom` (`render`, `display` or `timer`)
    and `measuredHz`, the phone's `capped` while it draws at most 60;
  - `car_surface` has `maxFps` (the cap set), `measuredHz` (the rate it was worked out for) and
    `capFrom`, and an `event` `fps` line at each new cap;
  - `car_error` from the clock's callbacks has `failures`: the first at once, then at most one each
    10 s with the count;
  - `car_look` has the host's `dark` beside the map's `look` ([Day and night](#day-and-night)).
- `renderFps` is Mapbox's own estimate, worked out against the rate it believes: on the car 60 Hz,
  so it reads the cap, 15 to 20 at 120 Hz; `render.drawnFps` is the car's real rate. The
  camera line's `p50Ms` and `p95Ms` take every call of the render thread, the paced ones with their
  near-zero times too.

### Still to come and unverified

- Unverified: whether the car map's render thread still gets its own display callbacks while the
  main thread gets none (the car's camera lines say it: `render.frames` and `drawn`; if not, the
  camera and the puck move but nothing is drawn); what `display_state` says while the phone is
  locked; the refresh rate Android reports for car-app's virtual display (the cap takes 60, also
  Mapbox's own fallback); the heat over a drive with the car (the `thermal` lines).
- A cost while the clock is on its timer: each puck update leaves a cleared entry in Android's
  animation list until the next display frame, since an animator of no duration still registers
  there. After an hour without display frames that is a few MB and a few percent of a core, gone
  at the first frame. The app's own puck layer of a later step ends it.
- The checks for the owner are in [DRIVES.md](DRIVES.md), The car screen after step 1c.

## Step 1c (built)

The owner asked for no phone map while the car shows one during a drive: the phone shows the turns
ahead as a list in its place, and the map comes back with one tap (DECISIONS 51). Step 1c also lets
the owner compare 30 and 60 frames a second on the car's screen.

### What the phone shows while the car shows the map

- **The rule** (`PhoneScreenRule`, `:core-nav` `nav/car`; one for the process in `PhoneScreens`,
  `:app`): the phone shows the **list** in place of its map while guidance is active and a car's
  surface shows the map (the engine's `carMapShown`), unless the driver chose the map in this drive.
  **Map** on the list chooses the map; **List** on the map's guidance bar takes the choice back. A
  new drive starts with no choice. A car that goes mid-drive brings the map back; a car that comes
  mid-drive brings the list, unless the map was chosen. The route preview, the arrival and the time
  before Start always show the map, with a car or without. With no car nothing of the phone changes.
- **The list** (`ListScreen`, `:app` `ui/map`): a black page in the night look's colours whatever the
  look, the least power on the phone's own screen; the system bars' icons are light over it. At the
  top the maneuver card as during guidance, without its Then chip, since the list's first row is
  that maneuver, and with the offline tab under it when the app is offline. Then the maneuvers ahead
  in this leg, as many as fit: the app's own arrow, the distance from the car (the distance to the
  next maneuver plus the maneuver's own after it, rounded as the card rounds its own) and the
  maneuver's words; all dimmed while the card waits for a new route and while the car is off the
  route. Then the replay slider during a simulated drive, and the guidance bar with the arrival, the
  time and distance left, **Map** in place of Overview and **End**, which ends the drive as the
  guidance bar's End always has. Under it a small static line, "© Mapbox © OpenStreetMap", as the
  list shows Mapbox's route data with no map and so no logo.
- **The maneuvers ahead** come with the progress (`RouteProgressSnapshot.upcoming`): every maneuver
  of the current leg after the next one, through the leg's arrival, each with its distance and time
  from the next maneuver, read from the route's steps as the Then chip's maneuver is (the first of
  them is it), a roundabout's way out being no maneuver of its own. A step without a banner or with a
  length that is not a number ends the list there. The engine builds the list once a step and hands
  out the same list for every update of that step (`UpcomingManeuvers`); the list's rows are made
  once a step too, and only their distances change with each update.
- **Nothing on the list moves by itself:** no animation and no spinner. It is drawn again when the
  progress changes, about once a second, and the replay slider's dot goes to each position at once
  instead of gliding.
- **The car's own screen** is as in step 1: its routing card shows the next maneuver and, when the
  phone would show Then, the one after it.

### The phone's map put away and brought back

- **Kept, out of the window.** While the list shows, the phone's map is not made again and its style
  does not load again: its view leaves the window. Mapbox's own lifecycle then takes the view to
  created, as for a stopped activity (`ViewLifecycleOwner`, Maps 11.34.1: the view's lifecycle drops
  to created when it is detached, and goes back to the activity's state when it is attached again):
  the render thread is paused and draws nothing, and the location component stops, cancels its
  animators and lets go of the puck's positions. Nothing in the app calls the view's lifecycle. Its
  scene is invisible meanwhile (`MapScene.setAway`): the Smooth follow camera asks for no frames,
  the two Mapbox camera styles start no animation, and the routes, the progress and the camera's data
  go on as before, once a second.
- **Made only when it is to show.** The phone's map is made in the composition that first shows it,
  with its view in the window at once (`PhoneMapSlot`): Mapbox takes the activity's lifecycle for a
  map's view only on the view's first attach, so a map made while the list shows would never be
  stopped or destroyed: its render thread would run and its puck would glide to every position of
  the drive. A phone screen that starts on the list, as one made again mid-drive while the car shows
  the map (the app swiped away and opened again), has no map until the map is to show: **Map**, the
  car gone or the drive's end makes it then, with its style load as at the app's start, and it is
  kept from then on. A released map's location component is turned off as well
  (`MapScene.release`), so no released map stays a listener of the engine's puck.
- **No part in the look.** No look is shown on the map while it is away; its close view holds
  nothing, and the car map's close view holds a switch, as while the phone's activity is stopped
  (`LookController.onPhoneMapAway`). A theme never waits on the stopped map.
- **Back as of now.** The same map comes back into the window within a second: the app's current
  look (the first look shown on it is the one decided meanwhile), the puck where the car is now (the
  guidance puck gives a returning map its newest position at once), the route line as driven, and the
  camera following the car, whatever it did before the map went away.
- **The camera line** goes on once a second while the phone's screen is on, with `away` true and
  `render.frames` 0, which shows that the map drew nothing. A screen that started on the list has no
  map yet, and so no phone camera line, until the map shows.

### The frame target

- **Settings → Debug → Car map frames**, 30 a second, the default, or 60, sets what the car's map
  aims at, at once, also during a drive ([CAMERA.md](CAMERA.md), Frame target). The car's host may
  show fewer frames than the map draws, at a rate of its own that the app cannot read: the switch is
  there to compare the two on the car's screen, with the car's camera line's `render.drawnFps` and
  the `thermal` lines.

### Lines

- `phone_screen` at each change of what the phone shows during a drive: `screen` (`list` or `map`)
  and `cause` (`drive`, `car`, `button` or `end`), and nothing else: no position, no time of day.
- The phone's camera line has `away` true while its map is put away; the car's camera line and
  every `car_surface` line have `targetFps`, 30 or 60; the `settings` line has the key
  `car_map_fps`.

### Not in this step

- Search or Start in the car; a list on the car's own screen; the list before Start or after the
  drive, where search and the route choice need the map.
- Keeping the phone's screen on: as before it may lock during a drive, which since step 1b is the
  best case for the car.

### Unverified

- How soon the map is drawn again when it comes back: the surface is made anew, and the first frame
  may show the camera as it was left until Smooth follow's next frame takes it to the car.
- The heat with the list in place of the phone's map, against step 1b's two maps (the `thermal`
  lines over ten minutes).
- Whether 60 frames a second look smoother than 30 on the car's screen, and what they cost.
- How the list reads from a phone mount by day and at night.
- Whether the car's host keeps the map's surface while another app is on the car's screen during a
  drive (step 1b's open question): the list follows the surface (`carMapShown`), so if the host keeps
  it, the phone stays on the list then, with **Map** one tap away; if it lets the surface go, the
  phone shows its map until the car shows the map again (`car_surface` and `phone_screen` lines).
- The checks for the owner are in [DRIVES.md](DRIVES.md), The car screen after step 1c.

## Modules and dependencies

### Modules

| Module | Stage 2 contents |
|---|---|
| `:core-nav` | Pure Kotlin on the Java 8 API. New: `CarMapRenderer`; the start result and the start problem; the route replacement for Go there now; the data the routing card needs; the link parser (`nav/link`); trip files and the GitHub client (`nav/debug`, `nav/upload`). |
| `:engine-mapbox` | The shared map scene, the car map (`CarNavigationMap`), the trip notification hook, the start guard, forward search and the lookup at a point. Mapbox types stay inside (DECISIONS 12). |
| `:car` (new) | Android library, namespace `io.github.purplepalm.custommaps.car`: the car session, the screens, the guidance bridge, mappers, image renderers and the notification extender. Depends on `:core-nav` and car-app; no Mapbox code. |
| `:app` | `CustomMapsCarAppService` (the service stays in the composition root, DECISIONS 12), the manifest, the link entry and its HTTP client, the upload worker and its store, Settings. |

The `:car` classes, wired by hand in `AppGraph`:

| Class | What it does |
|---|---|
| `CarSession` | Waits for the token, attaches the car as a navigator owner, creates the car map, picks the first screen, passes day and night on, logs the host and its Car API level |
| `CarHomeScreen`, `CarSearchScreen`, `CarRoutePreviewScreen`, `CarMessageScreen` | The [screens](#screens) |
| `CarGuidanceBridge` | Engine state to `NavigationManager` calls |
| `ManeuverToCar`, `LanesToCar`, distance and time mappers | Domain models to car models, in pure Kotlin |
| `ManeuverIconRenderer`, `LanesImageRenderer` | The app's own bitmaps, cached |
| `CarTurnNotification` | The extender for Mapbox's trip notification |

Its inputs: a car map factory `(CarContext) -> CarMapRenderer`, `NavigationEngine`, `SearchProvider`,
`MapsAccess`, the car host attach, the shared places and the drive marker. The notification
extender is not one of them: `AppGraph` sets it once for every drive, not per car Session (see
[Turn-by-turn notifications in the car](#turn-by-turn-notifications-in-the-car)). Step 1 has them in
`CarParts`: the engine, `MapsAccess` (moved to `:core-nav`), the car map factory, the car host attach,
the settings the car map follows and the debug log; the others come with their steps.

### Dependencies

| Artifact | Version | Where | For |
|---|---|---|---|
| `androidx.car.app:app` | 1.8.0-rc01 | `api` in `:car`; also in `:engine-mapbox` and `:app` | Templates, the surface, `NavigationManager`, `CarAppExtender` |
| `com.mapbox.extension:maps-androidauto-ndk27` | 11.34.1 | `implementation` in `:engine-mapbox` only | `MapboxCarMap`: the map on the car surface |
| `androidx.work:work-runtime` | 2.12.0 | `implementation` in `:app` | Trip log uploads that survive network loss and process death |

```toml
[versions]
androidx-car-app = "1.8.0-rc01"
androidx-work = "2.12.0"

[libraries]
androidx-car-app = { module = "androidx.car.app:app", version.ref = "androidx-car-app" }
androidx-work-runtime = { module = "androidx.work:work-runtime", version.ref = "androidx-work" }
mapbox-maps-androidauto = { module = "com.mapbox.extension:maps-androidauto-ndk27", version = "11.34.1" }
```

What they bring:

- **car-app 1.8.0-rc01:** activity 1.2.0, annotation 1.8.1, annotation-experimental 1.4.1, core 1.7.0,
  lifecycle-common-java8 2.2.0 and lifecycle-viewmodel 2.2.0, which Gradle raises to the app's newer
  versions; media 1.6.0, Guava 32.0.1-android, kotlin-stdlib 2.1.20 and JSpecify 1.0.0. It keeps
  app-projected, app-automotive and app-testing on its own version. The merged manifest gains
  `CarAppPermissionActivity`, `CarAppNotificationBroadcastReceiver` and a `<queries>` entry for the
  provider behind `CarConnection`. It needs minSdk 23, compileSdk 34 and AGP 8.1.1 or newer; the
  project has 26, 37 and 9.4.1.
- **maps-androidauto 11.34.1:** Maps `android-ndk27` 11.34.1, which ui-maps already brings, and car-app
  1.2.0, raised to 1.8.0-rc01; at runtime kotlin-stdlib-jdk8 1.7.20, core-ktx 1.9.0 and annotation
  1.1.0. No native code, so the 16 KB check is unaffected; no resources; its manifest adds only
  `ACCESS_SURFACE`. No Navigation, Search, Glide, WorkManager or voice code. It resolves from the
  Mapbox repository like the other Mapbox artifacts and moves with Maps (DECISIONS 5).
- **work-runtime 2.12.0** (stable since 2026-09-23): room-runtime 2.7.0, lifecycle-service and
  lifecycle-livedata 2.6.2 (raised to the app's 2.11.0), startup-runtime 1.1.1, concurrent-futures-ktx
  1.1.0, tracing-ktx 1.2.0, core 1.12.0, listenablefuture 1.0, coroutines 1.9.0 and kotlin-stdlib
  2.1.20; the app's newer versions win. It needs minSdk 24, compileSdk 35 and AGP 8.6.0. Its manifest
  adds `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`,
  `SystemJobService`, `SystemForegroundService` and the reschedule receivers. It sets itself up at
  app start through App Startup.

### Not added

| Artifact | Why not |
|---|---|
| `com.mapbox.navigationcore:android-auto-components-ndk27` 3.34.1 | Mapbox's ready-made car screens (DECISIONS 31). The reasons are listed below the table. |
| `androidx.car.app:app-projected` | Only car sensors, car information and the car microphone. |
| `androidx.car.app:app-automotive` | Android Automotive OS is out of scope. |
| `androidx.car.app:app-testing` | Brings Robolectric 4.16.1 and a JUnit 4 runner; the project runs JUnit 6 (DECISIONS 10). |
| `work-runtime-ktx`, `work-testing` | The first is empty since WorkManager 2.9.0; the upload logic is tested in `:core-nav`. |
| An HTTP or JSON library | Few requests: at most two GETs per link (a short link and a Google place page) and, per trip, a repository check and one PUT. `HttpURLConnection`, `java.util.Base64` (API 26, the minSdk) and `GZIPOutputStream` are enough, and a reader for top-level JSON fields is about 80 lines. OkHttp 4.10.0 already ships in the APK as a runtime dependency of Mapbox's common-ndk27 24.34.1, but it is not declared: app code would be tied to Mapbox's copy, and declaring 5.5.1 would upgrade Mapbox's copy for the whole app. |
| `androidx.security:security-crypto` | Deprecated in 1.1.0. The GitHub token lives in DataStore, like the Mapbox token. |

Why not android-auto-components:

- It is built around free drive. Its puck, camera and route origin take positions only from the
  navigator, which delivers them only while a trip session runs.
- When the host stops navigation, and when its arrival screen finishes, it clears the routes without
  stopping the session. That turns guidance into billed free drive (DECISIONS 13).
- Its default action strip registers Mapbox voice guidance.
- It makes requests of its own: road shields, junction images, routes, Search, Glide downloads, and
  telemetry on connect and disconnect.
- It pulls in the navigation umbrella (Mapbox's Copilot trace collection with work-runtime-ktx
  2.10.5, voice with mapbox-sdk-services 7.10.1, tripdata and ui-maps), Search 2.34.1, Glide 4.13.2,
  AppCompat 1.3.1 and car-app 1.7.0.
- It is one artifact; there is no smaller part to take.

Other notes:

- `lifecycle-livedata` 2.11.0 is declared only if `CarConnection`'s LiveData is collected as a Flow
  (`asFlow`).
- Mapbox's Android Auto guide quotes version 3.34.2, which Mapbox's repository does not serve. The
  project stays on Navigation 3.34.1 with Maps 11.34.1 and Search 2.34.1.
- car-app adds Guava and kotlin-stdlib to the runtime. R8 shrinks them in release builds; debug APKs
  get larger.

### Manifest

- `CustomMapsCarAppService` in `:app`: `exported="true"`, a label and an icon (the host shows them),
  and an intent filter with the action `androidx.car.app.CarAppService` and the category
  `androidx.car.app.category.NAVIGATION`. It runs in the default process, the same one as Mapbox's
  trip service, as Google advises. `onCreateSession(SessionInfo)` returns a `CarSession`; the form
  without arguments throws by default.
- On `<application>`:
  - `androidx.car.app.minCarApiLevel` = 7;
  - `com.google.android.gms.car.application` pointing to `@xml/automotive_app_desc`, which holds
    `<automotiveApp><uses name="template"/></automotiveApp>`;
  - optionally `androidx.car.app.theme`, with `carColorPrimary`, `carColorPrimaryDark`,
    `carColorSecondary` and `carColorSecondaryDark` (built: amber and dark amber, platinum and
    graphite).
- Permissions: `androidx.car.app.NAVIGATION_TEMPLATES`; `androidx.car.app.ACCESS_SURFACE` (also merged
  from maps-androidauto; registering a surface callback without it throws `SecurityException`);
  `ACCESS_BACKGROUND_LOCATION`, in the debug manifest only while this stage is built (see
  [The real car](#the-real-car)); `INTERNET`, which today arrives only through Mapbox's manifest.
- Already merged from Mapbox: `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS` and the
  location-type trip service.
- Not declared: `FEATURE_CLUSTER`, which adds a second Session; the `androidx.car.app.action.NAVIGATE`
  filter, because starting by voice comes later.
- Android 16: if the app ever opts into the safer intent matching, the service may need
  `android:intentMatchingFlags="allowNullAction"`.
- The link entry and its filters are under [Shared links](#intent-filters-and-open-by-default).

## Car App Library 1.8.0-rc01

- 1.7.0 (2025-07-16) is the latest stable release. 1.8.0-rc01 (2026-08-26) is a release candidate;
  Google's Maven repository had no final 1.8.0 when this was checked on 2026-11-03.
- The only code change from 1.8.0-beta01 to 1.8.0-rc01, in `app` and `app-projected`, is in
  `HostValidator`. On a phone (no `FEATURE_AUTOMOTIVE`) it no longer accepts a host just because the
  host holds `android.car.permission.TEMPLATE_RENDERER`: the host must match the signature
  allow-list. In 1.7.0, `validateHost()` accepts any such host, even when an allow-list is set. The
  release notes call it a security fix and ask everyone on a lower version to update; no CVE is
  published for it. 1.8.0-rc01 has the same features as 1.8.0-beta01 and a frozen API.
- The hole is real: [AARE](https://github.com/meraz9000/AARE), an open-source Android Auto client
  for phones, holds that permission and is accepted by release builds of apps on 1.8.0-beta01 or
  older.
- An accepted host gets what Android Auto gets: the surface the map is drawn on, so the driver's
  position; the template contents, such as the destination and the routes; and the clicks, so it
  can press Start, which starts a billed trip session.
- `HostValidator` is final. On 1.7.0 the only fix would be an own check of the host's package and
  signing digest in `onCreateSession`.
- `createHostValidator()` returns
  `HostValidator.Builder(context).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()`
  in every build type. The sample list holds three signing digests for Android Auto and three for
  the Android Automotive templates host. The DHU talks to the real Android Auto app, so it passes.
- Google's sample returns `ALLOW_ALL_HOSTS_VALIDATOR` for debuggable builds. This app never uses it
  on a build that lives on the phone, because the CI debug build is the daily driver.
- The service is exported without a permission. Google says the lint warning about it is safe to
  ignore, because the host validator controls access.
- Moving to 1.8.0 is a one-line catalog change on the day it ships.

## One drive, two screens

### Who keeps the navigator alive

- One process, one `AppGraph`, one `MapboxEngine`, one `MapboxNavigation` from `MapboxNavigationApp`
  (Navigation 3.34.1).
- `MapboxNavigationApp` counts owners. It creates `MapboxNavigation` when the first attached
  `LifecycleOwner` reaches CREATED and destroys it when none is left. Destroying it stops the trip
  session, and Mapbox bills a recreated navigator that continues the same trip as another trip.
- Three kinds of owner:

| Owner | Attached | Removed |
|---|---|---|
| A. `MainActivity` | by `MapScreen`, as today | when the activity is destroyed |
| B. each car `Session` (new) | at the Session's ON_CREATE once `MapsAccess` is Ready, or later when the token arrives | automatically at DESTROYED, never by hand |
| C. `GuidanceLifecycleOwner` | at `CommitRoutes` | at `ClearRoutes`, which always comes after `StopTripSession` |

- C is the only owner tied to the drive. Screens coming and going add or remove only A and B, so
  they never stop or restart the trip session and never cause a new billed trip.
- Without B, a cold start from the car has no navigator: today `previewRoutes` answers "Navigation is
  not ready yet" and `startGuidance` logs "navigator not attached", so search, preview and Start in
  the car would do nothing.
- B is never detached by hand. `MapboxNavigationApp` removes an owner at DESTROYED itself. A detach
  that runs before Mapbox's own observer sees ON_DESTROY (lifecycle observers run newest first on the
  way down) leaves the dead Session counted, and the navigator would never be destroyed again in
  that process.
- `RoutesObserver`, `RouteProgressObserver` and `NavigationSessionStateObserver` receive the current
  value when they are registered, so a car map that appears mid-drive draws the route and the
  progress at once.
- The engine splits `attachHost` into `attachPhoneHost` and `attachCarHost`, so the start guard knows
  whether the phone screen is visible.
- The phone watches `CarConnection(context).type`; `CONNECTION_TYPE_PROJECTION` (2) means Android Auto.
  Step 1: only the car session watches it, for the log of the host's stop.

### What happens when

| Event | What happens |
|---|---|
| Search on the phone | The preview lives in the engine, so both screens show it. The phone is the search input; the car's own search works too, but its keyboard is often locked while driving. |
| Start, on either screen | The [start guard](#the-plan) runs first. Then `CommitRoutes` (owner C attached), the navigator takes the routes, `StartTripSession`, Mapbox's location foreground service and its trip notification. The state machine ignores a second Start while ACTIVE, so both screens together start one session. |
| The car connects during a drive | Once Android Auto opens the app ([unverified](#reconnecting-unverified)), a new Session attaches owner B. The car's first template is the routing card. The bridge calls `navigationStarted()` and sends trips. The car map draws the route at once. |
| The car disconnects during a drive | Android Auto unbinds and the Session reaches ON_DESTROY. The bridge marks itself gone, the car map is released and owner B goes. Nothing dispatches a stop. The trip session, the foreground service and the phone notification go on. The session id stays the same, so the trip session log has one entry. Opening the phone app shows the drive. |
| Another navigation app starts in the car | The host calls `onStopNavigation`; guidance continues ([below](#onstopnavigation)). |
| End on the phone or in the car, End Navigation in the notification, or arrival | The existing stop sequence: `StopTripSession`, then `ClearRoutes` (owner C goes), then `ClearPreview`. Both screens end and the bridge calls `navigationEnded()`. If no owner is left, the navigator is destroyed after the session stopped, so no free drive starts. |
| The process dies mid-drive | The trip ends with it. At the next start the engine is IDLE and starts nothing by itself; the drive marker brings the [resume offer](#resume-after-a-crash). |

### Stop reasons

- `StopReason.CAR_DISCONNECTED` is removed: a disconnect never ends a drive. Today it appears only as
  a sample reason in `GuidanceStateMachineTest`, which changes with it.
- `StopReason.CAR_HOST_REQUEST` is removed too: a host stop no longer ends a drive either.
- End pressed in the car is `USER_CANCELLED`, as on the phone; End Navigation in the notification
  stays `STOPPED_OUTSIDE_APP`.

### A start from the car

- Android Auto can start the process while the phone is locked and the app is not running.
  `MapsAccess` starts as Checking and becomes Ready only after the token store's first read. So
  `CarSession` collects `MapsAccess` instead of reading it once: a loading message while it is
  Checking, the setup message when the token is missing, the map once it is Ready. When the token
  changes (a new `MapsAccess.Ready`), the car map is built again, as the phone activity is
  (DECISIONS 16).
- The car app service stays bound-only and single-session: no cluster session, and nothing ever
  calls `startService` on it. Otherwise Android hands the old binder to the next bind (`onRebind`),
  and the next disconnect sends no ON_DESTROY to the new Session.

### Reconnecting (unverified)

- Whether Android Auto opens the app again by itself when the car reconnects is not documented. A
  Session exists only once the host binds the app. If Android Auto shows its launcher or another app,
  the car has no routing card and the bridge cannot call `navigationStarted()`.
- The turn-by-turn notification does not help then: the host shows it only for the active navigation
  app, and that needs `navigationStarted()` from a live Session.
- Fallback to try on the DHU if needed: a plain car notification (not a turn-by-turn one) during the
  drive, whose content intent opens the car app through `CarPendingIntent.getCarApp`.

## The shared map scene

- `MapScene` (internal, `:engine-mapbox`, `map/MapScene.kt`) is everything in `PhoneMap` that is not
  tied to a View:
  - Mapbox Standard, with `MapPalette` applied in the STYLE data-loaded callback, so no frame shows
    the wrong look;
  - the route line and arrows (`MapboxRouteLineApi` and `MapboxRouteLineView`, ui-maps 3.34.1, slot
    `middle`);
  - the location provider: the platform provider before Start, `GuidancePuck` during guidance
    (CAMERA.md). One `GuidancePuck` feeds both maps;
  - the camera of the style chosen in Settings, with a `MapboxNavigationViewportDataSource` per map
    (CAMERA.md);
  - the navigator observer (routes, progress, location matcher) and the drawing of each
    `GuidanceState`.
- It takes a `MapboxMap` and a `MapPluginProviderDelegate`. `MapView` and `MapSurface` (Maps 11.34.1)
  both implement that interface, and the camera, location and gesture plugins hang off it. So the
  phone and the car draw the same scene, and every later visual change of stages 4 and 5 lands on
  both.
- `PhoneMap` keeps the view parts: the logo and attribution plugins, the fade-in and the tap that
  picks an alternative.
- `CarNavigationMap` keeps the car parts: the surface, the widgets, the insets, the gestures, and
  since step 1b the frame clock and the car's puck. Day and night are the app's one look.
- The extraction comes first, with the phone unchanged and its tests passing.

## The car map

### Surface

- `CarNavigationMap` (`:engine-mapbox`, `car/CarNavigationMap.kt`) implements a new interface in
  `:core-nav`:

  ```kotlin
  interface CarMapRenderer : MapRenderer {
      fun zoomBy(delta: Double)
      fun release()
  }
  ```

  Day and night come through the `setLook(LookOnMap)` it inherits ([Day and night](#day-and-night)).

  `MapboxEngine.createCarMap(carContext: CarContext): CarMapRenderer` makes one per car Session;
  since step 1b `createCarMap(carContext, lookOnMap)`, with the app's look it starts in. The
  engine takes the host's areas itself, so `setViewportPadding` is not used on the car.
- One `MapboxCarMap` (maps-androidauto 11.34.1) per Session, set up with
  `MapInitOptions(context = carContext, …)`: map options from
  `MapOptions.Builder().applyDefaultParams(carContext).contextMode(ContextMode.SHARED)`, only the camera
  and location component plugins, and `styleUri = null`.
  - `MapboxCarMap.setup` registers the surface callback itself (`AppManager.setSurfaceCallback`), and an
    app has one. `:car` never calls `setSurfaceCallback`, and there is never a second `MapboxCarMap`.
    Built: the car map takes the callback from `prepareSurfaceCallback` and registers it inside one
    that catches every host call, as car-app rethrows a surface callback's exception on the main
    thread; still one callback.
  - `ContextMode.SHARED`: widgets throw without it, and Mapbox's own installer forces it, logging that
    UNIQUE "leads to graphical artifacts and crashes". The phone's `MapView` stays UNIQUE.
  - `styleUri = null`: the default would load Standard by itself as soon as the surface starts; the
    scene loads it with the palette instead.
- `CarNavigationMap` is the map's `MapboxCarMapObserver`. Every `onAttached` is a new map: on each
  `onSurfaceAvailable`, which also comes when the size or the DPI changes, the extension builds a new
  `MapSurface` and the style loads again. So the car map keeps its state (the look, guidance state,
  camera mode) and applies it on every attach. Built: of the camera mode, an overview the driver
  asked for during the drive under way ([Step 1](#where-it-settled-or-changed-the-design)). Expect a
  short reload when that happens. If it shows on a real head unit, replace `MapboxCarMap` with an
  own surface callback that calls `MapSurface.surfaceChanged`.
- `onDetached` releases the scene and removes the widgets. On the `onSurfaceDestroyed` path the
  `MapSurface` is destroyed before `onDetached`, so this only unregisters and cancels; it does not
  touch the map or the style.
- `release()` at the Session's ON_DESTROY destroys the current `MapSurface` itself (`onStop`,
  `surfaceDestroyed`, `onDestroy`). `MapboxCarMap` has no teardown, and car-app drops an
  `onSurfaceDestroyed` that arrives after the Session ended (read in its 1.7.0 source). Without
  this, the surface (its render thread, GL context, location provider and the scene's observer)
  outlives the Session while the drive continues on the phone, and every reconnect adds another.

### Insets, logo and attribution

- The host reports two areas (`SurfaceCallback`): the visible area, which nothing covers right now,
  and the stable area, which treats the coverings "as if they were always present". When the action
  strip hides, only the visible area changes. An empty `Rect` means unknown.
- `CarNavigationMap` computes the insets itself from the `Rect`s and the attached surface's size. It
  does not use `MapboxCarMap.stableEdgeInsets`, which is computed only when a surface already exists
  and not again for a new one, so it can be null or stale.
- Camera padding, as in CAMERA.md: following uses the stable area, its insets plus 5 dp, but at the
  bottom the inset plus a third of the area's height; the overview uses the visible area's insets
  plus 5 dp; the visible area stands in until a stable area arrives.
- The Mapbox logo and the text attribution sit in a bottom corner inside the stable area, so nothing
  covers them and they do not move when the strips come and go. This is DECISIONS 25 for the car.
  - The logo is Mapbox's `LogoWidget` (the SDK's own logo drawable, 85×21 px at mdpi), placed with
    `BitmapWidget.setPosition`, never resized or recoloured.
  - The attribution is a `BitmapWidget` with "© Mapbox © OpenStreetMap" drawn on a canvas at the
    car's density, in a day colour and a night colour, redrawn with `updateBitmap` when the mode
    changes (built inside `CarNavigationMap`, not as a class of its own). The car has no (i) button
    to tap, so the text itself shows. Mapbox allows changing its colour and size as long as it stays
    legible.
  - View plugins (logo, attribution, compass, scale bar) cannot attach to a `MapSurface`. Without
    widgets the car map would show neither logo nor attribution.
  - The widget API is `@MapboxExperimental` and may change between Maps releases.
  - No compass widget: the app would have to rotate it itself.
- At 800×480 and 160 dpi the logo is 85×21 px. Check there that the attribution stays legible next to
  it, also with the DHU's `contentinsets` and `stablecontentinsets`.

### Camera and gestures

- The car camera is described in CAMERA.md ("The car map"): `SmoothNavigationCamera` with
  `CameraTuning.CAR`, its own data source and the shared `GuidancePuck`. The style chosen in Settings
  applies to the car (DECISIONS 26); Smooth follow is the only style designed for the car so far.
- Pan and zoom reach the app only when the map action strip contains `Action.PAN`. Touch screens do
  not show the pan button. A double-tap arrives as a scale, and rotary nudges in pan mode arrive as
  a scroll. Some head units never send a fling or a scale, so the strip also has zoom buttons.
- Gestures go through a subclass of `DefaultMapboxCarMapGestureHandler`, which calls the camera's
  `requestIdle()` before passing each one on. The camera goes IDLE, so the car shows Recenter. A scroll moves the
  camera and a scale zooms around its focal point; the default handler does not implement a fling.
- The zoom buttons step by 0.5 and the zoom stays between 6 and 20, as in Mapbox's
  `CarNavigationCamera`.

### Day and night

- **The car's map shows the app's one look, the phone's** (DECISIONS 50, the owner's choice of
  2026-11-13, built in step 1b): Auto by the sun at the phone's position, or Day or Night by hand in
  Settings, with the phone's rules for when a switch goes during a drive (DECISIONS 48;
  [CAMERA.md](CAMERA.md), Day and night during guidance). `LookController` decides it once for the
  process, and both maps show it ([Step 1b](#step-1b-built)).
- `CarContext.isDarkMode()` says whether the car wants a dark map; changes arrive in
  `Session.onCarConfigurationChanged`. It is not read before the Session is CREATED. It colours only
  the images the app draws for the host's routing card, the maneuver icon and the lanes image, which
  sit on the host's card: the session builds the template again and each image is drawn once in the
  host's look. The map is not touched, so a flip at a tunnel's entrance writes nothing on it.
- **MR-1.** Google's car quality guidelines expect apps to draw light by default and to switch to
  dark when the host says so. Internal testing is not reviewed against them
  ([The real car](#the-real-car)); this choice is to be looked at again before any closed or open
  testing of the car screen.
- At night: the phone's night look (DECISIONS 15). By day: a bright, colourful map, Mapbox Standard
  with the `day` light preset and its default theme, not a faded one (DECISIONS 36). The route stays
  amber; `MapPalette.routeLine(look)` gives the route line its colours for each look.
- The car map takes its look through the `MapRenderer.setLook(LookOnMap)` it inherits, the entry the
  phone map uses, from `LookController.showOn` as the phone's map does, and writes it as the phone
  map does: a basemap's theme that waits for a quiet moment of the drive waits on both maps. The car
  map is made with the app's look, and the map of every new surface draws its first tiles in it.
- Built in step 1 and gone in step 1b: two stages against the host's tunnel flips, the light and
  colours at once and the basemap's theme after 60 s of the same mode (`CarLookTuning`).
- Plates, signs and stop pictures drawn on the car's surface follow the map's look, the app's; the
  images on the host's card follow the host.
- While the phone shows the list in place of its map (step 1c), the phone's map takes no part in the
  look and the car map's close view holds a switch; the list itself is in the night look's colours
  on black whatever the look, and the phone's map comes back in the look decided meanwhile.
- The switch needs no style reload:
  - every basemap key is set on its own with `setStyleImportConfigProperty`, as `MapLook.write`
    does today with the columns of `MapPalette.keys`. The day and the night columns name the same
    keys, so nothing from one look stays in the other;
  - the route line gets the look's colours through `MapboxRouteLineView.updateDynamicOptions` and is
    drawn again from `MapboxRouteLineApi.getRouteDrawData`;
  - the attribution bitmap is redrawn.
- A **car_look** line, with `dark`, the host's mode (left out when the car's context cannot say it),
  `cause` (`START` when the car map is made, `APP` when it takes a new look of the app, `HOST` when
  the host's mode changes), `look`, the look the map shows, and `themeDeferred` true while the
  basemap keeps the theme of the look before. An `APP` line also has what the map wrote: `onMap`
  (whether a surface's map was there; without one the next surface's map takes the look as it is
  made), `keys`, `errors` and `failedKeys`, `writeMs` and `theme`.
- `setStyleImportConfigProperties` is not used. It replaces the import's whole config and resets
  every key it does not name to the style default (Maps 11.34.1). One such key is the Navigation
  SDK's `showHdRoads=false`, which `MapHdRoadsVisibilityController` (ui-maps 3.34.1) sets only when a
  style loads. Since stage 1.1 it is also the app's own setting for lane details on the map
  ([DECISIONS.md](DECISIONS.md), 39). The car map will set it the way the phone map does:
  `MapRenderer` has `setLaneZoom` and `setLaneDetails` for both.
- The first look is applied in the STYLE data-loaded callback, from the app's look as the map is
  made, so there is no frame in the wrong look.
- Unverified: whether the light preset change animates. Check with Day and Night in the phone's
  Settings, and use `setStyleTransition` if the jump looks harsh.
- Mapbox's own car loader switches between two separate styles with a full reload; it is not used.
- The host's mode may follow Android Auto's own "Day/night mode for maps" setting or the car's light
  sensor, and some cars are reported to stay in day mode. It now colours only the card's images; the
  `car_look` lines record it beside the map's look.

### Performance

- The phone map and the car map render separately, each with its own render thread and GL context.
  With the phone app open and the car connected, the GPU work and the heat add up: on the first car
  test both maps drew far more frames than they needed and the phone grew hot quickly. The phone map
  stops drawing when its activity stops.
- Built in step 1b ([Step 1b](#step-1b-built)): the car's surface draws a real 30 frames a second,
  its cap held from the rate its render thread really ticks at (during guidance its own count, else
  the display's rate), since Mapbox paces it against 60 Hz while its render thread is called at the
  phone's rate; and while a car surface shows the map, the phone's map draws at most 60 frames a
  second (`capped` in its camera line), a cap its own pacing knows the phone's real rate for. The
  DHU streams 30 fps.
- Built in step 1c ([Step 1c](#step-1c-built)): during a drive while a car's surface shows the map,
  the phone shows the list of the maneuvers ahead and its map is put away and draws nothing, unless
  the driver chose the map. The car's frame target can be set to 60 under Debug, to compare.
- With the phone locked or the app in the background the main thread may get no display frames. The
  car's camera, its zoom buttons' ease and its puck take their frames from the car's frame clock,
  which falls back to a main-thread timer then. Mapbox's background animation thread
  (`AnimationThreadController`, experimental, for every Mapbox animation in the app) is not used.
  [CAMERA.md](CAMERA.md) has the clock and the checks.

## Screens and templates

### Car API level 7

- `androidx.car.app.minCarApiLevel` is required; the library refuses a missing value or one outside
  1 to 8. The handshake fails when the host's level is lower than the app's.
- 7 is the level of `MapWithContentTemplate`, which replaces the deprecated
  `RoutePreviewNavigationTemplate`, `PlaceListNavigationTemplate` and `MapTemplate`. A header in a
  `ListTemplate` or a `MessageTemplate` needs 7 as well. `NavigationTemplate` (level 1) is not
  deprecated, also not in 1.9.0-alpha03.
- Current Android Auto hosts support at least level 8. This is an inference: Google requires media
  apps that use `MediaPlaybackTemplate` to set level 8, and those apps run on Android Auto.
- No `@ExperimentalCarApi` APIs and nothing that exists only in 1.9.

### Screens

| Screen | Template | Shows |
|---|---|---|
| `CarHomeScreen` (root) | `NavigationTemplate`, always | Idle: the map. Guidance: the routing card (`RoutingInfo`: the current step and its distance, the next step, the lanes image) and the arrival estimate (`TravelEstimate`). |
| `CarSearchScreen` | `SearchTemplate` | Suggestions from `SearchProvider`. The keyboard is often locked while driving (DHU `restrict all`), so the phone stays the main input. |
| `CarRoutePreviewScreen` | `MapWithContentTemplate` holding a `ListTemplate` | The routes, with a header and Start, over the map, plus the map action strip. Pushed whenever the engine enters PREVIEW, from either screen; popped when it leaves. |
| `CarMessageScreen` | `MessageTemplate` | Setup: a loading message while the token is read, then what is missing (the token, precise location) and that it is done on the phone, looking at the phone only when it is safe (VI-1). Also the resume offer and a refused start. |

### Action strips

| Strip | Idle | Guidance |
|---|---|---|
| Action strip, up to 4 actions | Search (step 1: Start on phone, which opens a message) | End |
| Map action strip, up to 4 icons | Pan, zoom in, zoom out, Recenter or Overview | the same |

- `NavigationTemplate.Builder.build()` throws without an action strip.
- The fourth map button is Recenter while the camera is IDLE; otherwise it switches between following
  and the overview.

### The template quota

- The host allows at most 5 templates per task. Past that, it shows an error and closes the app. The
  last template must be a `NavigationTemplate`, `PaneTemplate`, `MessageTemplate` or one of a few
  others.
- Reaching a `NavigationTemplate` resets the count, and any change inside a `NavigationTemplate` is a
  refresh. Popping screens gives quota back; a launch from a notification or the launcher resets it.
- A template of the same type with the same main content does not count. For a `ListTemplate` that
  means the same number of rows and the same title in each row.
- The engine moves the selected route to the front of its list (`selectRoute`). Rows built in that
  order change their titles with every tap, so every tap would count: root, search (1), preview (2),
  three taps reach 5 and the next one closes the app. The preview keeps its rows in a fixed order,
  the order the routes arrived in, and marks the selected one with a selectable `ItemList`.
- Every flow ends back on the root `NavigationTemplate`: Start pops to it.

### The routing card

- The mappers are pure Kotlin on domain types, tested with JUnit 6; only thin builders touch car-app
  classes.
  - `ManeuverToCar`: domain maneuvers to `Maneuver.TYPE_*` (49 types), with the rules of Mapbox's
    `CarManeuverMapper` for right-hand traffic. A U-turn is `TYPE_U_TURN_LEFT`. An off-ramp or a fork
    without a side goes to the near side. Arrival maps to `DESTINATION`, `DESTINATION_LEFT`,
    `DESTINATION_RIGHT` or `DESTINATION_STRAIGHT`. Roundabouts and rotaries map to
    `ROUNDABOUT_ENTER_AND_EXIT_CCW`, or its `_WITH_ANGLE` form, with an exit number (1 when unknown)
    and an angle from 1 to 360. Leaving a roundabout is `ROUNDABOUT_EXIT_CCW` without a number;
    continue is `STRAIGHT`. The host rejects a type that needs an exit number or angle without one.
  - `LanesToCar`: `LaneInfo` to `Lane` and `LaneDirection.SHAPE_*`.
  - Distances: `UNIT_FEET` for short distances, `UNIT_MILES_P1` under 10 mi and `UNIT_MILES` above, as
    `:core-nav` formats them on the phone.
  - Times: a `TravelEstimate` from a `Distance` and a `ZonedDateTime`, with `setRemainingTimeSeconds`.
- Images in the app's own style (DECISIONS 19), cached per maneuver, lane set and mode, never drawn
  again on a progress update:
  - the maneuver icon, 128×128 dp;
  - the lanes image, 500×74 dp on a transparent background, with the lanes to use standing out, in a
    day and a night version;
  - the junction image (500×312 dp) is stage 6.

  Mapbox's car renderers draw lanes at 294×44 dp and turn icons at 64 dp, smaller than Google's
  current guidance.
- A step with `Lane` objects always gets the lanes image as well: `RoutingInfo.build()` throws
  otherwise, and `Step.Builder.build()` throws for a lanes image without lanes. The template shows
  mainly the image; the `Lane` data feeds the cluster and the head-up display.
- The surface shows only the map (NF-2): directions, lanes and the arrival estimate go in the
  template, and the host draws them.
- Built in step 1: the card is made from the phone card's own content (`CarRouting`): the lane row
  is `CardLanes`' (the run-up's row first, then a lane choice ahead, then the maneuver's own), the
  Then step is `thenManeuver`, the words are the card's ("Exit 24: Oak Street · Office", and the
  road alone where the host has less room), and the arrows are the phone's shapes (`GlyphShapes`).
  The lanes image is the row without its words or distance. Lanes whose image could not be drawn
  are left out with it. The images have a day and a night version, with a thin dark edge round
  every arrow and bar ([Step 1](#where-it-settled-or-changed-the-design)).
- `RouteProgressSnapshot` has what the car needs beyond the phone's card, since the Then chip was
  built ([DECISIONS.md](DECISIONS.md), 37): the step after the next one for
  `RoutingInfo.setNextStep` (`thenManeuver`, set when the Then rule shows it, so the car shows the
  next step at the same moment as the phone), times per step for the `TravelEstimate` that
  `Trip.Builder.addStep` needs (`durationToNextManeuver`, and `following.durationAfterNext`), and
  `offRoute` as the reroute signal for `setLoading(true)`. Since the lane zoom was built (38) it
  also has `laneChoiceAhead`: the lanes of a lane choice the route goes straight through, with its
  distance, which the car's lanes image can show on a freeway.

## The guidance bridge to NavigationManager

`CarGuidanceBridge` in `:car` turns the engine's state into `NavigationManager` calls. It is plain
Kotlin over a small `NavigationManagerPort` interface, so its rules are tested on the JVM. It lives
while the Session is CREATED, not only while it is STARTED, so the cluster and the turn
notifications continue while another app is in front.

### Calls

| Engine | NavigationManager |
|---|---|
| The Session's ON_CREATE | `setNavigationManagerCallback`, the form with one argument (main thread), before anything else |
| ACTIVE, also when a Session is created mid-drive | `navigationStarted()` |
| Progress | `updateTrip(Trip)` on a maneuver change, otherwise at most once a second |
| A reroute | `updateTrip` with `Trip.Builder.setLoading(true)`, never `navigationEnded()` |
| IDLE or ARRIVED | `navigationEnded()` |

The library's rules, read in its 1.7.0 source: every call on the main thread; `navigationStarted()`
throws without a callback; `updateTrip()` throws when navigation has not started;
`clearNavigationManagerCallback()` throws while navigating; a second `navigationStarted()` or
`navigationEnded()` does nothing. The same in 1.8.0-rc01.

Built in step 1, without AUTO_DRIVE. A held-back update goes out when its second is over, with the
newest content, so the navigator's update a second all reaches the host. A drive that ends after a
start the navigator refused (back to the preview) ends navigation too. The library calls its own
`onStopNavigation()` at the session's ON_DESTROY, before the app's own ON_DESTROY observers run, so
the bridge clears its callback there without the refusal; the posted callback then finds none.

### try/catch around every call

- The car, the phone and the drive share one process. An exception from a `NavigationManager` call
  on the main thread would end phone guidance too, which DECISIONS 32 rules out.
- The gap is real: when the host stops navigation, the library marks navigation as stopped at once
  but only posts the app's `onStopNavigation`. A progress update handled in between calls
  `updateTrip`, which throws `IllegalStateException`. Errors raised by the host come back as
  `HostException`.
- So every call is wrapped in a try/catch for `RuntimeException`, logged and dropped, as Mapbox's own
  `MapboxCarNavigationManager` (android-auto-components 3.34.1) does. The bridge also keeps its own
  navigating flag and sends no trip unless it is set.

### onStopNavigation

- The host calls it when another source starts navigating, such as another navigation app or the
  head unit's own navigation. Google's contract is that the app then stops routing: no more trips,
  turn notifications or voice (quality guideline NF-5).
- The owner's choice (DECISIONS 32): this app keeps guiding. `onStopNavigation` never stops the trip
  session. The bridge:
  - clears its navigating flag, so no more `updateTrip`, and the cluster shows nothing from this app;
  - leaves the trip session, the phone screen and the phone notification running; the drive ends
    only with End or arrival. The host itself hides this app's turn notifications while another app
    is the active navigation app;
  - logs the call with the Session's lifecycle state and whether the car is still connected
    (`CarConnection`), so a disconnect can be told from a real host stop.
- A disconnect also arrives as `onStopNavigation`. When the host unbinds, the Session reaches
  ON_DESTROY; `NavigationManager` observes that and calls its own `onStopNavigation()`, which posts the
  app's callback if navigation had started (car-app 1.7.0 source). Because neither path stops the
  drive now, the drive no longer depends on telling them apart. At ON_DESTROY the bridge marks itself
  gone and clears its callback inside a try/catch; a posted callback that still arrives only logs.
- Taking the car back (the owner's choice): when the driver opens Custom Maps on the car screen again
  during the drive, the bridge calls `navigationStarted()` again (built: the host's new intent and
  the session's start both ask, once, while the bridge is not navigating). Unverified: whether that
  makes this app the active navigation app in the car again, and whether the host then asks the
  other app to stop, as it asked this one; check on the DHU.
- NF-5 blocks a release only on the open testing and production tracks. Closed testing is reviewed
  without blocking, and internal testing is not reviewed.

### AUTO_DRIVE

- `adb shell dumpsys activity service <package>/<car app service class> AUTO_DRIVE` calls
  `onAutoDriveEnabled` on every bound Session. If no callback is set yet, `NavigationManager` keeps the
  request and delivers it once one is set. Play requires this for its review (NF-7).
- The bridge sets a flag for the Session's lifetime: the next Start pressed in the car runs a
  simulated drive. It does not start guidance by itself: without a preview that would do nothing,
  and with one it would start a billed trip without Start.
- The debug build needs the full class name:
  `io.github.purplepalm.custommaps.debug/io.github.purplepalm.custommaps.car.CustomMapsCarAppService`.
  The short `/.car…` form resolves against the `.debug` application id and names a class that does
  not exist.

## Turn-by-turn notifications in the car

### Mapbox's trip notification, extended

- In the car, turn-by-turn notifications show in the rail at the bottom of the screen and pop up as
  heads-up notifications. They must be ongoing, use `CATEGORY_NAVIGATION` and carry a
  `CarAppExtender`; quality guideline NF-3 asks for them whenever the app shows text directions. They
  never appear in the car's notification centre. The host does not show them while this app shows
  routing in its own template, or while another app is the active navigation app. So they appear
  when another app, such as music, is in front.
- No second notification is posted. Mapbox's trip notification is extended through
  `MapboxNavigation.setTripNotificationInterceptor` (Navigation 3.34.1), whose documentation says it
  is needed "to extend the notification for Android Auto". Its type is
  `(NotificationCompat.Builder) -> NotificationCompat.Builder`, so no Mapbox type leaves the engine.
- The navigator has one interceptor slot, so the engine owns it:
  `MapboxEngine.setTripNotificationExtender(extender)` stores the extender, and the engine installs
  the interceptor when the navigator attaches and clears it when it detaches.
- `CarTurnNotification` (the extender, in `:car`) adds:
  - `setOngoing(true)` and `CATEGORY_NAVIGATION`;
  - a `CarAppExtender` with the distance and the arrival time as title, the instruction as text, the
    maneuver icon as large icon, the small icon, the amber colour, and a content intent from
    `CarPendingIntent.getCarApp` aimed at the car app service. `getCarApp` takes any `Context` and
    makes the intent mutable, as the library requires.
- `AppGraph` sets it once with the application context, for every drive, not per car Session. The
  rail then shows turns as soon as the car reconnects, nothing changes at a disconnect, and no dead
  `CarContext` is kept.
- Actions set on a `CarAppExtender` replace the notification's own actions in the car. Without them
  the car shows Mapbox's End Navigation, which ends the drive like End (`STOPPED_OUTSIDE_APP`). Check
  the rail on the DHU.

### Heads-up only when the maneuver changes

- Mapbox's channel ("Navigation Notifications") has `IMPORTANCE_LOW`, so the car shows a heads-up only
  when `CarAppExtender.setImportance(IMPORTANCE_HIGH)` is set.
- The trip notification is one ongoing notification, updated in place with every progress update.
  With `setOnlyAlertOnce(true)` on every update only the first maneuver would ever pop up; with HIGH
  and `setOnlyAlertOnce(false)` on every update it would alert constantly.
- So the update on which the maneuver changes gets HIGH and `setOnlyAlertOnce(false)`. Every other
  update gets `setOnlyAlertOnce(true)` and the channel's importance, which only refreshes the rail.
  Google's own navigation sample also alerts only on the updates it marks. The phone stays quiet,
  because the channel is LOW.

### Background thread

- The trip service calls the interceptor on the main thread at the trip's start and on a background
  coroutine afterwards.
- "The maneuver changed" is state shared between those threads. The extender keeps it behind a lock,
  as Mapbox's own `MapboxCarNotification` does; a `@Volatile` field alone is not enough. The bitmaps
  come from the renderers' cache; nothing is drawn per update.

### Notifications permission

- On Android 13 and newer, without `POST_NOTIFICATIONS` all of the app's channels are blocked. The
  foreground service still runs, but its notice shows only in the Task Manager, and the car gets no
  rail and no heads-up.
- Today the phone asks only when Start is pressed on the phone, and starts either way. A drive started
  in the car never asks.
- So setup on the phone asks for notifications too. A Start in the car with notifications off goes
  ahead, and both screens say that turn notifications in the car need them
  (`areNotificationsEnabled()`). The car can ask with `CarContext.requestPermissions`, which shows the
  request on the phone.

## The start problem on Android 14 and 15

### What goes wrong

- Google's `CarAppService` reference: an app shown on the car display is not in the foreground, so it
  counts as background for location. Google recommends a foreground service in the same process as
  the car app service.
- A trip session runs Mapbox's location foreground service (`NavigationNotificationService` with
  `foregroundServiceType=location`, Navigation 3.34.1). Starting it from the background runs into two
  rules:
  - Android 12 and newer refuse a foreground service start from the background without an exemption,
    with `ForegroundServiceStartNotAllowedException`, an `IllegalStateException`. The exemption lists
    do not mention Android Auto or car apps.
  - Android 14 and newer refuse a location foreground service without while-in-use eligibility, with
    a `SecurityException`, unless location is allowed all the time. In AOSP 15 the location type is
    granted when the location permission is "Allow all the time", or when it is "while in use" and
    the start is while-in-use eligible.
- How Mapbox 3.34.1 reacts:
  - `MapboxTripService` tries `startService` and, if that throws, `startForegroundService` with no
    further catch. A `ForegroundServiceStartNotAllowedException` there escapes `startTripSession`;
    `MapboxNavigationEngine` catches only `SecurityException` around it today.
  - `NavigationNotificationService` catches `ForegroundServiceStartNotAllowedException` from
    `startForeground` and only logs it; the service then runs without foreground status. It rethrows
    anything else, such as Android 14's `SecurityException`, inside the service, where the app cannot
    catch it: the app crashes.
  - Mapbox issue [#7888](https://github.com/mapbox/mapbox-navigation-android/issues/7888), still open
    and filed against SDK 2.20.2 on API 34, reports this crash when the service starts from the
    background with Android Auto.
- Simulated drives start the same service: `ReplayRouteSession` calls `startReplayTripSession` with a
  foreground service.
- `startTripSession(withForegroundService = false)` is no way out: Mapbox documents that there are then
  no notifications and no location updates while the app is in the background.
- Unverified: whether Android Auto's binding exempts the app. AOSP 15 lets a bound app inherit the
  right to start a foreground service from its client, and grants while-in-use through some kinds of
  binding. Google's own navigation sample (targetSdk 34) starts its location service when navigation
  starts on the car screen. Whether the Android Auto host binds in a way that qualifies is not
  documented.

### The plan

- **"Allow all the time"** (DECISIONS 33), asked for right after precise location: an explanation on
  the phone, then the app's location settings page. On Android 11 and newer "Allow all the time" can
  be set only there, and asking for foreground and background location together is ignored. Settings
  shows the state with a button. The explanation also serves as the prominent in-app disclosure that
  Google Play asks for. While this stage is built only the debug manifest
  (`app/src/debug/AndroidManifest.xml`) declares `ACCESS_BACKGROUND_LOCATION`, and a build without
  it skips this step and relies on the start guard; the Play bundle gets it once, by hand, with
  Google's declaration ([The real car](#the-real-car)).
- **Start guard**, `TripStartPolicy` in `:engine-mapbox`: a trip may start when Android is older than
  10 (API 29), or background location is granted, or the phone's `MainActivity` is at least STARTED,
  that is visible. Start on the phone with the app on screen is always safe: it is while-in-use
  eligible, and the running foreground service then keeps location with the screen off and across
  car connects and disconnects.
  - `NavigationEngine.startGuidance` returns a `StartResult`, Started or
    `Refused(NEEDS_PHONE_OR_ALWAYS_LOCATION)`, instead of nothing.
  - `execute(StartTripSession)` checks again. If the check fails there, a new
    `GuidanceEvent.StartBlocked` takes the state machine back to PREVIEW with `StopTripSession` and
    `ClearRoutes`, as `CommitFailed` does.
  - The reason reaches both screens through a `startProblem` state flow, because a block inside
    `execute` comes after `startGuidance` has already returned Started.
  - Simulated drives follow the same rule. Nothing calls `startTripSession` or
    `startReplayTripSession` while it fails.
- **Exceptions:** `startTripSession`, `startReplayTripSession` and the replay's attach are wrapped for
  `IllegalStateException` (which covers `ForegroundServiceStartNotAllowedException`) and
  `SecurityException`; either one dispatches `StartBlocked`. The `SecurityException` thrown later inside
  Mapbox's service cannot be caught; only the guard prevents it.
- **Back to the preview:** a refused or blocked start shows the routes again with the reason. In the
  car: unlock the phone and open Custom Maps, or allow location all the time in Settings on the
  phone, when parked.
- **Debug switch:** **Settings → Debug → Start without the guard** (debug builds only) skips the
  guard, to find out whether Android Auto's binding is enough without background location. With
  "while in use" location this may crash the app; that is the test. The crash lines of stage 1.1
  record it.
- **Foreground service check:** about 3 s after `StartTripSession` the engine looks for its own
  `NavigationNotificationService` in `ActivityManager.getRunningServices()` and logs `trip_service`,
  with whether it runs in the foreground. That call is meant for debugging, so it feeds only the log
  and a warning: if the service is not in the foreground (the start was refused and Mapbox swallowed
  it), both screens warn that the drive will stop when the car disconnects.
  `MapboxNavigation.isRunningForegroundService()` is the SDK's own flag, not the system's, and is not
  used.
- Each start also logs `PowerManager.getLocationPowerSaveMode()`, with a version check because it
  needs API 28.
- New log lines: `host_attached` with the kind of host, `car_session`, `start_refused`, `trip_service`,
  and `navigator_detached_mid_drive` if the navigator ever detaches while ACTIVE, after which the
  engine goes back to IDLE.

### Location before Start

- Before Start the puck and the route origin come from the platform location provider
  (DECISIONS 14). With the phone locked and only "while in use", the car's puck can be missing or
  stale, and a route asked for in the car can fail for lack of a position or start from an old one
  (`previewRoutes` falls back to the last known fix or the last puck position).
- Seen in the first car test: after the drive ended, with the phone app in the background, the car
  map's platform location brought no fix (`car_location` with `fixes` 0).
- "Allow all the time" covers this too. Free drive is not an option: it is billed (DECISIONS 13).

### The first device test

Right after the car skeleton, on the owner's Samsung phone with Android 15 and the DHU, before more
car screens are built: case (b) of the [test matrix](#test-matrix), with "while in use", with "all
the time" and with the guard skipped, plus the idle puck and a route asked for in the car with the
phone locked. The result decides whether the guard stays as it is or is relaxed.

## Resume after a crash

- A drive marker, a small DataStore file outside the backup rules (DECISIONS 21), holds the
  destination (name, address, point and stop point), the start time and whether the drive is
  simulated.
- It is written once the trip session has started, not when the state enters ACTIVE: `CommitFailed`
  and `StartBlocked` go back to PREVIEW without a session, and a marker left by them would offer to
  resume a drive that never began. Go there now updates it. Every move out of ACTIVE clears it.
- At the next start of the process, if the marker exists and the engine is IDLE, the phone and the
  car offer "Resume to <destination>". Resume asks for routes from where the car is and starts a new
  trip, which Mapbox bills as a new trip (DECISIONS 32). Not now clears the marker. The start guard
  applies as usual.
- Android may recreate the process and Mapbox's service on its own (the service is `START_STICKY`)
  with no trip running. The engine does not treat that as a drive; the offer covers it.
- The previous process's exit reason is in the debug log (stage 1.1). The interrupted trip's file is
  closed with the outcome `interrupted` and uploaded.
- Test with a **Crash now** button in Settings → Debug (debug builds only) during guidance, not with
  `am force-stop`: a force stop is a different kind of exit, and it also cancels the app's scheduled
  work until the app is opened again.

## Shared links

### Where links come from

- The share menu: Google Maps, Apple Maps and Waze share text with a link. The app takes
  `ACTION_SEND` text.
- Opening a link: the app can be the default for map links (Android's Open by default,
  [below](#intent-filters-and-open-by-default)) and for `geo:` addresses.
- Optional: a map link, a `geo:` address or coordinates pasted into the search field take the same
  path.

### Formats

| Source | Form | Result |
|---|---|---|
| Google | `!3d<lat>!4d<lng>` inside `data=` | Exact: the place itself. Wins over everything else. |
| Google | `/place/<lat>,<lng>`, `/search/<lat>,<lng>`, `q=` or `query=` with coordinates, `daddr=` with coordinates or `text@lat,lng`, `api=1` links with coordinates | Exact: a pin |
| Google | `/dir/a/b/…` | The destination is the last segment. Coordinates give Exact; otherwise the last `!1d<lng>!2d<lat>` pair in `data=` (longitude first), when there is one pair per waypoint; otherwise a search. |
| Google | `/place/<Name, Address>/data=…!1s<feature id>` and `maps.google.com?q=<Name, Address>&ftid=<id>`, which the Google Maps phone apps share | Name only: the [place page](#google-place-pages) is read; otherwise a search near the place hint |
| Google | `@lat,lng,zoom` alone (a map view or Street View), `map_action=map`, `map_action=pano` | Exact at the map centre |
| Google | `maps.app.goo.gl/<id>`, `goo.gl/maps/<id>` | Short: [one request](#short-links) |
| Google | `maps.app.goo.gl/?…&link=<URL>` | The inner link, read on the phone, only if it is a Google Maps link |
| Google | `placelists/…`, My Maps `d/…`, `/@/data=…` | Not a single place |
| Apple | `maps.apple/p/<id>`, which iOS 26 shares | Short: one request |
| Apple | `/place?coordinate=…`, `/directions?destination=<lat>,<lng>`, legacy `?ll=` | Exact, with `name` and `address` when present |
| Apple | `/place?address=…` or `name=…`, `/directions?destination=<text>`, `/search?query=…`, legacy `?q=` and `?daddr=` | Search, near `sll`, `center` or `near` when present |
| Apple | `/frame?center=…` | Exact at the map centre |
| Apple | `place-id` or `auid` only | Cannot be read without loading a page |
| Waze | `/ul/h<geohash>`, `live-map?h=<geohash>` | Exact, decoded on the phone with no request |
| Waze | `ul?ll=…`, `latlng=…`, `live-map/directions?to=ll.<lat>,<lng>` | Exact, named by `q=` when present |
| Waze | `ul?q=…` | Search |
| Waze | `live-map/directions/<slug>?to=place.…` | Search for the slug |
| Waze | `venue_id=…` or `place=w.…`; `a=share_drive` or `live-map/meeting`; `favorite=` | Cannot be read: a place id only, a live drive, a favourite |
| `geo:` | `geo:<lat>,<lng>[,<alt>][;crs=wgs84][;u=…][?q=…&z=…]` | Exact. `q=<lat>,<lng>(<label>)` gives a named pin; `q=<text>` a search near the point unless it is 0,0. |
| `geo:` | out of range (`geo:94,0`); another `crs`; bare `geo:0,0` | Refused: bad coordinates (RFC 5870 says invalid geo URIs must not be used); not supported; no location |
| Text | a lone `lat, lng` | Exact |
| Text | anything else, up to 256 characters | Search |

- In Google URLs, `@lat,lng,zoom` is only the camera. It is never the destination when the link has a
  pin or a name: in one sample the `@` point was a Street View camera 834 m from the pin. The zoom
  part can read `13z`, `883m` or a Street View camera. Google documents neither `@` nor `data=`.
- Precedence for Google: `!3d`/`!4d`, then coordinates written in the link, then the name or address,
  then the `@` centre.
- Links that the Google Maps phone apps make for a business or an address expand without
  coordinates, in one of the two name-only forms above. Dropped pins, and links made on the web, carry
  `!3d`/`!4d`.
- The expanded URL is written in the sharer's language; neither `Accept-Language` nor the `g_st`
  suffix changes it.
- Google's documented URLs (`/maps/search/?api=1&query=…`, `/maps/dir/?api=1&destination=…`) need
  `api=1`; without it the other parameters are ignored
  ([Maps URLs](https://developers.google.com/maps/documentation/urls/get-started)).
- Apple's unified Maps URLs (iOS 18.4 and newer) are `/place`, `/directions`, `/search`, `/frame`,
  `/look-around` and `/guides`; legacy forms such as `?ll=`, `?q=`, `?address=`, `?daddr=` and `?sll=`
  still circulate ([unified map URLs](https://developer.apple.com/documentation/mapkit/unified-map-urls)).
  Apple's developer support wrote in October 2025 that the short URL "is not intended to be a
  structured API or parsed out like this".
- Waze links ([deep links](https://developers.google.com/waze/deeplinks)): the Waze app shares "Use
  Waze to drive to <address>: https://waze.com/ul/h<geohash>". The geohash is standard base-32
  (`0123456789bcdefghjkmnpqrstuvwxyz`), 4 to 12 characters of 5 bits each, the first bit for
  longitude; the cell centre is used, and 9 characters are about ±2.4 m. Waze's server only
  redirects these links, so nothing is gained by asking it.
- Some links look like map links but name no single place: Google Search shares (`g.co/kgs/…`,
  `share.google/…`) and saved lists.

### The parser in :core-nav

- Package `nav/link`, pure Kotlin on the Java 8 API (DECISIONS 4), offline: `MapLinks.parse(text)`
  returns a `MapLink`, or null for "not a map link".
- Result types:
  - `Exact(point, name?, address?, kind)`, with kind PLACE, PIN or MAP_CENTER;
  - `Search(text, name?, near?)`, where `near` only biases the search and is never the destination;
  - `Short(url)`;
  - `Unsupported(reason)`, with PLACE_LIST, PLACE_ID_ONLY, LIVE_TRIP, FAVORITE, NO_LOCATION or
    BAD_COORDINATES.
- Files: `UrlText.kt` (a tolerant URL splitter and decoder), `MapLink.kt`, `GoogleMapsLinks.kt`,
  `AppleMapsLinks.kt`, `WazeLinks.kt`, `GeoUris.kt`, `Geohash.kt`, `GoogleFeatureId.kt` (the place
  hint), `MapLinks.kt` (dispatch by host and the short link policy), `SharedText.kt`, the
  `ShortLinkExpander` and `PlacePageReader` interfaces, and `SharedPlaceResolver.kt`.
- Decoding:
  - URLs are split by hand. `java.net.URI` rejects the raw spaces and `|` found in real links;
    `android.net.Uri` cannot read the query of a `geo:` URI, which is opaque, and is an Android type
    anyway.
  - Hosts are lowercased; http and https are both accepted, and so are bare hosts without a scheme.
  - In query values and in Google and Apple path text, `+` becomes a space first, then the bytes are
    percent-decoded as UTF-8, so `%2B` stays `+`. A malformed escape stays as it is, and invalid UTF-8
    becomes U+FFFD. `URLDecoder` is not used: it throws, for example on Apple's own documentation
    example.
  - Path segments are split before decoding, because names can contain `%2F`.
  - Numbers have up to 3 integer digits and 17 decimals, with a comma and optional spaces or `+`
    between latitude and longitude. The range is checked before a `GeoPoint` is built, whose
    constructor throws. 0,0 counts as no point.
- `SharedText.read(text, subject)`: the input is capped at 8 KB. It finds URLs with a scheme and bare
  map hosts; a link that starts a line runs to the end of the line, because legacy links contain
  spaces. It strips trailing punctuation, quotes and HTML leftovers such as `</a>` or `%3C/a%3E`. It
  takes the first link the parser recognises, and keeps the other lines and the subject as name
  hints, without "Use Waze to drive to" and a trailing colon.

### Short links

- Only `maps.app.goo.gl/*`, `goo.gl/maps/*` and `maps.apple/*` are expanded, always over https, with
  exactly one GET to Google's or Apple's link server. Google answers 302 and Apple 301, each with an
  empty body and the full URL in `Location`.
- `HttpShortLinkExpander` in `:app`: `HttpURLConnection`, GET (Apple answered a first HEAD with 404),
  redirects not followed, no caches, no cookies, the platform's default headers, 5 s connect and read
  timeouts. It reads only the status and `Location`, resolves a relative `Location`, and runs on the
  IO dispatcher.
- The `Location` is accepted only if it parses to a link that is not short, on an allowed host:
  `google.<tld>` or `maps.google.<tld>` for Google, `maps.apple.com` for Apple. If it points to
  `consent.google.com`, its `continue=` parameter is read; that page was not seen in tests.
- The chain is not followed further. Apple's chain ends on a generic "unsupported" page. Google's next
  hop is the place page itself, which is read only as described next.
- Messages: 404 or 410, "This link no longer works"; a 200 or a foreign host (a captive portal, for
  example), "Couldn't read this link"; no network, "No connection" with Retry.
- Expanding a link sends its id and the phone's IP address to Google or Apple, as tapping the link
  would.

### Google place pages

- The owner's choice (DECISIONS 34): on by default, and **Settings → Map links → Exact places from
  Google pages** turns it off.
- It applies to Google links without coordinates: the name-only forms that the Google Maps phone apps
  share, and Google links that carry only a place id.
- One GET of the place page, which is the expanded Google Maps URL or the link itself, on a Google
  host over https, with the same timeouts and no cookies. Unlike a short link, the body is read, up to
  a size cap. The coordinates read from the page are used as an exact point; the name and the address
  come from the link.
- Unverified: where the page holds the coordinates, and whether Google serves them to a plain HTTP
  client. The page format is undocumented and can change any day, and Google's terms do not allow
  reading its pages this way, so it may stop working. The reader is written against live pages during
  the build, tested on the JVM with recorded excerpts (CI makes no network calls), and treats
  anything unexpected as "not found".
- Everything else falls back to the search below: the switch off, no network, a consent or bot-check
  page, an error, or no coordinates in the page.
- Log: `link_page`, with the status, the host, the size and whether coordinates were found; never the
  page itself.
- Apple links with only a place id and Waze venue links get no page reading in this stage; they offer
  the hand-back buttons.

### From link to place

`SharedPlaceResolver` in `:core-nav` parses, makes at most one short link request, reads the page
when it applies, then searches. Its tests use fakes.

- **Exact:** the link's point is the place, used as it is. One Search Box lookup at that point
  (`SearchEngine.search(ReverseGeoOptions)`, Search 2.34.1) fills in a name and an address when the
  link has none; DRIVES.md takes the stop point from the same lookup when it lies within about
  150 m. The lookup's point never replaces the link's. If it fails, the place is "Dropped pin".
- **Search:** one Search Box `/forward` request (`SearchEngine.forward`, Search 2.34.1), in English,
  up to 5 results, at most 256 characters, with no country filter because the shared text names the
  country. Typed search stays limited to the US (DECISIONS 17).
  - `near` is the place hint when a Google link carries a feature id (the owner's choice), otherwise
    the link's `@` centre or Apple's `sll`, `center` or `near`, otherwise nothing, and the SDK then
    uses the phone's position and after that its IP address.
  - One result, or a top result whose name or street shares words with the link's name: that place,
    marked as found by search. Otherwise a pick list of the top 5.
- **The place hint:** the first 64 bits of a Google feature id (`0x…:0x…`) decode as an S2 cell near
  the place. This is undocumented. In seven samples the cell was 4 to 8 m from the place for a street
  corner, a street address and a restaurant, 0.4 km for a large park, and 6 to 21 km for two
  businesses and a city. So it biases the search and is never a destination.
- **Unsupported or failed:** the original link stays for two buttons. Open in Google Maps sends
  `ACTION_VIEW` with `setPackage("com.google.android.apps.maps")`; Open in browser uses `setPackage`
  with the browser that opens `https://example.com`. An intent with a package reaches that app
  whichever app holds the domain. A bare `ACTION_VIEW` is never used: once this app holds the domain,
  the link would come straight back.
- Without a token yet (`MapsAccess` not Ready), the input waits and runs once the token is applied.
- Neither search call writes search history (DECISIONS 17). Results are used for the moment only, as
  Mapbox's terms ask, and are not stored.
- Cost per link: at most one request to Google's or Apple's link server, one Google page when it
  applies, and one Search Box request. `/forward` and `/reverse` are billed per request, with 50,000 a
  month free at introductory preview pricing or 25,000 at standard pricing.
- Log lines: `link_received`, `link_parsed`, `link_expanded` (the status and the `Location` host),
  `link_page`, `link_resolved` and `link_failed`.

### A link during a drive

- The owner's choice: a link that arrives while guidance is ACTIVE offers **Go there now** on the
  phone and in the car. A confirmation names the new destination, and only then does the drive
  change.
- On the phone the offer is a dialog. In the car it is an `Alert` (`AppManager.showAlert`, Car API 5,
  within this stage's level 7) with the new place as its title and two actions, **Go there now** and
  **Not now**, and a 20-second timeout. Driving rules allow it: an alert is the car library's own
  surface for this, and it needs one tap.
- The first answer on either screen wins: the other screen's offer is dismissed at once
  (`AppManager.dismissAlert` in the car). Not now, the timeout, or ending the drive dismisses both.
  Without a car connection only the phone asks.
- `NavigationEngine.changeDestination(place)` asks for routes from where the car is to the new place
  and hands them to the navigator inside the running trip session. It never clears the routes in
  between, which would turn the session into billed free drive (DECISIONS 13). If the request fails,
  the drive goes on unchanged and the phone says so.
- In the state machine this is an event in ACTIVE whose only command replaces the routes. It stays
  ACTIVE, and a test checks that it never emits `ClearRoutes` or `StopTripSession`.
- The car's routing card, the drive marker and the trip file follow the new destination; the trip
  file logs `destination_changed`.
- A Directions request during a session is part of the trip. Unverified: whether Mapbox bills the new
  destination as a new trip. The trip session log and the
  [Mapbox statistics page](https://console.mapbox.com/account/statistics/) will show it.
- Without a drive, a link becomes a preview at once, like a picked suggestion, on the phone and in the
  car. In ARRIVED, the arrival is dismissed first.

### Intent filters and Open by default

- `LinkInActivity` (`:app`, `link/`): exported, `Theme.NoDisplay`, `excludeFromRecents`, `noHistory`,
  `taskAffinity=""`. In `onCreate` it reads the `ACTION_SEND` text and subject or the `ACTION_VIEW`
  data, hands them to `AppGraph.sharedPlaces`, starts `MainActivity` with `NEW_TASK`, `CLEAR_TOP` and
  `SINGLE_TOP`, and finishes. `MainActivity` keeps its single map (DECISIONS 16) and parses no
  intents, so recreating it cannot handle a link twice.
- `AppGraph.sharedPlaces` holds the result for the process; the phone and the car read it.
- Filters, all on `LinkInActivity` and none on `MainActivity`:
  - `SEND` with `text/plain`;
  - `VIEW` with the scheme `geo` (DEFAULT and BROWSABLE);
  - web links: one `<intent-filter>` per host, because all `<data>` elements in one filter combine with
    each other. Each has VIEW, DEFAULT and BROWSABLE, the schemes https and http, and no `autoVerify`.

| Host | Path |
|---|---|
| `maps.app.goo.gl` | any |
| `goo.gl` | `/maps` |
| `maps.google.com` | any |
| `www.google.com` | `/maps` |
| `google.com` | `/maps` |
| `maps.apple.com` | any |
| `maps.apple` | any |
| `waze.com` | `/ul` |
| `www.waze.com` | `/ul`, `/live-map` |
| `ul.waze.com` | `/ul` |

- The path prefixes leave other goo.gl, google.com and waze.com links to their usual apps.
  `app.goo.gl`, `g.co` and `share.google` are not claimed.
- `<queries>` names `com.google.android.apps.maps` and `com.waze`, for the hand-back buttons.
- On Android 12 and newer a web link goes to an app only if that app is approved for the domain;
  otherwise it opens in the browser. Only one app can hold a domain. The app cannot verify these
  domains, since they belong to Google, Apple and Waze, but the user's own selection works without
  verification ([app links](https://developer.android.com/training/app-links/verify-applinks)).
- **Settings → Map links** (Android 12 and newer): which declared hosts are selected
  (`DomainVerificationManager.getDomainVerificationUserState`), a button that opens the app's Open by
  default page (`ACTION_APP_OPEN_BY_DEFAULT_SETTINGS`), two buttons that open the same page for Google
  Maps and for Waze, and the Google page switch. Samsung calls the page "Set as default" (Open
  supported links, Supported web addresses); Pixel calls it "Open by default".
- Owner setup on the phone; the share menu works without it:
  1. Google Maps and Waze: Open by default, turn link opening off.
  2. Custom Maps: turn on Open supported links, then Add links: `maps.app.goo.gl`, `maps.google.com`,
     `goo.gl`, `maps.apple`, `maps.apple.com`, `waze.com`, `www.waze.com`, `ul.waze.com`.
  3. `www.google.com` and `google.com` only if they are not greyed out; the Google app may hold them.
  4. For `geo:`, choose Custom Maps and Always in the Open with dialog.
- A later build that changes the host list may need these steps again.

### Not done

- Following redirects beyond the requests above, HEAD requests, crawler or browser identities.
- The `@` point as a destination.
- `URLDecoder`, `java.net.URI`, or `android.net.Uri` for `geo:`.
- One filter for several hosts, `autoVerify`, filters on `MainActivity`.
- Copying code from [GeoShare](https://github.com/jakubvalenta/geoshare), the main prior art: it is
  GPL-3.0-or-later. Its links are fine as test data.
- Keeping resolved places beyond the moment.

### Tests

JUnit 6 parameterized tests in `:core-nav`. The fixtures are `Location` values recorded on
2026-11-03 and public test links from GeoShare. The places and coordinates live in the test files,
not here.

| Case | Expect |
|---|---|
| A Google short link whose place URL has `!3d`/`!4d` and an `@` Street View camera 834 m away | Exact at `!3d`/`!4d`, not the camera |
| Google short links with `@` in metres (`883m`) or as a zoom (`13z`), one in another language | Exact from `!3d`/`!4d` |
| A dropped pin, `/place/<lat>,<lng>/data=…!3d…!4d…` | Exact pin from `data=` |
| The two phone-app forms; the `?q=…&ftid=…` form has no `/` before `?` | Search for the name and address, with the place hint |
| `/maps/search/<lat>,+<lng>?entry=tts` | Exact |
| An old `goo.gl/maps` link whose `Location` is http with `?q=<lat>,<lng>` | Exact; an http `Location` is fine |
| A saved list | PLACE_LIST |
| An unknown short link (404) | Gone |
| `maps.app.goo.gl/?…&link=<Google Maps directions URL>` | Search near its `@`, offline; with `link=https://example.com`, null |
| A place name containing `%23` | The search text keeps `#` |
| `/maps/dir/` with three waypoints and three `!1d`/`!2d` pairs | Exact at the last pair, named by the last segment |
| `/maps/dir//<destination>/@…` without pairs | Search near `@` |
| Google's documented `api=1` examples: search by text, search by coordinates with `query_place_id`, directions by text, `map_action=map` | Search; Exact; Search for the destination; the map centre |
| Legacy `daddr=<text>@<lat>,<lng>` with a raw space | Exact, named by the text |
| `/place//data=…` with an empty name | PLACE_ID_ONLY, then the page |
| `g.co/kgs/…`, `share.google/…` | null |
| `maps.apple/p/<id>` with its recorded `Location` (`coordinate=` and `address=`) | Exact with the address |
| Apple `/place` with `place-id`, `address`, `coordinate` and `name`; with `coordinate` and `name` | Exact, named |
| Apple `/place?address=…` | Search |
| Apple `place-id` only; `auid` only | PLACE_ID_ONLY |
| Apple `/directions` to an address; to coordinates | Search; Exact |
| Apple's own documentation example with malformed escapes | Search, no exception |
| Apple `/search?query=…&center=…`; `/frame?center=…` | Search near the centre; the map centre |
| Apple legacy `?ll=`; `?daddr=`; `?q=…&sll=…&auid=…&hnear=…` | Exact; Search; Search near `sll` |
| Waze share text with a `/ul/h<hash>` link | The address as name hint, Exact from the hash, no request |
| Two geohashes from GeoShare's tests, as `/ul/h…` and as `live-map?h=…` | The cell centre, as an independent decoder computes it |
| Waze `ul?ll=`, `ul.waze.com` with `%2C`, `to=ll.…`, `latlng=` | Exact |
| Waze `ul?q=`; the same with `ll=` and `navigate=yes` | Search; Exact, named |
| Waze `live-map/directions/<slug>?to=place.w.…`; `venue_id=`; `a=share_drive`; `favorite=work` | Search for the slug; PLACE_ID_ONLY; LIVE_TRIP; FAVORITE |
| `geo:<lat>,<lng>`, with `?z=`; `geo:0,0?q=<lat>,<lng>(<label>)`; `geo:0,0?q=<address>` | Exact; Exact, named; Search |
| `geo:` with an altitude, with `;crs=wgs84;u=…`; `geo:94,0`; `geo:0,0`; `geo:<point>?q=coffee` | Exact; BAD_COORDINATES; NO_LOCATION; Search near the point |
| Plain text: `lat, lng`; an address; a link followed by `%3C/a%3E%3C/p%3E` | Exact; Search; the link without the leftovers |

Also:

- `UrlText`: malformed escapes, `%2B`, raw spaces, decimals with 17 digits.
- `SharedPlaceResolver` with a fake expander, page reader and search: at most one link server request;
  a foreign `Location` is refused; the lookup's point never replaces the link's; a pick list when the
  results are ambiguous; with the switch off, no page request.
- On the phone:
  `adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '<text>' io.github.purplepalm.custommaps.debug`,
  `adb shell am start -a android.intent.action.VIEW -d '<url>'`, and live short links.

## GitHub log upload

Each trip's debug log goes to a private GitHub repository when the trip ends (DECISIONS 35). The code
repository is public and the logs hold location history, so logs never go there. DECISIONS 20 had
ruled out automatic upload because it needed a server or a sign-in; a token pasted on the phone is
that sign-in. Sharing from Settings stays as it is.

### Owner setup

1. Create the repository with the
   [pre-filled form](https://github.com/new?name=custom-maps-logs&visibility=private&owner=@me). It must
   be private.
2. Create the token with the
   [pre-filled form](https://github.com/settings/personal-access-tokens/new?name=Custom+Maps+logs&description=Trip+logs+from+the+phone&expires_in=366&contents=write),
   or by hand: profile picture → Settings → Developer settings → Personal access tokens →
   Fine-grained tokens → Generate new token.
   - Resource owner: the owner's own account.
   - Repository access: **Only select repositories** → `custom-maps-logs`. This is always done by
     hand: no URL parameter selects repositories.
   - Permissions: **Contents: Read and write**. Metadata read-only comes with it.
   - Expiration: 366 days, pre-filled; GitHub allows 1 to 366 days or none.
   - Generate the token and copy it.
3. In the app, once the upload is built: **Settings → Debug → Upload to GitHub**. Paste the token,
   type `<owner>/custom-maps-logs` (a pasted github.com URL works too) and tap **Check and save**.
4. On the Samsung phone: Settings → Battery → Background usage limits → Never sleeping apps → add
   Custom Maps (older menus: Battery and device care → Battery).

### Token and repository checks

- Only fine-grained tokens: after trimming, the token must start with `github_pat_`, followed by 40 to
  255 letters, digits or underscores. Classic tokens (`ghp_`) are refused, because one with
  repository scope reaches every repository of the account. Other GitHub token kinds fail the same
  rule.
- The public code repository's name is refused before any request.
- **Check and save** makes one call, `GET /repos/{owner}/{repo}`, which needs Metadata read:
  - 401: the token is wrong, expired or revoked.
  - 404: the name is wrong, or the token cannot see the repository. GitHub answers 404, not 403, for
    private resources a token cannot reach.
  - `"private"` must be true.
  - `"archived"` must be false.
  - Write access: GitHub documents that merge settings such as `allow_merge_commit` appear only for
    tokens with contents read and write. Unverified; test once with a read-only token. If it
    misleads, the first upload's 403 still catches it.
- The expiry comes from the `GitHub-Authentication-Token-Expiration` header of that answer. Its format
  is undocumented, and it is probably absent for tokens without an expiry, so it is read leniently
  and shown as it came when it does not parse. Settings shows the date and warns from 14 days before
  it.
- The worker repeats the same call at the start of every run, so the privacy check and the expiry
  stay current.
- The token is kept in its own DataStore file (`github_upload`), never in backups (DECISIONS 21;
  `BackupRulesTest` asserts it, as it does for the Mapbox token). It is shown masked and never put in
  URLs, WorkManager input data (WorkManager stores that in its database), logs or exception text.
  After saving, the clipboard is cleared (`clearPrimaryClip`, Android 9 and newer).
- If the phone is lost, the token reaches only the logs repository; revoke it at
  [github.com/settings/personal-access-tokens](https://github.com/settings/personal-access-tokens).
  GitHub also revokes tokens when they expire, when they are pushed to a public repository, and after
  a year without use.

### Trip files

- One file per trip, from Start to arrival or End, cut out of the debug log by `FileDebugLog`:
  - `DebugLog` gains `beginTrip(simulated, fields)` and `endTrip(outcome, fields)`, which do nothing by
    default. The engine calls `beginTrip` when the state enters ACTIVE and `endTrip` when it leaves,
    possibly about 2 s later so that the closing `session_state` line lands in the file.
  - Every entry carries the current trip's tag, and the writer copies tagged lines into the open trip
    file. It closes the file on the end marker, or when it sees a different tag, in case the queue
    (10,000 events, the oldest dropped first) lost the marker.
  - While recording, plain JSON lines go to the app's files under `debug-log/trips/open/`. At the end
    the file is compressed into a temporary file and renamed into `debug-log/trips/pending/` as
    `.jsonl.gz`. A crash mid-trip leaves readable plain text: open files left by a dead process are
    closed at the next start with the outcome `interrupted`.
  - When pending files pass 100 MB, the oldest are deleted and `trip_log_dropped` is logged.
    **Clear** deletes trips too, and its dialog says so.
  - `debug-log/` is already outside the backup rules.
- Car connects and disconnects stay inside one trip file, because the same engine drives both screens.
- Contents:
  - line 1, `{"type":"trip_log","format":1,…}`: app, build, device, Android and SDK
    (`AppGraph.debugLogHeader()`), the local start time and zone, simulated or not, and the
    destination;
  - every redacted event of the trip;
  - the last line, `{"type":"trip_log_end","outcome":…,"lines":n}`.
- Path in the repository: `trips/<local date>/<HHmmss>-<guidance|simulated>-<outcome>.jsonl.gz`. The
  seconds keep names apart when Start is pressed twice in a minute. Outcomes: `arrived`,
  `user_cancelled`, `stopped_outside_app`, `start_failed`, `start_blocked`, `interrupted`, and
  `unknown` when the end marker was lost.
- The commit message doubles as the index: `Trip <date> <HH:mm> <guidance|simulated>, <outcome>
  (<minutes> min)`.
- **Upload now** runs the queue at once and adds `snapshots/<date>/<HHmmss>-recent.jsonl.gz`: today's
  and yesterday's day files with a header line, for problems outside trips such as search and
  previews.
- gzip: a synthetic hour of guidance, not a real drive, is about 3,600 lines of about 820 bytes: 2.95 MB
  as text and 0.38 MB gzipped. As Base64 for the upload that is 3.94 MB against 0.50 MB, about eight
  times less over mobile data. Real drives, with reroutes and full route dumps, may differ; measure
  one.

### Uploading

```http
PUT https://api.github.com/repos/<owner>/<repo>/contents/<path>
Authorization: Bearer <token>
Accept: application/vnd.github+json
X-GitHub-Api-Version: 2026-03-10
User-Agent: CustomMaps/<versionName> (Android <release>)
Content-Type: application/json

{"message":"Trip <date> <HH:mm> guidance, arrived (<minutes> min)","content":"<Base64 of the .jsonl.gz file>"}
```

- The [Contents API](https://docs.github.com/en/rest/repos/contents#create-or-update-file-contents)
  creates one file per call. No `sha`, because every path is new; no `branch`, so the default branch;
  no committer, so the token's owner. GitHub rejects requests without that client name header.
- `GitHubClient` (`:core-nav`, JDK only): `HttpURLConnection` with fixed-length streaming, 15 s connect
  and 60 s read timeouts, redirects not followed, the error stream read on failures. It returns the
  status, the remaining rate limit and GitHub's `message`, and never logs headers or bodies. Its tests
  run against the JDK's built-in `HttpServer`, so no test dependency is added.
- A small reader of top-level JSON fields (`JsonFields`, `:core-nav`) is enough for `private`,
  `archived`, `allow_merge_commit`, `full_name`, `size` and `message`.
- API version 2026-03-10 is the newest. Without the header GitHub uses 2022-11-28, which is supported
  until 2028-03-10. A retired version answers 410.
- The Git Data API (blob, tree, commit, ref) is not used: 4 to 5 calls per file, a 409 on an empty
  repository and a race on the branch. A trip file is far below GitHub's 100 MB file limit and its
  10 s processing limit per request.

| Answer | Meaning | Then |
|---|---|---|
| 201, 200 | Stored | Delete the local file and record it |
| 422 | The path exists: an earlier upload may have worked and only its answer was lost | GET the path with the object media type. Same size: delete the local file. Different size: upload again with `-2` in the name |
| 409, 5xx, no network; 403 or 429 with no rate limit left, a `retry-after` or "secondary rate limit" | Temporary | Retry later |
| 401 | The token is wrong, expired or revoked | Stop, keep the files, show it in Settings |
| 403 "Resource not accessible by personal access token" | A missing permission | Stop, show `X-Accepted-GitHub-Permissions` |
| 404 | The repository is gone or out of the token's reach | Stop, keep the files |
| 301, 307 | The repository was renamed | Stop, ask for the new name |
| 410 | The API version was retired | Stop; the app needs an update |

- Requests go strictly one after another, with at least 1 s between PUTs. GitHub advises serial
  requests, and warns that parallel calls to this endpoint and to "Delete a file" conflict. A token
  has 5,000 requests an hour, and at most 80 content-creating requests a minute and 500 an hour.
  On 403 or 429 the worker waits for `retry-after` or `x-ratelimit-reset` when given, otherwise at
  least a minute, then backs off exponentially.

### Why WorkManager

- `LogUploads.schedule()` calls `enqueueUniqueWork("github-log-upload", APPEND_OR_REPLACE, …)` with a
  network constraint (`NetworkType.CONNECTED`) and exponential backoff from 1 minute, which also
  meets GitHub's wait-at-least-a-minute rule. **Upload now** enqueues the same work with REPLACE and a
  snapshot flag.
- One queue, not one work per trip: separate works would run in parallel and break the one request
  at a time rule.
- `LogUploadWorker`, a `CoroutineWorker` doing its blocking I/O on the IO dispatcher:
  1. no token: fail, keep the files;
  2. check the repository: private, writable, not archived, or fail;
  3. upload pending files oldest first, stopping when asked, deleting each one once stored;
  4. on a temporary answer, retry; on a token, permission, 404, move or public repository problem,
     fail and keep the files;
  5. after 10 attempts it stops; the files wait for Upload now or the next trip.
- It logs `github_upload` with the path, size, status, remaining rate limit and GitHub's message;
  never the token, headers or bodies.
- Why not a coroutine in the app's scope: at arrival the trip's foreground service stops and the
  phone is often in a pocket. Android 15 fails network requests started outside a valid process
  lifecycle (an `UnknownHostException` or another socket error), and a coroutine dies with the
  process. WorkManager keeps the work in its database, waits for any network, and survives process
  death and reboots. A worker cut off mid-upload is retried, and the 422 size check prevents a
  duplicate. 2.12.0 includes the 2.11.1 and 2.11.2 fixes for the network constraint on Android 15.
- Not expedited work: before Android 12 it may run as a foreground service and then needs a
  notification (`getForegroundInfo`), and right after a drive the app is in the Active standby bucket
  anyway.
- A force stop cancels the app's jobs until the app is opened again; WorkManager then reschedules them.
  Every app start also schedules an upload when files are pending.
- A worker run that starts a cold process runs `Application.onCreate`, and with it `AppGraph` and
  `MapboxNavigationApp.setup`. That starts no trip session and no map. The worker never touches the
  engine.
- Samsung puts apps unused for about 3 days to sleep and after 16 days into deep sleep, which
  restricts jobs; Data Saver blocks background data on mobile networks; Doze delays work. Hence Never
  sleeping apps, Upload now, and the reschedule at every start.

### Redaction

- Today every log line is masked for Mapbox tokens and `access_token=` values only. GitHub tokens,
  `Authorization` header values, `?token=` download URLs and `temp_clone_token` would pass unmasked.
- `Redaction` in `:core-nav` gains rules for the GitHub token prefixes (`github_pat_`, `ghp_`, `gho_`,
  `ghu_`, `ghs_`, `ghr_`, including the long installation tokens that contain dots), `Authorization`
  values (Bearer, token, Basic), `token=` and `access_token=` query values, and JSON fields whose
  name ends in `token`.
- Tests build fake tokens by joining strings, so GitHub's push protection and secret scanners stay
  quiet (DECISIONS 11).

### Settings → Debug → Upload to GitHub

- Status: the last upload, waiting for a network, the last problem, the expiry date.
- The repository field, and the token field with the same input rules as the Mapbox token field.
- **Check and save**, the masked token, **Remove**, a switch **Upload after every drive** (on), **Upload
  now**, and the number and size of pending trips.
- Links: create the repository, create the token, open the repository on GitHub.

### Reading the logs

The logs repository is private; reading it needs the owner's GitHub access.

```bash
git clone --filter=blob:none https://github.com/<owner>/custom-maps-logs
cd custom-maps-logs
git log --oneline
zcat trips/<date>/<file>.jsonl.gz | head -1 | jq .
zcat trips/<date>/<file>.jsonl.gz | jq -c 'select(.type=="off_route" or .type=="routes_updated" or .type=="error")'
```

- `git log --oneline` lists the trips: date, kind, outcome and length. The partial clone fetches
  file contents only when they are checked out.
- The GitHub API returns file contents inline only up to 1 MB; larger files need the raw media type.
  Plain text would pass 1 MB after about 20 minutes of guidance, so the clone with `zcat` and `jq` is
  the dependable way for either format.
- Growth: about 0.4 to 0.5 MB per hour of driving, far below the 1 GB GitHub recommends for a
  repository. A backlog after a long time offline uploads at about one file every 2 to 3 seconds,
  within a worker's 10-minute limit.
- Deleted files stay in the repository's history; only a history rewrite removes them. If the
  repository were ever made public, the location history in it would be public too.

## Desktop Head Unit

### Setup

- DHU 2.0 from the stable channel (SDK package `extras;google;auto`); the README has the installation
  and Android Auto's developer mode.
- The phone must be unlocked to start the DHU. Lock it afterwards when a test needs it.
- `--usb` (accessory mode, recommended), or the head unit server with `adb forward tcp:5277 tcp:5277`.

### Profiles

`dhu/800x480.ini` and `dhu/1280x720.ini` in the repository, based on Google's `default.ini` and
`default_720p.ini` from DHU 2.0 (both 160 dpi, touch, 30 fps, with the location, night mode and
driving status sensors), plus the cluster window:

```ini
[general]
touch=true
touchpad=false
controller=false
instrumentcluster=true
resolution=800x480
dpi=160
framerate=30

[sensors]
location=true
night_mode=true
driving_status=true
```

- The 1280×720 profile differs only in `resolution`. Also try a higher `dpi` for denser layouts.
- `instrumentcluster=true` adds a cluster window with the Trip and Step data (NF-4). Cluster display
  emulation needs DHU 2.1 from the beta channel; whether 2.0 shows this window is unverified.
- `contentinsets` and `stablecontentinsets` emulate screens that are partly covered; one run with each
  checks the insets, the logo and the attribution.
- The DHU runs from the SDK folder, so `-c` takes an absolute path:
  `./desktop-head-unit --usb -c /path/to/repo/dhu/800x480.ini`. `-c` can be repeated, and later files
  override earlier ones. `-i rotary` checks pan mode as in a car with only a rotary controller.

### Console commands

| Command | Keys | Use |
|---|---|---|
| `day`, `night`, `daynight` | Shift+N, Ctrl+N, N | Day and night; check the mode in the log |
| `restrict all`, `restrict none` | Shift+U, U | Driving restrictions, such as a locked keyboard |
| `focus nav off`, `focus nav on` | | The head unit's own navigation takes over: `onStopNavigation` arrives |
| `focus video off`, `focus video on` | | Video focus |
| `screenshot <file>.png` | | A screenshot |
| `quit` | | A car disconnect |

Sensor commands work only when the matching `[sensors]` entry is on.

### Test matrix

Debug build, phone on USB, Android Auto developer mode. Unlock the phone to start the DHU each time.

| Case | Steps | Expect |
|---|---|---|
| a. Phone first | Search on the phone and start a simulated drive. Start the DHU, open Custom Maps in the car, lock the phone. Later, with no drive: search on the phone, then press Start in the car. | The routing card at once; one new trip in the trip session log. The car shows the phone's preview, and Start in the car starts one trip. |
| b. Start in the car, phone locked | DHU running. Search on the phone, lock it, press Start in the car. Three runs: "while in use"; "all the time"; "while in use" with the guard skipped. Also, with nothing started: the car's puck, and a route asked for in the car. | "While in use": the car shows the refusal and the preview, no crash, dumpsys lists no trip service. "All the time": the trip service is in the foreground; note `getFgsAllowStart`, then run (c) on this drive. Guard skipped: note `mAllowWiu_noBinding`, `getFgsAllowWiu_forStart` and `infoAllowStartForeground`; a crash here is a result. With "all the time" and nothing started, the car's puck moves and a route asked for in the car starts where the car is. |
| c. Disconnect | During guidance, `quit` the DHU or pull the cable. | The phone notification stays; `isForeground=true`; the log has the car session destroyed and no stop event; the session id is unchanged; the phone app shows guidance. The log shows the lifecycle state at any `onStopNavigation`. |
| d. Reconnect | Start the DHU again. | Unverified: the routing card and the maneuver at once; otherwise the reconnect fallback. `focus video off` and `on` keep guidance. With another app in front, such as music: turns in the rail and a heads-up at each new maneuver. |
| e. Ends | End in the car; End on the phone; End Navigation in the notification; a simulated arrival. Then `focus nav off` during a drive. | Every end: both screens end, one trip per drive. `focus nav off`: `onStopNavigation` arrives, guidance continues on the phone, no `updateTrip` error, no crash. |
| AUTO_DRIVE | `adb shell dumpsys activity service io.github.purplepalm.custommaps.debug/io.github.purplepalm.custommaps.car.CustomMapsCarAppService AUTO_DRIVE`, then pick a place in the car and press Start. | A simulated drive. |
| Process death | During guidance, **Settings → Debug → Crash now**. | No trip session until Resume; the resume offer on both screens; the exit reason in the log and in `adb shell dumpsys activity exit-info io.github.purplepalm.custommaps.debug`; the trip file closed as `interrupted`. |
| Quota | Search, results, preview, tap every route several times, Start; also starting from a phone search. | No host error. |
| Day and night | On the phone, Settings → Day and night: Day, Night, then Auto. Then `day`, `night`, `daynight` and Android Auto's own day and night setting. | The car's map changes with the phone's look, at once for a choice by hand, no reload, no frame in the wrong look (`car_look` `APP` lines). The DHU's commands change only the routing card's images (`car_look` `HOST` lines, the map's look unchanged). |
| Go there now | During a simulated drive, share a link on the phone and confirm. | The new destination in the routing card; the trip session log shows whether it counted as a new trip. |
| Phone locked | A simulated drive with the car map; lock the phone for a few minutes, then unlock it and leave the app with Home. | The car's map and puck keep moving: `frame_clock` lines when the clock goes to its timer and back, `display_state` lines, the car's camera lines with `clock.timerTicks`, `camWrites` near 60 and a small `puckLagM` ([CAMERA.md](CAMERA.md)). |

### Evidence

- The app's debug log, uploaded after each trip once the upload is built.
- `adb logcat` lines: "Foreground service started from background can not have
  location/camera/microphone access", "Service.startForeground() not allowed due to
  mAllowStartForeground false", "Starting FGS with type location".
- `adb shell dumpsys activity services io.github.purplepalm.custommaps.debug`:
  `NavigationNotificationService` with `isForeground=true` and `types=0x00000008`, plus
  `getFgsAllowWiu_forStart`, `getFgsAllowStart` and `infoAllowStartForeground`, which say why a start
  was allowed or refused.

## The real car

- In a car, Android Auto runs Car App Library apps only from a trusted source: Google Play, Internal
  App Sharing or an internal test track. Android Auto's "unknown sources" option does not apply to
  them ([testing](https://developer.android.com/training/cars/testing)). The CI debug APK runs only on
  the DHU.
- So the real car runs the build from Google Play's internal testing, which CI publishes from every
  green build of `main`. It came forward from stage 3 on 2026-11-11 and replaces the Internal App
  Sharing the owner had chosen: a track build updates by itself, and an Internal App Sharing install
  could not update from the track without an uninstall. The other car checks of this stage run on
  the DHU with the debug APK.
- Play checks apps against the car quality guidelines only on some tracks: internal sharing and
  internal testing are not reviewed, closed testing does not block, open testing and production do.
- "Allow all the time" brings Play's background location declaration, with a video, the prominent
  disclosure and a review that can take several weeks. Google reviews the declaration only with a
  bundle that requests the permission, and an API release of such a bundle fails until Google has
  approved it. So the permission stays in the debug build while this stage is built. When the stage
  is ready, one bundle with it is uploaded and rolled out by hand in Play Console together with the
  declaration; during the review the publish job fails and the phone keeps the last Play version, so
  new builds come through the debug APK, and once Google approves, CI publishes again. Play lists
  turn-by-turn navigation as foreground use, "not applicable if any functionality is conducted while
  the user is outside the app", so the declaration has to explain the car case.
- In the car: repeat (a) to (e) with a real unplug and replug, the phone locked in a pocket, and a
  drive of more than 20 minutes. Set the battery to Unrestricted and add the app to Never sleeping
  apps first. Samsung says foreground services of apps targeting Android 14 work as intended since
  One UI 6.0, but its sleeping-app lists still apply.

## Order of work

Stage 1.1 comes first ([ROADMAP.md](ROADMAP.md)). Then the work below. Step 1 built 4, 6, 7 without
the reconnect fallback, and 10, with the parts of 2 and 3 they need (the stop reasons,
`CarMapRenderer`, the routing card's data, the phone and car hosts, `MapScene`), and the README,
DECISIONS and ROADMAP updates of 14. Step 1b made 10 the phone's look and gave the car map's camera
and puck of 6 a frame clock of their own ([Step 1b](#step-1b-built)).

| # | Work | Checked by |
|---|---|---|
| 1 | Trip files and GitHub upload: redaction rules, trip files in `FileDebugLog`, engine marks, recovery, `GitHubClient`, the store and Settings, the WorkManager queue and worker. First, so that every DHU and car drive arrives logged. | JVM tests. On the phone: Check, a simulated drive, the file appears; airplane mode at arrival, then online again; a force stop and reopen; a revoked token shows a message. |
| 2 | `:core-nav`: the stop reasons, `StartBlocked`, `StartResult`, the start problem, the route replacement, the routing card data, `CarMapRenderer`. | JVM tests: one session for two Starts, StartBlocked, no stop on a disconnect or a host stop, a route replacement never clears routes. |
| 3 | Engine: the start guard, phone and car hosts, caught exceptions, the foreground service check, the debug switch, the logs; `MapScene` out of `PhoneMap` (the day column of `MapPalette` is there since stage 1.1). | The phone unchanged; tests pass. |
| 4 | Car skeleton: catalog, `:car`, manifest, host validator, `CustomMapsCarAppService`, `CarSession` with the setup gate and the car host, the root `NavigationTemplate`, `CarNavigationMap` (surface, style, palette, logo, attribution, insets). | DHU at 800×480 and 1280×720. |
| 5 | The "Allow all the time" flow, then the first device test. | Case (b) and location before Start on the owner's Samsung phone with Android 15; keep or relax the guard. |
| 6 | Guidance bridge, mappers, image renderers, `updateTrip`; guidance on the car map, gestures, the map action strip. | A simulated drive on the DHU; the CAMERA.md checks. |
| 7 | Disconnect and reconnect, the car map release, the reconnect fallback if needed. | Cases (c), (d). |
| 8 | Search and preview in the car, the phone as search input, fixed rows. | Case (a); the quota walk. |
| 9 | The turn notification extender; the notifications permission checks. | Case (d): rail and heads-up. |
| 10 | Day and night. | DHU commands. |
| 11 | AUTO_DRIVE. | The dumpsys command. |
| 12 | Drive marker, resume, Crash now. | Process death. |
| 13 | Shared links: parser and tests, the search additions, short links, Google pages and their switch, `LinkInActivity` and filters, the Settings rows, Go there now. | JVM tests, adb intents, live links. |
| 14 | DHU profiles in `dhu/`; README, DECISIONS and ROADMAP updates. | |
| 15 | The real car. | The internal testing build; "Allow all the time" there once its declaration is approved. |

## Risks

| Risk | Answer |
|---|---|
| Start in the car with the phone locked crashes on Android 14 and 15: Mapbox's service rethrows the `SecurityException` | The start guard, "Allow all the time", the first device test |
| A drive started in the car runs without a foreground service (the start was not exempt and Mapbox swallowed the refusal), and stops or loses location when the car disconnects | The foreground service check and a warning on both screens; `getFgsAllowStart` in case (b) |
| A `NavigationManager` call throws on the main thread and takes phone guidance down with it | try/catch around every call; the bridge's own navigating flag |
| Android Auto does not reopen the app on reconnect, and the car shows nothing of the drive | Case (d); a plain car notification that opens the car app |
| The template quota runs out and the host closes the app | Fixed row order; every flow ends on the root `NavigationTemplate`; the quota walk on the DHU |
| `RoutingInfo.build()` throws for lanes without a lanes image | Always both, covered by the mapper tests |
| The car surface reloads on every `onSurfaceAvailable`; a short flash | State applied again on attach; an own surface callback if it shows in a car |
| `MapboxCarMap` keeps a surface alive after its Session, one more per reconnect | `release()` destroys it at ON_DESTROY |
| `stableEdgeInsets` is null or stale | Own insets from the `Rect`s, the visible area as fallback |
| The widget API is experimental and needs `ContextMode.SHARED` | Checked with every Maps upgrade |
| `setStyleImportConfigProperties` resets other style settings | Keys set one by one |
| A car that stays in day mode | The car's map follows the phone's look since step 1b (DECISIONS 50); the host's mode, logged, colours only the card's images |
| Google's quality guidelines expect a dark map when the car says night (MR-1), and the car's map follows the phone | Internal testing is not reviewed; looked at again before any closed or open testing (DECISIONS 50) |
| Two maps double the GPU work and the heat | Step 1b: a real 30 frames a second on the car's surface, the phone's map at most 60 while the car shows the map; the `thermal` lines over ten minutes with the car (DRIVES.md); stage 5 measures heat too |
| Main-thread frames may stop with the phone locked or the app in the background (as in the first car test) | Step 1b: the car's frame clock with its timer, Smooth follow, the zoom buttons and the puck on it; the two Mapbox camera styles and the double tap still wait for display frames |
| The notification interceptor runs on a background thread and has one slot | The engine owns the slot; the extender's state is behind a lock |
| Notifications refused: no rail and no heads-up in the car | Asked for during setup; both screens say so |
| Before Start, with "while in use" and the phone locked, no puck and no route origin in the car | "Allow all the time"; case (b) |
| Play's background location review, up to several weeks, during which Play updates stop; Play lists turn-by-turn navigation as foreground use | The permission stays in the debug build until the stage is ready, then one bundle by hand with the declaration, and the debug APK carries new builds meanwhile; the disclosure is designed now; the declaration explains the car |
| The rule that keeps the navigator alive lives in Mapbox internals, and Navigation SDK v3 is not API-stable | Checked again with every SDK upgrade |
| car-app raises `onStopNavigation` by itself at ON_DESTROY; library behaviour can change | Logged; checked again with every car-app upgrade |
| If the app ever leaves internal testing: SA-1 and NF-2 limit animations and overlays, NF-5 conflicts with guiding next to another app, NF-6, NF-7 and VC-1 become mandatory | Internal tracks only for now |
| The link formats are undocumented and can change: Google's `data=` and `@`, Apple's redirect | Every expansion logged; the search fallback; the hand-back buttons |
| Most places shared from the Google Maps phone apps carry no coordinates, and a Mapbox search may find nothing or the wrong place | Page reading; the place hint; a pick list; the found place is shown before Directions |
| Reading Google's pages is against its terms and may stop working | The fallback keeps links working |
| Google could add bot checks to its link server; expansion fails offline and behind captive portals | Messages with Retry; the hand-back buttons |
| Open by default needs the owner's steps; the Google app may hold `www.google.com`; a changed host list needs the steps again | Settings → Map links |
| A new destination mid-drive may be billed as a new trip | Watch the trip session log and the Mapbox statistics |
| The write permission check rests on one documented sentence | A test with a read-only token; the first 403 catches it |
| The token expiry header has no documented format | Lenient parsing; the raw value shown |
| Uploads delayed by sleeping apps, Data Saver and Doze; uploads outside WorkManager fail on Android 15 | Never sleeping apps, Upload now, a reschedule at every start; WorkManager only |
| Lines lost when the log queue overflows, a trip marker dropped | Tags on every entry; the outcome may then read unknown |
| The size figures are synthetic | Measure on a real drive |
| A public logs repository would publish location history | The worker refuses public repositories on every run |

## The owner's answers

Answered on 2026-11-10:

1. **Internal App Sharing:** yes, set it up during this stage as soon as the Play account is
   verified. Replaced on 2026-11-11: the Google Play release came forward, and the real car runs the
   internal testing build (decision 42).
2. **Taking the car back:** yes.
3. **Where Go there now asks:** on the phone and in the car; the first answer wins.

Links and addresses shared into the app from other apps stay in this stage (step 13 of the order of
work), as designed.
