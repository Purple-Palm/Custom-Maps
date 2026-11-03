# Roadmap

Where the project stands. Updated at the end of every stage: what was done, what the owner checks,
and what moved. Decisions and their reasons live in [DECISIONS.md](DECISIONS.md).

| Stage | Goal | Status |
|---|---|---|
| 0 | Project skeleton, CI, token and signing setup | Done (2026-11-03) |
| 1 | Phone: map, location, search, routes with traffic, guidance | Done (2026-11-03) |
| 1.1 | Guidance polish after the first drives (next build) | Designed; paused at a checkpoint |
| 2 | Android Auto: car screen on the navigation template | Designed; paused at a checkpoint |
| 3 | Signed release build and Google Play internal testing | Planned |
| 4 | Smart camera: automatic overview and following | Planned |
| 5 | 3D navigation and visual polish | Planned |
| 6 | Junction view in the car's routing card (variant A) | Planned |
| 7 | Guidance tones instead of voice | Planned |
| 8 | Split screen with a live maneuver view (variant B) | Planned |

## Stage 0: skeleton (done)

- [x] Modules `:core-nav` (pure Kotlin), `:engine-mapbox` (Mapbox, types stay inside), `:app` (Compose).
- [x] The Mapbox token is entered on the phone, stored only there, never built into any APK.
- [x] Optional shared debug signing key from GitHub Secrets.
- [x] GitHub Actions: build, unit tests, lint, 16 KB check, APK published on every push to `main`.
- [x] README, DECISIONS, local.properties template.

Owner checks: install the APK from Actions, paste the token, see it masked in Settings with the
build number.

## Stage 1: phone (done)

- [x] Domain logic: miles and feet, durations, arrival time; trip session log; guidance state machine
  (a trip session starts only on Start and is stopped before routes are cleared).
- [x] Map in a night Mapbox Standard style with 3D buildings and the graphite and amber palette.
- [x] Current location without starting a trip session.
- [x] Search for addresses and places (Search Box), English.
- [x] Routes with alternatives and traffic colours; tap to pick an alternative.
- [x] Guidance: maneuver card with large distance, lanes, ETA; stop on arrival and on cancel.
- [x] Debug section: trip session starts this month; simulated drive for testing at home.
- [x] Detailed debug log: every route with its roads, every second of guidance, searches and errors;
  shared from Settings as text files, for example to Google Drive.
- [x] Review fixes: one trip session per drive, End Navigation in the trip notification, precise
  location for routes, a message when Mapbox refuses the token, backups limited to the settings and
  the trip log, no search history, portrait phone screen, and the Mapbox logo in a fixed place.

Owner checks: install the APK from Actions over the old one, allow precise location, search, pick a
route, run a simulated drive (Settings → Debug), end it from the app and once from the notification,
see one trip per drive in Settings → Debug, and share the debug log to Google Drive.

## Stage 1.1: guidance polish after the first drives (next build)

The first two drives showed what to fix before the car screen; see [DRIVES.md](DRIVES.md) and
[CAMERA.md](CAMERA.md). Work is paused at a checkpoint until the owner answers the open questions in
[STATUS.md](STATUS.md) and says to continue.

