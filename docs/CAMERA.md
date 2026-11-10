# Guidance camera

How the map camera moves during guidance: what the owner reported after the first drive, why the
camera behaved that way, the designs considered, the one chosen, and how it will be tested. SDK
references are to Maps SDK 11.34.1 and Navigation SDK 3.34.1 (ui-maps 3.34.1). The drives are
described in [DRIVES.md](DRIVES.md); stages are in [ROADMAP.md](ROADMAP.md).

**Status:** being built in stage 1.1 (from 2026-11-10). The owner answered the camera questions;
the answers are [at the end](#the-owners-answers).

## What the owner reported

- The car and the camera move "frame by frame".
- The camera "switches views very abruptly".
- Before a turn the camera does not zoom in far enough to read the lanes.
- Liked: on a gently curving road the camera turned into the curve well before the car did; Google
  Maps did not. Keep it.

## What the owner wants

- The camera is "not tied to the route and to my point", but "simply follows it with animation":
  it chases the moving car smoothly, on every frame, instead of being fixed to it.
- Before a turn the view still tilts down onto the junction, but gradually, and comes back up after
  the turn.
- The early turn on gently curving roads stays.
- A strong zoom before turns, so the lanes are large.
- Camera styles to choose from in Settings, so the old and the new camera can be compared.

## Camera styles

| Style | What it is | Needs system animations? |
|---|---|---|
| **Mapbox standard** | Today's camera, unchanged, for comparison: `NavigationCamera` with its default options, two evaluations per navigator update, the 1.0 s puck. | Yes |
| **Mapbox tuned** | The same camera with calmer settings: one update per navigator update, a gradual tilt, more zoom at turns, the 1.1 s puck. | Yes |
| **Smooth follow** (default) | The new camera: it moves the map on every display frame and glides after the drawn car. Described from [Smooth follow](#smooth-follow-the-chosen-design) on. | The camera no, the puck yes (see [System animations](#system-animations)) |

Mapbox tuned is built from changes the drive analysis proposed for the standard camera. Its numbers
are starting points for tuning.

- **One update per navigator update.** The data source is evaluated once, on the progress. The
  location evaluates only when no progress came for 1.5 s, because the navigator drops progress
  while it replaces routes. A teleport (a jump of the matched position) snaps the puck and the
  camera instead of sliding them across the map.
- **Calmer frame updates.** An own following transition (a `NavigationCameraStateTransition` given
  to `NavigationCamera`) plays longer linear animators: centre 1.1 s, bearing 1.3 s, zoom 2.5 s,
  tilt 2.0 s, padding 1.5 s, at most 2.5 s. Each update restarts them from where they are, so they
  act like a low-pass filter: zoom and tilt changes settle over 2 to 4 s instead of 1 s.
- **A gradual tilt.** The view still goes flat near a turn, as the owner wants, but through the 2 s
  tilt animator instead of one second at 45°/s.
- **More zoom at turns.** A higher `maxZoom` than today's 16.35; 17.5 was suggested. Side effects:
  whenever only the location is framed (before the first progress after Start, after every
  reroute), the zoom target jumps to the cap for about a second, on a freeway from about 14 to 17.5
  and back. And the whole town drive gets closer, about 350 to 500 m ahead, not only the approach
  to turns.
- **The 1.1 s puck** (see `GuidancePuck` below). It trails the fix by about 0.1 s more, about 3 m
  at 65 mph.
- **Limits.** It still runs on once-a-second updates, so it can be calmer but not continuous (see
  [the verdict](#the-verdict-on-once-a-second-updates)). It helps only while system animations run.
  Its animators must carry `NavigationCamera.NAVIGATION_CAMERA_OWNER`, or
  `NavigationBasicGesturesHandler` drops the camera to idle.
  `UpdateFrameAnimatorsOptions.useSimplifiedAnimatorsDependency` must stay false, because that path
  skips the own transition. Start and **Recenter** must pass the same transition options, or
  **Recenter** falls back to 1000 ms.

The chosen style applies at once, also mid-drive, and on the car screen from stage 2. The debug log
records the active style, so drives can be compared ([DECISIONS.md](DECISIONS.md), 26).

Only one camera may move the map at a time. Smooth follow treats any camera animator as the driver
taking over, and `NavigationCamera` moves the map with animators. So `PhoneMap` keeps
`NavigationCamera` and `NavigationBasicGesturesHandler` for the two Mapbox styles and connects only
the camera of the chosen style. The design below was drawn up with `NavigationCamera` removed; the
style setting keeps it. A style change mid-drive also undoes what the previous style set on the
data source: Mapbox tuned's `maxZoom`, or Smooth follow's tilt override
(`clearFollowingOverrides`).

## Why the camera behaved that way

The evidence comes from the debug log of the first drive and from the SDK and Android sources.

### System animations were most likely off

- The log has three camera transitions. They ended 6, 9 and 28 ms after they started.
- With animations running, `NavigationCamera` cannot make them that short. Its flight to the
  overview takes at least 673 ms, or a fixed 1.8 s. At Start the following frame already had a
  zoom, so that flight could not be instant either.
- What fits is Android's rule for an animator duration scale of 0: an `AnimatorSet` ends on its
  first frame, and a `ValueAnimator` jumps straight to its end value.
- The puck and the camera both move with `ValueAnimator`s. So both jumped to each new position once
  a second, and every change of tilt, zoom or framing near a turn became a one-frame cut. That is
  the "frame by frame" motion and much of the abruptness.
- Only Off fits. At 0.5x the 1.8 s overview flight would still take about 0.9 s.
- Likely switches: **Remove animations** in the accessibility settings, or **Animator duration
  scale: Off** in the developer options. Battery saver is unlikely: Android 15's own battery saver
  does not turn animations off by default. A phone maker could change that, but nothing in the log
  points to it.
- The owner confirmed it: Reduce animations was on. With animations on again, guidance became
  smooth ([answer 1](#the-owners-answers)).

### Even with animations on, the standard camera moves in one-second pieces

- The navigator sends one update a second. Each update restarts the puck: 1000 ms at constant speed
  through the key points the navigator sends with it.
- Each `evaluate()` of `MapboxNavigationViewportDataSource` makes `NavigationCamera` cancel its five
  animators (centre, zoom, bearing, tilt, padding) and start five new linear ones of 1000 ms from
  where the camera is.
- A restarted animator draws its start value on its first frame, so each restart costs one frame
  with no motion. The puck loses a frame at every fix too.
- A piece ends exactly when the next update is due. Updates came every 1.000 s (median), 98 % of
  them between 0.977 and 1.016 s, the slowest after 1.070 s; 16 % came later than 1000 ms. When an
  update is a few ms late, the puck and the camera stand still for a frame. Speed and turning rate
  change abruptly every second.
- `PhoneMap` evaluates twice per update: on the location, with the previous second's progress, and
  a few ms later on the progress. The second cancels the first set of animators before it has
  moved: two restarts a second, half of the work wasted. (That the wasted set can flash an
  in-between frame while animations are off is not proven; a frame would have to land between the
  two calls.)
- The camera does rest at red lights: a frame that differs by less than 1e-6° in position and 0.1°
  in bearing, with the same zoom, tilt and padding, is not sent.

### The view changes in steps near turns

Three default following options of the data source (`MapboxNavigationViewportDataSourceOptions`)
change the whole view at fixed points, while the camera stays in following:

1. **`pitchNearManeuvers`:** within 180 m of a maneuver the tilt goes from 45° to 0° in one step.
   It applies to every banner type except continue, merge, on ramp, off ramp and fork.
   `NavigationCamera` plays the step as one linear second, 45°/s.
2. **`maximizeViewableGeometryWhenPitchZero`:** at the same moment the car is no longer held near
   the bottom. The frame is fitted around the rest of the step, 100 m after the turn
   (`frameGeometryAfterManeuver`) and any following steps shorter than 150 m, and the car jumps up
   the screen. The bearing still points at the maneuver then.
3. **At the step change** the tilt goes back to 45°, the car back to the bottom, the zoom is
   computed again for the new step, and the bearing swings toward the new step's framed points, by
   up to 45° from the heading.

In 11 minutes of the logged drive these switches happened 10 times. A freeway step also pulled the
zoom from 16.35 down to roughly 13 to 14.5 and back; that is an estimate, as the log had no camera
fields. With animations off each switch was a cut; with animations on each is a one-second linear
move.

### No zoom left for the lanes

- `maxZoom` 16.35 caps every following frame, so when a turn approaches there is no zoom left to
  add.
- At 45° tilt, roughly 550 to 770 m of road fits at the cap between the maneuver card and the bottom
  bar, depending on the phone's display size setting. Any shorter look-ahead is clamped to 16.35.
  That probably covers most town steps; it was not measured.
- The flat frame near a maneuver (the rest of the step plus 100 m after the turn) would need zoom
  16.6 to 17.6 and is clamped to 16.35 too.
- The map draws no lane markings either way. Mapbox Standard's HD roads, which would draw them, are
  opt-in, cover a limited set of cities and are off in this app (see the watch list in
  [ROADMAP.md](ROADMAP.md)). The lanes are shown in the maneuver card.

| Zoom | A 3.7 m lane on screen |
|---|---|
| 16.35 (today's cap) | about 5 dp |
| 17.5 | about 11 dp |
| 18.75 | about 27 dp, about 4 mm |

The sizes use Mapbox's zoom levels with 512-pixel tiles
([zoom level glossary](https://docs.mapbox.com/help/glossary/zoom-level/)) at the latitude of the
drives.

### Where the early turn comes from

- The data source's `bearingSmoothing` (on, `maxBearingAngleDiff` 45°) points the camera from the
  car toward the last framed point of the current step, not along the car's heading. If that
  direction is more than 45° off the heading, it uses the heading ± 45°.
- The framed points run from the car to about 7 times the average spacing of the step's junctions
  (`intersectionDensityCalculation`, `averageDistanceMultiplier` 7, counting junctions more than
  20 m apart). They stop early only at a bend of 100° or more.
- So on a gently curving road the camera starts turning into the curve long before the car does.
- The curve the owner liked was rebuilt from the logged positions. With a 700 m look-ahead the
  camera was already 13° into the curve about 24 s before the car began to turn, and 28° about 16 s
  before. With 400 m it was 13° about 12 s before. Either way it was near the 45° limit about 4 s
  before the car turned. The real look-ahead depends on junction data the log does not have, so
  these lead times are estimates.
- Keep `bearingSmoothing` and `intersectionDensityCalculation` as they are. Zoom limits and tilt do
  not enter this bearing, so changing them does not touch the early turn.

### Smaller causes

- **The maneuver card resizes the frame.** The top padding follows the card's bottom edge, and each
  change evaluates the camera again. The card grows by 56 dp when lanes appear (a 12 dp gap and a
  44 dp lane row) and by a line when the instruction wraps. The car stays put, but the zoom (below
  the cap) and the flat frames near a turn change. Proposed: during guidance the top padding only
  grows. In Smooth follow padding changes glide in any case.
- **Not the cause, but wasteful.** On every frame of the puck animation `PhoneMap` updates the
  travelled part of the route line. The SDK accepts a point only every 62.5 ms; each rejected call
  reads the style URI through a regex and logs an error, about 45 to 105 times a second once
  animations run. Fix: render only the accepted updates. The burst of map errors while the phone was
  offline (2337 lines) also ran regexes on the main thread, a few ms per second at worst. Updates
  kept arriving every 1.00 ± 0.02 s, so neither delayed them.
- **Unknown:** whether Standard with night lighting and 3D buildings is too heavy for the phone. The
  log has no frame times, and with animations off the map redrew mostly once a second. Once
  animations run it renders continuously, at up to 120 Hz. Measure frame times before changing the
  style.
- **Also found:** outside guidance the data source learns the location only from `centerOnPuck`
  (the first fix after launch, or a tap on my location). The preview's overview and the Start
  flight use that point, so a preview built in one place and started elsewhere starts from the old
  spot and then jumps. Fix: feed the data source from puck updates during the preview.

## Designs considered

Three designs were worked out and scored from 1 to 10 on six points. For risk, 10 is the safest.

| Design | Smooth | Gradual tilt | Early turn | Lane zoom and 3D | Car | Risk |
|---|---|---|---|---|---|---|
| A. `NavigationCamera` fed 20 times a second | 6 | 8 | 9 | 6 | 7 | 4 |
| B. Camera fixed to the puck (viewport plugin) | 7 | 7 | 8 | 5 | 8 | 6 |
| **C. Own controller, every frame (chosen)** | **9** | **9** | **9** | **9** | **9** | **7** |

**A. `NavigationCamera` fed 20 times a second.** Keep `NavigationCamera` and the data source. A frame
tick evaluates the data source 20 times a second (4 in the overview) from the drawn puck. A custom
frame transition plays springs that start from the camera's current speed. A ramp through
`followingPitchPropertyOverride` replaces the 45°/0° tilt switch. The puck glides 1.1 s. This is
not the Mapbox tuned style, which keeps one update a second. Why not:

- It still cancels and restarts animators, now 20 times a second. It looks smooth only thanks to
  two undocumented framework details: a one-frame lead in every curve, and a frame callback posted
  with a negative delay so it runs before Android's animation handler. Without them a check on the
  JVM showed a dead frame every third frame.
- About ten times today's evaluations and animator restarts, each tick with two or three native
  camera fits and a native animation hint of unknown cost.
- The puck animation and the camera springs are two filters in series, in about 600 lines.
- `NavigationCamera`'s mode transitions aim at the frame captured when they start and ignore updates
  while they run. After a 1.5 s flight the camera has to catch up speed × 1.5 s, about 21 m at
  50 km/h: a visible surge. Stage 4 switches modes often, so it would show.
- Its tilt ramp also moves the moment the view turns flat, and the owner liked that timing as it
  is: at 30 km/h the view would be flat only 8 s before the turn.

**B. Camera fixed to the puck.** The Maps SDK viewport plugin's `FollowPuckViewportState` sets the
camera centre to the puck on every puck frame. The data source supplies zoom, tilt, bearing and
padding, smoothed by ramps and springs; a small custom viewport state shows the overview. About
420 new lines. Why not:

- The centre is bolted to the puck: the opposite of what the owner asked for.
- Every flaw of the puck animation reaches the whole map one to one: the speed step at each fix and
  the frozen first frame of each restart.
- It allocates an `AnimatorSet` and five camera animators on every frame, 5,000 to 15,000
  short-lived objects a second.
- The centre can only be the puck. A view centred on the junction (stage 4) would need its own
  viewport state.
- The tilt is a 2.5 s S-curve from the 180 m point, with no regard for speed. It also turns
  `maximizeViewableGeometryWhenPitchZero` off, which changes the maneuver framing the owner has
  seen.

**C. Own controller, every frame (chosen).** One `MapboxMap.setCamera` per display frame, from
critically damped springs. The navigator's once-a-second data only moves the targets. While
following, the camera chases the drawn puck. It never restarts animators (unlike A) and is not
bolted to the puck (unlike B). A simulation of the compiled code on the JVM showed:

- A steady 5°/s curve turns at 4.83 to 5.17°/s with the drive's fix-interval jitter. A plain spring
  on the same input gives 2.6 to 6.6°/s.
- At 50 km/h the camera's speed stays within 0.23 m/s of the car's, while the puck itself loses a
  whole frame at each fix.
- The tilt peaks at 27°/s, with zero speed and acceleration at both ends.

More numbers are in [What it does on the road](#what-it-does-on-the-road). Its costs: it owns
the camera, so every other camera move during guidance must go through it; it replaces
`NavigationCamera`'s state machine; the car needs an explicit gesture hook; and the main-thread
frame loop with the phone locked has to be checked on the DHU.

### The verdict on once-a-second updates

Once-a-second frame updates cannot look continuous, whatever durations or curves are used:

- Ease-in-out stalls at every update.
- Linear has a speed step at every update.
- Durations longer than the interval still jump in speed, by the size of the target step divided by
  the duration.
- Even a spring that keeps its speed pulses when it chases a 1 Hz target. In a simulation of a
  steady 15°/s turn, the rotation speed went 7, 20, 7°/s every second. It also drifts against the
  puck, which moves on its own curve through the key points.

So the chosen design moves the camera on every frame and uses the navigator's data only as targets.

### Taken from A and B into C

From A:

- The route id in the key of each step, so a reroute is a new step even when its step numbers
  repeat.
- A 2 s hold after the turn, besides the 25 m.
- The 1.1 s puck, and a teleport that jumps the puck (duration 0).
- One puck provider shared by the phone and the car.
- Logging `ValueAnimator.areAnimatorsEnabled()`.

From B:

- A fresh puck provider for every drive, built like Mapbox's `CarLocationProvider` as a wrapper
  that swaps its inner provider. A provider replays its last fix to every new consumer, which would
  jump the puck to where the last drive ended.
- Frame statistics every second.
- Skipping identical camera writes, so the camera does nothing while parked.
- The compass, covered by the same hand-over rule.

Also added: the renderer's frame rate in the log (`MapView.setOnFpsChangedListener`).

## Smooth follow: the chosen design

### In short

- One `MapboxMap.setCamera` per display frame, on the main thread, from Android's `Choreographer`:
  the same thread and frame as the puck animation.
- The navigator's data, once a second, only moves targets. Each sample ramps in over 1.1 s, so no
  input stops between samples.
- Critically damped springs follow the targets. They never overshoot, and their step is exact for
  any frame time.
- While following, the centre chases the drawn puck, not the raw fix. The camera trails the car a
  little and settles onto it when the car stops.
- `MapboxNavigationViewportDataSource` stays as the source of targets. The look-ahead bearing (the
  early turn), the zoom, the maneuver framing and the overview are unchanged; the next build's
  extra zoom at turns comes on top (see [Hooks for stages 4 and 5](#hooks-for-stages-4-and-5)).
- A timed, eased schedule (`ManeuverFocus`) replaces the 45°/0° tilt switch.
- When nothing moves, the loop stops: no frames, no camera writes.

### Classes

| Class | Where | What it does |
|---|---|---|
| `CameraTuning` | `:core-nav`, `nav/camera/CameraMotion.kt` | Every constant, in seconds, degrees and metres, with `PHONE` and `CAR` presets. One place to tune after a drive. |
| `Spring` | same file | Critically damped spring with an exact closed-form step. Never overshoots. |
| `SampleRamp` | same file | Moves from where it is to each new sample at a constant rate over 1.1 s, plus a lead of rate × 2τ that cancels the spring's lag. |
| `ManeuverFocus` | same file | The tilt schedule: 0 on the road, 1 over the junction; tilt = lerp(45°, 0°, focus). Stage 4's junction zoom reads the same curve. |
| `Angles`, `easeInOut`, `lerp` | same file | Angle wrapping, smootherstep (zero speed and acceleration at both ends), interpolation. |
| `GuidancePuck` | `:engine-mapbox`, `map/camera/GuidancePuck.kt` | The puck during guidance: a `LocationProvider` around `NavigationLocationProvider` with a 1.1 s glide (the default is 1.0 s). `newDrive()` swaps in a fresh inner provider; a teleport jumps. |
| `SmoothNavigationCamera` | `:engine-mapbox`, `map/camera/SmoothNavigationCamera.kt` | The controller, a `Choreographer.FrameCallback` with modes IDLE, FOLLOWING and OVERVIEW. Calls: `requestFollowing`, `requestOverview`, `requestIdle`, `onUserGesture`, `setVisible`, `onLocation`, `onRouteProgress`, `onGuidanceEnded`, `diagnostics`, `destroy`. |
| `PhoneMap` | `:engine-mapbox`, `map/PhoneMap.kt` | Feeds the controller once per navigator update and writes the camera line to the debug log. |
| `MapScreen` | `:app`, `ui/map/MapScreen.kt` | Turns the camera on when the activity starts and off when it stops (`LifecycleStartEffect`). |
| `CarNavigationMap` (stage 2) | `:engine-mapbox`, `car/CarNavigationMap.kt` | The same camera on the car's map surface. |

The math is pure Kotlin on the Java 8 API in `:core-nav` (DECISIONS 4) and is tested on the JVM.
Everything that touches Mapbox types stays in `:engine-mapbox` (DECISIONS 12).

### Once per navigator update

The navigator delivers the location and then the progress of the same update, on the main thread
(`MapboxTripSession`, navigation 3.34.1).

1. **Location.** `GuidancePuck.onFix` starts the puck's glide. The data source gets the new location.
   `onLocation` stores the speed and whether the fix was a teleport. The data source is evaluated
   here only if no evaluation ran for 1.5 s, because the navigator drops progress while it replaces
   routes.
2. **Progress.** `onRouteProgress` feeds `ManeuverFocus` with the step key (route id, leg, step), the
   metres to the maneuver and along the step (`RouteStepProgress.distanceRemaining` and
   `distanceTraveled`), the banner's maneuver type, the data source's excluded maneuver types and
   the speed. It then sets the data source's `followingPitchPropertyOverride` to the tilt the camera
   will show 1.1 s from now, so the zoom is fitted for that tilt. The data source takes the progress
   and is evaluated once.
3. The new `ViewportData` reaches the controller's observer, which only stores it.

Result: one sample per update, instead of two animator restarts.

### On every display frame

While anything moves:

1. The frame time comes from the `Choreographer`. A gap counts as at most 0.1 s.
2. A new sample, if one arrived, feeds the ramps: zoom, bearing (unwrapped), the world centre, the
   four padding sides and the on-puck weight. The weight is 1 when the frame puts the car on the
   data source's one-pixel focal point (following on the road), and 0 for maneuver framing and the
   overview.
3. Targets:
   - centre: between the world-centre ramp and the drawn puck, by the on-puck weight. The drawn
     puck comes from `OnIndicatorPositionChangedListener`;
   - zoom and bearing: the ramp plus its lead, outside mode changes at most 1 zoom level and 60°
     per second;
   - tilt: `ManeuverFocus` at this moment; in the overview, the frame's tilt;
   - padding: the ramps.
4. During a mode change, the remaining start offset is added (see
   [Modes and transitions](#modes-and-transitions)).
5. The springs step toward the targets.
6. One `setCamera` with centre, zoom, bearing, tilt and padding, skipped when nothing moved. While
   the camera moves, `MapboxMap.setUserAnimationInProgress(true)` keeps label placement steadier.
7. The loop asks for the next frame while a transition, a ramp, the tilt or a spring is still
   moving, or the puck moved in the last 250 ms. Otherwise it stops. Any input wakes it.

### Constants

| Value | Phone | Car |
|---|---|---|
| Centre spring τ | 0.20 s | 0.25 s |
| Zoom spring τ | 0.45 s | 0.45 s |
| Bearing spring τ | 0.35 s | 0.35 s |
| Tilt spring τ | 0.12 s | 0.12 s |
| Padding and on-puck weight τ | 0.35 s | 0.35 s |
| Sample ramp | 1.1 s | 1.1 s |
| Fastest zoom and bearing change (outside transitions) | 1 level/s, 60°/s | same |
| Puck glide per fix | 1.1 s | the same puck |

The tilt and transition constants follow below. All of them come from simulation and need drives
to tune.

### What it does on the road

From the compiled code, simulated at 60 frames a second:

- **Centre.** The camera trails the drawn puck by 2τ × speed: about 5.4 m at 50 km/h and 8.7 m at
  80 km/h, about 7 to 12 dp at zoom 16.35. It settles onto the puck when the car stops. At 50 km/h
  its speed stays within 0.23 m/s of the car's.
- **Puck.** It glides 1.1 s per fix. 98 % of the fix intervals on the drive were 0.98 to 1.02 s and
  the slowest 1.07 s, so the puck never stops between fixes. With the default 1.0 s it stopped
  briefly at the 16 % of fixes that came later than 1000 ms.
- **Bearing.** A steady 5°/s curve turns at 4.83 to 5.17°/s. The camera is about 1.1 s behind the
  data source's bearing; today's linear second is 1.0 s. The early turn stays: the camera still
  turns hundreds of metres before the bend.
- **Tilt.** At most 27°/s, with zero speed and acceleration at both ends. Today: 45°/s with a hard
  start and stop.

### The tilt before a turn

`ManeuverFocus` keeps the SDK's rule (the same maneuver types, flat from 180 m) and replaces its
step with timed, eased moves:

- **Which maneuvers.** Every banner type except the data source's excluded ones: continue, merge,
  on ramp, off ramp and fork. For those the view stays at 45°.
- **Down** over 3.5 s, starting at 180 m + speed × 3.5 s (speed counted as at least 3 m/s), so the
  view is flat just as the SDK's maneuver framing starts at 180 m. Once started for a step it stays
  latched: braking never tilts the view back up before the turn.
- **Held** flat until at least 25 m and 2 s into the next step.
- **Up** over 3.0 s, unless the next counted maneuver is already inside its own window. Close turns
  keep the view down, with no bounce.
- A **reversal** starts from where the tilt is and takes the remaining share of the time, at least
  0.3 s.
- The step key includes the route id, so a reroute is a new step even when its leg and step numbers
  repeat.

Distances at the drawn puck:

| Speed | Starts down | Halfway | Flat by | Rises from | 45° again |
|---|---|---|---|---|---|
| 30 km/h (19 mph) | 212 m | 201 m | 190 m | 21 m after | 40 m after |
| 50 km/h (31 mph) | 230 m (16.6 s before) | 212 m | 194 m | 47 m after | 78 m after |
| 80 km/h (50 mph) | 253 m | 224 m | 195 m | 57 m after | 107 m after |

- Braking from 50 to 20 km/h: the same descent, and the view stays down.
- A second turn 120 m later: flat throughout.
- Fork, merge and ramps: 45° throughout.
- The tilt is 0° at the junction.

The SDK's maneuver framing stays: from 180 m the frame covers the rest of the step and 100 m after
the turn. It glides in over about 1.5 s through the on-puck weight instead of jumping.

### Modes and transitions

- `CameraMode` works as today. A change is reported at once with the mode it goes to; asking for the
  current mode does nothing.
- A transition starts from wherever the camera is. After idle, the springs start from the map's
  current camera (`MapboxMap.cameraState`), so **Recenter** flies from where the driver left the
  map.
- The offset between the camera and the live target fades out on a smootherstep curve. A moving
  target is chased from the first frame, and a still one is reached without a jolt. So stage 4's
  frequent switches end without a catch-up surge.
- Length: 1.5 s; 2.2 s for more than 3 zoom levels or more than 2 screens of distance; 0.8 s after a
  teleport that leaves the camera more than 60 m from the puck.
- Moves longer than one screen dip the zoom like a fly-to: by log2 of the distance in screens, at
  most 3 levels.
- Overview, also used for the route preview: the data source's overview frame and its tilt, through
  the same ramps and springs.
- While the activity is stopped (phone) or the surface is detached (car), no frames run. When the
  phone's activity starts again, the first frame snaps to the targets, since the map was not on
  screen. The car builds a new controller for each new surface.

### Gestures hand the camera to the driver

- The controller never starts a camera animator. Gestures, the compass and any app `easeTo` do, and
  the camera plugin reports every start, even zero-length ones. So any animator start means someone
  else took the camera.
- The mode then goes to IDLE in the same call, the frame loop stops and the **Recenter** button
  appears.
- A plain tap starts no animator, so it does not stop following.
- **Recenter** calls `requestFollowing()` and comes back in one smooth flight.
- Rule for later code: nothing else may move the camera during guidance. An `easeTo` hands the
  camera to the driver, by design.
- Car scroll and scale gestures call `setCamera` directly, without animators, so the car needs an
  explicit hook (see [The car map](#the-car-map-stage-2)).

### End of guidance

- `onGuidanceEnded()` resets the tilt schedule and clears the data source's following overrides
  (`clearFollowingOverrides`).
- If the camera was not idle yet, it goes idle and `PhoneMap` eases to the browsing view as today:
  zoom 15.5, tilt 40°, 0.8 s.
- At the next Start, `GuidancePuck.newDrive()` swaps in a fresh provider, so the puck does not jump
  from where the last drive ended.

### The car map (stage 2)

`CarNavigationMap` is a `MapboxCarMapObserver` (maps-androidauto 11.34.1), registered with
`registerObserver(...).setGestureHandler(...)`. The style chosen in Settings applies to the car
screen too (DECISIONS 26); this section covers Smooth follow, the default and the only style
designed for the car so far. The rest of the car screen is in [STAGE-2.md](STAGE-2.md).

- **On attach:** the same puck settings as the phone, its own `MapboxNavigationViewportDataSource`,
  and a `SmoothNavigationCamera` with `CameraTuning.CAR`. `MapboxCarMap` builds a new map surface
  whenever the host sends a new one, for example after a size change, so each attach starts a new
  data source and controller.
- **Location:** as on the phone, the platform provider before Start (DECISIONS 14) and the engine's
  `GuidancePuck` during guidance.
- **Padding:** following padding comes from the stable area, which does not change when the action
  strip comes and goes, so the car does not move with it. It is the area's insets plus 5 dp, but at
  the bottom the inset plus a third of the area's height. Mapbox's own `CarNavigationCamera` uses
  the same 5 dp and third, on the visible area. The visible area stands in until a stable area
  arrives. Overview padding is the visible area's insets plus 5 dp.
- **Padding on a new surface:** `MapboxCarMap` does not send the stable area again when a new
  surface attaches, and its stable insets can be missing or stale. So the padding is computed from
  the stable area's rectangle and the surface size on every attach.
- **Gestures:** car scroll and scale call `setCamera` directly, without animators. A double-tap zoom
  eases with an animator, which the hand-over rule catches anyway, and Mapbox's default handler
  does nothing on a fling. The gesture handler calls `onUserGesture()` before passing on scroll,
  fling and scale. **Recenter** comes from the map's action strip.
- **One puck for both maps:** `GuidancePuck` and the navigator feed move into the engine. Each fix
  then reaches the phone and car maps once, and `newDrive()` runs when guidance starts. Mapbox's
  `CarLocationProvider` is not used: it keeps the 1000 ms glide.

### Logging

A **camera** line about once a second during guidance:

| Field | Meaning | Expected |
|---|---|---|
| `camMode` | IDLE, FOLLOWING or OVERVIEW | |
| `camFps` | controller frames per second | about the display rate while moving; low when parked, because the loop stops |
| `camMaxGapMs` | worst gap between two frames that second | under 25 ms at 60 Hz; spikes mean dropped frames |
| `camWrites` | `setCamera` calls that second | about `camFps` while moving, 0 while parked |
| `puckLagM` | how far the camera centre trails the drawn puck | about 2τ × speed: 0.4 s × speed on the phone, 0.5 s × speed on the car |
| `pitch`, `focus`, `focusGoal` | the tilt and the schedule behind it | |
| `zoom`, `zoomTarget` | the zoom and its target | |
| `bearingError` | target bearing minus camera bearing | within a few degrees |
| `onPuck` | the on-puck weight | 1 on the road, 0 in junction framing and overview |
| `animatorsEnabled` | `ValueAnimator.areAnimatorsEnabled()` | false: the animator scale is 0 and the puck jumps |
| `renderFps` | the renderer's own rate (`MapView.setOnFpsChangedListener`, called on the render thread) | at least 55 at 60 Hz |

- The log records the active camera style, so drives in different styles can be compared.
- The two Mapbox styles do not run the controller. For them the line holds the map's zoom, tilt and
  bearing against the data source's targets, and the frame rate.
- On the phone the line is written only while the map is on screen; the `activity` and `screen`
  lines show when it was not.
- A **camera_state** line on every mode change. For the Mapbox styles it should also record how
  long each transition took: one that should take 673 ms or more and ends within a few ms means
  animations are off.
- A **camera_taken** line when something else takes the camera, with the owner of the animator that
  took it (`Maps-Gestures` for a touch), and a **camera_request** line when **Recenter** or
  **Overview** is tapped. The first drive's log could not say why the camera went idle about 22 s
  before End.
- An **animations** line: `areAnimatorsEnabled()`, the animator duration scale setting
  (`Settings.Global.ANIMATOR_DURATION_SCALE`) and power saving. It is written at Start, after the
  first activity exists, not at app start: a process learns the real scale only when its first
  activity launches and reports the default 1.0 until then, so a value read in
  `Application.onCreate` says "enabled" even with animations off. On Android 13 and newer a
  duration-scale listener logs changes, and a receiver does the same for power saving. The scale
  setting shows the developer and accessibility switches but not a phone maker's battery-saver
  override; `areAnimatorsEnabled()` shows the effective state.
- Optional Perfetto trace: the controller's frame should take under 0.2 ms; the data source's
  `MapboxNavigationViewportDataSource#evaluateImpl` should run once a second; no
  `CameraAnimations#commitChanges` while following, which proves nothing else writes the camera.

### Hooks for stages 4 and 5

Stage 4, smart camera:

- **Junction zoom.** Where the targets are computed, raise the zoom target and pull the centre
  toward the maneuver point by `maneuverFocus.focusAt(now)`, the same eased curve as the tilt,
  through the existing blend of world point and puck. The controller sets the zoom itself, so the
  data source's 16.35 cap does not limit it. Because it reads the tilt's curve, close turns get one
  zoom-in, as they get one tilt. The roadmap brings a first part of this, more zoom at turns, into
  the next build as the Lane zoom setting ([answer 2](#the-owners-answers)).
- **Keep the early turn.** If stage 4 shortens the framed geometry near turns or overrides the
  bearing (`followingBearingPropertyOverride` bypasses the data source's bearing smoothing), it must
  keep that smoothing or rebuild it. Otherwise the early turn disappears near junctions.
- More tiles load at zoom 18 to 19. Where the phone is offline, the map stretches tiles from lower
  zooms.
- **Automatic overview and following** call `requestOverview()` and `requestFollowing()`. The
  transitions chase a live target, so they end without a surge.
- The **20 s pause after a touch** is a timer started when the mode changes to IDLE.

Stage 5, 3D:

- Raise `cruisePitch` for a steeper tilt while following.
- A 3D car in place of the arrow reports its position through the same listener.
- Cap the frame rate with `MapView.setMaximumFps(60)` if the phone gets hot.
- An own frame clock for the puck, if it is wanted (see [System animations](#system-animations)).

### Residual risks

- **It owns the camera.** Any later camera write during guidance must go through the controller. An
  `easeTo` hands the camera to the driver, by design.
- **`NavigationCamera`'s state machine is not inherited** in Smooth follow, nor future SDK fixes to
  it. Its tile pre-load hint for the overview is not used either, because that API is restricted, so
  tiles may load a little later in the first seconds after leaving the overview.
- **SDK upgrades.** Check three behaviours of the data source again with each upgrade: the one-pixel
  focal padding, the framing around the rest of the step near maneuvers, and the look-ahead
  bearing. If the padding shape changes, the on-puck detection falls back to centring on the frame's
  world point, which is safe.
- **The puck is still the SDK's animation.** Its speed is constant between fixes and steps at each
  fix, and it holds still for one frame at each restart. The springs keep this out of the map's
  motion, but the car icon may show a tiny hitch against the road. If it does, the next step is a
  smoother (Hermite) curve passed through `changePosition`'s transition options, or driving the
  puck from the controller.
- **The puck may be read one frame late:** a constant offset of about 0.23 m at 50 km/h, which the
  spring absorbs.
- **The junction framing still switches at 180 m.** The camera glides there over about 1.5 s, but
  the car leaves its place near the bottom and moves up the screen. If that looks busy before stage
  4, `maximizeViewableGeometryWhenPitchZero = false` is a one-line alternative.
- **The zoom follows the tilt** during the 3.5 s descent and the 3 s ascent, because the data source
  fits the zoom at the coming tilt. That is deliberate, but check on a drive that it does not feel
  like breathing.
- **Labels** update less eagerly while the camera moves (`setUserAnimationInProgress`).
- **Chase distance.** If 2τ × speed reads as lag, lower the centre τ to 0.15 s. It is one constant.
- **Phone locked in the car.** The frame loop and the puck run on the main thread. Mapbox's
  `AnimationThreadController` (Maps 11.34.1) says its background animation thread can be essential
  for Android Auto on some devices when the phone is locked. Check on the DHU and in the car.
  Fallbacks: take the controller's frame tick from the car surface, or from a `Choreographer` on a
  background thread that posts the camera write to the main thread; move the puck animators with
  `AnimationThreadController.useBackgroundThread()`, which is experimental and moves every Mapbox
  animation in the app. Measure before choosing.
- **Two maps at once.** With the phone app open and the car connected, two loops and two renderers
  run. The phone loop stops with the activity, but Settings drawn over the map keeps it running, as
  map rendering does today.
- **Step changes.** The bearing still swings toward the new step, by up to 45°; it is now a glide of
  at most 60°/s instead of a cut (animations off) or a one-second linear move (animations on).
- **The constants** come from simulation and need drives to tune.

## System animations

- **The Smooth follow camera does not depend on the system animation setting.** Its frames come from
  `Choreographer` callbacks, which the animator duration scale does not affect. Tilt, zoom, bearing
  and padding stay smooth with animations off, and so does the centre in junction framing and in
  the overview.
- **The puck does.** Its glide is a `ValueAnimator` in the Maps SDK's location component; with the
  scale at 0 it jumps once a second. While following, the camera chases the drawn puck, so its
  centre then gets a once-a-second input again: no more cuts, but not continuous either (see
  [the verdict](#the-verdict-on-once-a-second-updates)).
- **The two Mapbox styles depend on it entirely:** their camera is made of animators.
- **If the owner keeps animations off on purpose,** an always-smooth puck needs its own frame clock:
  one `Choreographer` callback that moves the puck, the camera and the route line together, which
  the stage 5 plans already point to. That path would ignore the system setting on purpose. Remove
  animations is an accessibility setting, for example for motion sensitivity, so it must not be
  overridden silently. The owner turned animations back on and wants no override
  ([answer 1](#the-owners-answers)), so this path is not built.

## Test plan

### Build order

1. `:core-nav`: `CameraMotion.kt` and `CameraMotionTest`.
2. `:engine-mapbox`: `GuidancePuck` and `SmoothNavigationCamera`.
3. `PhoneMap` wiring, the style setting with the Mapbox tuned transition, and the
   `LifecycleStartEffect` in `MapScreen`.
4. CI: build, lint, unit tests.
5. A simulated drive, then tuning from the camera line.
6. A road drive.
7. Stage 2: `GuidancePuck` and the navigator feed in the engine, then `CarNavigationMap`, then the
   DHU and the car.

### JVM tests, no device

`CameraMotionTest` in `:core-nav` (JUnit 6): seven tests, all passing against the draft code.

- The spring is exact for any frame time and never overshoots.
- Ramp, spring and lead hold a steady 5°/s curve within 4.8 to 5.2°/s.
- At 50 km/h the tilt is below 3° at 180 m, 0° at the junction and back at 45° after it, and peaks
  below 30°/s.
- Braking never tilts the view up before the turn.
- Excluded maneuver types keep 45°.
- A second turn 120 m later keeps the view down.
- A reroute that reuses the step numbers is still a new step.

Worth adding: a reversal halfway down, and a ramp fed with the fix-interval jitter from the log.

### Build

CI runs the debug build, lint and the unit tests. So far the draft code was compiled with Kotlin
2.4.21, warnings as errors, against the class jars of Maps 11.34.1, Navigation 3.34.1 and Android 37
(the `:core-nav` part also against the Java 8 API), and the car observer against maps-androidauto
11.34.1 and car-app 1.8.0-rc01. It has not been through Gradle yet.

### Simulated drive

In **Settings → Debug → Simulate drive**, pick a route with a long gentle curve, ordinary turns, two
turns less than 150 m apart, a fork or off-ramp, a roundabout and the arrival. Drive it once in
each camera style; the points below are for Smooth follow.

Watch for:

- The map moves on every frame, with no once-a-second steps.
- The car glides a few dp ahead of its usual place near the bottom and settles back when it stops.
- On the gentle curve the view starts turning before the curve, at an even rate.
- Before a turn the tilt goes down over about 3.5 s and is flat about 180 m before the turn. It is
  0° at the junction and rises over about 3 s, starting 20 to 60 m after it.
- No tilt for forks, merges or ramps.
- The two close turns stay flat throughout.
- At 180 m the junction framing glides in over about 1.5 s, with no jump.

Try:

- Pan, pinch, rotate, tilt, double-tap and the compass: each switches to idle at once, and
  **Recenter** appears.
- **Recenter**: one smooth flight back.
- **Overview** on and off while moving: the camera tracks the moving target, with no surge at the
  end.
- The preview, then Start.
- The app to the background and back.
- The map created again mid-drive: with **Developer options → Don't keep activities** on, leave the
  app and open it again.
- A second drive in the same session: the puck must not jump to where the first drive ended.
- A teleport, if the replay produces one.

### Road drive

Drive the owner's usual route at city and highway speeds, in each camera style, changing the style
only while stopped:

- the gentle curve where Google Maps did not turn early: the early turn must remain;
- several turns, each approached while braking;
- a stop at a red light: the camera settles and `camWrites` drops to 0;
- a tunnel or GPS loss: the puck stops, then resumes without a jump;
- a reroute.

The owner rates the overall smoothness and three things that each have one knob in `CameraTuning`:

| What | Knob |
|---|---|
| How far the camera trails the car | `centerTau`, 0.15 to 0.3 s |
| When the tilt goes down | `descentSeconds` or `maneuverFramingMeters` |
| When it comes back up | `holdAfterManeuverMeters` and `holdAfterManeuverSeconds` |

Tuning uses the shared debug log.

### Desktop Head Unit (stage 2)

With the 800×480 and 1280×720 profiles, run the simulated drive with the car map showing:

- The car camera follows smoothly with `CameraTuning.CAR`.
- Showing or hiding the action strip does not move the car.
- Car scroll and scale switch to idle, and **Recenter** from the action strip comes back smoothly.
- One fix moves both pucks the same way.

Then lock the phone and turn its screen off. The car camera's `camFps` must stay near the display
rate. If it collapses, the main thread's frames are being held back: try the fallbacks under
[Residual risks](#residual-risks). Measure before committing to one.

### Acceptance

- On a mid-range phone, `camMaxGapMs` stays under 34 ms in 99 % of seconds.
- `renderFps` is at least 55 at 60 Hz.
- No visible steps.
- The tilt peaks at 30°/s or less, and is 0° at each counted junction.

## The owner's answers

Answered on 2026-11-10.

1. **System animations.** They were off through an accessibility switch (Reduce animations). The
   owner turned them back on, and guidance became smooth. The app never overrides the setting, and
   the drawn car gets no separate always-smooth motion.
2. **Lane size.** A **Lane zoom** setting with two choices: **Default**, today's zoom, and
   **Large**, a very close view at junctions where the lanes are clearly visible (a lane about 4 mm
   wide on screen, zoom about 18.75 and closer). Large also zooms in on freeways where lanes split
   off while the route goes straight on. The map cannot show which lane the car is in: phone GPS is
   not lane-accurate and the public SDK has no lane geometry, so the car stays on the road line and
   the lanes to use stay in the maneuver card.
3. **Freeways.** The long view ahead stays as it is: no minimum zoom of 14.
