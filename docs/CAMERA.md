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
whose Large mode goes close at junctions, and with [Lanes on the map](#lanes-on-the-map): Mapbox's
lane details, or lanes the app draws itself.

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
| `RunUpLatch`, `TurnAhead` | `:core-nav`, `nav/camera/RunUp.kt` | The run-up to a freeway exit: when it begins, how long it holds and when it is taken up again, one latch for the card and both cameras; and a turn right after the exit that keeps the close view until it is framed. See [The run-up to a freeway exit](#the-run-up-to-a-freeway-exit). |
| `LookAhead`, `PeakSpeed` | `:core-nav`, `nav/camera/LookAhead.kt` | How much road the camera shows above the car, by a perspective model, and the closest zoom that still shows 12 s of it at the highest speed of the last 10 s. |
| `guidancePitch` | `:core-nav`, `nav/camera/CameraMotion.kt` | Smooth follow's tilt: 45° on the road, up to 55° along the lane focus with Large, and down to 0° over a junction, which wins. |
| `RunUpRule`, `CardLanes` | `:core-nav`, `nav/guidance/` | The card's row on a run-up, with its edge points, the composed lane and the lanes to use in words; and which lane row the card draws. |
| `LaneChoices` | `:engine-mapbox`, `navigation/LaneChoices.kt` | The points with lanes ahead of the car, read from the route once for each step, and the run-up's one latch and row. The engine owns the one copy; the cameras and the maneuver card read from it. |
| `GuidanceCamera` | `:engine-mapbox`, `map/camera/GuidanceCamera.kt` | What `PhoneMap` asks of the camera of any style: `requestFollowing`, `requestOverview`, `requestIdleFollow`, `requestIdle`, `onLocation`, `onRouteProgress` (with the lane choices ahead), `onPuckGlide`, `onGuidanceEnded`, `setLaneZoom`, `setVisible`, `diagnostics`, `destroy`, and a listener for its state and for who took the map. |
| `LaneZoomFrames` | `:engine-mapbox`, `map/camera/LaneZoomFrames.kt` | What the lane zoom changes on the viewport data source for each camera style, and the way a change of it reaches a route the data source already has. |
| `NavigationCameraAdapter` | `:engine-mapbox`, `map/camera/NavigationCameraAdapter.kt` | The two Mapbox styles: `NavigationCamera` and `NavigationBasicGesturesHandler` behind `GuidanceCamera`. |
| `FollowingTransition` | `:engine-mapbox`, `map/camera/FollowingTransition.kt` | Mapbox tuned's longer animators between following frames. |
| `SmoothNavigationCamera` | `:engine-mapbox`, `map/camera/SmoothNavigationCamera.kt` | Smooth follow: the controller, a `GuidanceCamera` and `Choreographer.FrameCallback` with modes IDLE, IDLE_FOLLOW, FOLLOWING and OVERVIEW. Its frames come from a `FrameSource`. |
| `FrameSource`, `DisplayFrames` | `:engine-mapbox`, `map/camera/FrameSource.kt` | Where Smooth follow and the zoom buttons' ease get their frames, as from Choreographer: the main thread's display frames on the phone (`DisplayFrames`), the car's frame clock in the car. |
| `GuidancePuck` | `:engine-mapbox`, `map/camera/GuidancePuck.kt` | The puck during guidance: a `LocationProvider` around `NavigationLocationProvider`. It glides 1.1 s per fix and jumps on a teleport, from where it glides on for one glide along its heading at its speed (`PuckGlide`); for Mapbox standard it glides 1.0 s and slides also on a teleport. In every style, Mapbox standard included, the first position after a seek of a simulated drive (`onFix(jump = true)`) puts it at the new place at once, and it glides on from there in the same way. `newDrive()` swaps in a fresh inner provider at every Start, in every style. |
| `PlatformPuck` | `:engine-mapbox`, `map/camera/PlatformPuck.kt` | The puck before guidance: the Maps SDK's `DefaultLocationProvider`, a report of each new position for the camera and the data source, and a jump in place of the glide when a position is far from the drawn car. |
| `LaneDetails`, `RouteLineWidths` | `:engine-mapbox`, `map/LaneDetails.kt`, `map/RouteLineWidths.kt` | Lane markings on the map, set after the SDK has turned them off, and the route line that stays thin close in while they are on. |
| `MapScene` | `:engine-mapbox`, `map/MapScene.kt` | Everything of a map that is not tied to a view, the same on the phone and in the car: creates the camera of the chosen style, hands it every navigator update with the lane choices ahead, sets up the data source for the lane zoom, and writes the camera line to the debug log (in the car with `screen` `car`). |
| `PhoneMap` | `:engine-mapbox`, `map/PhoneMap.kt` | The phone's map view around its `MapScene`: the logo and attribution strip, the compass, the look's (i) dialog, the fade-in and the tap that picks a route. |
| `MapScreen` | `:app`, `ui/map/MapScreen.kt` | Passes on the chosen style, the lane zoom and Lanes on the map, measures the padding, and says when the map is on screen: from the activity's start to its stop (`LifecycleStartEffect`). |
| `CarNavigationMap` | `:engine-mapbox`, `car/CarNavigationMap.kt` | The car's map (stage 2, step 1): a `MapScene` with `CameraTuning.CAR` on the car's surface, with its padding, gestures, zoom buttons, logo and attribution, its frame clock, puck and frame rate cap (step 1b), and the app's look. |
| `CarFrameClock` | `:core-nav`, `nav/car/CarFrameClock.kt` | The car map's frame clock, its decisions: the display's frames while they come, a timer every 16 ms once none has come for 50 ms while a frame is wanted, back to the display when two of its frames come within 25 ms, never two ticks within 15 ms. |
| `CarFrames` | `:engine-mapbox`, `car/CarFrames.kt` | The clock on Android for each car surface: Choreographer and a main-thread `Handler`, the car's `FrameSource`, the `frame_clock` lines, and the rate of the display's frames and whether the clock left them, for the cap. |
| `PuckTrack` | `:core-nav`, `nav/car/PuckTrack.kt` | Where the car's puck is at a tick: through an update's points at one speed, in the update's time, the bearing the short way round, a new update from where the puck is. |
| `CarPuck`, `PuckRelay` | `:engine-mapbox`, `car/CarPuck.kt`, `map/camera/PuckRelay.kt` | The car's location provider: follows the platform's location or the guidance puck and, on each tick of the car's clock, hands the location component the puck's place and bearing with no duration. |
| `CarFrameRate`, `VsyncRate` | `:core-nav`, `nav/car/CarFrameRate.kt` | The cap of the car's surface for a rate, `round(30 × 60 / rate)`, and the rate of the display's frames. |
| `CarFrameCap` | `:core-nav`, `nav/car/CarFrameCap.kt` | The car's cap held at a real 30 frames a second: from the render thread's own ticks during guidance, else from the display's rate, 60 on the timer. |
| `CarSurfaces`, `PhoneFrameCap` | `:core-nav`, `nav/car/PhoneFrameCap.kt` | The car surfaces that show the map, one count for the process in the engine, and each phone map's cap: 60 while any does, none set before the first, lifted when the last goes. |

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

1. The frame time comes from the `Choreographer`, on the car from its frame clock
   ([The car map](#the-car-map-stage-2)). A gap counts as at most 0.1 s.
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

Built in stage 2's step 1, its frames reworked in step 1b. `CarNavigationMap` is a
`MapboxCarMapObserver` (maps-androidauto 11.34.1), registered with
`registerObserver(...).setGestureHandler(...)`. The style chosen in Settings applies to the car
screen too (DECISIONS 26); this section covers Smooth follow, the default and the only style
designed for the car so far. The rest of the car screen is in [STAGE-2.md](STAGE-2.md).

- **On attach:** a new `MapScene` on the car's map surface: the same puck settings as the phone,
  its own `MapboxNavigationViewportDataSource`, and a `SmoothNavigationCamera` with
  `CameraTuning.CAR`. `MapboxCarMap` builds a new map surface whenever the host sends a new one, for
  example after a size change, so each attach starts a new data source and controller; the camera
  starts where it was on the surface before, or else at the newest position the app has. During a
  drive it then follows the car, or shows the overview again when the driver asked for it during
  this drive and has not pressed Recenter, moved the map or zoomed since (`CarOverview`); a drive
  that ends forgets it, so the next one starts by following the car. In the route preview the
  scene frames the routes on every surface, so the preview's Recenter is not kept.
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
  eases with an animator, which the hand-over rule catches anyway and which, like every animator,
  waits for the main thread's display frames; Mapbox's default handler does nothing on a fling. The
  gesture handler calls `requestIdle()` before passing on scroll, fling and scale. **Recenter** comes
  from the map's action strip.
- **One puck for both maps:** `GuidancePuck` and the navigator feed are the engine's. Each fix
  reaches the phone and car maps once, and `newDrive()` runs when guidance starts. Mapbox's
  `CarLocationProvider` is not used: it keeps the 1000 ms glide. On the car the puck is drawn on the
  car's frame clock (below).
- **Zoom buttons** in the map's action strip step by 0.5 between 6 and 20 and hand the camera to
  the driver, as a gesture does; a pinch past the limits is dropped. Since step 1b their 300 ms ease
  runs on the car's frame clock (smootherstep, one camera write a tick), not on an animator.
- **Frames (step 1b).** The car's map takes its frames from a clock of its own (`CarFrameClock`, run
  for each surface by `CarFrames`): the main thread's display frames while they come, and once none
  has come for 50 ms while a frame is wanted, a main-thread timer every 16 ms, until two display
  frames come less than 25 ms apart again. Two ticks are never closer than 15 ms, so there are at
  most about 60 a second, also on a phone at 120 Hz. Each tick has a `System.nanoTime` time,
  Choreographer's own clock. The clock runs only from a surface's attach to its detach, and only
  while something wants a frame. Smooth follow, the zoom buttons' ease and the puck move on it: on
  the first car test the main thread got no display frames for many seconds with the phone app in
  the background, and the car's map stood still.
- **The puck on the clock (step 1b).** The car's location component takes `CarPuck` as its provider,
  which follows the platform's location before Start and the guidance puck during a drive. It takes
  each update with its points and its glide time (the options' duration; without options the
  component's default of 1 s; 0 a jump; every update a jump while Android's animations are off, as
  on the phone), and on each tick `PuckTrack` gives the place and bearing of that moment: through
  the key points at one speed, each part of the way taking its share of the distance as the
  navigation SDK's provider has it, the bearing the short way round, a new update in the middle of
  a glide from where the puck is then. They go to the component with a duration of 0, which it draws
  inside the call. The puck asks for each tick first, so the camera of the same tick reads where the
  puck is. The phone's puck is Mapbox's animation, as before.
- **Smooth follow on the car.** Only Smooth follow keeps moving while the main thread gets no display
  frames. The two Mapbox styles keep Mapbox's own animators, and so does the double tap's zoom: they
  stand still then, as the whole car map did before step 1b.
- **Frame rate (step 1b).** The car's surface draws a real 30 frames a second (`CarFrameCap`):
  `setMaximumFps(round(30 × 60 / rate))`, within 1 and 60, for the rate at which Mapbox's render
  thread ticks as Mapbox counts it. Mapbox paces the car's surface against 60 Hz, the rate of
  car-app's own virtual display by Android's sources and Mapbox's own fallback, while its render
  thread is called at the phone's rate, so the former `setMaximumFps(30)` drew 60 frames a second at
  120 Hz. It draws on `cap` of every 60 ticks, a tick being a call of its render thread plus the
  display frames it counts as missed when a call comes more than 1/60 s and a millisecond after the
  one before. At 120 Hz a frame that takes longer than one display frame makes the next call wait
  for the display frame after next, which counts as one tick: the ticks then come slower than the
  display's frames, and the cap for the display's rate alone, 15, drew about 24 frames a second
  with frames of 9 to 16 ms. So during guidance 30 is held from the render thread's own ticks: each
  second the camera line's window of its statistics gives `render.frames` plus `render.missedVsync`.
  A window counts when the frame clock stayed on the display, the cap did not change in it and the
  render thread was called on at least half of the display's frames (a map that draws now and then,
  standing at a light, says nothing); the cap goes down at once when the map would draw more than a
  tenth over 30 at the cap set now, and up only when two windows in a row say it would draw more
  than a tenth under, for the faster of the two. Without windows (before the first guidance) the
  rate is that of the display's frames, measured from display callbacks asked for one right after
  the other (the lower quartile of the last 16 intervals); after a guidance the last render
  thread's rate, while the display's rate stays within a tenth of the one it was counted at; 60
  while the clock runs on its timer. At 120 Hz the cap is 15 to 20. While a car surface shows the
  map, the phone's map draws at most 60 frames a second (`setMaximumFps(60)`, which its pacing knows
  the phone's real rate for), and as before once the surface goes.
- **Frame target (step 1c).** **Settings → Debug → Car map frames** sets what the car's map aims
  at: 30 frames a second, the default, or 60, at once on the surface there is, and on every later
  one. Settings opens before Start and after the arrival, so a drive keeps the target it started
  with. The cap is then `round(target × 60 / rate)`, within 1 and 60, and the band that holds a cap is
  a tenth of the target either way (54 to 66 at 60): at 60 the cap is 30 at 120 Hz, 40 at 90 Hz and
  60 at 60 Hz and on the timer. Every `car_surface` line and the car's camera line carry `targetFps`,
  and a new target writes a `car_surface` line with `event` `fps` while a surface is there. The car's
  host may show fewer frames than the map draws: it shows the app's surface on the car's screen at a
  rate of its own, which the app cannot read, so 60 drawn may look no smoother there than 30. The
  switch is there to compare the two on the car's screen; the car's camera line says what the map
  drew (`render.drawnFps`), and the `thermal` lines what it cost.
- **The phone's map while the car shows one (step 1c).** During a drive while a car's surface shows
  the map, the phone shows the list of the maneuvers ahead in place of its map, unless the driver
  chose the map ([STAGE-2.md](STAGE-2.md), Step 1c). The phone's map is put away meanwhile: its view
  is out of the window, which stops it, and its camera draws nothing (`MapScene.setAway`): Smooth
  follow asks for no frames, and the two Mapbox styles start no animation, as their data source's
  frames do not reach the navigation camera then. The data source goes on taking the positions and
  the progress, so when the map comes back the camera follows the car from the frame of now: Smooth
  follow snaps to it on its first frame, the Mapbox styles go to it at once; a camera that was in the
  overview or left to the driver before follows the car again. A phone screen that starts on the
  list, as one made again mid-drive, makes its map only when the map is to show: Mapbox stops a map's
  view with the activity only once the view has been in the window, so a map made for the list would
  keep its render thread and its puck's animators going, unseen. The new map then starts as at the
  app's start, style load and all.

### Logging

A **camera** line once a second during guidance. The map scene writes it, in every camera style, and
on the phone only while its map view is shown; the `activity` and `screen` lines show when it was
not. While the phone's map is put away for the list of the maneuvers ahead (stage 2's step 1c), the
phone's line goes on while its screen is on, with `away`; a phone screen that started on the list
has no map, and so no line, until the map shows. The car's map writes its own, with `screen` `car`.

| Field | Meaning | Expected |
|---|---|---|
| `style` | the camera style: `MAPBOX_STANDARD`, `MAPBOX_TUNED` or `SMOOTH_FOLLOW` | |
| `state` | what the camera is doing: `FOLLOWING`, `OVERVIEW`, `IDLE`, and `TRANSITION_TO_FOLLOWING` or `TRANSITION_TO_OVERVIEW` on the way there | |
| `zoom`, `pitch`, `bearing` | the map's camera | |
| `laneDp` | how wide a lane of 3.7 m is on the screen at the camera's centre, in dp | about 4 to 6 on a town road; with Large about 28 at a junction and 14 near a lane choice, less at speed on a run-up |
| `aheadM` | while the camera follows the car: how much road it shows above the car, in metres, by the model of [The run-up to a freeway exit](#the-run-up-to-a-freeway-exit), from the car's place to the top of the padding, under the card; left out at other times | with Large on a run-up, at least 12 s of driving |
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
| `render` | the render thread that second: `frames` and `fps`, how often it was called; `drawn` and `drawnFps`, how many of those calls drew a frame, since under a frame rate cap some calls draw nothing (paced); `missedVsync`; and `p50Ms`, `p95Ms` and `maxMs` of every call, the paced ones with their near-zero times too | on the car `drawnFps` about 30; on the phone `drawn` the same as `frames` without a cap |
| `renderFps` | the renderer's own estimate of its rate (`MapView.setOnFpsChangedListener`, called on the render thread), worked out against the display rate the map library believes, which on the car's surface is 60 whatever the phone's: there it reads the cap, 15 to 20 at 120 Hz, and `render.drawnFps` is the real rate; left out while the map draws nothing | at least 55 at 60 Hz on the phone |
| `animatorsEnabled` | `ValueAnimator.areAnimatorsEnabled()` | false: the animator scale is 0 and the puck jumps |
| `hz` | the display's refresh rate; left out on the car, whose host does not say it | |
| `capped` | the phone's map only: true while it draws at most 60 frames a second because a car's surface shows the map too; left out otherwise | |
| `away` | the phone's map only: true while it is put away, the phone showing the list of the maneuvers ahead in its place; its view is out of the window and stopped, so `render.frames` is 0, and the camera's and the puck's fields are those it was left with; left out otherwise | `render.frames` 0 |
| `clock` | the car's map only: the ticks of its frame clock that second, `vsyncTicks` from the display's frames and `timerTicks` from the timer | about 60 together while moving; `timerTicks` 0 while the display sends frames |
| `maxFps`, `targetFps`, `capFrom`, `measuredHz` | the car's map only: the cap set on its surface; the frames a second it is worked out for, 30 or 60 (**Settings → Debug → Car map frames**); where the rate it was worked out for came from, `render` (the render thread's own ticks), `display` or `timer`; and the rate of the display's frames measured now (60 on the timer; left out before it is measured) | 15 to 20 at a target of 30 (30 to 40 at 60), `render` and about 120 on a phone at 120 Hz during a drive |
| `laneDetails` | whether Mapbox's lane details are wanted on this map: Lanes on the map at Mapbox's lane details, or the measuring drive's check under the app's drawing | |
| `hdRoads` | what the style said about them when it was last asked, once a second; left out while the style has no such setting | the same as `laneDetails` |
| `hdRoadsReapplied` | how often they had to be set again since the map was made | 0 |
| `routeLine` | only while the app draws the route line, with **Settings → Debug → App's own route line** on or with **Lanes on the map** at **Drawn by the app** ([The app's own route line, behind a Debug switch](#the-apps-own-route-line-behind-a-debug-switch)): `own`, whether the app's own line is drawn; `trims`, how often its trim was set that second; `trimLagM`, the largest distance along the route between its trimmed end and the SDK's vanishing point; `trimMaxMs`, the longest the app's part of a puck update took on the main thread. The last two are left out without a sample. With the lanes also `swapped`, whether the lit lane takes the route line's place over the lit stretches ([The app's own lanes](#the-apps-own-lanes)); it is the camera's state, so a junction view with no lane lit sets it too; and `swaps`, how often that changed in the second | `trims` up to 16 while moving; `trimLagM` 0 but where the SDK skipped its point; `trimMaxMs` under 0.5; `swaps` 0, and 1 in the second the close view latches or lets go |
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
| `laneChoice` | the lane choice that holds the view: `key`, `lanes`, `usable`, `atManeuver`; left out on the road | |
| `runUp` | the exit of the run-up that holds the view, by its key, also through the hold past the gore, through the ramp the lane plan holds it through, and a turn's bridge; left out at other times | the same key from a run-up's start to about 2 s past its gore, or past the split on its ramp that the plan constrains within 15 s of the gore |
| `laneCapZoom`, `capSpeed` | the closest zoom the lanes may take and still show 12 s of road, and the speed it is worked out for, the highest of the last 10 s, in m/s; left out without a speed, a padding or a laid-out map | below the 14 dp zoom only above about 25 m/s |
| `centerLeadS` | how many seconds ahead of the drawn car the centre aims. It is logged also while `onPuck` is 0, in the frame of a maneuver, where it does not move the centre | 0 with Default, and with Large up to about zoom 17; closer in, up to about 0.3 |

The two Mapbox styles do not run the controller. They add `frameUpdates`, the frame animations
`NavigationCamera` started that second, and `frameUpdatesCut`, those cancelled before they
finished because the next one came. While the car moves, Mapbox standard starts about two a second
and Mapbox tuned one. They also add `laneZoom` (the setting, which Mapbox standard does not act
on), `laneGoal` and `maxZoom` and `minZoom`, and Mapbox tuned also `runUp`, `laneCapZoom` and
`capSpeed` as Smooth follow, its floor worked out at the SDK's tilt of 45°, below the 14 dp zoom
above about 16.5 m/s. `laneGoal` is the goal of the lane choices' schedule,
as in Smooth follow: 1 from the start of a counted lane choice's window until the view would be
let go again. Mapbox tuned runs the schedule with Default too, where it moves nothing; Mapbox
standard does not run it, so it is always 0 there. `maxZoom` and `minZoom` are the limits the data
source fits its frames within: 16.35 and 10.5, but for Mapbox tuned with Large near a maneuver and
near a lane choice. So `laneGoal` 1 with `minZoom` 10.5 is Mapbox tuned with Default, or with
Large while the SDK frames a maneuver.

Other lines:

- A **frame_clock** line, with `screen` `car`, at each switch of the car's frame clock: `source`
  `timer` when the timer takes over, with `quietMs`, how long the display's frames had been awaited;
  `source` `vsync` when the display's frames are back, with `quietMs`, how long the display was quiet
  from the start of that wait, and `timerTicks`, the ticks the timer gave meanwhile.
- A **display_state** line, from the app (not an activity), at its start and at every change of the
  phone's own display, also while the car's screen shows the app: `state` (`ON`, `OFF`, `DOZE`,
  `DOZE_SUSPEND`, `VR`, `ON_SUSPEND` or `UNKNOWN`) and `hz`, its refresh rate to a tenth. No position
  and no time of day.
- A **phone_screen** line at each change of what the phone shows during a drive while a car's screen
  shows the map (stage 2's step 1c): `screen`, `list` or `map`, and `cause`: `drive` (a drive
  started with a car showing the map), `car` (a car's surface came or went), `button` (**Map** or
  **List**) or `end` (the drive ended). Nothing else: no position and no time of day. With no car it
  is never written.
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
  change of `camera_style`, `lane_zoom` (`default` or `large`) or `lanes_on_map` (`off`, `mapbox` or
  `app`) during a drive shows there, and so do `day_night` (`auto`, `day` or `night`),
  `car_map_fps` (30 or 60), `own_route_line` and `measure_own_lanes` (true or false). The carry-over
  of the earlier switch Lane details on the map writes one `lanes_on_map` line with the choice it
  wrote.
- A **look** line for each change between the day and the night look
  ([Day and night during guidance](#day-and-night-during-guidance)): `look` and `was` (`DAY`,
  `NIGHT`), `cause` (`START`, the first look of the app's process; `RESUME`, back on screen;
  `CHOICE`, the driver's; `SUN`; `PLACE`, a better position than the one the look was chosen for),
  `choice` (`AUTO`, `DAY`, `NIGHT`), `place` (only where the sun was worked out for: `FIX`,
  `LAST_KNOWN` or `TIME_ZONE`, never the position), `heldS` and `heldBy` (`MANEUVER`, `CLOSE_VIEW`,
  `TUNNEL`) when the change waited for a quiet moment, `guidance`, and `themeDeferred` true when the
  basemap's theme was left for one. From the map: `keys` (the style's settings written), `errors`
  (and `failedKeys` with an error), `writeMs` (the whole write on the main thread), `reset` by day
  (`null` when every colour given back to Standard read back as Standard has it, `snapshot` when one
  had to be written back from what the style said as it loaded), `transitionMs` (the style's
  transition for the light change) and `routeLine` (the route line took the look's colours). On
  `START` also `startMs`, how long the first frame waited; a `START` line has no fields from the
  map, whose first tiles are drawn in the look, and nor has a line while no map is on screen. With
  the phone's map and the car's both showing the look, the fields are those of the first map that
  wrote it, and a car's map between two surfaces leaves the line to the other; one that no map
  showing could write goes without them. A change by the sun outside guidance writes no line: its
  time alone would be the local dusk or dawn. Enums, whole seconds and booleans; no position, no sun
  angle, no time of the sun, no time zone.
- A **look_theme** line when a theme left for a quiet moment goes: `themeHeldS`, `heldBy` (what
  held it last) and the map's `writeMs`. A map made while it waited drew its tiles with the whole
  look, so it then writes nothing.
- A **look_settled** line after a `look` or `look_theme` line that wrote the basemap's theme, and
  only when that line was written: `idleMs`, the time until the map had drawn every tile again, or
  null when that had not happened within 10 s, and `look`.
- The **map_style_loaded** line, at each style load, has `look` (what the basemap shows as it
  loads), `keys` (the settings the style's data wrote, 16) and `errors` (and `failedKeys` with an
  error).
- A **lane_choice** line once for each point with lanes, when it comes as near as the close view
  would start for it, with every camera style and either lane zoom: `kind` (`straight` where the
  route goes straight on, `maneuver` at an exit or a fork), `key` (the route, leg, step and
  intersection, or `end` for the exit), `metersAhead`, `window` (the distance at which the close
  view starts at this speed), `lanes` (not counting `designated` ones), `usable`, `leaving`,
  `designated`, `freeway`, `speed` in m/s (left out while unknown) and, when the rule passes the
  point over, `skipped` with the reason: `NOT_A_FREEWAY`, `TOO_FEW_LANES`, `NO_USABLE_LANE`,
  `NO_LANE_LEAVES` or `TOO_FEW_LEAVING`. The lines without `skipped` are where Large goes close;
  the others show what a looser rule would add. `edgeLeaves` says whether the lane at the side the
  step's exit leaves on leaves the route there (an edge point), and `fromLeft` lists every lane as
  the route has it, from the leftmost: its arrows (`ind`), `valid`, `active`, the arrow it takes
  (`validInd`), each null where the route says nothing, and `des`, the vehicles a lane is kept for,
  only where it has any.
- A **lane_row** line whenever what the maneuver card's lane row draws changes, with every camera
  style and either lane zoom: where the row comes from, its lanes, which are lit, or the words beside
  them; never for a distance alone. `source` (`maneuver`, `choice`, `run_up` or `none`), `key` (the
  point the row shows), `row` (one token a lane from the left: its arrows `s`, `sl`, `l`, `shl`,
  `sr`, `r`, `shr`, `u` joined by `+`, `-` for none, `*` when lit, and `>` with the arrow lit on it
  where that is not the lane's only arrow; `~` instead for a lane drawn as an outline, a lane still
  to come, which is never lit: `sr~`), `lit` and `des` (indices; an outline is never among `lit`),
  `label` (a lane choice's distance), `words` (`R1`, `R2`, `R3`, `R1+1` and `R1+2` for Right lane,
  2 right, 3 right, 2nd and 3rd from right, and `L...` for the left), `need` (the composed lane,
  counted from the exit's edge), `metersAhead`, `runUp` and `start` (the run-up's exit, and what
  began it: a point's key, `edge`, `resume` or `seam`, the lane plan's seam; on a ramp the run-up
  holds through after its gore, `runUp` is still the exit whose gore was passed and `key` is the
  split's row), `odd` (why a run-up's row has no words: `composed`, `pieces`, `unknown_branch` (the
  rule's own reading, where no lane plan reads the row), `opens_ahead` (by the plan, a lane on the
  exit's side opens or may begin between the car and the row's point, or the lit lane counts
  otherwise from the exit's edge at the car), `unknown_ahead` (by the plan, a road without lane data
  leaves on the exit's side ahead on the step), `not_named` (the plan names no lane at the row's
  point or at the car, as before an exit beyond a lane for car pools, or the route's plan is still
  on its way), or `no_fit` when the card left words out that did not fit; and `designated_only` on
  any row, the maneuver's and a lane choice's too, whose only lit lanes are kept for car pools or
  buses), `pending` and `offRoute`. Words that do not fit are found when the card lays the row out,
  so such a row has a second line a moment after its first. Keys, indices and tokens only.
- A **missed_maneuver** or **diverge_taken** line for each place the road divides, to tell later
  whether a rule could find a car that stayed on the freeway sooner than the navigator does. A
  watch starts when the step changes past an exit, a fork or an on ramp (`kind` `maneuver`), and
  when the car passes an edge point (`edge`), a lane choice where the route goes straight on
  (`choice`) or a road without lane data that leaves on the exit's side (`branch`). It ends with
  `missed_maneuver` when the navigator finds the car off the route within 30 s or 1,000 m, and with
  `diverge_taken` after them; a newer diverge ends the one before without a line, and so do a new
  route, a seek and the end of guidance. Fields: `kind`, `maneuverType` and `modifier` (the
  maneuver's), `key`, `freeway`, `lanes`, `usable`, `card`, `words` and `runUp` (what the card showed
  on the way), `advanced` (the step changed), `sinceS` and `sinceM` (to the line), `probAt`,
  `probMin`, `probMax`, `probLast` (how sure the navigator was of the road it matched), `uncertainS`
  (seconds it said it was unsure), `sideAt`, `sideMin`, `sideMax` (the phone's position to the side,
  as `match.raw.sideM`), `speedAt`, `speedEnd`, `teleportAtOff` on a miss, and `match` with `raw`
  only. Nothing acts on these lines.
- A **lane_details** line each time the app sets Mapbox's lane details on the map: `reason`
  (`setting`, `style_loaded` or `reapplied`), `wanted`, `read` (what the style says afterwards) and
  `error` if the style refused. `setting` comes also when the measuring drive turns them on under
  the app's drawing and back ([Measuring the drawn lanes](#measuring-the-drawn-lanes)).
- A **map_layers** line once per style load: `arrowAboveLine`, true when every layer of the
  maneuver arrow is drawn above the route line's top layer (null while either is missing), and
  `middle`, the ids of the layers in the style's middle slot, where the route line and the arrow
  are, in the order they are drawn. Ids only. See
  [The maneuver arrow close in](#the-maneuver-arrow-close-in). While the app's own route line is
  in the style, also `ownLine`: true when its casing and line lie right above the SDK's route line
  and below every layer of the arrow; the line comes again when the app's own line is turned on or
  off, by its Debug switch or by Lanes on the map. While the app's own lanes are in the style, also
  `lanes`: true when the drawing's layers lie in their order below the SDK's route line and the lane
  arrows above the app's route line and below the maneuver arrow.
- A **route_line** line, while the app draws the route line (**Settings → Debug → App's own route
  line**, or **Lanes on the map** at **Drawn by the app**), at each change of who draws it; with
  Drawn by the app a **lane_drawing** line for each window of the app's own lanes that loaded and a
  **car_layer** line at each change of who draws the car; with **Measure the drawn lanes** a
  **lane_measure** line for each close view; see
  [The app's own route line, behind a Debug switch](#the-apps-own-route-line-behind-a-debug-switch),
  [The app's own lanes](#the-apps-own-lanes) and [DRIVES.md](DRIVES.md).
- With **Lane counts between lane points** on, a **horizon_lanes** line for each lane point of the
  route that Mapbox's electronic horizon covers, once: its list against the horizon's count of the
  edge that leads to it, and under which reading they agree. The progress line's `motion` has
  `ehLanes` on freeways, with **Measure the drawn lanes** the line has `horizon`, the observer's
  main-thread time, and the `trip_summary` line has `horizon`, the drive's counts; see
  [The app's own lanes](#the-apps-own-lanes) and [DRIVES.md](DRIVES.md).
- The **route_response** and **routes_updated** lines carry, for each step with any, the
  intersections that have lanes (`laneIntersections`): `meters` from the start of the step,
  `lanes`, `usable`, `active`, `designated`, the `classes` of the road that leaves it, and
  `freeway`, whether the road that leads to it counts as one. With these the rule can be tried with
  other numbers after a drive. A step's `lanes` are those of its last banner that has lanes, the
  one shown near its maneuver. Every step also has `bearingBefore` and `bearingAfter`, the
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
  `replaySeek` true, and nothing on the screen acts on it. Its `match`, and that of the `off_route`
  line, has `raw`: where the phone's own newest position lay against the matched one, `sideM`
  across the matched direction (to the right positive), `aheadM` along it (the navigator puts the
  car about a second ahead, so expect about minus the speed), `accM` (its accuracy), `ageMs` (how
  old it was when the matched one came; none in a simulated drive) and `bearingDiff` (its direction
  less the matched one, null under 3 m/s). The phone's position itself is not written.
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
- Cap the frame rate with `MapView.setMaximumFps(60)` if the phone gets hot. Built for two maps at
  once in stage 2's step 1b: the phone's map draws at most 60 while a car surface shows the map.
- An own frame clock for the puck, if it is wanted (see [System animations](#system-animations)).
  Built for the car in step 1b (`CarFrames`, `PuckTrack`, `CarPuck`); the phone's puck is still
  Mapbox's animation.

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
- **Phone locked in the car.** On the first car test the main thread got no display frames for many
  seconds with the phone app in the background, and the car's camera and puck, which moved on them,
  stood still. Built in stage 2's step 1b: the car's camera, its zoom buttons' ease and its puck
  take their frames from the car's frame clock, which falls back to a main-thread timer then. Still
  waiting for the main thread's frames on the car: the two Mapbox camera styles and the double tap's
  zoom. And the map's render thread draws only on display callbacks of its own: whether it still
  gets them while the main thread gets none is not known yet (the car's camera lines,
  `render.frames` and `drawn`, say it). While the clock is on its timer, each puck update leaves a
  cleared entry in Android's animation list until the next display frame, since an animator of no
  duration still registers there: a few MB and a few percent of a core after an hour, gone at the
  first frame. The app's own puck layer of a later step ends it.
  `AnimationThreadController.useBackgroundThread()`, experimental and for every Mapbox animation in
  the app, is not used.
- **Two maps at once.** With the phone app open and the car connected, two loops and two renderers
  run. The phone loop stops with the activity, but Settings drawn over the map keeps it running, as
  map rendering does today. Since step 1b the phone's map draws at most 60 frames a second while a
  car surface shows the map, and the car's surface a real 30.
- **Step changes.** The bearing still swings toward the new step, by up to 45°; it is now a glide of
  at most 60°/s instead of a cut (animations off) or a one-second linear move (animations on).
- **The constants** come from simulation and need drives to tune.

For the Large lane zoom and Mapbox's lane details:

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
- **The lane-choice rule is a guess** until a drive: 3 lanes, 2 leaving, 25 s, and for a run-up
  60 s or 1,500 m before the window. Lane data may also be thin on intersections the route goes
  straight through; then the rule fires only at exits and forks. The `lane_choice` lines show both.
- **The route line's lane means nothing** on a run-up, which holds the close view for up to about
  85 s: the line lies along one of the freeway's lanes and crosses every lane at the gore, while the
  card names the lanes to use. Until the app draws the lanes itself, the card's words are the
  instruction ([The run-up to a freeway exit](#the-run-up-to-a-freeway-exit)).
- **The road ahead is a model.** The 12 s rest on a perspective model of the native camera (a field
  of view of 36.87°, the camera 1.5 map heights away), which is not published. The camera line's
  `aheadM` and a screenshot on a drive check it.
- **The tilt of 55° draws more map** far ahead, about 1.3 km to the screen's top against 0.5 km at
  45°: more data and frame time, which the next drive measures (the camera line's `render` and the
  phone's data use).
- **Rows without words.** Where lanes are added between two edge points, or the lane data
  contradicts itself, a run-up's row lights the careful lane and has no words. How often, the
  `lane_row` lines count (`odd`).
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
  and before a lane choice on a freeway. It comes back out after each, except that on a freeway the
  run-up to an exit is one approach: the view comes in once and stays to the gore, or through the
  ramp to a split the lane plan constrains soon after it
  ([The run-up to a freeway exit](#the-run-up-to-a-freeway-exit)). Smooth follow and Mapbox tuned
  carry it. Mapbox standard keeps its own zoom, and Settings says so under the row while that style
  is chosen.

A change applies at once, also during a drive, and the view glides to it. The reasons are in
[DECISIONS.md](DECISIONS.md), 38.

Large has since been on a real drive, and what it showed before a freeway exit changed the close
view there ([The run-up to a freeway exit](#the-run-up-to-a-freeway-exit)). Every threshold below is
still a first setting from reasoning and simulation. Each is one constant in `CameraTuning`
([Constants of the lane zoom](#constants-of-the-lane-zoom)).

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
- The run-up's tilt of 55° (Smooth follow with Large) shows more of the map ahead: about 1.3 km to
  the screen's top, under the card, against about 0.5 km at 45°. That loads more tiles of the same
  kind, which Mapbox's map pricing by monthly active users already covers: no new bill, but more
  data and frame time.
- Not checked: whether [Mapbox's lane details](#mapboxs-lane-details) have map data of their own,
  and whether that is among what gets saved for offline use. The app's own lanes need none: they are
  drawn from the route.

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
data is in every route. Mapbox's Electronic Horizon also knows lane counts; it is a beta whose free
use Mapbox may end, and the lane choices and the zoom do not read it. Only the app's own drawing
takes its counts, between lane points that agree with them
([The app's own lanes](#the-apps-own-lanes), lane counts between lane points).

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
kilometres the long view ahead stays, as the owner asked, except on the run-up to an exit
([below](#the-run-up-to-a-freeway-exit)).

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

**The view.** The zoom goes to a lane of 14 dp, but no closer than the zoom that still shows 12 s of
road ahead, and Smooth follow tilts it up to 55° rather than down: straight down, that zoom would
show only a few seconds of road at freeway speed ([The run-up to a freeway
exit](#the-run-up-to-a-freeway-exit), the tilt and the road ahead). While a point holds the view,
the bearing may lead the car's heading toward the road ahead by at most 20° instead of the SDK's
45°, so the lanes at the car stay close to upright on a curving freeway. Elsewhere the early turn
is unchanged.

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
by the engine from the same `LaneChoices`). On a run-up the card shows the run-up's one row instead
([The card's row on a run-up](#the-cards-row-on-a-run-up)).

**Limits.** Lane choices are read for the current step only, so one that follows right after a
maneuver gets a shorter start. What kind the next maneuver is comes from the route's banner; while
the navigator has none, an exit or a fork is not taken as a lane choice. The run-up has limits of its
own ([The run-up to a freeway exit](#the-run-up-to-a-freeway-exit)).

### The run-up to a freeway exit

On a freeway the last part of a step before its exit or fork is one approach, the **run-up**: the
close view comes in once and stays to the gore, and the maneuver card shows one lane row for it.
Before, on an approach with several points on the way, the view went in, out and in again between
the points, and the card's lane row changed at each point, each row with its own distance counting
down: every row right for its own point, and together a set of conflicting orders. The reasons are
in [DECISIONS.md](DECISIONS.md), 46.

**When it begins** (`RunUpLatch` in `:core-nav`). The step's exit, the next maneuver where it is a
lane choice that counts (an exit or a fork the view stays up for, at least 3 lanes, some usable and
some leaving), must lie within reach: the close view's window plus 60 s of driving, at least
1,500 m. That is 1,900 m at 15 m/s, 2,125 m at 25 m/s, 2,465 m at 29 m/s and 2,805 m at 33 m/s; a
car that stands, or whose speed is not known, gets 1,900 m. Then the run-up begins at the first
update where one of these holds:

- an **edge point** lies ahead on the step: a point where the route goes straight on and the lane
  at the exit's side leaves the route while another lane goes on, as at an earlier exit, a split
  or a lane that ends on that side;
- a lane choice that counts is inside its own close view window, the exit's own included, so at
  the latest the run-up begins where the close view began before;
- a run-up ended less than 3 s ago: a new route that keeps an exit in reach, a short time off the
  route, a seek of a simulated drive, or the gore of the run-up before, so an exit right after a
  fork is one approach. 3 s is the camera's own hold of 2 s by time plus one update.

It also begins **no later than the lane plan's seam** for the exit: 6 s of driving before the plan's
lanes first narrow for a lane point of that run-up within its reach, but not before the step begins,
also where that lies beyond the reach. The engine hands the latch the seam's distance before the
gore with every update while the step's exit is not yet held, so the close view, the card's row and
the lit lane of the app's own lanes begin together. A run-up the seam began says `seam` in
`lane_row`'s `start`; one that an edge point, a window or a resume would begin at the same update
keeps that reason. A seam nearer the gore than the latch's own begin changes nothing. A new route
within 3 s of a run-up's end, as after a reroute beyond the reach, may also find the exit as far
ahead as the seam that run-up began with: the run-up goes on, and does not wait for the new route's
plan. Only where that run-up ended with its gore still ahead: not after its gore, nor on the ramp
it held through after it, nor after a seek or a new drive. On invented approaches the seam begins a
run-up from not at all to about a kilometre earlier; where an edge point within the reach begins it,
nothing changes.

**How long it holds.** Whatever the speed does, until the exit is no longer among the points ahead:
at the gore, when the step changes; with a new route, whose points have new keys; off the route,
where there are none. Each of these keeps the time it ended, for the 3 s above. A seek of a
simulated drive ends a run-up at the seek, so a seek that lands on the same approach takes it up
again; a new drive and the end of guidance forget it.

**Through the ramp (the ramp bridge).** Past the gore the latch holds on through the ramp where the
lane plan constrains the next point there, a fork or a split with lane data or a ramp step's
maneuver it names a lane for, and that point lies within 15 s of driving at the ramp's planning
speed (`rampBridgeSeconds`; the engine hands its distance after the gore with every update). It
holds until the car is at that point, whatever the speed does, and ends before it on a new route,
off the route and at a seek, each keeping the time it ended as above; an exit or fork that counts
and lies ahead within reach takes over at once with its own run-up. On the ramp the card shows that
point's row, the split's, from the gore on, and `runUp` stays the exit whose gore the car passed.

**One latch for the card and both cameras.** The engine's lane choices hold the one latch and move
it at most once for each update of the route progress, whoever asks first; the map's cameras ask
the engine for the run-up's exit with the same update. So the card and the view never disagree. A
camera made anew in the middle of a run-up, as after a change of camera style, holds the view at
once; its lane focus still goes in over its 6 s from where it starts.

**The close view on a run-up** (`LaneFocus`). Between the points on the way the exit holds the view,
so it does not go out and in again. Past the gore, where the latch holds on through the ramp, the
point it holds to holds the view as a lane choice ahead does, and the hold below is measured from
that point; a camera made anew on the ramp comes in at once. Past the gore, or past that point, the
view is held 2 s and 30 m, as after any lane choice, then goes out unless a lane choice of the next
step is within 10 s of driving of its window, a run-up takes over, or a turn follows right away:

- **A turn right after the exit** (`TurnAhead`). When the hold is over and the next maneuver is a
  turn the camera frames as a junction, whose framing (from 180 m before it) begins within 13.5 s
  of driving (the tilt's 3.5 s descent plus the 10 s above), the view stays in until that framing
  begins, and the junction view takes over along its own curve. It ends there, at a new step or
  route, or off the route, never past the turn. A turn farther on, a fork of the ramp (which the
  SDK does not frame) or a ramp that splits in two lanes (no lane choice) is not bridged this way:
  the view goes out after its 2 s, unless the ramp bridge above holds it to such a fork.

**The tilt and the road ahead.** With Large, Smooth follow tilts the close view up to **55°** along
the lane focus's own 6 s curve (`guidancePitch`), so the zoom and the tilt move together; over a
junction the view still goes down to 0°, which wins. The owner chose this on 2026-11-11, over the
tilt of 45° with the same road ahead and lanes a third to a half narrower. Smooth follow and Mapbox
tuned with Large also never take the lanes closer than the zoom that still shows **12 s** of road
above the car at the highest speed of the last 10 s (`LookAhead`, `PeakSpeed`): when the car speeds
up the view goes out at once, and it comes in again only once the speed has stayed lower for 10 s.
Without a speed, before the padding is set and before the map is laid out, there is no such floor.

The road ahead comes from a perspective model of the map's camera: a vertical field of view of
36.87°, the camera 1.5 map heights from the screen, the car on the bottom edge of the padding, and
the road counted up to its top edge, under the card. It is a model: the native camera is not
published, and on a drive the camera line's `aheadM` and a screenshot check it. On a map 800 dp tall
(2,400 px at 3 px a dp) with a lane row in the card (padding 838 px at the top, 459 px at the
bottom), with the lanes counted in levels from the zoom where a lane is 14 dp:

| Speed | Smooth follow at 55°: lane at the car | Road to the padding's top | Road to the card's edge | Mapbox tuned at 45°: lane at the car |
|---|---|---|---|---|
| 25 m/s (90 km/h) | 14.0 dp, 0 levels (no floor) | 12.0 s | 17.0 s | 9.2 dp, -0.60 levels |
| 29 m/s (104 km/h) | 12.1 dp, -0.21 levels | 12.0 s | 16.9 s | 8.0 dp, -0.81 levels |
| 33 m/s (119 km/h) | 10.6 dp, -0.39 levels | 12.0 s | 16.9 s | 7.0 dp, -1.00 levels |

At 45° and 14 dp the same screen showed about 8 s of road at 25 m/s. Metres, seconds and lane
widths are the same at every latitude; only the zoom number moves with it, which is why the table
counts levels from the 14 dp zoom. At 55° the screen's top row lies about 1.3 km ahead, under the
card, against about 0.5 km at 45°, and more map is drawn: more data and frame time, but no new bill
([No new tiles](#no-new-tiles)). At 60° it would be several kilometres, which is why 55° and no
more.

**Mapbox tuned** keeps the SDK's tilt of 45° and gets the same floor, worked out at that tilt with
the same peak speed, so its lanes get narrower at speed (the last column). **Default** and **Mapbox
standard** change nothing in the camera: no tilt, no floor and no hold that moves anything, the
seam's and the ramp's included. The card's run-up row and its words, the seam and the split's row
on a ramp come with every camera style, either lane zoom and every choice of
[Lanes on the map](#lanes-on-the-map), as the card's lane rows did before.

#### The card's row on a run-up

`CardLanes` decides in one place which lane row the card draws; the card, the `lane_row` line of the
debug log and every later reader take it from there.

| Stretch of the step | Row | Lit | Beside the lanes |
|---|---|---|---|
| Up to the run-up's start | the exit's own lanes, as the navigation SDK reads them | the active lanes; a lane for car pools or buses only where no other lane is active | nothing |
| Outside a run-up: a lane choice where the route goes straight on, from its window until passed | its lanes | every lane the route can be followed from | its distance |
| From the run-up's start to the last edge point | the next edge point's lanes, every lane, car pool lanes dim, a lane still to come as an outline | one lane: the lane the lane plan names at that point, which is the composed lane (below) where the data leaves no doubt | its words where the counts leave no doubt and the plan lets them show (below) |
| From there to the gore | the exit's own lanes, every lane, car pool lanes dim, a lane without arrows drawn straight on, a lane still to come as an outline | the lanes of the plan that still work there, which are those the route can be followed from, narrowed to those it prefers where it prefers any | their words where they have some and the plan lets them show |
| Past the gore, on a ramp the run-up holds through | the row of the point it holds to, the split's | as above, from the plan | as above |
| Past the gore, otherwise | the next step's rows | | |

**The composed lane** (`RunUpRule`). Counting the lanes open to every car from the exit's edge (0 is
the lane at that edge), the lane at each edge point that leads through every point after it to the
exit, worked out backward from the exit:

- Points no more than 100 m apart with the same lanes are one point: one junction often comes as
  several points. A lane counts as usable there when it is usable at each of them.
- At each point, the lanes that leave at the exit's edge push the lane to take that many lanes in,
  and so do lanes for car pools that begin at that edge before the next point.
- Where the counts cannot say where lanes went, the careful reading: lanes added between two edge
  points are taken to be on the far side, and lanes gone between them are taken at the exit's edge.
- The lane stays among the point's open lanes, on a lane that can be used there.

With data that leaves no doubt, the lit lane is the same lane on the road from row to row, its count
from the edge falling by the lanes that left, and its words change with it. Within a run-up the lit
lane never moves away from the exit's side, except where the data contradicts itself; such rows have
no words. What it does not promise: the run-up's first row can light a lane farther from the exit's
side than the row before it, which is the exit's own lanes, where the exit leaves rather than where
to drive now. Since an edge point ahead begins the run-up at the reach, that switch comes at least
a minute of driving plus the window before the exit, never in its last part.

**The words** (`LaneTarget`, and `laneWordsRes` in the app):

| The lanes to use | In the row | What the screen reader says |
|---|---|---|
| the lane at the right edge | Right lane | Use the right lane |
| the two at the right edge | 2 right | Use the 2 right lanes |
| the three at the right edge | 3 right | Use the 3 right lanes |
| one lane, the second from the right | 2nd from right | Use the second lane from the right |
| one lane, the third from the right | 3rd from right | Use the third lane from the right |
| the same at the left edge | Left lane, 2 left, 3 left, 2nd from left, 3rd from left | the same with left |

Lanes are counted among the lanes open to every car, so a lane for car pools is never "the left
lane". The row never says "keep right", never "any lane but", and never names the far side. A lane
for car pools or buses is drawn dim and is not lit while another lane can be used; where the route
marks only such lanes, they keep their arrow lit, so the row is never empty. **Nothing is spoken:**
asked on 2026-11-11 whether the lanes should be said aloud, the owner answered no. The words are on
the card and for the screen reader only, and the app makes no sound.

The row has **no words** where the exit has no lane data or its side cannot be told; where the lanes
to use are not side by side, or are every open lane; for a single lane three or more lanes in, or
four lanes or more; where the composed lane needed the careful reading; and while a road without
lane data leaves on the exit's side before the exit, since such an earlier exit may take the lane
at that side with it. It may still light lanes then. Outside a run-up, on a pending card and off the
route there are none either.

**From the lane plan** ([DECISIONS.md](DECISIONS.md), 53). With every choice of Lanes on the map the
row reads the route's lane plan: the rule above still composes the row and its words, and the plan
lights them. Where the rule lights one lane, the lit lane is the lane the plan names at the row's
point; where it lights the exit's lanes, they are the plan's lanes that still work there. A lane of
the row's point that the map would not draw at the car yet, a lane on the exit's side that the
plan does not name and that opens only ahead, is drawn hollow, as an outline in the colour of the
lanes not to take: never lit and never with the bar, taking a lane's place like any other, so the
row keeps its width and height. The words are the rule's, counted without outlines, and show only
where the rule's reading leaves no doubt, the plan lights the rule's own lanes, and:

- the plan names a lane at the row's point and where the car is: before an exit whose lanes lie
  beyond a lane for car pools or buses it names none, so such a row has no words (`not_named`; the
  interim card still said "Left lane" there);
- no road without lane data leaves on the exit's side ahead on the car's step, by the plan's
  reading (`unknown_ahead`). Before such a road the plan keeps the lit lane one lane in from the
  edge and moves it to the edge once past it; where the road comes less than about 12 s of driving
  before the exit the lane reaches the edge before it, and the words wait until it is passed (the
  owner's answer of 2026-11-11);
- no lane on the exit's side opens or may begin between the car and the row's point, also one that
  is gone again before it, and the lit lane counts the same from the exit's edge at the car as at
  that point, every lane the map draws at the car counted, also one that ends before the point
  (`opens_ahead`): the words of a row whose exit-only lane is still to come wait until it is there,
  and those of a point ahead wait while a lane beside the lit one still ends on the way. A lane in
  its last 5 m, as the one that left at the point just passed, no longer counts.

While a new route's plan is on its way the row is the rule's, without words; where no plan could be
made it is the rule's row as before. A failure of the reading costs it for the drive and writes one
`error` line, never the drive.

The words take up to 45 % of the row (a distance keeps 30 %) and show whole or not at all: measured
at their smallest size, 10 sp at the phone's font scale, words wider than their share are left out,
never cut, and the lanes take the row; the screen reader still says them. The row is as high with
the words as without them, so the card does not change height when they come or go.

**The route line.** With **Drawn by the app** the line is the app's own, on the plan's path: **the
line is in the lane the card names**, and close in the lit lane takes its place
([The app's own lanes](#the-apps-own-lanes)). With Off and with Mapbox's lane details the route
line's look does not change: its widths, also with Mapbox's lane details
([Lanes on the map](#lanes-on-the-map)), its colours, the maneuver arrow and the order of the layers
are as before, and nothing fades, thins or hides it. It still follows the road's centre line, which
on a freeway runs along one of its lanes, and at the gore it still cuts across every lane to the
ramp. A run-up holds the close view, with its line about a lane wide, for up to about 85 s, while
the card says for example "Right lane" for the last kilometre and more. **There the lane the route
line lies in means nothing; the card's words are the instruction.** The next drive asks at each
exit whether the line or the card was followed ([DRIVES.md](DRIVES.md), What the next drive tests).

**In synthetic replays** (one update a second, 30 frames a second; a made-up approach with points
2,000 m, 1,500 m, 1,470 m, 1,460 m, 1,440 m and 400 m before the gore of an exit to the right, five
lanes at each point, an exit that takes the two right lanes of six, and 25 rising to 29 m/s):

- With the code before the run-up: in at 2,125 m, out at 1,349 m, in again at 1,039 m, 72 s close,
  as little as 6.8 s of road ahead in the last 600 m, and six changes of the card's row.
- With the run-up: in once at 2,125 m and out 2 s after the gore (its ramp has no split), about 70
  to 100 m past it, 84 s close, at least 12 s of road ahead, lanes of 12.2 to 12.9 dp at the car in
  the last 600 m. Where the right lane leaves at the first point, and the right and the left lane at
  the next points, the card shows the first point's row with the middle lane lit and no words (a
  lane is added between the two points, and its side is not known), "2nd from right" from 2,000 m
  and "2 right" from about 1,425 m; where the lanes leave on the left instead, one row, "2 right",
  from 2,125 m to the gore.
- At 15 m/s: in at 1,900 m, 130 s close. With Default: the road's zoom throughout.
- Two exits close together, the second taken, at 27 m/s: in at the reach, about 2,290 m before the
  second, since the first one's gore ahead is an edge point; "2nd from right", then "Right lane".
- A left turn 400 m after the gore at 27 m/s: held to about 224 m past the gore, where the turn's
  framing begins; without the bridge the view would go out about 89 m past it.
- A split 3,000 m before an exit: two approaches, the split beyond the reach.
- A new route with the same exit at 1,300 m or 1,100 m, a seek at 1,300 m, a camera made anew at
  1,300 m and two updates off the route: no gap. Six updates off the route before the new route:
  out, and in again at the next window.
- A fork with the exit 600 m, 1,000 m or 1,500 m after its gore: one approach; 2,500 m after it:
  two.

**Limits.**

- A seek from outside into the middle of a run-up has no run-up to take up again: past the last edge
  point it begins at the next window, with the exit's own lanes.
- Off the route for longer than the camera's hold before the new route comes: two approaches.
- An update without the banner's maneuver type ends the run-up for that update; it takes it up again
  with the next, and the card shows a lane choice's row for that second where one is in its window.
- Lane choices are read for the current step only, so a run-up split by a straight step, a road that
  only changes its name before the exit, begins on the exit's step.
- Edge points rest on the route's marks of the lanes it can be followed from, which narrow toward an
  exit within about 1 km and are local farther out; how often lanes are added between two edge
  points, and so how often a row has no words, the `lane_row` lines count.

**The lane plan.** For every route a drive uses, once, the engine works out a lane plan, off the
main thread together with the route's `lane_evidence` line, and keeps it in memory with the route,
never stored: the lanes that work at each point of the route and, on the run-ups and the last 180 m
before a street maneuver, the one lane to take, counted from the exit's edge. The plan names a lane
only inside such a window, and never moves it back but where the road leaves no other way. It is
read with every choice of Lanes on the map: the card's run-up row and its words (above), the seam
that begins a run-up, the ramp the latch holds through, and with Drawn by the app the route line's
path and the lit lane ([The app's own lanes](#the-apps-own-lanes)). The debug log gets a
`lane_plan` line for each route and a `lane_agree` line for each row the card shows at a point with
lane data, which compares the card's row, read from the plan with its outlines and words, with the
plan ([DRIVES.md](DRIVES.md), checks 46 to 48).

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
- the frame of a maneuver holds 30 m after the turn, as for Smooth follow;
- on a run-up the same latch as Smooth follow's holds the lane window from the run-up's start to
  the gore, and a turn right after the exit keeps it by the same bridge; near a lane choice the lower
  limit is no closer than the zoom that still shows 12 s of road at the SDK's tilt of 45°, at the
  highest speed of the last 10 s ([The run-up to a freeway exit](#the-run-up-to-a-freeway-exit)).

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

### Lanes on the map

**Settings → Guidance → Lanes on the map** has three choices, stored under `lanes_on_map` as `off`,
`mapbox` and `app`; a missing or unknown value reads as Off. The reasons are in
[DECISIONS.md](DECISIONS.md), 39.

- **Off:** no lane lines on the map.
- **Mapbox's lane details:** Mapbox Standard's own, below, as the earlier switch had them.
- **Drawn by the app:** on freeways with lane data, during guidance, the app's own lanes under its
  own route line, the line in the lane the card names, that lane lit close in, and the car as a bar
  across the road over drawn lanes in a close view
  ([The app's own lanes](#the-apps-own-lanes)). Mapbox's lane details are off meanwhile, but for the
  measuring drive's one check ([Measuring the drawn lanes](#measuring-the-drawn-lanes)).

A change applies at once, also during a drive, and touches nothing of the drive itself: no new
route and no trip session. The card's lane row and its words, the run-up with its seam and the ramp
it holds through are the card's and the camera's, the same with every choice.

The switch **Lane details on the map** of earlier versions, stored under `lane_details`, is carried
over once: on becomes Mapbox's lane details and off becomes Off. The new choice is written first and
the old key removed after it, so a phone that stops between the two writes carries it over again at
its next start. The Debug switch **Draw the app's lanes** of the test builds before, which Drawn by
the app replaces, is never carried over: its stored value goes with the carry-over's second write or
with the next choice. With nothing stored nothing is written, and a phone that never chose starts
with Off. The owner answered yes on 2026-11-11 to Drawn by the app as what a phone starts with,
after the owner's own checks: the previews, a simulated drive and a real drive with the measuring
switch on; a later step makes it the default, for phones that never chose too.

With Drawn by the app, Settings says under the camera style that the close view shows the lit lane
in the route line's place, and that in Mapbox tuned, and above about 75 mph, the route line stays
over it instead, in the lane to take.

#### Mapbox's lane details

They are Mapbox Standard's lane details (the style setting `showHdRoads`): lane lines, the arrows
painted in each lane, stop lines, crosswalks and the road surface, where Mapbox has them, which is
on major highways and in some cities. They are drawn from about zoom 16, so with Default they are
small and with Large they are large at every close view. Outside those areas the map is as before.

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
- **The route line stays thin close in** while they are chosen (`RouteLineWidths`). The SDK's line
  grows with the zoom: at the junction zoom its casing is about as wide as a lane, and over lane
  markings it would hide a lane and look like a claim about which lane to take. With them, the line
  and its casing keep the widths they have at zoom 16.5 for every closer zoom, about 10 and 15.5 dp,
  and the routes on the map are drawn again. Up to 16.5 nothing changes.
- **Not checked on a device**, which is why they are off unless chosen: whether the route line and
  the maneuver arrow, which have no height, hide under a bridge that lane details draw in 3D (the
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

So nothing moves the car's icon sideways, with any choice of Lanes on the map. An icon drawn in the
lane the route needs would show the driver in the right lane whether they are in it or not, and a
driver who sees that does not change lanes. With Mapbox's lane details, the icon will often sit on
a middle lane or on a lane line; that is its place on the road, not a lane, and Settings says so.
With Drawn by the app, over drawn lanes in a close view the car is a bar across the whole road at
its place along it, which says nothing about the lane; elsewhere it is the usual dot. The owner
answered yes to the bar on 2026-11-11.

What the owner gets in its place:

1. **Large:** the junction fills the map with a lane about 4.4 mm wide, and before a lane choice
   on a freeway a lane is about 2.2 mm wide.
2. **Lanes on the map:** Mapbox's lane details where Mapbox has them, with the arrows painted in
   each lane, or the app's own lanes with the route line in the lane the card names.
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

### The app's own route line, behind a Debug switch

**Settings → Debug → App's own route line** is a switch, stored under `own_route_line` and off
unless turned on. With it off, and Lanes on the map at Off or Mapbox's lane details, nothing
changes: the route line is the navigation SDK's, with the widths, colours, other routes, arrow and
layer order described above. With it on, during guidance, the app draws the route line itself
(`OwnRouteLine`), the line its own lanes run in, here without them: the switch is there to try the
line with the other two choices. With Drawn by the app the line is the app's whatever the switch
says ([The app's own lanes](#the-apps-own-lanes)). Before Start, in the preview, nothing changes.

- **Where the line runs.** Along the route's path (`RoutePath`), built from the route's lane plan
  ([The run-up to a freeway exit](#the-run-up-to-a-freeway-exit), the plan in the log): on the
  road's line where the plan has no lanes to draw, which is most of a route and every street for
  now; on a freeway with lane data, on the middle of the lanes that work outside the run-ups, and
  on the run-up in the lane the plan names, moving into it with a gentle S of 6 s of driving per
  lane (80 to 270 m), only toward the lane the plan heads for and never back, and at the gore along
  that lane onto the ramp instead of across the gore. Where no lane can be named before an exit (its
  lanes lie beyond a lane kept for car pools or buses), the line keeps to the road's line to the gore
  and, onto a ramp with lane data, until the ramp's lanes lie on the ramp, and moves into the ramp's
  lane only from there. A move out of a lane that leaves by a road at the edge it moves from, with no
  taper, is done by where that lane ends (but takes at least 15 m a lane), so the line never runs off
  the lanes there. Where the road's line bends on an arc tighter than the line's distance from it (a
  bend of a few degrees between two close points of the road's line), the line keeps its lane and
  turns where it would cross itself; only around a corner of more than 40 degrees does it keep to
  the road's line there, blending to it and back over 60 m on each side. A move still under way
  where the drawn lanes end, as when the plan names the exit's lane only shortly before the gore,
  ends with them: at the gore the line goes on straight from where it is onto the ramp's line, and
  where the lane data stops it goes back to the road's line from where it is. The lanes' places are
  rules (3.66 m lanes, laid out around the road's line), not the paint on the road. Those rules can
  lay the drawn carriageway off the road's line, as where lanes leave on one side and join on the
  other; where no lane can be named before an exit, and over the stretch before it where the line
  heads for the road's line, the drawn carriageway moves sideways, by the least that puts the
  road's line at least half a lane inside its edge lines, no faster than the line moves across a
  lane and smoothed over 40 m, and holds there to the gore, so the line runs on the drawn asphalt
  and never along or beside an edge line (`CrossSection.Shift`). Away from lanes
  the line lies exactly on the SDK's. The car is where it always is: nothing moves its icon sideways
  ([DECISIONS.md](DECISIONS.md), 39).
- **How it looks.** Today's line and casing: the SDK's widths up to zoom 16.5 and from there on in
  the widths of 16.5, about 10.4 and 15.5 dp, as with lane details on (`RouteLineWidths`), never
  narrower; the route line's colours of the look (`MapPalette.routeLine`), with the traffic of the
  route's congestion in the SDK's ranges (low 0 to 39, moderate 40 to 59, heavy 60 to 79, severe 80
  to 100) and closures; emissive strength 1 and not faded behind 3D buildings, as the SDK's. The
  SDK's other routes and its maneuver arrow are drawn as before. The own layers lie in the style's
  middle slot right above the SDK's route line and below the arrow, placed above the SDK's top
  layer each time they are added; the `map_layers` line checks the order. Whether slots inside
  Standard's middle slot would hold the order by themselves is not known, so they are not used.
- **The SDK's line under it.** While the own line is drawn, the SDK's primary line is hidden
  (`hidePrimaryRoute`), and it stays hidden across the SDK's own redraws until it is shown again.
  The SDK still gets every route and every progress update, as without the switch, so its hidden
  line is always trimmed to the car and showing it again is right at once.
- **The trim.** The part behind the car is cut off the line with `line-trim-offset`, set with the
  same puck update as the SDK's own line, at most every 62.5 ms: the SDK's vanishing point
  (`getVanishPointOffset`), taken onto the own path through its station table (`StationTable`), and
  only through the table of the route the SDK's point belongs to. The table is in Web Mercator for a
  reason: a line's progress, its trims and its colour stops are fractions of its length as measured
  on the Web Mercator plane, and the SDK measures its vanishing point the same way; a fraction of
  metres would put the cut tens of metres off on a long north-south route (58 m on 333 km north of
  the equator). Where the SDK skips its point while the navigator follows the car on the route (the
  puck more than 10 m from its line around the progress's index, or that index older than 1.5 s),
  the app's own projection of the puck onto the route's line stands in (`PuckProjection`: within
  30 m of the line, from 100 m behind the last trim to 1 km ahead of it).
- **A change of route.** At Start, a reroute, another route taken and a style load (the map made
  anew, as a token switch does), the SDK's line is shown first, already trimmed, and the own layers
  hidden. The new path is built off the main thread once the route's lane plan is there or could not
  be made, and sent to the own GeoJSON source with a data id of its own. Only when the map says that
  data has loaded are the path's table, colours and trim taken up together, the own layers shown and
  the SDK's line hidden again. In between the driver sees the SDK's line on the new route, never the
  old path. At the end of guidance the SDK's line is shown and the own source cleared.
- **The watchdog.** A loading error of the own source, a call the style refuses or a path that
  cannot be built, and no line data while guidance runs (the own layers gone from the style, or a
  path not loaded within 10 s) give the line back to the SDK at once: the own drawing is taken away,
  one `route_line` line says why, and that route is not tried again; the next route is. A failure
  costs the own line, never the drive.
- **Its cost.** The path, its table and its traffic are built once for each route on the map's own
  worker thread; a traffic refresh changes only the line's colours (no new GeoJSON). A puck update
  adds one call to the SDK, one lookup in the table and the trim of two layers on the main thread
  (budget under 0.5 ms, the camera line's `routeLine.trimMaxMs`); nothing runs per display frame.
  The thread that parses GeoJSON is shared with the SDK's line and arrow, so `route_line` logs how
  long the own source took from set to loaded.
- **Not checked on a device:** the frame cost (the camera line's `render` and `renderFps` against a
  drive with the switch off); whether the SDK's hidden line keeps its hidden state across a style
  load (so it is shown at every style load until the own line has loaded again); and how long a long
  route's path takes to load while the SDK's own line loads on the same thread (`loadMs`).

### The app's own lanes

**Settings → Guidance → Lanes on the map → Drawn by the app** ([Lanes on the map](#lanes-on-the-map)).
With any other choice nothing here runs. With it, during guidance, the app draws freeway lanes
itself under the route line, from the route's lane plan, with the app's own route line, whatever
that line's own Debug switch says, and draws the car. Before Start nothing changes, and streets
wait for a later step. The reasons are in [DECISIONS.md](DECISIONS.md), 52 and 53.

- **What is drawn** (`LaneDrawing`, `LaneLayers`): asphalt with an apron, white broken and solid lane
  lines, edge lines (yellow on the left), dotted lines beside exit-only lanes, car-pool lanes with
  their wide line and diamonds, lane arrows before lane points, and at a gore the old road's lanes
  going on for a stub and tapering away, with the gore's two channelizing lines to the nose; in the
  run-up to an exit, the lane the plan names lit in amber with edges in the route's casing colour,
  and the lanes that still work beside it tinted, each tint ending in a wedge toward the lit lane
  where its lane stops working. Lane lines only where the route's lane points give the count: a
  zone, where the count changes and nothing tells how, or a gap in the lane data between two drawn
  stretches inside a card window, is asphalt without lane lines, with the named lane lit through it;
  a lane on the exit's side that may already be there before the point that first lists it is a
  possible lane, asphalt with no line between it and its neighbour and no arrow, never lit or
  tinted. Widths are the ground's, so the drawing grows with the zoom (the table below). Both looks
  have their own colours (`MapPalette.lanes`), all with emissive strength 1; nothing of the drawing
  is faded behind 3D buildings, and nothing is drawn over the Mapbox logo or the attribution. Where
  no lane can be named before an exit and the line keeps to the road's line to the gore, the route's
  line runs across the lanes there, which go on straight: from where it turns off at the gore to
  where it moves into the ramp's lane, the asphalt there, the stub and the lanes beyond the gore are
  drawn without any line, arrow, diamond, lit lane or tint (`RoutePath.Diagonal`).

What shows at which zoom, at latitude 0, with a drawn lane 3.66 m wide (farther from the equator
every zoom is lower by log2(1 / cos latitude), 0.29 at 35°, at the same lane widths on the screen):

| Zoom | A lane on the screen | What shows |
|---|---|---|
| below 15.5 | under 2.2 dp | the map as without the drawing, and the route line on the plan's path |
| 15.5 to 16.5 | 2.2 to 4.3 dp | the asphalt and its lines, small, the route line above them |
| 16.5 to 16.85 | 4.3 to 5.5 dp | also the lane arrows and the car-pool diamonds |
| from 16.85, before the swap | from 5.5 dp | also, inside a card window, the lit lane under the opaque route line, which hides it, and the tints beside the line, which show |
| the swap (a state of the camera, below) | 10.4 dp and more, from zoom 17.76; back under 9.6 dp, below 17.65 | with the close view or the junction view latched, the lit lane in the route line's place over the lit stretches, as a step; the line shows where no lane is lit |

Default's 16.35 is a lane of about 3.9 dp: the drawing small, under the line. Where Large's close
views rest is in the swap below.

- **Layer order** in the style's middle slot, from the bottom: Standard's roads; the asphalt, tints,
  lit lane and its edges, the eraser, the lane lines and the diamonds; the SDK's route line (its
  primary hidden) and the app's route line in two pairs (one over the lit stretches, one over the
  rest, each transparent where the other draws); the lane arrows and their edge; the SDK's maneuver
  arrow. Each layer is placed against its neighbour when it is added (`LaneLayers.PLACEMENT`, chained
  positions; slots inside the middle slot are built but not used until seen to work on a phone); the
  `map_layers` line checks the order. The car's own layer lies above all of them, on top of the
  style.
- **Windows** (`DrawingWindows`): the map holds a window of the drawing around the car, from 50 m
  behind it to 300 m past the next card window (the run-up to a maneuver), at most 3 km unless a
  card window has to be taken whole. Card windows that begin less than 500 m after the end of the
  one before are taken together, so the window held in the first reaches 300 m past the last of
  them. The first window is built with the route's path and sent with it, and the line is drawn once
  both have loaded; the next is built off the main thread and sent 1 km before the held one ends, or
  as soon as it can take the next card window whole, never inside a card window and never while a
  new route loads. A 3 km window is about 4 to 90 KB of GeoJSON and up to about 3,000 coordinates
  (design budget 600 KB and 20,000).
- **The lit lane and the eraser.** The lit lane runs along the route's path, a lane wide, from the
  run-up's start to the gore and on onto the ramp where the ramp has lanes. It is drawn only where
  it is the lane the plan names: where the line lies within half a lane of that lane's middle, or
  moves into it, and the lit lane lies on the lanes drawn there. Where the line waits on the road's
  line beside a lane that is still opening, or keeps to the road's line around a corner too sharp for
  the lanes, the route line shows there instead; through a gentler bend the line keeps its lane and
  the lit lane goes on with it. A line of the road's colour on the route line's own path, trimmed
  with the route line's own trim, covers it behind the car, so the amber ends at the car. The tint
  pieces the car has passed are hidden one station at a time by feature state; a lit piece takes its
  stretch's traffic colour the same way.
- **The swap** (`LaneSwap`): over the lit stretches the route line is drawn whole or not at all.
  While the camera's close view for the lanes or its junction view holds (`CloseView`'s latched
  rules: Smooth follow once the view has reached its goal and no longer moves; never Mapbox tuned,
  whose close views are the navigation SDK's frames) and a drawn lane is at least 10.4 dp wide, the
  pair over the lit stretches gets opacity 0 and the amber lane is the route there; it gets 1 again
  when the view lets go or the lane is under 9.6 dp. Between the two widths nothing changes, and the
  opacity is written only when that state flips, as a step with no transition. At the swap the amber
  is 9.8 dp against the held line's 10.38, and wider at every closer zoom. With Large in Smooth
  follow the swap comes at peak speeds up to about 33 m/s; above, in Mapbox tuned, whose frames may
  hold lanes wider than the swap's at a low peak speed and at a maneuver, and with Default (about
  4 dp) the line stays, opaque, in the named lane.
- **The maneuver arrow** (`GoreArrow`): where the next maneuver leaves at a gore of the drawing, the
  SDK's arrow is given the path's points from 12 m before the gore to 30 m past it, as the U-turn
  arrow is, so it runs along the lit lane onto the ramp rather than across the lanes. It is set
  again when the app's line comes or goes; without it, and with another choice of Lanes on the map,
  the arrow is the SDK's, as it is at a gore the line reaches on the road's line, where both follow
  that line.
- **A change of route, a style load, a look switch.** At a new route the SDK's line shows and the lit
  layers are hidden until the new path and its first window have loaded; a style load sends the held
  window again with the path; a look switch colours the lanes and both pairs of the line in place.
  At guidance end the drawing's source is cleared. The watchdog of the app's route line also counts
  the lanes' layers, and a window that does not load in the time a path has.
- **Its cost.** The drawing, its windows' features and the arrows are built off the main thread. A
  progress update looks up the swap's state, the arrow and the next window, and sets at most a few
  feature states; a camera change computes the drawn lane's width and writes the opacity only on a
  flip. The `lane_drawing` line gives each window's size, build and load times.
- **The car** (`CarLayer`, `CarBar`). From Start to the end of guidance the location component's
  puck is invisible: set once at Start, or once as a map is made during such a drive, and back at
  guidance end. In between no setting of the location component changes, whatever the camera style,
  the lane zoom or anything else does, since every change rebuilds the puck and cancels its glide;
  the camera still follows that puck, which glides on unseen. The app draws the car on a
  location-indicator layer of its own (`custommaps-car`), on top of everything, moved every frame
  from the puck's animated position: two property sets a frame, its place and its bearing. Over the
  drawn lanes, while the lit lane shows in the route line's place, the car is a **bar across the
  road** at its place along the route, from edge line to edge line of the drawn lanes, 2 m deep and
  square to them, in the puck's blue with a white rim and a short forward tick on each outer apron.
  It is placed by its station along the route, never by the matched point beside it, so at a gore it
  never travels the route's diagonal across the lanes. It says where the car is along the road and
  nothing of its lane. Elsewhere the car is the usual dot, with the puck's own images and bearing.
  The bar's image is made again only where the road's width has changed by 0.5 m; its size is a
  zoom expression that keeps it at its ground size. While the map is off the screen or put away for
  the list nothing is set or drawn, and what became due is done when it is back. The owner answered
  yes to the bar on 2026-11-11.
- **The car's watchdog** (`CarLayerWatch`): where a fix the puck was given has had no position after
  it for a second, while the layer draws the car, the map is on screen and drawing frames, nothing
  covers it and the car moves faster than 2 m/s, the car goes back to Mapbox's puck for the rest of
  the drive: the puck visible again, the only setting change of the drive, the own layer gone, and
  one `car_layer` line with `carLayerFallback` `no_position`. A style that refuses a call costs the
  own car the same way, with one `error` line (`exception`). Measured from the fixes, it holds with
  Android's animations off too, where the puck jumps to each fix. The next drive tries again. Each
  change of who draws the car writes a `car_layer` line ([DRIVES.md](DRIVES.md)). The car's map does
  all of this on its own, with its own camera.
- **Mapbox's lane details under the drawing.** They are off while the app draws, as two lane truths
  would disagree by up to a lane, but for the measuring drive's check
  ([Measuring the drawn lanes](#measuring-the-drawn-lanes)): whether they draw below the app's
  asphalt where the app draws, show where it does not, and how the seams at the drawing's ends look.
  If they do, a later step keeps them on under the drawing for every drive
  (`LanesOnMap.DETAILS_UNDER_DRAWING`), set once a drive and never per window, since setting them
  fades them.
- **Lane counts between lane points** (`HorizonLanes`, `HorizonEvidence`), with **Settings →
  Guidance → Lane counts between lane points** on, as it is unless turned off: during guidance the
  app listens to Mapbox's electronic horizon, the navigator's reading of the road about 3.5 km
  ahead, at most once a second, each edge with its count of lanes where Mapbox has one
  ([DECISIONS.md](DECISIONS.md), 54). Between two lane points in a row on one freeway carriageway,
  where the route does not turn and every edge between them is a motorway with a count and no
  ramp, the count of the edge that ends at each point (within 20 m) must be that point's list,
  every lane of it or the lanes open to every car; under neither reading nothing is taken there.
  Where they agree, a stretch without lane data, or one bridged by a rule, gets lane lines where
  the count is the lanes the plan draws there, with no lit lane, tint or arrow of its own: the
  lanes keep the identities the plan gives them. A change of the count that the plan does not have
  is a zone ending at the edge end where the count changes (twice the lane's taper and 60 m), and
  where the count is not the plan's lanes, a rule's bridge is a zone too, and a stretch without
  lane data where it lies between drawn stretches and is at most 600 m long, or in a card window.
  A lane the plan adds on the side away from the exit, where the horizon has that one rise by every
  lane it adds (under the reading of the lanes open to every car, never with a lane kept for car
  pools among them), opens at the edge end where the count rises, its taper before it inside the
  zone, instead of late before the point that lists it; a lane on the exit's side and every lane
  that ends stay where the plan has them, so the lit lane counts from the edge as the card does. A
  lane point's own reach stays as the route's list draws it. The counts come only with the next
  window's send, from 40 m past the window on screen on and past a card window there, also between
  two lane points whose first the window on screen holds, with the edges behind the car that the
  horizon's earlier updates gave (kept up to 3.5 km back), never while nothing is drawn at the car
  and never into a window already sent: the window that is due is built off the main thread
  with the route's path built anew past the window on screen, its points up to that window's end
  kept as they are, and the path and the window go to the map together, the line drawn as it is
  until both have loaded; Mapbox's line is not shown for it. Where the newest counts change nothing
  of the drawing the window goes alone. With the switch off, the next windows take nothing new;
  what was sent stays until the next route. A build with the counts that fails costs them for the
  route and one `error` line, never the line. The `lane_drawing` line's `horizonM` and
  `horizonZones` say what they drew in each window ([DRIVES.md](DRIVES.md)).
- **Not checked on a device:** the frame cost (the measuring drive); whether the chained order holds
  on a phone (the `map_layers` line says); how the drawing reads at speed by day and at night;
  whether the puck's position still comes every frame while the puck is invisible (a `car_layer`
  fallback with `no_position` would say it does not), and how the bar lies on a map tilted 55°;
  what the electronic horizon's lane count counts, lanes kept for car pools and one direction or
  both, and how well its edge ends meet the route's lane points ([DRIVES.md](DRIVES.md), checks 70
  and 71).

#### Measuring the drawn lanes

**Settings → Debug → Measure the drawn lanes** is a switch, stored under `measure_own_lanes` and off
unless turned on. It measures what the drawing costs the phone's map in frames, the gate the app's
drawing must pass before it can be what a phone starts with ([DECISIONS.md](DECISIONS.md), 52). It
acts only with Drawn by the app, during guidance, and only on the phone's map; the car's map never
measures. With it on:

- **Every other close view drawn.** The close views of a drive, run-ups and junction views counted
  in turn, alternate: the first with the drawing, the next without it, and so on. Without it the
  lanes and the lit lane are hidden, and the app's route line stays, in the named lane. The drawing
  is shown or hidden when a view ends, never as the next begins: showing or hiding the layers makes
  the map lay out and upload their tiles again, which must not fall into a measured view. A view
  under way when the measuring begins, or still held when a view ended for another reason, is not
  one of its views.
- **Whole views only.** The gate counts a view only when it had frames, began 5 s or more after such
  a change, lasted 3 s or more from the update it began on, and ended as its close view let go
  (`let_go`). Every other view is logged and not counted: a shorter one has too few frames to say
  what the drawing costs, and one that the guidance's end, the switch, Lanes on the map, the
  background, Settings, the list, the camera style or a map made again cut short measured only part
  of its close view, whatever its length.
- **One statistics collection a view.** From the update a view begins on, Mapbox's performance
  statistics run for it, a sample a second, and stop however the view ends: it lets go, guidance
  ends, the switch or Lanes on the map changes, the app goes to the background, Settings covers the
  map, the map is put away for the list, the camera style changes, or the map is made again. A
  view's render p95 and longest frame are worked out over every frame time of all its seconds, the
  camera line's, never from the seconds' own p95s.
- **One `lane_measure` line a view** ([DRIVES.md](DRIVES.md)): whether the drawing showed, its
  seconds, how long it lasted, its frames, render p95 and longest frame, the display frames it
  missed, the largest upload of a frame and the time the renderer spent making graphics programs as
  Mapbox counts it (in the drive's first run-up, whether making the drawing's shaders falls there),
  the drawing windows that loaded in it with their set-to-loaded times and sizes, how many positions
  the car's layer was handed, why it ended and whether it counts.
- **Mapbox's lane details under the drawing, once a drive:** as the measuring begins they go on under
  the drawing for the whole drive, set once, with a `lane_measure` line that names the check
  (`details_under_drawing`), and they go back as Lanes on the map has them when it ends.
- **The gate** (`LaneMeasureGate`), worked out when the drive ends and written into its
  `trip_summary` line as `laneGate`: in one look, day or night, with at least 6 counted views of each
  kind, the drawing views' median render p95 at most 1 ms above the other views', their median of
  display frames missed a second at most 10 % more, and no upload in a drawing view over 8 ms, one
  frame at 120 Hz. `pass`, `fail` where either look fails, or `too_few_views`. The drawing becomes
  the default only after a pass.

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
| A run-up reaches this far beyond the close view's window (`runUpSeconds`, `runUpMinMeters`) | 60 s of driving, at least 1,500 m |
| A run-up that ended this recently is taken up again on its reach alone (`runUpResumeSeconds`) | 3 s |
| Past the gore the run-up holds through the ramp to a point the lane plan constrains this near, at the ramp's planning speed (`rampBridgeSeconds`; `LanePlanner.RAMP_BRIDGE_SECONDS` the same) | 15 s of driving |
| The close view's tilt with Large, Smooth follow (`lanePitch`) | 55° (on the road `cruisePitch`, 45°) |
| Road the lane zoom keeps in view above the car (`laneLookAheadSeconds`), at the highest speed of a time back (`laneCapSpeedSeconds`) | 12 s of driving, at the highest speed of the last 10 s |

Outside `CameraTuning`: a run-up begins no later than 6 s of driving before the lane plan's first
narrowing for a point within its reach (`LanePlanner.SEAM_SECONDS`); the swap of the app's lanes
comes at 10.4 dp and goes under 9.6 dp (`LaneSwap.SWAP_IN_DP`, `SWAP_OUT_DP`); points on a run-up
no more than 100 m apart with the same lanes are one point (`RunUpRule.EDGE_CLUSTER_METERS`), and
the lanes to use in words may take 45 % of the card's
lane row, a distance 30 % (`LANE_WORDS_MAX_WIDTH_SHARE` and `LANE_LABEL_MAX_WIDTH_SHARE` in the
app). The debug log's watch of a diverge runs 30 s or 1,000 m (`DivergeWatch.WATCH_SECONDS`,
`WATCH_METERS`).

## Day and night during guidance

The app is light by day and dark at night ([DECISIONS.md](DECISIONS.md), 48). Under Auto the look
follows the sun, and during guidance a change by the sun, or by a better position after the time
zone's guess, waits for a quiet moment of the drive (`LookSwitch`, `LookTuning` in `:core-nav`):

- **a tunnel** (the navigator's `inTunnel`) holds it for as long as it lasts;
- **a close view** holds it for as long as it lasts (`MapRenderer.closeView`, worked out once per
  progress update): with Smooth follow while the tilt onto a maneuver is on or still moving, and with
  Large also while the lane-choice view or a run-up's is; with Mapbox tuned and Large while its lane
  window holds or the SDK frames a maneuver at Large's zoom; never with Mapbox standard. So a change
  never rebuilds the map's lane markings in the middle of a lane-choice close-up, 2 km before the
  maneuver too;
- **a maneuver** holds it within 300 m or 20 s before it, and for 6 s after the step changes. After
  10 minutes of waiting, counted from the start of the wait with tunnels included, that window
  narrows to 150 m or 10 s before and 3 s after, and stays so, never to nothing: on a drive with a
  turn every 200 m the change comes between two turns, never at one;
- **a car that stands**, below 0.5 m/s, outside a tunnel and a close view, takes it at once, as at a
  red light.

The driver's choice in Settings goes at once, at a turn too, and so does a change while Settings
covers the map, unless the car's screen shows the map then. Each progress update only looks at a
change that waits; the sun is worked out once a minute while the app is on screen, the phone's or
the car's, never on an update.

**The car shows the same look** (stage 2's step 1b, [DECISIONS.md](DECISIONS.md), 50): one look for
the app, decided once for both screens, so a change, and a theme that waits, reach the phone's map
and the car's at the same moment. While the phone's app is not on screen, the car camera's close
view is the one that holds a change.

**The camera is never moved by a change of look.** It changes colours only, not the camera's frame,
zoom, tilt or padding, and the panels keep their places in both looks (the strip above the Mapbox
logo is 12 dp in both).

**The basemap redraws its tiles once, at the theme.** Of the style's settings, the basemap's theme
(`monochrome` at night, `default` by day) is the one write that makes the map rebuild its tiles
(Maps 11.8.0: "Clear tile pyramid on color theme change"); the light preset and the colours change
the tiles in place. The `look_settled` line says how long the map took.

**A theme left for a quiet moment.** Coming back on screen during a drive near a maneuver, in a
tunnel or a close view, the rest of the look goes at once: the screen, the light preset and the
colours, the route line, the compass. Only the theme waits for the next quiet moment, with the same
window and narrowing, and goes at once when guidance ends or Settings covers the map; meanwhile the
map shows the night light on the day theme, or the other way round, dark at once with no rebuild
while the driver looks at the turn. The `look` line says `themeDeferred`, and a `look_theme` line
when the theme went. A map made in the meantime, as when Android makes the screen again, takes the
whole look as it loads. The theme is a config write like the others: no style reload, no trip call,
no route request.

## System animations

- **The Smooth follow camera does not depend on the system animation setting.** Its frames come from
  `Choreographer` callbacks, on the car from the car's frame clock, which the animator duration
  scale does not affect. Tilt, zoom, bearing and padding stay smooth with animations off, and so
  does the centre in junction framing and in the overview.
- **The puck does.** Its glide is a `ValueAnimator` in the Maps SDK's location component; with the
  scale at 0 it jumps once a second. While following, the camera chases the drawn puck, so its
  centre then gets a once-a-second input again: no more cuts, but not continuous either (see
  [the verdict](#the-verdict-on-once-a-second-updates)).
- **The two Mapbox styles depend on it entirely:** their camera is made of animators.
- **On the car** the puck moves on the car's frame clock since stage 2's step 1b, and still follows
  the setting as on or off: with animations off each update is a jump there too.
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

`CameraMotionTest` in `:core-nav` (JUnit 6), 29 tests. Among them:

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
- Smooth follow's tilt is 45°, 55° and 0° at the three corners of the lane and the junction focus,
  and the plain junction tilt with no lane focus.

Beside it: `OncePerUpdateTest` (6 tests: which message of an update computes the frame),
`JumpFrameTest` (4: after a seek, the frame of the new place comes with its progress),
`IdleFollowTest` (13: when the camera follows the car before guidance, and which new position is a
jump) and `GuidancePaddingTest` (7: the top inset that only grows).

For the lane zoom, `LaneZoomTest` in `:core-nav` (60 tests, on example latitudes and made-up
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
- Without a run-up the class is as before; with one, on the synthetic approach of [The run-up to a
  freeway exit](#the-run-up-to-a-freeway-exit), the view is in from the first update with the run-up
  to 2 s and 30 m past the gore with no gap (its ramp has no split; through a ramp the latch holds
  through, to 2 s and 30 m past the split the plan constrains), a lane choice in its window, or held
  after the car braked out of it, holds the view before the exit does, a camera made anew mid-run-up
  holds at once, and a turn whose framing starts 5 s or 13 s after the hold is bridged and one at
  14 s is not, never past the turn. The look-ahead floor caps the lanes, never the junction, never
  below the road.

Beside it, in `:core-nav`: `RunUpLatchTest` (13: the run-up begins by its reach with an edge point
ahead or a choice in its window, holds through a stop and without a speed, ends off the route, on a
new route and at the gore, and is taken up again within 3 s after a new route, a short time off the
route, a seek and a fork's gore, never after a new drive), `LookAheadTest` (10, at latitude 0 at the
zoom of 14 dp lanes: the model's 198 m and 301 m at 45° and 55°, its inverse, at least 12 s at
25, 29 and 33 m/s, no floor where the model has none, the highest speed of the last 10 s),
`RunUpRuleTest` (31: the exit's side, edge points and points taken as one, a lane the route allows
but does not prefer, the composed lane with lanes added, gone and kept for car pools, the clamps,
doubt farther on that takes the words from every point before it, the exit's row and every case
without words, and the synthetic replays' rows), `CardLanesTest` (3), `LaneTargetTest` (4: which
lanes have words), `LaneRowLogTest` (11), `RawOffsetTest` (6) and `DivergeWatchTest` (14: an exit
missed on the synthetic approach, a car that leaves at an edge point, a ramp taken, the limits, the
time the navigator was unsure, a return from off the route, a new route, a seek and the end of
guidance).

In `:engine-mapbox`, on the JVM with the Directions data classes and made-up coordinates:
`LaneChoicesTest` (46 tests: the points ahead with their distances and lane counts, designated
lanes, the maneuver as a lane choice, the card's lanes with every usable lane lit, a step's lanes
read once and none off the route, each point reported to the log once with every lane, the edge
points by the exit's side, the run-up's latch moved once for each update with its rows, and roads
off the exit's side without lane data),
`LaneDetailsTest` (12: nothing is set within the callback of a style load, the setting is made
after it and made again when something changed it, and nothing after the map is gone),
`RouteLineWidthsTest` (9: the held widths, and that the copied stops are the SDK's own) and
`LaneZoomFramesTest` (17: the limits Mapbox tuned gives the data source with Default and with
Large, on the road, in the frame of a maneuver and near a lane choice, also with the floor of 12 s;
the upper limit Smooth follow gives it, raised only with Large in the frame of a maneuver; Smooth
follow's tilt toward the lanes, only with Large while it follows the car; and the rule for when a
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
  seconds and come back. On the freeway it stays far ahead, except on the run-up to the exit: close
  once, from up to about 1.5 km plus 25 s before it where a lane on the exit's side leaves on the
  way (else about 25 s before), tilted up to 55°, and held to the gore; about 25 s before a split
  that is no run-up's, with the same tilt, and back out after it.
- The camera line: `laneDp` about 28 at junctions and 14 in lane windows, `zoomPlan` `ROAD` on
  most seconds, and no `steps.zoom` above about 0.02 outside a change of mode. `centerLeadS` is 0
  on the road between turns and on the open freeway, and above 0 only while the view is closer
  than about zoom 17: up to about 0.25 around a town turn, from about 140 m before it to about
  60 m after it, and about 0.3 in a lane window at freeway speed. The `lane_choice` lines: one for
  each point with lanes, with `skipped` on those the rule passed over. On the run-up the card shows
  one row, with words such as "Right lane", and `runUp` names the exit in the camera line from the
  run-up's start to about 2 s past the gore, or past a split on its ramp that the plan constrains
  within 15 s of it.
- Lane zoom to Default and back during the drive, near a turn and away from one: a glide each
  time, never a cut.
- Mapbox tuned with Large: close at turns too, with its zoom coming back at the step change.
  Mapbox standard: its usual zoom, and `maxZoom` 16.35 in its camera line.
- With **Lanes on the map** at **Mapbox's lane details**, in an area that has them: lane markings
  appear without a fade out and in at the start, `hdRoads` is true in every camera line and
  `hdRoadsReapplied` stays 0, the route line is thinner close in with the markings visible beside
  it, and changing the choice fades them out and in during the drive. Look at a bridge: is the route
  line on it, and where is the car's icon? With **Drawn by the app**, the checks of
  [DRIVES.md](DRIVES.md), the app's own lanes.

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

With Large, and Mapbox's lane details where the area has them, the owner also notes:

| What | Knob |
|---|---|
| Whether the lanes are large enough at turns, or the view too close | `junctionLaneDp`, 24 to 32 dp |
| Whether exits and splits go close early enough | `laneLeadSeconds` and the two window distances |
| An exit where it went close and should not, or did not and should | `laneChoiceMinLanes`, `laneChoiceMinLeaving`, with the `lane_choice` lines |
| Whether the ground moves too fast close in on a freeway | `freewayLaneDp`, 12 to 14 dp |
| Whether the car rides above its usual place close in | `maxPuckLagDp` |
| Whether the close view comes too early or too late before an exit | `runUpSeconds`, `runUpMinMeters` |
| Whether the close view shows enough road ahead, and its tilt | `laneLookAheadSeconds`, `lanePitch` |

Tuning uses the shared debug log.

### Desktop Head Unit (stage 2)

With the 800×480 and 1280×720 profiles, run the simulated drive with the car map showing:

- The car camera follows smoothly with `CameraTuning.CAR`.
- Showing or hiding the action strip does not move the car.
- Car scroll and scale switch to idle, and **Recenter** from the action strip comes back smoothly.
- One fix moves both pucks the same way.

Then lock the phone, and later leave the app with Home: the car's map and its puck keep moving.
`frame_clock` lines say when the car's clock went to its timer and came back, the car's camera lines
keep `camFps` and `camWrites` near 60 (`clock.timerTicks` while on the timer) and a small `puckLagM`,
`render.frames` and `drawn` say whether Mapbox still drew, and `display_state` lines say what the
phone's display did. With the phone app in front the car's `render.drawnFps` is about 30.

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
- With Large, one move in for each run-up: `laneGoal` stays 1 from a run-up's start to its gore.
- With Mapbox's lane details, `hdRoads` equals `laneDetails` in every camera line.

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
3. **Freeways.** The long view ahead stays as it is: no minimum zoom of 14. Since the run-up it
   stays except on the run-up to an exit
   ([The run-up to a freeway exit](#the-run-up-to-a-freeway-exit)).

Answered on 2026-11-11, after the drive with the missed freeway exit:

4. **A spoken lane hint:** no. The lanes to use are words on the card and for the screen reader; the
   app says nothing aloud.
5. **The close view's tilt:** yes to the stronger tilt. With Smooth follow and Large the close view
   before an exit tilts to 55° and keeps at least 12 s of road ahead, with lanes as large as before
   at about 90 km/h. Mapbox tuned keeps the SDK's tilt.