- Camera styles in Settings: **Mapbox standard** (today's camera, unchanged, for comparison),
  **Mapbox tuned** (the same camera with calmer settings) and **Smooth follow** (new, the default).
- Smooth follow: the camera moves on every display frame and glides after the drawn car; the tilt
  goes down onto the junction gradually before a turn and back up after it; the early turn on
  curving roads stays.
- More zoom at turns, so the junction and its lanes are larger (the full junction zoom is stage 4).
- A **Then** chip that slides out under the maneuver card on the approach, with the arrow of the
  maneuver after the next one.
- Routes end where a car stops for the place: Mapbox Search's stop point for cars is the route's
  end, and the place itself is the arrival target, so the arrival names the right side. No more
  routes that end behind a building when Search knows the stop point.
- Start checks how old the routes are and how far the car has moved since they were planned, and
  refreshes them when needed (whether Start waits for the refresh is an open question).
- Diagnostics in the debug log: crashes and earlier exit reasons, device state (screen, power
  saving, heat, network), one camera line a second with the frame rate, repeated map errors folded
  into one line a minute, stop points of search results and GPS fix age.
- Offline: the street map and routing data along the route are downloaded ahead while online, and a
  chip on the map says when the app has no internet.

## Stage 2: Android Auto (designed)

The full design is in [STAGE-2.md](STAGE-2.md).

- Car app service (navigation category) and screens on the navigation template, on Car App Library
  1.8.0-rc01: 1.7.0 lets any app on the phone that holds the template-renderer permission pose as
  Android Auto. Move to 1.8.0 when it is released.
- Map drawn on the car surface, camera padding from the visible and stable areas.
- Maneuver card with distance and lanes, ETA, action strips.
- A bright, colourful map by day and the dark map at night, following the car (`isDarkMode`).
- Turn-by-turn notifications on the car screen while another app is in front.
- Mapbox logo and © attribution visible on the car map.
- Start a route on the phone and continue in the car: with the car connected, the phone is the
  search input and the route appears in the car.
- One drive across phone and car: when the car disconnects mid-drive, guidance continues on the
  phone; when the car connects again, it continues on the car screen. Arrival or End in either
  place ends the drive. If another navigation app starts guiding in the car, this app keeps
  guiding too.
- Location "Allow all the time", asked for after precise location, so Start works from the car with
  the phone locked and the car map shows the position before Start (Google Play needs a declaration
  for it in stage 3).
- If the app stops mid-drive (crash or system kill), the phone and the car offer to resume to the
  same destination.
- Links shared into the app: Google Maps, Apple Maps and Waze links and `geo:` addresses, from the
  share menu and as the default app for those links (Android settings: **Open by default → Add
  links**). The link becomes a place, and routes to it are offered on the phone and in the car.
  Coordinates in the link are used as they are. A Google Maps link with only a name and address
  (what the Google Maps phone app shares) has its place page loaded once to read the exact
  coordinates; this is the owner's choice, on by default and switchable in Settings, and Google's
  terms do not allow it, so it may stop working. The fallback is a Mapbox search near the place
  hint the link carries. A link that arrives during a drive offers to go there
  now, which changes the destination of the running drive.
  Short links (`maps.app.goo.gl`, `maps.apple/p`) are expanded with one request to Google's or
  Apple's link server; Waze short links decode on the phone.
- Debug log upload: each trip's log goes to a private GitHub repository when the trip ends, with a
  fine-grained token (one repository, 366 days, reminder before it expires) that the owner pastes
  on the phone and that stays only there. The public code repository never gets logs.
- Desktop Head Unit profiles for 800×480 and 1280×720 head units.

## Stage 3: release and Google Play (planned)

- Signed release app bundle, versioning, keys from a local file or environment variables.
- Google Play Console: app, internal testing track, install from Play (required for Android Auto in a real car).

## Stage 4: smart camera (planned)

- Overview by default, following before maneuvers (distance threshold from speed, hysteresis, manual
  touch pauses automation for 20 s), the same on phone and car, settings on the phone, scenario tests.
- A strong zoom into the junction before each maneuver, so the lanes fill the screen.

## Stage 5: 3D navigation and visual polish (planned)

Queued right after the smart camera, once guidance is smoother; it builds on that camera work and is
checked on the car screen from stage 2. The bar is Google Maps' immersive navigation: smoother motion,
a cleaner night look and larger, clearer lanes, as far as the public Mapbox SDK allows.

The goal is guidance as beautiful and smooth as possible, on the phone and in the car, with lanes that are
impossible to miss. Mapbox's own "3D Live Navigation" and "Magic Carpet" (a glowing path on the lane to
take) are products for car makers, in private preview and in production only in BMW cars; they need
lane geometry and the car's own cameras, and are not in the public SDK this app uses. This stage builds
what the public SDK allows:

- **3D following view:** a steeper tilt while following, 3D buildings, landmarks and landmark icons
  (trees and building facades behind a detail switch), a 3D car in place of the arrow, a glowing route
  line with traffic colours, and camera, car and route line moving smoothly together.
- **Route visible behind buildings:** the hidden part of the route shows through tall buildings, or the
  buildings over the route fade out.
- **Magic carpet, road level:** a glowing ribbon along the road ahead that brightens before a maneuver.
  It follows the road, not a lane: the public route data says which lanes to use at each junction but
  not where each lane is on the map.
- **Lanes as clear as possible:** larger, high-contrast lane arrows in the maneuver card and in the car's
  routing card, with the lanes to use standing out from a distance.
- **Smoothness and heat:** frame-rate caps, detail stepping down when the phone gets hot, frame times in
  the debug log, and a 3D switch in Settings with today's flat view as the fallback.
- **The car screen:** the same 3D view on Android Auto, measured on the Desktop Head Unit and in the car.

Mapbox logo and attribution rules apply in every view.

## Stages 6 to 8 (planned)

- **6. Junction view (variant A):** snapshot image of the next complex maneuver in the routing card, highlighted lanes, distance countdown bar.
- **7. Tones:** short own tones at the moments of Mapbox voice instructions, audio focus with ducking; tones, voice or silent.
- **8. Split screen (variant B):** live zoomed maneuver view next to the main map before complex maneuvers; falls back to variant A on small screens.

## Future (not scheduled; the owner decides when)

- **Route preferences:** fastest, eco, fewer stops and traffic lights in the city (to save fuel),
  avoid highways, tolls or ferries, and similar fine-tuning.
- **Standalone phone navigation polish:** using the app as a full navigator without the car,
  including a landscape layout for a phone in a mount (the phone screen is portrait-only for now).
- **Speed cameras:** open data of California cities (AB 645) and OpenStreetMap via Overpass, with ODbL attribution.
- **Favorites:** home and work.
- **Voice input** for the destination, including "Hey Google, navigate to …" in the car.
- **Offline regions.**

## Watch list

- **Lane-level magic carpet:** Mapbox 3D Lanes (lane geometry, in private preview since January 2026)
  is promised for mobile apps in 2027. With it, the stage 5 ribbon can move onto the exact lane. Mapbox's
  detailed lane markings in the map (Standard "lane details", HD roads) are off by default and cover
  about 70 major cities; on top of that, Navigation SDK 3.34's camera data source switches them off
  on every style load unless Mapbox's non-public coordination module is attached. To be checked
  with each SDK upgrade.

- **Sideloading:** Android developer verification will require apps installed outside Google Play to be
  registered to a verified developer. Enforcement starts outside the US in 2026 and worldwide in 2027;
  before that, CI builds keep installing as they do now.
- **Mapbox Navigation SDK v3** is officially not API-stable; Navigation, Maps and Search are upgraded together.
