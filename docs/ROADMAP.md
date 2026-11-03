# Roadmap

Where the project stands. Updated at the end of every stage: what was done, what the owner checks,
and what moved. Decisions and their reasons live in [DECISIONS.md](DECISIONS.md).

| Stage | Goal | Status |
|---|---|---|
| 0 | Project skeleton, CI, token and signing setup | Done (2026-11-03) |
| 1 | Phone: map, location, search, routes with traffic, guidance | In progress |
| 2 | Android Auto: car screen on the navigation template | Planned |
| 3 | Signed release build and Google Play internal testing | Planned |
| 4 | Smart camera: automatic overview and following | Planned |
| 5 | Junction view in the car's routing card (variant A) | Planned |
| 6 | Guidance tones instead of voice | Planned |
| 7 | Split screen with a live maneuver view (variant B) | Planned |

## Stage 0: skeleton (done)

- [x] Modules `:core-nav` (pure Kotlin), `:engine-mapbox` (Mapbox, types stay inside), `:app` (Compose).
- [x] The Mapbox token is entered on the phone, stored only there, never built into any APK.
- [x] Optional shared debug signing key from GitHub Secrets.
- [x] GitHub Actions: build, unit tests, lint, 16 KB check, APK published on every push to `main`.
- [x] README, DECISIONS, local.properties template.

Owner checks: install the APK from Actions, paste the token, see it masked in Settings with the
build number.

## Stage 1: phone (in progress)

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

## Stage 2: Android Auto (planned)

- Car app service (navigation category) and screens on the navigation template.
- Map drawn on the car surface, camera padding from the visible and stable areas.
- Maneuver card with distance and lanes, ETA, action strips, day and night by `isDarkMode`.
- Mapbox logo and © attribution visible on the car map.
- Start a route on the phone and continue in the car: with the car connected, the phone is the
  search input and the route appears in the car.
- Trip session stops when the car disconnects.
- Links shared into the app: Google Maps, Apple Maps and Waze links and `geo:` addresses, from the
  share menu and as the default app for those links (Android settings: **Open by default → Add
  links**). The link becomes a place, and routes to it are offered on the phone and in the car.
  To decide at the start of the stage: short links (`maps.app.goo.gl`, `maps.apple/p`) can only be
  expanded with one request to Google's or Apple's link server; Waze short links decode on the phone.
- Desktop Head Unit profiles for 800×480 and 1280×720 head units.

## Stage 3: release and Google Play (planned)

- Signed release app bundle, versioning, keys from a local file or environment variables.
- Google Play Console: app, internal testing track, install from Play (required for Android Auto in a real car).

## Stages 4 to 7 (planned)

- **4. Smart camera:** overview by default, following before maneuvers (distance threshold from speed, hysteresis, manual touch pauses automation for 20 s), the same on phone and car, settings on the phone, scenario tests.
- **5. Junction view (variant A):** snapshot image of the next complex maneuver in the routing card, highlighted lanes, distance countdown bar.
- **6. Tones:** short own tones at the moments of Mapbox voice instructions, audio focus with ducking; tones, voice or silent.
- **7. Split screen (variant B):** live zoomed maneuver view next to the main map before complex maneuvers; falls back to variant A on small screens.

## Future (not scheduled; the owner decides when)

- **Route preferences:** fastest, eco, fewer stops and traffic lights in the city (to save fuel),
  avoid highways, tolls or ferries, and similar fine-tuning.
- **Standalone phone navigation polish:** using the app as a full navigator without the car,
  including a landscape layout for a phone in a mount (the phone screen is portrait-only for now).
- **Speed cameras:** open data of California cities (AB 645) and OpenStreetMap via Overpass, with ODbL attribution.
- **Favorites:** home and work.
- **Voice input** for the destination.
- **Offline regions.**

## Watch list

- **Sideloading:** Android developer verification will require apps installed outside Google Play to be
  registered to a verified developer. Enforcement starts outside the US in 2026 and worldwide in 2027;
  before that, CI builds keep installing as they do now.
- **Mapbox Navigation SDK v3** is officially not API-stable; Navigation, Maps and Search are upgraded together.
