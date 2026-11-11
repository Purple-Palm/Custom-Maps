# Guidance camera

How the map camera moves during guidance: what the owner reported after the first drive, why the
camera behaved that way, the designs considered, the one chosen, and how it will be tested. SDK
references are to Maps SDK 11.34.1 and Navigation SDK 3.34.1 (ui-maps 3.34.1). The drives are
described in [DRIVES.md](DRIVES.md); stages are in [ROADMAP.md](ROADMAP.md).

**Status:** built in stage 1.1 (2026-11-10). The three camera styles, the setting that chooses one,
[following before guidance](#following-the-car-before-guidance) and the
[Lane zoom](#lane-zoom) setting with its Large mode are in the app, and the motion math has JVM
tests. None of it has been on a simulated or a real drive yet, so every number is still the one
from simulation. The owner answered the camera questions; the answers are
[at the end](#the-owners-answers).

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
| **Mapbox standard** | The camera of the first drives, kept for comparison: `NavigationCamera` with its default options, two evaluations per navigator update, the 1.0 s puck. | Yes |
| **Mapbox tuned** | The same camera with calmer settings: one update per navigator update, longer moves from frame to frame, a gradual tilt, the 1.1 s puck. | Yes |
| **Smooth follow** (default) | The new camera: it moves the map on every display frame and glides after the drawn car. Described from [Smooth follow](#smooth-follow-the-chosen-design) on. | The camera no, the puck yes (see [System animations](#system-animations)) |

The style is chosen in **Settings → Guidance → Camera style** and stored under `camera_style` as
`mapbox_standard`, `mapbox_tuned` or `smooth_follow`; a missing or unknown value reads as Smooth
follow.

Mapbox tuned is built from changes the drive analysis proposed for the standard camera. Its numbers
are starting points for tuning.

- **One update per navigator update.** The data source is evaluated once, on the progress. The
  location evaluates only when no progress came for 1.5 s, because the navigator drops progress
  while it replaces routes. A teleport (a jump of the matched position) snaps the puck and the
  camera instead of sliding them across the map; the puck then glides on from its new place, along
  its heading at its speed, until the next position, instead of standing still for a second.
- **Calmer frame updates.** An own following transition (a `NavigationCameraStateTransition` given
  to `NavigationCamera`) plays longer linear animators: centre 1.1 s, bearing 1.3 s, zoom 2.5 s,
  tilt 2.0 s, padding 1.5 s, at most 2.5 s. Each update restarts them from where they are, so they
  act like a low-pass filter: zoom and tilt changes settle over 2 to 4 s instead of 1 s.
- **A gradual tilt.** The view still goes flat near a turn, as the owner wants, but through the 2 s
  tilt animator instead of one second at 45°/s.
- **No higher zoom cap.** A `maxZoom` of 17.5 instead of 16.35 was suggested and is not built.
  Whenever only the location is framed (before the first progress after Start, after every
  reroute), the zoom target would jump to the cap for about a second, on a freeway from about 14 to
  17.5 and back. And the whole town drive would get closer, about 350 to 500 m ahead, not only the
  approach to turns. Tuned keeps 16.35 on the road; its extra zoom at turns comes from the Large
  [lane zoom](#mapbox-tuned-and-mapbox-standard), which raises the limit only while the SDK frames a
  maneuver.
- **The 1.1 s puck** (see `GuidancePuck` below). It trails the fix by about 0.1 s more, about 3 m
  at 65 mph.
- **The preview knows where the car is.** In the route preview the data source gets the puck's
  position when the preview opens and then whenever the car has moved 5 m, at most once a second
  (see "Also found" under [Smaller causes](#smaller-causes)). Smooth follow does the same.
- **Limits.** It still runs on once-a-second updates, so it can be calmer but not continuous (see
  [the verdict](#the-verdict-on-once-a-second-updates)). It helps only while system animations run.
  Its animators must carry `NavigationCamera.NAVIGATION_CAMERA_OWNER`, or
  `NavigationBasicGesturesHandler` drops the camera to idle.
  `UpdateFrameAnimatorsOptions.useSimplifiedAnimatorsDependency` must stay false, because that path
  skips the own transition. Start and **Recenter** must pass the same transition options, or
  **Recenter** falls back to 1000 ms.

**What Mapbox standard shares.** Its camera behaviour stays as it was: two evaluations per
navigator update, the SDK's one-second moves, the 1.0 s puck that slides also when the matched
position jumps, and no position from the puck during the preview. Only a seek of a simulated drive
puts the puck and the camera at the new place at once, as in every style. What is not camera behaviour
applies to every style: the route line fix, the released data source, the top padding that only
grows during guidance and its band for the Then chip, a fresh puck for every drive, following
before guidance, and the camera line (see [Smaller causes](#smaller-causes) and
[Logging](#logging)).

The chosen style applies at once, also mid-drive, and on the car screen from stage 2. The debug log
records the active style, so drives can be compared ([DECISIONS.md](DECISIONS.md), 26).

Only one camera may move the map at a time. Smooth follow treats any camera animator as the driver
taking over, and `NavigationCamera` moves the map with animators. So `PhoneMap` talks to one
`GuidanceCamera` at a time: `NavigationCameraAdapter`, which is `NavigationCamera` with
`NavigationBasicGesturesHandler`, for the two Mapbox styles, and `SmoothNavigationCamera` for
Smooth follow. The design below was drawn up with `NavigationCamera` removed; the style setting
keeps it. On a style change the old camera lets go of the map and takes back what it set on the
data source: Smooth follow its tilt override (`clearFollowingOverrides`), and both it and Mapbox
tuned the limits the Large lane zoom moved. Mapbox standard sets nothing. `PhoneMap` then sets up
the data source's frames for the new style and the lane zoom
([Lane zoom](#changing-the-setting-or-the-style-during-a-drive)), the data source computes a fresh
frame, and the new camera is asked for what the old one was doing: following, the overview, or
following on the browsing map.

## Following the car before guidance

Before guidance, with no route on the map, the camera stays on the car in every style. The map
shows the car from the phone's own location, as before ([DECISIONS.md](DECISIONS.md), 14), and the
camera uses nothing else: no trip session and no request.

- **When.** From the car's first position until the driver pans, zooms or rotates the map, and
  again after **My location**. A map the driver moved before the first position, as while the
  phone still looks for one, stays where it is too. A route on the map, in the preview and during
  guidance, has the cameras described below. When the route goes, the camera returns to the car
  and follows it if the route's camera still had the map, and stays where it is if the driver had
  taken the map. The rule is `IdleFollow` in `:core-nav`, tested on the JVM.
- **The view.** Zoom 15.5, tilt 40°, the bearing the map has, and no padding: the car sits in the
  middle of the map, and no panel, chip or list moves it.
- **The two Mapbox styles** ease to the car over 0.8 s. Then every new position of the phone's
  location starts one more ease to that position, linear and 1.0 s long like the puck's own glide,
  so the camera and the car move together. The eases carry an owner name of their own
  (`CUSTOM_MAPS_IDLE_FOLLOW`); an animation of any other owner is the driver's.
- **Smooth follow** goes after the drawn puck on its frame loop and springs, as during guidance. It
  comes to the car over 0.8 s, or 2.2 s from more than two screens away. Its loop stops while the
  car stands.
- **A far position.** The location component glides the car to each new position in a second,
  whatever the distance. When the app comes back after the car drove on, or the phone had no
  position for a while, a camera that follows would cross all the map in between, over tiles that
  are not loaded. So a position more than 150 m from where the car is drawn puts the car there at
  once, and the camera with it, in every style.
- **Any gesture** hands the map to the driver, and so does the compass. A plain tap does not. In
  the two Mapbox styles a tap still cancels the ease that is running, as it does with the SDK's
  camera during guidance: the camera stands until the phone's next position starts a new ease, at
  most a second while driving. **My location** takes it to the car at once, also while it follows
  already.
- **The data source** gets every position of the phone's location while the camera follows, in
  every style. Nothing is computed from it until a route is shown; the preview's overview and
  the Start flight then begin from where the car is, not from where it was when following began.
- **In the log** the `camera_state` line says `IDLE_FOLLOW` while the camera is on the car and
  `IDLE` once the driver has the map. A `camera_taken` line says who took it. A map moved before
  the car's first position was not taken from a camera and leaves no such line: the state then
  stays `IDLE` when the car appears.

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

This is how the first drives were. Stage 1.1 answers it with the [Lane zoom](#lane-zoom) setting,
whose Large mode goes close at junctions, and with a switch for
[lane details on the map](#lane-details-on-the-map).

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

- **The maneuver card resized the frame.** The top padding followed the card's bottom edge, and
  each change evaluated the camera again. The card grows by 56 dp when lanes appear (a 12 dp gap
  and a 44 dp lane row) and by a line when the instruction wraps. The car stays put, but the zoom
  (below the cap) and the flat frames near a turn change. Fixed for every style: the top padding
  comes from the maneuver card alone and only grows during guidance (`GuidancePadding` in
  `:core-nav`); the search bar and its suggestions no longer count. In Smooth follow padding
  changes glide in any case.
- **The Then chip never moves the frame.** The chip that names the maneuver after the next one
  ([DECISIONS.md](DECISIONS.md), 37) hangs under the maneuver card, outside the card's measured
  size. During guidance the top padding is the card's lowest bottom edge so far, plus a constant
  band of 56 dp for the chip, plus the 16 dp margin. The band is there from Start to the end of
  guidance, whether the chip shows or not: the chip never covers what the camera frames, and the
  padding does not change when the chip slides out or back.
- **Not the cause, but wasteful.** On every frame of the puck animation `PhoneMap` updated the
  travelled part of the route line. The SDK accepts a point only every 62.5 ms; each rejected call
  reads the style URI through a regex and logs an error, about 45 to 105 times a second once
  animations run. Fixed: `PhoneMap` asks only when a point is due and renders only an accepted
  update. The burst of map errors while the phone was offline (2337 lines) also ran regexes on the
  main thread, a few ms per second at worst. Updates kept arriving every 1.00 ± 0.02 s, so neither
  delayed them.
- **Unknown:** whether Standard with night lighting and 3D buildings is too heavy for the phone. The
  log has no frame times, and with animations off the map redrew mostly once a second. Once
  animations run it renders continuously, at up to 120 Hz. Measure frame times before changing the
  style.
- **Also found:** outside guidance the data source learned the location only when the map centred
  on the puck (the first fix after launch, or a tap on my location). The preview's overview and the
  Start flight use that point, so a preview built in one place and started elsewhere starts from
  the old spot and then jumps. Fixed in two parts. While the camera follows the car on the browsing
  map, the data source gets every position, in every style. And for Mapbox tuned and Smooth
  follow, in the preview the data source gets the puck's position when the preview opens, and then
  whenever the car has moved 5 m, at most once a second. Mapbox standard goes without that second
  part, as before: there a preview opened after the driver had taken the map, or one the car
  drives on in, still starts from the old spot.

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

- A steady 5°/s curve turns at 4.93 to 5.07°/s with the drive's fix-interval jitter. A plain spring
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
- Critically damped springs follow the targets. Their step is exact for any frame time, and they
  come up to a target that stops without swinging past it.
- While following, the centre chases the drawn puck, not the raw fix. The camera trails the car a
  little and settles onto it when the car stops.
- `MapboxNavigationViewportDataSource` stays as the source of targets. The look-ahead bearing (the
  early turn), the zoom, the maneuver framing and the overview are unchanged; with the Large lane
  zoom the camera adds its close views on top (see [Lane zoom](#lane-zoom)).
- A timed, eased schedule (`ManeuverFocus`) replaces the 45°/0° tilt switch.
- When nothing moves, the loop stops: no frames, no camera writes.

### Classes

| Class | Where | What it does |
|---|---|---|
| `CameraTuning` | `:core-nav`, `nav/camera/CameraMotion.kt` | Every constant, in seconds, degrees and metres, with `PHONE` and `CAR` presets. One place to tune after a drive. |
| `Spring` | same file | Critically damped spring with an exact closed-form step. From rest it never passes its target. |
| `SampleRamp` | same file | Moves from where it is to each new sample at a constant rate over 1.1 s. Its lead, rate × τ, is what a spring aims at: the spring then trails the ramp by τ × rate and stops on the last sample, not past it. |
| `ManeuverFocus` | same file | The tilt schedule: 0 on the road, 1 over the junction; tilt = lerp(45°, 0°, focus). After a maneuver the Large lane zoom lets the junction's zoom go along the same curve. |
| `Angles`, `easeInOut`, `lerp` | same file | Angle wrapping, smootherstep (zero speed and acceleration at both ends), interpolation. |
| `OncePerUpdate` | `:core-nav`, `nav/camera/OncePerUpdate.kt` | Which message of a navigator update the frame is computed on: the progress, or the location when no progress came for 1.5 s or the position jumped. Used by Mapbox tuned and Smooth follow. |
| `JumpFrame` | `:core-nav`, `nav/camera/JumpFrame.kt` | After a seek of a simulated drive, the new place's progress frames it and the camera goes there at once; the locations before it move nothing unless that progress is 1.5 s overdue. Used by both Mapbox styles. |
| `IdleFollow` | `:core-nav`, `nav/camera/IdleFollow.kt` | Decides when the camera follows the car before guidance, and holds the browsing view's zoom and tilt and the distance from which a new position is a jump. |
| `GuidancePadding` | `:core-nav`, `nav/camera/GuidancePadding.kt` | The map's top inset: from the maneuver card alone, only growing during guidance, plus a constant band under the card for the Then chip. |
| `MapScale`, `LaneChoice`, `LaneFocus`, `ZoomPlanner`, `centerLeadSeconds` | `:core-nav`, `nav/camera/LaneZoomPlan.kt` | The Large lane zoom's math: a lane's width on the screen at a zoom, which points of a route count as a lane choice, when the view goes close for one, the zoom the camera aims at, and how far ahead of the car its centre aims. See [Lane zoom](#lane-zoom). |
| `LaneChoices` | `:engine-mapbox`, `navigation/LaneChoices.kt` | The points with lanes ahead of the car, read from the route once for each step. The engine owns the one copy; the cameras and the maneuver card read from it. |
| `GuidanceCamera` | `:engine-mapbox`, `map/camera/GuidanceCamera.kt` | What `PhoneMap` asks of the camera of any style: `requestFollowing`, `requestOverview`, `requestIdleFollow`, `requestIdle`, `onLocation`, `onRouteProgress` (with the lane choices ahead), `onPuckGlide`, `onGuidanceEnded`, `setLaneZoom`, `setVisible`, `diagnostics`, `destroy`, and a listener for its state and for who took the map. |
| `LaneZoomFrames` | `:engine-mapbox`, `map/camera/LaneZoomFrames.kt` | What the lane zoom changes on the viewport data source for each camera style, and the way a change of it reaches a route the data source already has. |
| `NavigationCameraAdapter` | `:engine-mapbox`, `map/camera/NavigationCameraAdapter.kt` | The two Mapbox styles: `NavigationCamera` and `NavigationBasicGesturesHandler` behind `GuidanceCamera`. |
| `FollowingTransition` | `:engine-mapbox`, `map/camera/FollowingTransition.kt` | Mapbox tuned's longer animators between following frames. |
| `SmoothNavigationCamera` | `:engine-mapbox`, `map/camera/SmoothNavigationCamera.kt` | Smooth follow: the controller, a `GuidanceCamera` and `Choreographer.FrameCallback` with modes IDLE, IDLE_FOLLOW, FOLLOWING and OVERVIEW. |
| `GuidancePuck` | `:engine-mapbox`, `map/camera/GuidancePuck.kt` | The puck during guidance: a `LocationProvider` around `NavigationLocationProvider`. It glides 1.1 s per fix and jumps on a teleport, from where it glides on for one glide along its heading at its speed (`PuckGlide`); for Mapbox standard it glides 1.0 s and slides also on a teleport. In every style, Mapbox standard included, the first position after a seek of a simulated drive (`onFix(jump = true)`) puts it at the new place at once, and it glides on from there in the same way. `newDrive()` swaps in a fresh inner provider at every Start, in every style. |
| `PlatformPuck` | `:engine-mapbox`, `map/camera/PlatformPuck.kt` | The puck before guidance: the Maps SDK's `DefaultLocationProvider`, a report of each new position for the camera and the data source, and a jump in place of the glide when a position is far from the drawn car. |
| `LaneDetails`, `RouteLineWidths` | `:engine-mapbox`, `map/LaneDetails.kt`, `map/RouteLineWidths.kt` | Lane markings on the map, set after the SDK has turned them off, and the route line that stays thin close in while they are on. |
| `PhoneMap` | `:engine-mapbox`, `map/PhoneMap.kt` | Creates the camera of the chosen style, hands it every navigator update with the lane choices ahead, sets up the data source for the lane zoom, and writes the camera line to the debug log. |
| `MapScreen` | `:app`, `ui/map/MapScreen.kt` | Passes on the chosen style, the lane zoom and the lane details switch, measures the padding, and says when the map is on screen: from the activity's start to its stop (`LifecycleStartEffect`). |
| `CarNavigationMap` (stage 2) | `:engine-mapbox`, `car/CarNavigationMap.kt` | The same camera on the car's map surface. |

The math is pure Kotlin on the Java 8 API in `:core-nav` (DECISIONS 4) and is tested on the JVM.
Everything that touches Mapbox types stays in `:engine-mapbox` (DECISIONS 12).

### Once per navigator update

The navigator delivers the location and then the progress of the same update, on the main thread
(`MapboxTripSession`, navigation 3.34.1).

1. **Location.** `GuidancePuck.onFix` starts the puck's glide. `onLocation` stores the speed and
   whether the fix was a teleport, and gives the data source the new location. The data source is
   evaluated here only if no progress came for 1.5 s, because the navigator drops progress while
   it replaces routes, or if the position jumped (`OncePerUpdate`).
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
   - zoom and bearing: within a mode the ramp plus its lead (its rate × τ), at most 1 zoom level
     and 60° per second; during a change of mode the plain ramp, as fast as it goes. A frame that
     comes during the change, as the navigator's first one after Start does, can differ much from
     the one before, and aiming ahead of it would first take the camera the wrong way;
   - tilt: `ManeuverFocus` at this moment; in the overview, the frame's tilt;
   - padding: the ramps.
4. During a mode change, the remaining start offset is added (see
   [Modes and transitions](#modes-and-transitions)).
5. The springs step toward the targets.
6. One `setCamera` with centre, zoom, bearing, tilt and padding, skipped when nothing moved. While
   the camera moves, `MapboxMap.setUserAnimationInProgress(true)` keeps label placement steadier.
7. The loop asks for the next frame while a transition, a ramp, the tilt or a spring is still
   moving, or the puck moved in the last 250 ms. Otherwise it stops. Any input wakes it. Two
   details keep it stopped while the car stands. The puck's animation reports on every frame
   during guidance, so the puck counts only when it has moved more than about a centimetre, and
   only while the camera is on it. And a ramp that is given the same sample every second only comes
   ever closer to it, so it counts as arrived within a small tolerance. A parked car costs one or
   two frames a second, from the navigator's updates.

### Constants

| Value | Phone | Car |
|---|---|---|
| Centre spring τ | 0.20 s | 0.25 s |
| Zoom spring τ | 0.45 s | 0.45 s |
| Bearing spring τ | 0.35 s | 0.35 s |
| Tilt spring τ | 0.12 s | 0.12 s |
| Padding and on-puck weight τ | 0.35 s | 0.35 s |
| Sample ramp | 1.1 s | 1.1 s |
| Lead of zoom and bearing ahead of their ramps (`rampLead`) | 1 τ | 1 τ |
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
- **Bearing.** A steady 5°/s curve turns at 4.93 to 5.07°/s. The camera is about 1.45 s behind the
  data source's bearing, 1.1 s of ramp and 0.35 s of spring; today's linear second is 1.0 s. When
  a turn ends, the map comes up to the road's bearing and does not swing past it. With a lead of
  2 τ the camera would be 1.1 s behind and swing up to 6° past after a sharp turn. The early turn
  stays: the camera still turns hundreds of metres before the bend.
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
the turn (30 m with the Large lane zoom, which also lets that frame be fitted closer; see
[The junction zoom](#the-junction-zoom)). It glides in over about 1.5 s through the on-puck weight
instead of jumping.

### Modes and transitions

- `CameraMode` works as today. A change is reported at once with the mode it goes to; asking for the
  current mode does nothing.
- A transition starts from wherever the camera is. After idle, camera animations still running,
  such as the end of a fling, are cancelled, and the springs start from the map's current camera
  (`MapboxMap.cameraState`), so **Recenter** flies from where the driver left the map.
- While a change of mode is on its way, the state in the debug log is `TRANSITION_TO_FOLLOWING` or
  `TRANSITION_TO_OVERVIEW`, as with the Mapbox styles.
- The offset between the camera and the live target fades out on a smootherstep curve. A moving
  target is chased from the first frame, and a still one is reached without a jolt. So stage 4's
  frequent switches end without a catch-up surge.
- Length: 1.5 s; 2.2 s for more than 3 zoom levels or more than 2 screens of distance; 0.8 s after a
  teleport that leaves the camera more than 60 m from the puck.
- Moves longer than one screen dip the zoom like a fly-to: by log2 of the distance in screens, at
  most 3 levels.
- Overview, also used for the route preview: the data source's overview frame and its tilt, through
  the same ramps and springs.
- While the activity is stopped (phone) or the surface is detached (car), no frames run. The
  navigator's updates still reach the data source during a drive. When the phone's activity
  starts again, the camera starts its mode over from the data source's newest frame, and the first
  frame snaps to the targets, since the map was not on screen: the road may have turned and the
  zoom changed meanwhile. The car builds a new controller for each new surface.
- A map that still shows the whole world (zoom below 3), as one does that was never moved, is not
  flown from either: the camera is put on its target with the next frame. That is the first
  position after launch, and a map created again during a drive. The data source may have no
  frame yet at that moment, because its first one waits for the map's size; the camera is then put
  on the first frame that comes.

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

- `onGuidanceEnded()` resets the tilt schedule and the lane zoom's schedules, clears the data
  source's following overrides (`clearFollowingOverrides`) and sets the limits Large moved during
  the drive back to where a drive starts.
- If the camera still showed the route, it goes back to the car on the browsing map and follows it
  there (see [Following the car before guidance](#following-the-car-before-guidance)). If the
  driver had taken the map, it stays where it is.
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
  does nothing on a fling. The gesture handler calls `requestIdle()` before passing on scroll,
  fling and scale. **Recenter** comes from the map's action strip.
- **One puck for both maps:** `GuidancePuck` and the navigator feed move into the engine. Each fix
  then reaches the phone and car maps once, and `newDrive()` runs when guidance starts. Mapbox's
  `CarLocationProvider` is not used: it keeps the 1000 ms glide.

### Logging

A **camera** line once a second during guidance. `PhoneMap` writes it, in every camera style, and
only while its map view is shown; the `activity` and `screen` lines show when it was not.

| Field | Meaning | Expected |
|---|---|---|
| `style` | the camera style: `MAPBOX_STANDARD`, `MAPBOX_TUNED` or `SMOOTH_FOLLOW` | |
| `state` | what the camera is doing: `FOLLOWING`, `OVERVIEW`, `IDLE`, and `TRANSITION_TO_FOLLOWING` or `TRANSITION_TO_OVERVIEW` on the way there | |
| `zoom`, `pitch`, `bearing` | the map's camera | |
| `laneDp` | how wide a lane of 3.7 m is on the screen at the camera's centre, in dp | about 4 to 6 on a town road; with Large about 28 at a junction and 14 near a lane choice |
| `to` | the following frame the data source asks for: `zoom`, `pitch`, `bearing`, and `m`, the metres from the camera centre to the frame's centre | |
| `puckLagM` | how far the drawn puck trails the latest matched position | below speed × the puck's glide |
| `sinceFixMs` | how long ago that position came | up to about 1000 |
| `camLagM` | how far the camera centre is from the drawn puck | Smooth follow on the road: about 2τ × speed, 0.4 s × speed on the phone; larger in maneuver framing and in the overview |
| `camUpdates`, `camGapMs` | how often the map's camera changed that second, and the longest time between two changes | close to `render.frames` and to one frame while moving |
| `puckUpdates`, `puckGapMs` | the same for the drawn puck | |
| `steps` | the largest change from one camera frame to the next: `m`, `zoom`, `bearing`, `pitch` | small and even; a jump shows as one large step |
| `fixes` | matched positions that second | 1 |
| `keyPoints` | the points the navigator sent with the latest position, which the puck glides through | |
| `padding`, `paddingChanges` | the map's padding in pixels (top, left, bottom, right) and how often it changed; in the first line and whenever it changed. The top includes the Then chip's band | rare, since the top padding only grows; never when the Then chip comes or goes |
| `render` | the render thread that second: `frames`, `fps`, `missedVsync`, `p50Ms`, `p95Ms`, `maxMs` | |
| `renderFps` | the renderer's own rate (`MapView.setOnFpsChangedListener`, called on the render thread); left out while the map draws nothing | at least 55 at 60 Hz |
| `animatorsEnabled` | `ValueAnimator.areAnimatorsEnabled()` | false: the animator scale is 0 and the puck jumps |
| `hz` | the display's refresh rate | |
| `laneDetails` | the switch for lane details on the map | |
| `hdRoads` | what the style said about them when it was last asked, once a second; left out while the style has no such setting | the same as `laneDetails` |
| `hdRoadsReapplied` | how often they had to be set again since the map was made | 0 |
| `bendM`, `bendDeg` | with Large, while the SDK frames a maneuver: how far past the maneuver point the road bends, in metres, and by how many degrees off the way in, negative to the left ([The frame reaches the bend](#the-frame-reaches-the-bend)); left out at other times | 0 at an ordinary turn |
| `extraPts` | how many points of the road to the bend the data source frames besides its own | 0 but where a step begins well before its bend |

`puckLagM`, `sinceFixMs` and `keyPoints` are left out until the guidance has had its first position.

Smooth follow adds its controller's own numbers:

| Field | Meaning | Expected |
|---|---|---|
| `camFps` | controller frames per second | about the display rate while moving; 1 or 2 when parked, because the loop stops |
| `camMaxGapMs` | the longest time between two frames in a row that second; a longer gap counts as 100 | under 25 ms at 60 Hz; spikes mean dropped frames |
| `camWrites` | `setCamera` calls that second | about `camFps` while moving, 0 or 1 while parked |
| `focus`, `focusGoal` | the tilt schedule: how far the view is tilted onto the next maneuver, 0 to 1, and where that is going | |
| `zoomTarget` | the zoom the camera is going to | |
| `bearingError` | target bearing minus camera bearing | within a few degrees |
| `onPuck` | the on-puck weight | 1 on the road, 0 in maneuver framing and overview |
| `laneZoom` | the Lane zoom setting: `DEFAULT` or `LARGE` | |
| `zoomPlan` | what sets the zoom with Large: `ROAD`, `JUNCTION`, `AFTER_JUNCTION` or `LANES`; left out in the overview | `ROAD` on most seconds, and always with Default |
| `laneFocus`, `laneGoal` | the lane choices' schedule: how far the view is in for one, 0 to 1, and where that is going. It runs with Default too, without moving the zoom | 0 on most freeway seconds |
| `laneAheadM` | metres to the nearest lane choice ahead that counts; left out when there is none on the step | |
| `laneChoice` | the lane choice that holds the view: `lanes`, `usable`, `atManeuver`; left out on the road | |
| `centerLeadS` | how many seconds ahead of the drawn car the centre aims. It is logged also while `onPuck` is 0, in the frame of a maneuver, where it does not move the centre | 0 with Default, and with Large up to about zoom 17; closer in, up to about 0.3 |

The two Mapbox styles do not run the controller. They add `frameUpdates`, the frame animations
`NavigationCamera` started that second, and `frameUpdatesCut`, those cancelled before they
finished because the next one came. While the car moves, Mapbox standard starts about two a second
and Mapbox tuned one. They also add `laneZoom` (the setting, which Mapbox standard does not act
on), `laneGoal` and `maxZoom` and `minZoom`. `laneGoal` is the goal of the lane choices' schedule,
as in Smooth follow: 1 from the start of a counted lane choice's window until the view would be
let go again. Mapbox tuned runs the schedule with Default too, where it moves nothing; Mapbox
standard does not run it, so it is always 0 there. `maxZoom` and `minZoom` are the limits the data
source fits its frames within: 16.35 and 10.5, but for Mapbox tuned with Large near a maneuver and
near a lane choice. So `laneGoal` 1 with `minZoom` 10.5 is Mapbox tuned with Default, or with
Large while the SDK frames a maneuver.

Other lines:

- A **camera_state** line on every change of the camera's state, with `state`, `style` and, once a
  change has arrived, `ms`: how long it took. Besides the states above there is `IDLE_FOLLOW`, the
  camera on the car before guidance. For the Mapbox styles a transition that should take 673 ms or
  more and ends within a few ms means animations are off.
- A **camera_settled** line with Smooth follow, after each move between modes that ended (a
  `camera_state` line with `ms`): `state`, `ms`, the time from that line until the springs came to
  rest, and `settled`. The line that says a move ended comes when its path is done, while the
  springs still settle for a second or two; checks that measure the camera can leave out the time
  up to this line. With `settled` false the camera had not come to rest within 5 s, as while it
  turns through a curve, and `zoomGap`, `pitchGap`, `bearingGap` and `paddingGap` say how far it
  still was. The camera is asked ten times a second for those 5 s at most, never on every frame;
  the Mapbox styles have no springs of their own and write no such line.
- A **camera_taken** line when something else takes the camera: `owner`, who started the animator
  (`Maps-Gestures` for a touch, `Maps-Compass` for the compass), `animator`, its kind, and `from`,
  the camera's state. The first drive's log could not say why the camera went idle about 22 s
  before End.
- A **camera_request** line when **Recenter**, **Overview** or **My location** is tapped: `mode`,
  `from` and `guidance`.
- A **settings** line on every change of a setting: `key`, `value` as stored, and `guidance`. A
  change of `camera_style`, `lane_zoom` (`default` or `large`) or `lane_details` (true or false)
  during a drive shows there.
- A **lane_choice** line once for each point with lanes, when it comes as near as the close view
  would start for it, with every camera style and either lane zoom: `kind` (`straight` where the
  route goes straight on, `maneuver` at an exit or a fork), `metersAhead`, `window` (the distance
  at which the close view starts at this speed), `lanes` (not counting `designated` ones),
  `usable`, `leaving`, `designated`, `freeway`, `speed` in m/s (left out while unknown) and, when
  the rule passes the point over, `skipped` with the reason: `NOT_A_FREEWAY`, `TOO_FEW_LANES`,
  `NO_USABLE_LANE`, `NO_LANE_LEAVES` or `TOO_FEW_LEAVING`. The lines without `skipped` are where
  Large goes close; the others show what a looser rule would add.
- A **lane_details** line each time the app sets lane details on the map: `reason` (`setting`,
  `style_loaded` or `reapplied`), `wanted`, `read` (what the style says afterwards) and `error` if
  the style refused.
- A **map_layers** line once per style load: `arrowAboveLine`, true when every layer of the
  maneuver arrow is drawn above the route line's top layer (null while either is missing), and
  `middle`, the ids of the layers in the style's middle slot, where the route line and the arrow
  are, in the order they are drawn. Ids only. See
  [The maneuver arrow close in](#the-maneuver-arrow-close-in).
- The **route_response** and **routes_updated** lines carry, for each step with any, the
  intersections that have lanes (`laneIntersections`): `meters` from the start of the step,
  `lanes`, `usable`, `active`, `designated`, the `classes` of the road that leaves it, and
  `freeway`, whether the road that leads to it counts as one. With these the rule can be tried with
  other numbers after a drive. Every step also has `bearingBefore` and `bearingAfter`, the
  compass directions into and out of its maneuver as the route gives them, and `bendM` and
  `bendDeg`, where its road bends after the maneuver point by the rule of the junction frame (none
  for the departure, or where it does not bend within 150 m). Numbers only. These two lines are put
  together off the main thread and are written a moment after the lines that follow them in the
  code.
- The **progress** line, once a second during guidance, has `card`: `fresh` when the update
  brought the next maneuver, `kept` when it brought none and the card stayed as it was, and
  `pending` when it was of a new route without its first instruction yet, and the card showed that
  route's next maneuver dimmed and without a distance, never one of the route before. An update
  between a seek of a simulated drive and the car's first position at its new place has
  `replaySeek` true, and nothing on the screen acts on it.
- A **replay_seek** line for each release of the replay slider of a simulated drive: `fromS`,
  `toS`, `ok`, `durationS`, `fromM`, and `ms` or `why` (DRIVES.md, "What to read"). The first
  position at the new place reaches the camera as a jump: the camera lets go of what it kept of the
  road it left (`onJump`: the tilt onto a maneuver, the close view for lanes, the last junction's
  zoom). Smooth follow then flies to the car as after a teleport; both Mapbox styles go at once to
  the frame of the new place, computed with the progress of the same update, never with the one of
  the place the car left. The drawn car is put there at once in every camera style.
- An **animations** line: `reason`, `enabled` (`areAnimatorsEnabled()`), `durationScale` (the
  setting `Settings.Global.ANIMATOR_DURATION_SCALE`) and `powerSave`. It is written when the first
  activity starts and at every Start, not at app start: a process learns the real scale only when
  its first activity launches and reports the default 1.0 until then, so a value read in
  `Application.onCreate` says "enabled" even with animations off. On Android 13 and newer a
  duration-scale listener writes the line again with `newScale` when the scale changes. Power
  saving turned on or off is a **power** line. The scale setting shows the developer and
  accessibility switches but not a phone maker's battery-saver override; `areAnimatorsEnabled()`
  shows the effective state.
- Optional Perfetto trace: the controller's frame should take under 0.2 ms; the data source's
  `MapboxNavigationViewportDataSource#evaluateImpl` should run once a second; no
  `CameraAnimations#commitChanges` while following, which proves nothing else writes the camera.

### Hooks for stages 4 and 5

Stage 4, smart camera:

- **Junction zoom: done in stage 1.1** by the Large lane zoom ([Lane zoom](#lane-zoom)). It takes
  the zoom of the SDK's own maneuver frame, fitted closer, so the hook once planned here, pulling
  the centre toward the maneuver point, was not needed: that frame already holds the car and the
  junction. Stage 4 may still use the tilt's curve for a view centred on a complex junction.
  Its automatic following would start where the two schedules start, the tilt's and the lane
  choices'.
- **Keep the early turn.** If stage 4 shortens the framed geometry near turns or overrides the
  bearing (`followingBearingPropertyOverride` bypasses the data source's bearing smoothing), it must
  keep that smoothing or rebuild it. Otherwise the early turn disappears near junctions.
- No tiles load for a closer view: Mapbox's tiles end at zoom 16, and above it the map draws the
  zoom 16 tiles larger, online and offline ([No new tiles](#no-new-tiles)).
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

For the Large lane zoom and lane details:

- **The SDK's maneuver frame at close zoom** is untried on a device. The model in the simulation
  fits it flat; the real one can differ by a few tenths of a zoom level, and a junction wider than
  the view never reaches 28 dp. The log's `laneDp` shows what a drive gets.
- **The fit of a road frame depends on where its search starts**, which is why Large leaves the
  road's limit alone ([The junction zoom](#the-junction-zoom)). That is read from the native map
  library and from models, for Maps SDK 11.34.1. On a drive with Large, `to.zoom` on the open road
  is never above 16.35, and on a winding stretch it does not drop to 10.5 for a few seconds and
  come back. Read the fit again with each SDK upgrade.
- **Little around the junction.** At 28 dp the view is about 55 m wide on a phone. The Then chip
  and the 30 m after the turn carry what comes next. One constant, `junctionLaneDp`.
- **Fast ground near a lane choice.** At 14 dp and 105 km/h the road moves about 110 dp a second.
  If that tires the eyes, lower `freewayLaneDp`.
- **The lane-choice rule is a guess** until a drive: 3 lanes, 2 leaving, 25 s. Lane data may also
  be thin on intersections the route goes straight through; then the rule fires only at exits and
  forks. The `lane_choice` lines show both.
- **The bearing lead of 20°** changes the early turn on curves inside a lane window. Setting
  `laneMaxBearingLead` to 45 brings the SDK's back.
- **The centre lead** makes the camera follow the drawn car's changes of speed more closely when
  close in. If the car's icon shows a hitch, raise `maxPuckLagDp`.
- **Mapbox tuned** takes its zoom back at the step change, not with the tilt, and its limits move
  in steps that only its animations smooth. It is a comparison style.
- **Lane details rest on an order of events** read from the SDK's source: the SDK turns them off
  only at a style load. A later SDK may add another moment; the look once a second sets them again,
  and `hdRoadsReapplied` counts it. Check with each SDK upgrade, as the three behaviours above.
- **The route line under 3D bridges** with lane details on is not checked; nor is the car's icon,
  which may seem to float over a bridge or sit on the road under it.
- **The route line's widths** with lane details on are the SDK's own stops, copied. A new SDK with
  other widths fails a unit test (`RouteLineWidthsTest`) instead of leaving the held line behind.

## Lane zoom

**Settings → Guidance → Lane zoom** has two choices. They are stored under `lane_zoom` as `default`
and `large`; a missing or unknown value reads as Default.

- **Default** is the zoom guidance has had so far, never closer than 16.35. A phone starts with it.
  With Default nothing in this section runs, and every camera style moves exactly as before.
- **Large** is Default on the open road plus two close views: onto the junction before every turn,
  and before a lane choice on a freeway. It comes back out after each. Smooth follow and Mapbox
  tuned carry it. Mapbox standard keeps its own zoom, and Settings says so under the row while that
  style is chosen.

A change applies at once, also during a drive, and the view glides to it. The reasons are in
[DECISIONS.md](DECISIONS.md), 38.

None of this has been on a simulated or a real drive. Every threshold below is a first setting from
reasoning: the drive logs they were to be tuned from have not arrived. Each is one constant in
`CameraTuning` ([Constants of the lane zoom](#constants-of-the-lane-zoom)).

### How wide a lane is

Large is set by how wide a lane is on the screen, not by a zoom number, so it looks the same at
every latitude. Mapbox zoom levels use 512-dp tiles: at zoom z and latitude φ one dp covers
40,075,016.686 m / 512 × cos φ / 2^z (`MapScale` in `:core-nav`). A lane of 3.7 m on the screen, in
dp, at three example latitudes:

| Zoom | 25° | 35° | 45° | What it is |
|---|---|---|---|---|
| 14.0 | 0.9 | 0.9 | 1.1 | a freeway frame, several kilometres ahead |
| 16.35 | 4.4 | 4.8 | 5.6 | the closest Default goes, and Large on the open road |
| 17.5 | 9.7 | 10.7 | 12.4 | |
| 17.9 | 12.8 | 14.1 | 16.4 | |
| 18.9 | 25.5 | 28.2 | 32.7 | |

Large asks for a lane of **28 dp** at a junction, about 4.4 mm: zoom 19.03 at 25°, 18.89 at 35° and
18.68 at 45°. Before a lane choice on a freeway it asks for **14 dp**, about 2.2 mm, one zoom level
less. It never goes closer than zoom 20. In a tilted view the zoom is the scale at the camera's
centre, the car's place near the bottom; lanes farther up the screen are narrower.

### No new tiles

- Mapbox's map tiles end at zoom 16. A closer view draws the zoom 16 tiles larger: shapes stay
  sharp, and labels are placed for the camera's zoom. That is the same online and offline.
- So Large loads no tiles that Default does not, and it works offline wherever the street map at
  zoom 16 is on the phone. A closer view covers less ground, so fewer tiles are on screen.
- Not checked: whether [lane details](#lane-details-on-the-map) have map data of their own, and
  whether that is among what gets saved for offline use.

### The junction zoom

It runs for the maneuvers the tilt goes down for
([The tilt before a turn](#the-tilt-before-a-turn)): turns, ends of roads, roundabouts, U-turns
and the arrival.

- **What Large changes on the data source** (Smooth follow, `LaneZoomFrames`): the frame of a
  maneuver may be fitted as close as zoom 20 instead of 16.35, and it holds 30 m of road after the
  turn instead of 100 m. The SDK still fits that frame, flat and within the padding, around the
  car, the rest of the step and any maneuver that follows within 150 m: a next step of 150 m or
  less it frames whole, with 30 m of the step after it. Where the road bends well after the
  maneuver point, the frame also holds the road to 30 m past the bend
  ([The frame reaches the bend](#the-frame-reaches-the-bend)). So the car and the junction are
  both on screen all the way, and the frame gets closer as the car does.
- **The upper limit is raised only for that frame.** With every navigator update the camera gives
  the data source the progress, sets the limit, and has the frame computed
  (`LaneZoomFrames.beforeSmoothEvaluate`): zoom 20 while the SDK frames a maneuver, by its own
  rule within 180 m of it, and 16.35 for every other frame. With Default the limit is 16.35 on
  every update and nothing is written.
- **The camera takes the zoom of that frame**, up to the zoom at which a lane is 28 dp wide
  (`ZoomPlanner`). It moves there over the 1.1 s of a sample and never faster than 1 zoom level a
  second.
- **The open road stays at 16.35, and its frames are fitted exactly as with Default.** The SDK
  fits a frame of the road to the road ahead of the car, the rest of the step or seven times the
  mean distance between intersections, not to the junction, so its zoom says nothing about the
  junction. And it fits it with a search that starts at the upper limit, and where the search ends
  depends on where it starts:
  - At zoom 20 the tilted camera stands only about 80 to 115 m behind the car. The fit is given
    the box around the car and the framed road. A corner of that box that lies farther back, as on
    an S-shaped stretch whose far end lies to the other side of the car, or with points behind a
    car that heads away from the route, has no place on the screen. The search takes it for an
    outermost point and runs out until the SDK stops it at its lowest zoom, 10.5.
  - Started at 16.35, where the camera stands a kilometre or more behind the car, the search
    passes over the same corner and ends at the zoom the road needs, 13.6 to 14.4 in the cases
    looked at.
  - In a model about 1 in 100 random curved road frames went wrong from zoom 20. On a drive that
    would be the open-road view drifting out to a map some 20 km wide, at up to one zoom level a
    second, and coming back. The cap below cannot catch that direction.
  - This comes from reading the native map library (11.34.1) and from two models of its fit. It
    was not seen on a device.

  So a frame of the road never gets the raised limit. The view goes close from where the SDK
  starts to frame the maneuver, 180 m before it, when the tilt is already down: first the tilt,
  then the zoom. The first design blended the zoom in while the tilt went down; that would have
  followed the road frame's zoom.
- **A frame with the car on its usual place counts no closer than 16.35** in the camera as well.
  One such frame can still come with the raised limit: when the data source loses its route or
  its progress while it frames a maneuver, at a new route or at the end of guidance, it frames
  the car alone until the next progress, and its zoom is then the limit itself, with no fit.
- **After the maneuver** the frame shows the road beyond it. The camera keeps the zoom the
  junction had for as long as the view is held over it, and lets it go along the same eased curve
  as the tilt comes back up (`ManeuverFocus.afterManeuver`). Once the tilt is back up the
  junction is forgotten. That is checked with every navigator update, also while the camera does
  not follow the car: in the overview, with the map in the driver's hands, or with the app in the
  background. A camera that comes back on the approach to a later turn starts from the road's
  zoom and that turn's frame; one that comes back while the view is still held over the same
  junction finds its zoom kept.
- **Close turns** keep the view down and close, as they keep the tilt down. Turns within 150 m
  of each other the SDK frames together, so the zoom for both is a little farther out. A turn a
  little farther on, near enough that the tilt stays down for it but not yet framed, leaves a
  stretch where the frame is of the open road again: there the camera keeps the zoom the first
  junction had until the SDK frames the second, and then eases to that frame. It does not go out
  to the road's zoom and back in. A little farther apart still, about 250 to 300 m at town
  speeds, the tilt has begun to come back up when the next turn takes the view and turns it down
  again. The zoom that was let go by then is not taken up again: it waits where it is until the
  SDK frames the next turn, and goes in once, with that frame.

#### The frame reaches the bend

A route's step begins at its maneuver point, which is where the card counts down to, and that is
not always where the road visibly turns. At a turn bay, or a split that the map has as a road of
its own, the step of a left turn can begin 50 m or more before the bend. The SDK's frame of the
maneuver then holds only 30 m past the maneuver point, and the card covers about 22 m more: a
bend 45 to 65 m on lay under the card, a farther one above the screen, and after the point the
view turned up to 45 degrees toward a bend that was still ahead, close in. So, with Large:

- **The road to the bend joins the frame** (`TurnBend`, `JunctionFrame`, `JunctionPoints`). The
  bend is the first point of the next step that heads 20 degrees or more off the way in, or else,
  for a road that only veers, the first place more than 4 m (about one lane) to the side of the
  way in, looked for within 150 m of the maneuver point. A road that leaves the maneuver point at
  a slight angle and runs on along that line does not count as bending, however far it gets from
  the way in: the veer counts only where the road also gets 4 m off the line of its own first
  15 m. While the SDK frames the maneuver, the
  road from the maneuver point to 30 m past the bend goes to the data source as more points to
  frame, ordered back toward the maneuver point. The SDK takes the frame's bearing from the car to
  the last point it frames, and that is then the maneuver point: the bearing stays on the road
  the car is on. The frame comes out as far as the bend needs, and no farther.
- **Only where it is needed.** A bend nearer than 15 m to the maneuver point is in the frame
  already, as at an ordinary turn or a U-turn, and so is all of a next step of 150 m or less: no
  points then. The points are worked out once for each step, from the route's own shape, and the
  data source gets none in any frame of the road, at a new route or after guidance. With Default,
  and in Mapbox standard, none of this runs.
- **The view turns less while it looks down onto a turn.** In Smooth follow with Large the view
  turns at most 20 degrees toward the road ahead while its focus is on a maneuver, as it does near
  a lane choice. After the point the bend then stays in view while the tilt is still down, and
  the map turns no more than that until the tilt is back up.

In the replay with the built camera (a phone screen with the card and the bottom panel; made-up
roads at latitude 35; the next step longer than 3 km unless named; at 25 mph, at the update
nearest 20 ft, half a second later):

| Road after the maneuver point | Before: the bend | Now: lane width, the bend, the first 30 m past it |
|---|---|---|
| Straight 62 m, then 26 degrees left | under the card | 13.2 dp, clear, all 30 m |
| The same veer 45 m on | under the card | 15.8 dp, clear, all 30 m |
| The same veer 90 m on | above the screen | 10.4 dp, clear, all 30 m |
| Straight 62 m, then 15 degrees left (found 4 m to the side, 77 m on) | under the card | 11.3 dp, clear, all 30 m |
| A left curve of radius 160 m (20 degrees 56 m on) | above the screen | 14.2 dp, clear, 26 of 30 m |
| A right angle 60 m on | under the card | 18.4 dp, clear, all 30 m |
| A right angle 60 m on, next step only 120 m | clear: the SDK frames the step whole | unchanged |
| A turn at the maneuver point; a U-turn 13 m across | clear | unchanged |

At 35 mph every bend is clear with 29 to 31 of its 30 m, at lanes of 9.4 to 15.6 dp. That is the
cost: where a step begins well before its bend, the junction comes out to lanes of 9 to 18 dp
instead of 27 to 28. After the point (a right angle 60 m on, 30 mph) the bend stays on the clear
map all the way, where before it went off the right edge from 26 to 38 m past the point, and the
bearing turns no more than 20 degrees until the tilt is back up. A plain turn moves as before. The
replay models the data source's fit without its margins; real frames can differ by a few tenths
of a zoom level.

Simulated with the compiled camera at 60 frames a second, against a model of the data source (the
car on its focal point on the road; from 180 m a flat fit of the rest of the step and the road
after it into the padded height). The road is made up and lies on the equator, where 28 dp is zoom
19.18 and 16.35 shows a lane 3.9 dp wide. A turn approached at 50 km/h, braking over the last
120 m to 20 km/h:

| Where | Tilt | Zoom | Lane on screen |
|---|---|---|---|
| 240 m before | 45° | 16.35 | 3.9 dp |
| 183 m before | 3° | 16.35 | 3.9 dp |
| 141 m before | 0° | 17.37 | 8.0 dp |
| 101 m before | 0° | 17.99 | 12.3 dp |
| 67 m before | 0° | 18.40 | 16.4 dp |
| 31 m before | 0° | 19.03 | 25.2 dp |
| 17 m before, to 33 m after | 0° | 19.18 | 28 dp |
| 43 m after | 5° | 19.09 | 26.3 dp |
| 56 m after | 30° | 18.44 | 16.8 dp |
| 84 m after | 45° | 16.67 | 4.9 dp |
| 111 m after | 45° | 16.35 | 3.9 dp |

In the same model two turns 100 m apart stay flat with a lane no narrower than 13.5 dp between
them, two turns 200 m apart with 10.6 dp, a roundabout with its way out 45 m on stays at 22.6 dp
or wider, and the arrival ends at 28 dp with the car standing. Two turns 300 m apart get the
road's view back in between. Turns 160 to 280 m apart were run in steps: between them the zoom
goes out once and in once in every case, and with 260 m it waits at a lane of about 6 dp until the
second turn is framed. The real frames of the SDK can differ from the model by a few tenths of a
zoom level.

### The lane-choice zoom on freeways

On a freeway the next maneuver can be many kilometres away while lanes leave the route in between:
a split, or lanes that turn into an exit. Large goes close for those too, also where the route
goes straight on.

**Where the lanes come from.** The route carries lanes for its intersections, also for those it
goes straight through, with a mark on each lane the route can be followed from. `LaneChoices` in
the engine reads them once for each step, with the distance of each along the step, and adds the
next maneuver's own lanes where that maneuver is one the view stays up for (an exit, a fork, a
merge, an on ramp or a continue). No request is made for this and no other service is asked: the
data is in every route. Mapbox's Electronic Horizon also knows lane counts; it is a beta whose
pricing may change and is not used.

**Which points count** (`LaneChoice.skipReason`):

- the road that leads to the point is a freeway: the class `motorway`, or the road class motorway
  or trunk where the route names one;
- at least **3 lanes** reach it, not counting lanes kept for buses, car pools, taxis and the like;
- at least one lane can be used and at least one cannot;
- at an exit or a fork that is the next maneuver, that is enough;
- where the route goes straight on, at least **2 lanes** leave it. One lane that turns into an
  exit is what nearly every exit looks like, and the view would go close at each of them.

A wide freeway where every lane goes on, a single exit lane, and every road that is no freeway do
not count: lane choices in town come with a turn, which has the junction zoom. So on most freeway
kilometres the long view ahead stays, as the owner asked.

Both thresholds are a first setting. Every point with lanes that comes as near as the close view
would start is written to the debug log once, counted or passed over, with its lane counts and the
reason (the `lane_choice` line under [Logging](#logging)). A drive then shows how often the rule
fires and how often a looser one would.

**When** (`LaneFocus`):

- In over 6 s, from 25 s of driving before the point, no nearer than 400 m and no farther than
  1,200 m: 556 m at 80 km/h, 729 m at 105 km/h, 833 m at 120 km/h. Once a point holds the view it
  keeps it, also when the car brakes and the distance shrinks.
- Held past the point for at least 30 m and 2 s, then out over 6 s.
- A next point no more than 10 s of driving from its own start keeps the view in: no zoom out and
  in again between a split and the next exit.
- A reroute starts the route's metres again; the point that held the view is then held by its time
  only.
- Off the route there are no lane choices and the route's metres stand still. A point that holds
  the view when the car leaves the route is then held by its 2 s only as well, and the view eases
  back out to the road's. A new route within a second or two still finds the view close. Back on
  the same route, a point that is still ahead takes the view again.

**The view.** The zoom goes to a lane of 14 dp and the tilt stays at 45°: straight down, that zoom
would show only a few seconds of road at freeway speed. While a point holds the view, the bearing
may lead the car's heading toward the road ahead by at most 20° instead of the SDK's 45°, so the
lanes at the car stay close to upright on a curving freeway. Elsewhere the early turn is unchanged.

**Where a turn follows closely**, as on a short exit ramp that ends in one, the lane choice can
still hold the view when the SDK starts to frame the turn. That frame is centred on its own point,
not on the car, and closer than its fit the car would slide under the bottom panel and the
junction under the card. So in the frame of a maneuver the lane zoom goes no closer than the
frame: the view eases out to the frame, about half a zoom level in the simulation, and goes in
again with it as the junction zoom takes over.

In the simulation at 105 km/h (window 730 m, zoom 18.18 for 14 dp on the equator, the road's frame
at 14.2): the zoom starts in 675 m before a split where 2 of 5 lanes leave, a lane is 14 dp from
483 m before it, the view stays in to a second split 964 m on, starts out 114 m past that and is
back about 300 m past it. A point where one of four lanes leaves does not move the zoom. The 4
zoom levels take about 7 s, not 6, because of the limit of 1 level a second.

**The maneuver card shows the same point.** From where the close view would start until the car
has passed it, the card's lane row shows the lanes of a straight-on lane choice with its distance,
in every camera style and with either lane zoom (`RouteProgressSnapshot.laneChoiceAhead`, filled
by the engine from the same `LaneChoices`).

**Limits.** Lane choices are read for the current step only, so one that follows right after a
maneuver gets a shorter start. What kind the next maneuver is comes from the route's banner; while
the navigator has none, an exit or a fork is not taken as a lane choice.

### The centre at close zoom

The centre trails the drawn car by 2τ × speed: 5.4 m at 50 km/h. At zoom 16.35 that is about 7 dp.
Close in the same metres are many more dp, and the car would ride visibly above its usual place.

So with Large, while it follows the car, the centre aims ahead of the car along the car's own
motion by `max(0, 2τ − 12 dp × metres per dp ÷ speed)` seconds (`centerLeadSeconds`): it then
trails the car by no more than 12 dp. The car's motion is read from the drawn puck, from one frame
to the next and smoothed over 0.3 s, and the lead is worked out for the closer of the camera's zoom
and the zoom it is going in to. At 16.35 and town speeds the lead is 0. In the frame of a maneuver
the centre is the frame's point, not the car, and the lead does not act. With Default there is no
lead at all. In the simulation the centre is at most 11.1 dp behind the car in a lane window at
105 km/h, with a lead of 0.29 s.

### Mapbox tuned and Mapbox standard

**Mapbox tuned** has no camera of its own that could hold the road at 16.35, so with Large the
limits of the data source move with the drive. Before the frame of each navigator update is
computed (`LaneZoomFrames.beforeTunedEvaluate`):

- while the SDK frames a maneuver, by its own rule within 180 m of it, the frame may be fitted
  until a lane is 28 dp wide; elsewhere the limit is 16.35;
- while a lane choice holds the view (the same `LaneFocus`), the frame of the road is no farther
  out than a lane of 14 dp, and the bearing leads by at most 20°. The frame of a maneuver keeps
  the SDK's lower limit also then: the SDK applies the lower limit to that frame as well, and it
  is fitted around the car and the junction, so a lower limit above its fit would push both off
  the map;
- the frame of a maneuver holds 30 m after the turn, as for Smooth follow.

Both styles go by the same rule for when the SDK frames a maneuver, and neither raises the upper
limit for a frame of the road outside a lane window. Inside one, tuned raises both limits to the
same zoom, so the frame has that zoom whatever the fit of the road ends at.

The camera's own animations (zoom 2.5 s, restarted by each update) turn these steps into glides.
Its zoom back out after a turn starts when the step changes, not with the tilt. With Default the
limits are the SDK's on every update. In the model above the limit is zoom 19.18 from 175 m before
the turn and the frame reaches 28 dp from 32 m before it.

**Mapbox standard** keeps the SDK's values whatever the setting: 16.35, 10.5, 45° and 100 m.

### Changing the setting or the style during a drive

- `PhoneMap.setLaneZoom` sets up the data source for the camera style and the new mode
  (`LaneZoomFrames.apply`), tells the camera, and has a frame computed.
- For Smooth follow that set-up also sets the upper limit, for the progress the data source
  frames by: a change to Large in the frame of a maneuver raises it at once, and anywhere else it
  is 16.35 until the camera moves it with a later progress.
- The data source works out the road after each maneuver when it is given a route, and ignores a
  route it already has. So when the 30 m or 100 m change, the route is taken out and given back
  together with the latest progress, within one call on the main thread. No frame is computed in
  between, so none shows a data source without its route.
- Smooth follow glides to the new frame: the sample ramps in over 1.1 s, the zoom target moves at
  most 1 level a second, and the spring smooths the rest. Both schedules, the tilt's and the lane
  choices', run with either mode, so a change to Large near a maneuver or a lane choice picks up
  where they are. After a maneuver a change to Large does not zoom in late.
- Mapbox tuned gets its limits with the next navigator update, and its animations glide there.
- A change of camera style runs the same set-up for the new style, after the old camera has taken
  back the limits it moved and before the new one is made. Mapbox standard gets the SDK's values
  and the 100 m back.

In the simulation a change to Large 100 m before a turn, back to Default 40 m before it, and both
inside a lane window, stayed within 1 zoom level a second, and the centre never moved more than
3.2 dp from one frame to the next.

### Lane details on the map

**Settings → Guidance → Lane details on the map** is a switch, stored under `lane_details` and off
unless turned on. It turns on Mapbox Standard's lane details (the style setting `showHdRoads`):
lane lines, the arrows painted in each lane, stop lines, crosswalks and the road surface, where
Mapbox has them, which is on major highways and in some cities. They are drawn from about zoom
16, so with Default they are small and with Large they are large at every close view. Outside
those areas the map is as before. The reasons are in [DECISIONS.md](DECISIONS.md), 39.

- **The navigation SDK turns them off.** Every viewport data source looks at the setting when the
  style is first ready and on every style load, and sets it to false if it is true; at no other
  time. A switch in the SDK stops that, but it is for Mapbox's own modules and is not used.
- **So the app sets it after the style load, never within it** (`LaneDetails`). From the callback
  of the style load it only leaves a message for the main thread. By the time that message runs,
  everything that listens for the style load has run and found nothing to turn off. Set within the
  callback, the SDK would turn the lane details off again and the map would show them fade out.
- **It looks again** once a second during guidance, with the camera line, and once a second after
  a style load, and makes the setting again if something changed it. The camera line counts that
  (`hdRoadsReapplied`): the order above is read from the SDK's source, not promised by it.
- **The colour** of the road surface under lane details is set with the night palette
  (`colorHdRoads`, the grey of the trunk roads).
- **The route line stays thin close in** while the switch is on (`RouteLineWidths`). The SDK's line
  grows with the zoom: at the junction zoom its casing is about as wide as a lane, and over lane
  markings it would hide a lane and look like a claim about which lane to take. With the switch on,
  the line and its casing keep the widths they have at zoom 16.5 for every closer zoom, about 10
  and 15.5 dp, and the routes on the map are drawn again. Up to 16.5 nothing changes.
- **Not checked on a device**, which is why the switch ships off: whether the route line and the
  maneuver arrow, which have no height, hide under a bridge that lane details draw in 3D (the
  Settings text says they can); the frame rate with lane details on; and whether they draw offline.

### Why the car is not drawn in a lane

The owner asked to see the car in the lane it should be in. Nothing the app has can say which lane
the car is in:

- **Phone GPS is not lane-accurate.** A phone is typically within about 5 m under open sky and
  worse between buildings; a lane is 3.5 to 3.7 m wide.
- **The navigator's position has no lane.** It lies on the line of the road in Mapbox's road
  data, which on a divided freeway is the middle of the carriageway.
- **The public SDK has no lane geometry.** The route says how many lanes reach an intersection and
  which can be used, not where each lane is on the map. Lane details are a drawing with nothing
  that ties a painted lane to a lane number.

So nothing moves the car's icon sideways, with or without lane details. An icon drawn in the lane
the route needs would show the driver in the right lane whether they are in it or not, and a
driver who sees that does not change lanes. With lane details on, the icon will often sit on a
middle lane or on a lane line; that is its place on the road, not a lane, and Settings says so.

What the owner gets in its place:

1. **Large:** the junction fills the map with a lane about 4.4 mm wide, and before a lane choice
   on a freeway a lane is about 2.2 mm wide.
2. **Lane details on the map** where Mapbox has them, with the arrows painted in each lane.
3. **The lane arrows in the card**, the lanes to use lit, now also at lane choices where the route
   goes straight on.

### The maneuver arrow close in

The arrow of the next maneuver is the navigation SDK's: platinum with a graphite casing, along the
last 30 m of the step and the first 30 m of the next one, with its head at the end. Close in, as
Large goes, it is what shows the turn's path.

- **It lies on top of the route line.** Both are in the style's middle slot, where layers are drawn
  in the order they were made, and the arrow puts its layers above the route line's top layer only
  if that layer is there when the arrow makes them. Left to the first renders of the browsing map,
  the arrow's layers came a moment before the route line's, the amber line covered the arrow, and
  only the head showed at its sides. So each style load makes the route line's layers first and
  the arrow's right after, before anything is drawn. Both stay in the style for as
  long as the map lives, and a style that loads again keeps them in their order (Mapbox keeps
  persistent layers at their relative position). The `map_layers` line says the order once per
  style load ([Logging](#logging)).
- **A U-turn across a median is one curve** (`UTurnArrow`). On a divided road the map has one road
  for each direction and a short road across the median opening, so a U-turn goes up the near
  side, across and back down the far side: two corners a few meters apart, and close in a box.
  Where the route turns back through two corners 4 to 40 m apart, both the same way round, the
  arrow rounds them into one arc of half the width, at most 8 m. Across an opening of 13 m that is
  an arc of 6.5 m: each corner of the amber line lies about 2.7 m outside it, about 20 dp with
  Large, so the line's box still shows behind the white U. The route line keeps the road's shape:
  the SDK has no way to reshape it, and the line is the route. The arrow is worked out once for
  the step, from the route's own shape, and kept in memory only. Where the far corner is more than
  20 m from the maneuver point, the arrow goes on 10 m past it, so its head points back down the
  road. A U-turn at one point, as on an undivided road, and every other maneuver keep the SDK's
  arrow.
- **Other routes are not drawn where they share the route.** During guidance the route line gets
  the navigator's account of where each other route leaves the route, read on the phone without a
  request, so the grey of another route no longer runs over the road behind the car, or under the
  route up to where it turns off. The preview draws every route whole, as it has no such account.
- **The streets stand out from the ground**, about two to one in the night palette
  ([DECISIONS.md](DECISIONS.md), 43). Close in a street is a plain band, since the map's road data
  ends at zoom 16, and a band that stands out from the ground reads as a road: the median of a
  divided road as a median, not as a block of houses.

### Constants of the lane zoom

All in `CameraTuning`, the same for the phone and the car.

| Constant | Value |
|---|---|
| Closest zoom with Default, and on the open road with Large (`defaultMaxZoom`) | 16.35 |
| Closest zoom Large ever goes (`maxZoom`) | 20 |
| Lane width at a junction (`junctionLaneDp`) | 28 dp |
| Lane width near a lane choice (`freewayLaneDp`) | 14 dp |
| Road kept after a maneuver in its frame (`largeMetersAfterManeuver`) | 30 m (the SDK: 100 m) |
| Lanes a lane choice takes, and lanes that must leave where the route goes straight on (`laneChoiceMinLanes`, `laneChoiceMinLeaving`) | 3 and 2 |
| Start of the close view before a lane choice (`laneLeadSeconds`, `laneMinWindowMeters`, `laneMaxWindowMeters`) | 25 s of driving, within 400 to 1,200 m |
| In and out (`laneInSeconds`, `laneOutSeconds`) | 6 s each |
| Held after a lane choice (`laneHoldMeters`, `laneHoldSeconds`) | 30 m and 2 s |
| A next lane choice this near keeps the view in (`laneCoalesceSeconds`) | 10 s of driving from its own start |
| Bearing lead near a lane choice (`laneMaxBearingLead`) | 20° (the SDK: 45°) |
| Bearing lead while the view looks down onto a turn, Smooth follow (`junctionMaxBearingLead`) | 20° (the SDK: 45°) |
| Where the road bends after a maneuver (`bendDegrees`, `bendSideMeters`, `bendSearchMeters`) | 20° off the way in, else 4 m to its side and to the side of the road's own first 15 m, within 150 m |
| A bend nearer than this is in the frame already, and the road's first stretch that gives the line it leaves on (`bendMinMeters`) | 15 m |
| The centre trails the car by no more than (`maxPuckLagDp`) | 12 dp |

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

Steps 1 to 4 are done, and so is the Lane zoom setting with lane details on top of them.

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

`CameraMotionTest` in `:core-nav` (JUnit 6), 27 tests. Among them:

- The spring is exact for any frame time and, from rest, never passes its target. After one time
  constant it has gone 1 − 2/e of the way, and a snapped spring is at rest.
- Ramp, spring and lead hold a steady 5°/s curve within 4.8 to 5.2°/s, also with fix intervals
  between 0.977 and 1.016 s. The spring trails the ramp by one time constant there.
- At the end of a turn, and of a change of zoom, the spring comes up to the last sample without
  passing it or turning back.
- A ramp ends exactly on its sample, and one that is given the same sample every second comes to
  rest within a tolerance.
- At 50 km/h the tilt is below 3° at 180 m, 0° at the junction and back at 45° after it, and peaks
  below 30°/s.
- Braking never tilts the view up before the turn.
- A car that stands, or whose speed is not known yet, gets the window of 3 m/s.
- Excluded maneuver types keep 45°, also in the step after them.
- After a turn the view stays down until the car is both 25 m and 2 s past it.
- A second turn 120 m later keeps the view down.
- A reroute that reuses the step numbers is still a new step.
- Guidance that ends halfway down turns the tilt back from where it is, and leaves nothing behind
  for the next drive.

Beside it: `OncePerUpdateTest` (6 tests: which message of an update computes the frame),
`JumpFrameTest` (4: after a seek, the frame of the new place comes with its progress),
`IdleFollowTest` (13: when the camera follows the car before guidance, and which new position is a
jump) and `GuidancePaddingTest` (7: the top inset that only grows).

For the lane zoom, `LaneZoomTest` in `:core-nav` (46 tests, on example latitudes and made-up
routes). Among them:

- Zoom and lane width are each other's inverse, with 18.89 for 28 dp and 17.89 for 14 dp at 35°.
- Only freeway points with at least 3 lanes and lanes that leave count; at the maneuver one
  leaving lane is enough, straight on it takes two; each reason for passing a point over.
- The start of the close view grows with the speed between 400 and 1,200 m.
- A lane choice 1.5 km ahead at 105 km/h: in from 725 m, fully in 6 s later, held at least 2 s and
  30 m past it, out over 6 s. Braking does not let the view out before the point.
- Two lane choices 900 m apart keep the view in, and the second has a hold of its own, by its own
  place and time; 2.5 km apart let the view out between them. Of two inside the window the nearer
  one holds the view, and a next one keeps the view in up to exactly 10 s of driving from its own
  window.
- A reroute during the hold does not keep the view in, and a point at a distance that is no number
  never holds it. Off the route a point the car never reaches is let go after its 2 s, wherever
  the route's metres stand, and back on the route it takes the view again.
- Default returns the frame's zoom. Large on the road is Default, and a frame of the open road
  counts no closer than that; it takes the junction frame's zoom up to 28 dp, lets it go along the
  tilt's curve after the turn, keeps it between two junctions the view stays down for, does not
  take up again what it let go, forgets it when told that the tilt is back up, and goes to 14 dp
  near a lane choice. In the frame of a maneuver a lane choice goes no closer than the frame.
- The centre leads only where it would trail by more than 12 dp, and then holds 12 dp.

In `:engine-mapbox`, on the JVM with the Directions data classes and made-up coordinates:
`LaneChoicesTest` (35 tests: the points ahead with their distances and lane counts, designated
lanes, the maneuver as a lane choice, the card's lanes with every usable lane lit, a step's lanes
read once and none off the route, and each point reported to the log once),
`LaneDetailsTest` (12: nothing is set within the callback of a style load, the setting is made
after it and made again when something changed it, and nothing after the map is gone),
`RouteLineWidthsTest` (9: the held widths, and that the copied stops are the SDK's own) and
`LaneZoomFramesTest` (11: the limits Mapbox tuned gives the data source with Default and with
Large, on the road, in the frame of a maneuver and near a lane choice; the upper limit Smooth
follow gives it, raised only with Large in the frame of a maneuver; and the rule for when a
maneuver is framed, with the SDK's own options).

The cameras themselves touch Android and Mapbox types and have no JVM tests. While it was written,
`SmoothNavigationCamera` was compiled against stand-ins for those types and run frame by frame
through a made-up drive: the browsing map, the preview, Start, a turn, a red light, a drag,
Recenter, the background and back (also after the road turned meanwhile), a teleport, the overview
and the end. For the lane zoom the same was done with a model of the data source: a turn, turns
100 to 300 m apart, a roundabout, the arrival and a freeway with lane choices in Large, a change
of the setting and of the style on the approach, a reroute and the background inside a lane
window. After a review these were added: the car off the route inside a lane window with no new
route for minutes, an exit ramp of 200 to 260 m that ends in a turn and a lane choice 100 m
before a turn (at town speed and in a queue), turns 160 to 280 m apart in steps, and a gesture,
the overview and the background over one junction with the return 183 to 400 m before a later
turn. After a second review: every path that has the data source compute a frame, in the order
of two drives (the preview, Start, the road, a turn, seconds without a progress, a padding change,
a change of the setting and of the style, a new route, the arrival and the end of guidance), with
the upper limit checked at each, and a road whose fit depends on where it starts. With Default,
every frame of the older runs came out the same as before the lane zoom was built, and the same
with lane choices fed as without. Those checks are not part of the build; the simulated drive
below is the first real one. What `LaneZoomFrames` does to the data source itself, the upper
limit moved with each progress and the route taken out and given back when the road kept after a
maneuver changes, has only these runs behind it: no JVM test reaches the data source.

### Build

CI runs the debug build, lint and the unit tests on every push. The car observer of stage 2 is
not in the build yet; its draft was compiled against maps-androidauto 11.34.1 and car-app
1.8.0-rc01.

### Simulated drive

In **Settings → Debug → Simulate drive**, pick a route with a long gentle curve, ordinary turns, two
turns less than 150 m apart, a fork or off-ramp, a roundabout and the arrival. Drive it once in
each camera style; the points below are for Smooth follow. The replay slider over the guidance bar
takes the car back to before a turn to watch it again (DRIVES.md, "Checking a turn at home").

Watch for:

- The map moves on every frame, with no once-a-second steps.
- The car glides a few dp ahead of its usual place near the bottom and settles back when it stops.
- On the gentle curve the view starts turning before the curve, at an even rate.
- Before a turn the tilt goes down over about 3.5 s and is flat about 180 m before the turn. It is
  0° at the junction and rises over about 3 s, starting 20 to 60 m after it.
- No tilt for forks, merges or ramps.
- The two close turns stay flat throughout.
- At 180 m the junction framing glides in over about 1.5 s, with no jump.
- Before the two close turns the Then chip slides out from under the maneuver card. The map does
  not move when it comes or goes, and the camera line shows no new `padding`.

With **Lane zoom** on **Large**, in Smooth follow, on a route that also has a freeway with an
exit and, if the data has one, a split:

- Before each turn the view first goes flat and then close: from about 180 m the junction grows
  until it fills the map's width, and the car and the junction both stay on screen all the way.
  After the turn the zoom eases back out as the tilt comes up.
- The two close turns stay flat and close, with at most a small step out between them. The
  roundabout fits; the arrival ends close on the destination.
- Between maneuvers the view is the one Default has, also on a winding stretch: `to.zoom` in the
  camera line is never above 16.35 on the open road, and it does not drop to 10.5 for a few
  seconds and come back. On the freeway it stays far ahead, except about 25 s before the exit (and
  a split), where it goes close with the tilt unchanged and comes back after.
- The camera line: `laneDp` about 28 at junctions and 14 in lane windows, `zoomPlan` `ROAD` on
  most seconds, and no `steps.zoom` above about 0.02 outside a change of mode. `centerLeadS` is 0
  on the road between turns and on the open freeway, and above 0 only while the view is closer
  than about zoom 17: up to about 0.25 around a town turn, from about 140 m before it to about
  60 m after it, and about 0.3 in a lane window at freeway speed. The `lane_choice` lines: one for
  each point with lanes, with `skipped` on those the rule passed over.
- Lane zoom to Default and back during the drive, near a turn and away from one: a glide each
  time, never a cut.
- Mapbox tuned with Large: close at turns too, with its zoom coming back at the step change.
  Mapbox standard: its usual zoom, and `maxZoom` 16.35 in its camera line.
- With **Lane details on the map** on, in an area that has them: lane markings appear without a
  fade out and in at the start, `hdRoads` is true in every camera line and `hdRoadsReapplied`
  stays 0, the route line is thinner close in with the markings visible beside it, and the switch
  fades them out and in during the drive. Look at a bridge: is the route line on it, and where is
  the car's icon?

Before Start, in each style:

- With the app open and no route, the camera stays on the car as it moves.
- A pan, a pinch or a rotation leaves the map where the driver put it; **My location** brings the
  camera back to the car and it follows again.
- The search bar, its suggestions and the place card do not move the car on the screen.
- The app to the background and back after driving on: the car and the camera are on the new
  position at once, and nothing slides across the map.
- From there, a preview and Start, also after driving on with the app open: the flight into
  following goes straight to the car, with no detour to where the car was earlier.

Try:

- Pan, pinch, rotate, tilt, double-tap and the compass: each switches to idle at once, and
  **Recenter** appears.
- Changing the camera style in Settings during the drive: the new camera takes over in one glide.
- **Recenter**: one smooth flight back.
- **Overview** on and off while moving: the camera tracks the moving target, with no surge at the
  end.
- The preview, then Start.
- The app to the background and back, also across a turn: the first frame shows the road as it is
  now, and the map does not swing round.
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

The owner rates the overall smoothness and four things that each have one knob in `CameraTuning`:

| What | Knob |
|---|---|
| How far the camera trails the car | `centerTau`, 0.15 to 0.3 s |
| Whether the map turns late in curves | `rampLead`, 1 to 2; above 1 the map swings a few degrees past the end of a sharp turn |
| When the tilt goes down | `descentSeconds` or `maneuverFramingMeters` |
| When it comes back up | `holdAfterManeuverMeters` and `holdAfterManeuverSeconds` |

With Large, and Lane details on where the area has them, the owner also notes:

| What | Knob |
|---|---|
| Whether the lanes are large enough at turns, or the view too close | `junctionLaneDp`, 24 to 32 dp |
| Whether exits and splits go close early enough | `laneLeadSeconds` and the two window distances |
| An exit where it went close and should not, or did not and should | `laneChoiceMinLanes`, `laneChoiceMinLeaving`, with the `lane_choice` lines |
| Whether the ground moves too fast close in on a freeway | `freewayLaneDp`, 12 to 14 dp |
| Whether the car rides above its usual place close in | `maxPuckLagDp` |

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
- With Large, `laneDp` reaches at least 24 at every counted maneuver whose junction is smaller
  than the view, and at least 12 at every lane choice that counts.
- With Large, the zoom never changes faster than 1 level a second outside a change of mode.
- On a freeway, seconds with `zoomPlan` `ROAD` outnumber `LANES` by far: the long view stays the
  rule.
- With Lane details on, `hdRoads` equals `laneDetails` in every camera line.

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
   the lanes to use stay in the maneuver card. Built as [Lane zoom](#lane-zoom); Default is what a
   phone starts with, and Large is one tap away in Settings.
3. **Freeways.** The long view ahead stays as it is: no minimum zoom of 14.
