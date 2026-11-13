# Decisions

Short records of non-obvious choices: what was chosen, the alternatives, and why.

## 1. Android Gradle Plugin 9.4.1 with built-in Kotlin

- **Chosen:** AGP 9.4.1, Gradle 9.8.2 and Kotlin 2.4.21. Kotlin is compiled by AGP's built-in support; `org.jetbrains.kotlin.android` is not used.
- **Alternatives:** AGP 9.5.0, released on 2026-10-28, which needs Android Studio Rabbit 2. AGP 8.x with the separate Kotlin plugin.
- **Why:** 9.4.1 opens in Android Studio Quail 4 and newer and matches Google's own project templates (AGP 9.4.1, Kotlin 2.4, Gradle 9.8). AndroidX Core 1.19 and Compose 1.12 require AGP 9.1 or newer and compileSdk 37.
- **Known effect:** on Gradle 9.8 AGP 9.4.1 prints a harmless `Configuration.setVisible` deprecation warning, which AGP 9.5 removes.
- **Moving later:** upgrading to 9.5 is a one-line change. If the combination misbehaves, fall back to AGP 9.3.3 with Gradle 9.7.1.

## 2. Kotlin Gradle plugin pinned in the root plugins block

- **Chosen:** the root build file declares the Kotlin JVM and Compose compiler plugins (2.4.21) with `apply false`. AGP's built-in Kotlin then resolves KGP 2.2.10 → 2.4.21; check with `./gradlew buildEnvironment`.
- **Alternative:** the `buildscript { classpath(...) }` route from the AGP 9.0 release notes.
- **Why:** one mechanism for all modules. Mixing the legacy buildscript classpath with versioned plugin aliases fails.

## 3. SDK levels: compileSdk 37, targetSdk 36, minSdk 26

- **compileSdk 37:** required by AndroidX Core 1.19 and Compose 1.12.
- **targetSdk 36:** what Google Play requires for new apps since 2026-08-31. Targeting 37 would opt into Android 17 behaviour changes for no current benefit.
- **minSdk 26 (Android 8.0):** covers every phone that runs current Android Auto; `java.time` and notification channels are available.

## 4. Bytecode levels and the Java API used by `:core-nav`

- **Chosen:**
  - Gradle runs on JDK 21 everywhere, both in Android Studio and on CI.
  - Android modules compile to Java 17 bytecode.
  - `:core-nav` production code compiles to Java 8 bytecode against the Java 8 API (`-Xjdk-release=1.8`); its tests target Java 17 because JUnit 6 needs it.
- **Why:** `:core-nav` runs on Android 8 phones, whose Java library is close to Java 8. Lint does not run its Android API checks (`NewApi`) on plain JVM modules: a probe calling `Optional.isEmpty()` passed lint. With the Java 8 API, such calls fail to compile.
- **Alternatives:**
  - Java 17 API plus lint: lint does not catch these calls in JVM modules.
  - JDK toolchains: extra JDK downloads.
  - Turning `:core-nav` into an Android module: it would no longer be pure Kotlin.

## 5. Mapbox: 16 KB page artifacts, no download token, versions moved together

- **Chosen:**
  - `-ndk27` artifacts.
  - Explicit modules added stage by stage: `navigation-ndk27` now, `ui-maps-ndk27` and `tripdata-ndk27` in stage 1. The umbrella `android-ndk27` is not used.
  - Navigation 3.34.1 with Maps 11.34.1, and Search 2.34.1 when it is added. All three move together on the same minor version.
  - `com.mapbox.*` groups resolve only from the Mapbox repository (`exclusiveContent`).
  - Copilot (Mapbox trace collection) is never started.
- **No download token:** the Mapbox Maven repository serves all artifacts without credentials, so no secret token exists anywhere. If Mapbox ever requires one again, it goes only into GitHub Secrets or `~/.gradle/gradle.properties`, is used by Gradle for downloads, and never reaches an APK.
- **Alternatives:**
  - Default artifacts: 4 KB alignment, not accepted by Google Play for apps targeting Android 15 and newer.
  - The credentials block with `MAPBOX_DOWNLOADS_TOKEN`: no longer needed.

## 6. The Mapbox access token lives only on the phone

- **Chosen:**
  - No build contains a token: debug, CI or Play.
  - The driver pastes a public token on first launch or in Settings; Settings shows it only masked. The typed value never enters saved instance state. Up to five can be kept, one of them in use (47).
  - It is stored in a dedicated DataStore file. The backup rules include only the settings and the trip log (see 21), so that file is never backed up or transferred; a unit test keeps the rules in sync with the file paths.
  - The token reaches the SDKs at runtime through `MapboxOptions.accessToken`. Secret tokens are refused.
- **Alternatives:** `resValue` from `local.properties`, or a GitHub secret built into CI APKs.
- **Why:** this repository is public, so anyone with a GitHub account can download its CI artifacts. A token inside them would be public too.
- **Cost:** after a reinstall or on a new phone the token must be entered again.

## 7. Shared debug signing key from GitHub Secrets

- **Chosen:** optional `DEBUG_KEYSTORE_*` values from environment variables (CI secrets) or `local.properties`. Either all four are set or none; a partial setup fails the build.
- **Alternatives:**
  - A temporary key per CI run: updates would not install over each other and would need an uninstall, which loses the token and adds a Mapbox monthly active user.
  - A keystore committed to the repository: never.
- This is not the upload key for Google Play (42).

## 8. Continuous integration

- **Triggers and pinning:** GitHub Actions run on pushes to `main` and on demand. Every action is pinned to a commit SHA.
- **Never a `pull_request_target` or `workflow_run` trigger:** both run as the default branch with the repository's secrets, also for events that a pull request from a fork causes, so a step that builds or reads that pull request's code would hand it the keys; the branch rule of the environment `google-play` would not stop them either (42).
- **Where the logic lives:** the build job only calls `scripts/ci.sh`, and the publish job `scripts/play-upload.sh` (42), so build changes do not require editing the workflow file. `scripts/ci.sh` first tests the CI scripts themselves.
- **Checks:** build, unit tests, lint, and the 16 KB check, which covers both ELF LOAD alignment and zip alignment.
- **Artifacts:** the debug APK is uploaded on every run whose debug build passes, even when the app bundle part after it fails, and kept for 30 days; with the upload key, the app bundle for Google Play too (42).
- **Caching:** `setup-gradle` uses `cache-provider: basic`, which is open source with no extra terms. The default "enhanced" provider is proprietary. Cost: caches start cold after catalog changes.
- **Caches hold no secret:** only `cache-provider: basic`, which saves `~/.gradle/caches` and `~/.gradle/wrapper`. Never save the project's `.gradle/` folder and never set `cache-encryption-key`: configuration cache entries can hold the signing passwords (`signingConfigs` in `app/build.gradle.kts`), and the key that decrypts them, `~/.gradle/caches/9.8.2/cc-keystore`, is in what basic caching saves.

## 9. No build-logic module yet

- **Chosen:** shared SDK levels live in the version catalog, and each module keeps a short `android {}` block.
- **Revisit:** move to convention plugins once modules repeat more configuration.

## 10. JUnit 6 (Jupiter) for all JVM tests

- **Chosen:** JUnit Platform in both JVM modules and Android local unit tests, with `useJUnitPlatform()` and the explicit `junit-platform-launcher` dependency that Gradle 9 requires.
- **Alternative:** JUnit 4, the AGP default.
- **Why:** parameterized and nested tests suit the camera scenarios planned for stage 4.

## 11. Packaging

- **ABIs:** only `arm64-v8a` (phones) and `x86_64` (emulator); 32-bit ABIs are dropped. The CI APK is arm64 only (`-Pcustommaps.abis=arm64-v8a`), about half the size, because it is meant for the phone.
- **Debug suffix:** debug builds use the application ID suffix `.debug`, so they install next to the Play build, and are named **Custom Maps Debug** on the phone (`app/src/debug/res/values/strings.xml`), so the two icons are told apart.
- **Test fixtures:** fake tokens in tests use values that cannot be mistaken for real Mapbox tokens, so secret scanners stay quiet.

## 12. Module boundaries and wiring

- **Mapbox boundary:** Mapbox is an `implementation` dependency of `:engine-mapbox`, so no other module can compile against Mapbox types; `:app`'s compile classpath has no `com.mapbox` artifacts. The public API of `:engine-mapbox` uses only domain and Android types.
- **Car service:** `CarAppService` stays in `:app`, the composition root. Car screens go to `:car` in stage 2.
- **Dependency injection:** done by hand in `AppGraph`. A single-user app does not need Hilt or Koin.
- **DataStore:** one DataStore instance per file per process, created at application level.

## 13. Trip sessions start only on Start and stop before routes are cleared

- **Chosen:** a pure state machine in `:core-nav` (`GuidanceStateMachine`) turns driver actions and SDK events into engine commands, and it alone starts and stops trip sessions.
  - Preview routes stay in the engine and reach the navigator only when the driver presses Start.
  - The session starts only once the navigator has accepted the routes. Started earlier, it begins as a separate free drive session and becomes active guidance a moment later. If the navigator rejects the routes, guidance goes back to the preview.
  - End and arrival stop the trip session first and clear the routes after that. Clearing routes while a session runs would switch it to free drive, which Mapbox bills.
  - A session stopped outside the app, by End Navigation in the SDK's trip notification, ends guidance the usual way.
  - A dedicated lifecycle owner keeps the navigator alive only for the length of a drive. When the app goes to the background mid-drive, guidance carries on with the trip notification.
  - Inside a running trip the routes are refreshed or replaced only by the start plan of decision 40, under its own rules, so that the trip stays one billed trip.
- **Alternatives:** Mapbox's ready-made navigation UI, or free drive just to show the location. Both start billed sessions on their own.
- **Why:** trip sessions are what Mapbox bills for navigation, and the rules are testable on the JVM this way.

## 14. Location before guidance comes from the platform

- **Chosen:** before Start, the puck uses the Maps SDK's platform location provider; during guidance it switches to the navigator's map-matched location.
- The camera follows the car before Start from that same location, until the driver moves the map and again after My location. It takes no trip session and no request ([CAMERA.md](CAMERA.md), "Following the car before guidance").
- **Alternative:** free drive for map-matched location at all times. It is billed as a trip.
- **Cost:** no snapping to roads before Start.

## 15. Mapbox Standard by night and by day

- **Chosen:** one style, Mapbox Standard with 3D buildings, in two looks (48), each a column of one table of the style's settings (`MapPalette.keys`):
  - **At night** the night light preset and the monochrome theme, with a graphite ground, roads and water and amber routes. The road colours are those of 43, the night look's own.
  - **By day** the day light preset and Standard's default theme: every colour the night sets goes back to Standard's own, and the places' icons keep Mapbox's colours. The one colour of its own is the road under lane details (39), a dark asphalt `#575C64`, on which the white lane markings stand out.
  - Both columns name the same keys, so nothing set at night is left over by day. The look is applied when the style's data loads, before the first tiles draw, so a map never shows a frame of the other look; a change later is config writes on the same style, never a reload.
- **The route line** is amber in both looks, with a dark casing that deepens by day (`#5C3B00`; `#3A2800` at night) to carry the route on the light map. Moderate traffic is orange in both. Heavy and severe traffic are red and dark red by day and coral `#FF6B5E` and pink `#FF5C9E` at night: on 43's lighter night roads the dark reds all but vanished, a severe jam reading as a gap in the route. A closed section has a colour of its own in each look, platinum at night and dark grey by day, and the other routes a light grey `#9AA1AB` with a dark casing in both.
- **Alternatives:** a custom style made in Mapbox Studio, which would have to be maintained in the Mapbox account, outside this repository; two separate styles, one for each look, as Mapbox's own navigation screens switch, which reloads the whole style at every change.

## 16. One map per activity

- **Chosen:**
  - The map stays composed for the whole life of the activity. Settings opens on top of it.
  - The activity handles configuration changes such as resizing itself, so the map is not rebuilt for them.
  - A map created during a drive, after the app was reopened, draws the navigator's current route at once.
  - Switching to another token or removing the last one recreates the activity, so a new map uses the new token. The navigator lives through the recreation, as through any configuration change, and reads the new token with its next request. Switching is blocked during guidance (47).
- **Why:** the Maps SDK releases a map only when its activity is destroyed, and a new token in the middle of a drive would put the rest of its trip session on another token.

## 17. Search

- **Chosen:**
  - Search Box API, which covers addresses and places (businesses, points of interest), limited to the US and English.
  - Requests start 300 ms after typing stops, from two characters on; at most 8 suggestions. Clearing the field cancels a request at once, and after a failure the same text can be searched again.
  - The search engine is recreated when the token changes.
  - No search history: the engine has no local history or favorites, picked places are not added to history, and history left by earlier builds is cleared.
- **Alternative:** the Geocoding API, which covers addresses only.

## 18. Simulated drive and the trip session log

- **Simulated drive:** a switch in **Settings → Debug** replays the selected route instead of following GPS, for testing at home and in the Desktop Head Unit. It still starts a trip session, and that session is logged as simulated.
- **Trip session log:**
  - Every new trip session (its session id changes) is stored with its time and kind, and the app's own id of the token it ran on (47), up to 500 entries.
  - Settings shows the count for the current month and the latest entries, plus a link to the Mapbox statistics page.
  - The count is an estimate; the Mapbox statistics page has the billed numbers.

## 19. Own guidance visuals

- **Chosen:**
  - Maneuver arrows and lane arrows are drawn by the app in its own style.
  - Maneuver text and lanes come from the Navigation SDK's maneuver API.
  - Distances and times are formatted in US units by `:core-nav`.
- **Alternative:** the Mapbox UI components for maneuvers and lanes. They bring their own look and are harder to fit into the graphite and amber palette.

## 20. Debug log on the phone, shared by hand

- **Chosen:**
  - Events are written as JSON lines, one file per day, in the app's private files: routes with every step, guidance once a second, searches, trip sessions and errors.
  - Logging never blocks the main thread: a single background writer drains a bounded queue.
  - Days that are over are kept gzipped (44), and the oldest days are deleted once the log, as stored, passes 50 MB. The log is never backed up or transferred. Clear deletes it together with the copies made for sharing.
  - The owner shares it from Settings through the share sheet as plain text files, for example to Google Drive, where it can be read for diagnosis.
  - Every line passes a filter that masks Mapbox tokens and `access_token` parameters.
- **Alternatives:**
  - Automatic upload: needs a server or a sign-in inside the app.
  - Logcat only: lost on restart and needs a computer with ADB.
  - A crash reporting service: sends data to a third party all the time.

## 21. Backups include only the settings and the trip log

- **Chosen:** the backup rules (Android 8 to 11) and data extraction rules (Android 12+) list the two files worth keeping: the settings and the trip session log. With an include list, nothing else is backed up or transferred.
- **Alternatives:**
  - An exclude list. It left in whatever the Mapbox SDKs write to the app's files, such as search history and map and routing caches.
  - No backups at all. The settings and the trip count would not survive a new phone.
- **Why:** files that SDKs add later stay on the phone without anyone having to notice them.

## 22. The phone screen stays in portrait

- **Chosen:** the main activity is locked to portrait. The Mapbox logo has a fixed place at the bottom (see 25), so no layout can push it off the map.
- **Alternative:** a landscape layout with the panels at the sides. It belongs with phone-only use, which the roadmap keeps for later.
- **Why:** the panels are laid out for a tall screen; in landscape the route sheet covered Start and pushed the logo off the map.
- **Cost:** from Android 16, large screens such as tablets ignore the lock. Phones keep it.

## 23. Routes and guidance need precise location

- **Chosen:** Directions and Start need precise location. With approximate location the prompt offers to switch, and once Android stops showing the permission dialog it opens the app's system settings. The engine also refuses to start guidance on GPS without precise location; a simulated drive needs none.
- **Alternative:** accept approximate location.
- **Why:** approximate positions can be kilometres off and update rarely. Routes would start from the wrong place, and a billed trip session could not follow the route.

## 24. A refused token is named as such

- **Chosen:** a refused token is recognised by the router's authentication failure, by HTTP 401 or 403 from search, and by the status code or wording in map loading errors. The screen then says that Mapbox did not accept the token and offers Settings.
- **Alternative:** check the token with a request when it is entered. That is one more request, and a token can still be revoked later.
- **Cost:** map errors carry no status code, so the map check reads the error message.

## 25. A fixed place for the Mapbox logo

- **Chosen:** the Mapbox logo and the attribution button sit in a fixed strip at the bottom left of the map, just above the navigation bar or the keyboard. Every panel stays above that strip, so nothing covers them and they do not move when the panels change.
- **Alternatives:**
  - Lift the logo above whatever panel covers the bottom of the map. With the route sheet open it floated around the middle of the screen, and during guidance it sat next to the car.
  - The top left corner, below the search bar or the maneuver card. It still jumps whenever lanes or suggestions appear.
  - A smaller or semi-transparent logo. Mapbox does not allow it: "You may adjust the position of the Mapbox wordmark and attribution notice, but they must stay visible on the map ... You may not otherwise alter the Mapbox wordmark" (Maps SDK for Android guide), and "You may not style the Mapbox logo" (Mapbox attribution guide). The SDK has no setting for it either.
- **The strip above the logo is 12 dp** in both looks, so that no panel's shadow, which by day reaches about 10 dp below the panel, falls on the logo or the (i) button. The logo itself does not move. **The (i) dialog** is the SDK's own, with its content and links, in its day or night variant by the app's look rather than the phone's theme (48).
- **Cost:** the bottom panels sit about 27 dp higher than before.

## 26. Camera styles chosen in Settings

- **Chosen (owner):** Settings offers three camera styles, applied at once, also mid-drive, and on the car screen from stage 2:
  - **Mapbox standard:** the NavigationCamera of the first drives with its camera behaviour unchanged, kept for comparison.
  - **Mapbox tuned:** the same camera with calmer settings: one camera update per navigator update instead of two, longer moves from frame to frame, a gradual tilt before turns and a puck that glides 1.1 s per update. It gets no higher zoom cap on the road: its extra zoom at turns comes from the Large lane zoom (38).
  - **Smooth follow:** the per-frame camera of decision 27, the default.
- The choice is stored with the app settings as `camera_style`; a missing or unknown value reads as Smooth follow.
- What is not camera behaviour is the same in every style: the top padding that only grows during guidance, the band it keeps free for the Then chip (37), the route line fix, a fresh puck for every drive, and following the car before guidance ([CAMERA.md](CAMERA.md)).
- The debug log records the active style on every camera line and every change of the setting, so drives can be compared.
- **Alternative:** a single camera. Comparing styles on the same roads is the quickest way to tune them.

## 27. A per-frame follow camera

- **Chosen:** a camera controller that runs once per display frame (Choreographer) and sets the camera with critically damped springs. While following, it glides after the drawn puck; the once-a-second navigator data only moves its targets (zoom, bearing and the tilt schedule from the Navigation SDK's viewport data source). Details in [CAMERA.md](CAMERA.md).
- **Alternatives:**
  - Tune NavigationCamera. It restarts its animations on every update, so once-a-second updates can never look continuous.
  - The Maps viewport plugin's follow-puck state. It moves every frame but bolts the camera to the puck, the opposite of what the owner asked for.
- **Why:** it is the only design whose motion is continuous by construction; the camera itself does not depend on the system animation setting (the drawn car's glide still does, see CAMERA.md); and the same controller drives the car map.
- **Cost:** the app owns the camera: every other camera change during guidance hands the camera back to the driver or goes through the controller. Re-check the Navigation SDK's framing behaviour on every SDK upgrade.

## 28. Routes end at the stop point, not the display point

- **Chosen:** a place keeps two points: where it is, and where a car stops for it. Built in stage 1.1.
  - **The stop point** is the first routable point of the Mapbox Search result whose name is not `entrance`, in any letter case, and that lies within 1 km of the place. Mapbox names these points in free text, so no list of good names is kept; `entrance` marks a door for people on foot. Routable points come with the result itself, with no extra request. A result without such a point has no stop point and works as before.
  - **Routes go to the stop point.** The place itself goes along as the arrival target, so the arrival instruction names the side the place is on. Nothing is asked about the side of the road the car arrives on: the routes are the ones the service finds best.
  - **The map shows the place** as an amber dot, from the moment it is picked through the routes, the drive and the arrival; the route line keeps its own end marker. When a place has no stop point and the route ends more than 50 m from it, the route sheet says so, for example "Route ends 250 ft from the place".
  - **Reroutes are only watched.** The navigator builds the request of a reroute itself. That it ends at the same point is in the SDK's sources; whether it keeps the arrival target is not. Every reroute request is written to the debug log as it is and handed back unchanged; a drive shows whether anything needs correcting.
  - A place also keeps the search provider's id of the result. Nothing reads it before the trip history.
- **Alternatives:** the display point only, which the Directions API snaps to the nearest road, sometimes the service road behind a building; or always arriving curbside, which can add a loop around the block and which the owner chose to leave off.
- **Why:** the first drive ended behind a big-box store whose address point lies in the middle of the building.
- **Cost:** whether Mapbox has a stop point for a place, and where it lies, shows only in a drive's log. 1 km and 50 m are first settings, and none of it has been on a drive yet.

## 29. Diagnostics survive a crash

- **Chosen:**
  - The debug log gets a synchronous write path next to its background writer: an uncaught-exception handler writes the crash with its stack, after the events still queued, on the crashing thread, then hands it to Android's own handler, which still ends the process and keeps its record.
  - Every start logs Android's record of earlier exits (crash, native crash, ANR, low memory, the user) on Android 11 and newer, with the short state summary the app left with the process, and on Android 15 and newer why this start happened.
  - One network monitor, the app's only network callback, logs every change of the app's network and the settings that can cut off only this app; its state is plain data the offline chip can read.
  - The log also records device state, whether Android's animations run, one camera line a second with the frame rate, and repeated map errors as one line a minute.
  - All of it goes into the debug log: in this stage the log stays on the phone and the owner shares it by hand (20); from stage 2 each trip's log is also uploaded to the owner's private GitHub repository (35).
- **Alternatives:** logcat only, which is lost and needs a computer; a crash reporting service, which sends data to a third party.
- **Why:** the app restarted once on the first drive and the log could not say why.

## 30. Car App Library 1.8.0-rc01

- **Chosen (owner):** androidx.car.app 1.8.0-rc01 now, 1.8.0 when it is released.
- **Alternative:** the stable 1.7.0. Its host check accepts any app on the phone that holds the template-renderer permission, even with an allow-list, and such an app would see the map, the destination and could press Start.
- **Why:** 1.8.0-rc01 differs from 1.8.0-beta01 only by that fix and its API is frozen.

## 31. The car map uses Mapbox's map surface only

- **Chosen:** maps-androidauto (MapboxCarMap) for the car surface, with the app's own camera, route line, puck, maneuver images and notifications on the domain models, as on the phone.
- **Alternative:** Mapbox's android-auto-components. They assume free drive, clear routes without stopping the session (which bills free drive), register Mapbox voice guidance, and pull in the navigation umbrella, Search, Glide and AppCompat.
- **Why:** the billing rules of decision 13 and the module boundaries of decision 12 hold on the car too.

## 32. A drive outlives both screens

- **Chosen (owner):**
  - When the car disconnects mid-drive, guidance continues on the phone; when it connects again, guidance continues on the car screen.
  - If another navigation app starts guiding in the car, this app keeps guiding too.
  - When the driver opens Custom Maps on the car screen again during the drive, it takes the car back: the routing card, the turn notifications and the cluster.
  - After a crash or a system kill mid-drive, the phone and the car offer to resume to the same destination, which is a new billed trip.
- **How:** the drive belongs to the engine's state machine, not to a screen. Car sessions only keep the navigator alive while connected; the guidance lifecycle owner keeps it alive for the drive.
- **Cost:** the trip session runs until arrival or End, whichever screen is gone.

## 33. Location "Allow all the time"

- **Chosen (owner):** after precise location, the app asks for location all the time.
- **Why:** Android 14 and 15 treat an app shown only on the car screen as in the background. Without background location, Start pressed in the car with the phone locked may be refused, and the car map has no position before Start.
- **Cost:** Google Play asks for a background-location declaration, a video and a review, at the end of stage 2 with one bundle rolled out by hand, and Play updates pause during the review (42). A start guard still checks before every trip start, and a refused start goes back to the route preview instead of crashing.

## 34. Shared links and Google place pages

- **Chosen (owner):** coordinates in a link are used as they are. A Google Maps link without coordinates, which is what the Google Maps phone app shares, has its place page loaded once to read the exact coordinates; on by default, with a switch in Settings. The fallback is a Mapbox search near the place hint the link carries. A link that arrives during a drive offers to go there now, on the phone and in the car; the first answer on either screen wins.
- **Cost:** Google's terms do not allow reading its pages this way, and the page format can change at any time; the fallback keeps links working.

## 35. Trip logs go to a private GitHub repository

- **Chosen (owner):** each trip's debug log is uploaded when the trip ends, as one gzip-compressed JSON-lines file, to a private repository, with a fine-grained token that reaches only that repository (366 days, a reminder two weeks before it expires). Uploads run through WorkManager, so they survive network loss and process death; Settings has Upload now.
- **Guards:** the app refuses a public repository and classic tokens, never logs the token, and masks GitHub tokens in every log line.
- **Why:** the code repository is public, and the logs hold location history.

## 36. A bright day map in the car and on the phone

- **Chosen (owner):** a bright, colourful day map by day and the existing dark map at night. The route stays amber. In the car the map followed the car's day and night setting until stage 2's step 1b, and since then it follows the phone (50); on the phone, since the owner asked for the light version of the app too, the sun at the phone's position or the driver's choice (48).
- **Alternative:** a pale or faded day theme, which the owner rejected.

## 37. The Then chip

- **Chosen (owner):** on the approach to a maneuver that is closely followed by another, a chip slides out from under the maneuver card with the word **Then**, the arrow of the second maneuver and its road, for example "Then, right, Pine Street". The label is the owner's.
- **The data comes from the route's own steps**, not from the banner's `sub` line. The Directions API fills `sub` with lanes whenever the next maneuver has lanes, which is where the second maneuver matters most. A roundabout's way out, a step of its own in these routes, is not a second maneuver: what follows a roundabout is the maneuver after its way out.
- **One rule decides, in `:core-nav`** (`ThenRule`), once per progress update in the engine. The phone reads its answer now and the car screen reads the same answer in stage 2, so both show the same maneuver at the same moment.
  - It shows when the second maneuver follows the first within 300 m or 20 s (1,000 m or 40 s around ramps, forks and merges, where lanes are picked early), from 400 m or 30 s before the first (1,500 m or 50 s when the first is a ramp, fork or merge), and not from closer than 30 m.
  - It never shows going straight on, a road that only changes its name, a merge or a note, and nothing after the arrival.
  - Once shown it stays until the step changes, so GPS jitter, braking and a traffic refresh cannot make it flicker. Off the route it hides. When the next step keeps it, only the arrow or the road that changes fades; the same arrow again does not move.
  - The numbers are a first setting. The progress line of the debug log carries the second maneuver and whether the chip showed, to tune them after a drive.
- **The chip is outside the measured panels.** It hangs under the maneuver card over the map, and during guidance the camera's top padding holds a constant band of 56 dp under the card for it, whether the chip shows or not. The chip never covers what the camera frames, and the camera never moves when the chip comes or goes.
- **Alternatives:**
  - The banner's `sub` line. It is missing at junctions with lanes.
  - The chip inside the measured panel. The camera would frame the road again whenever the chip first showed, and with a top padding that only grows it would then keep the lost space for the rest of the drive.
  - The chip over the framed map with no band. It would cover the road just after the turn, the very road the chip names.
- **Cost:** the framed area is 56 dp shorter during guidance. Below the zoom cap that is a slightly wider view; at the cap, which is most frames in town, nothing changes.

## 38. Lane zoom, Default and Large

- **Chosen (owner):** Settings, Guidance has a **Lane zoom** with two choices, applied at once, also mid-drive, and on the car screen from stage 2.
  - **Default** is the zoom guidance has had so far, never closer than 16.35. It is what a phone starts with; a missing or unknown stored value reads as Default.
  - **Large** is Default on the open road plus two close views. Before every turn the view goes onto the junction until a lane is 28 dp wide on the screen, about 4.4 mm. On a freeway, before a point where lanes leave the route, it goes in until a lane is 14 dp wide, about 25 s ahead. It comes back out after each, except on the run-up to a freeway exit, which is one approach: the view comes in once, up to a minute of driving (at least 1.5 km) plus 25 s before the exit, and stays to the gore, Smooth follow tilting it up to 55° and both cameras keeping at least 12 s of road in view (46).
- **Large is set by the lane's width on the screen, not by a zoom number,** so it looks the same at every latitude. It needs no tiles beyond those Default uses: Mapbox's tiles end at zoom 16, and a closer view draws them larger, online and offline.
- **The frame stays the navigation SDK's.** At a junction Large lets the SDK fit its own frame of the car and the junction closer and with less road after the turn. The camera takes that frame's zoom from where the SDK starts to frame the maneuver, and holds the open road at 16.35 itself.
- **The frame holds the bend.** A route's step can begin well before the road visibly turns, as at a turn bay, and the 30 m past the maneuver point then end before the bend: the bend lay under the card or above the screen. So the road to 30 m past the first point that heads 20 degrees off the way in, or else lies 4 m to its side and off the line the road set out on, joins the frame as more points, ordered so that the frame's bearing stays on the road; while the view looks down onto a turn, Smooth follow also turns it at most 20 degrees toward the road ahead. Ordinary turns, U-turns and short steps are framed as before. Where the bend lies farther on, the junction comes out wider: lanes of 9 to 18 dp instead of 28 in the replays ([CAMERA.md](CAMERA.md), The frame reaches the bend).
- **Which freeway points count is one rule in `:core-nav`,** read from the lanes the route already carries, with no request: at least 3 lanes reach the point and lanes leave the route; where the route goes straight on, at least 2. A wide freeway where every lane goes on, and a single lane that turns into an exit, do not zoom, so the long freeway view the owner likes stays the rule. Both numbers are a first setting: every point with lanes is written to the debug log with its lane counts, counted or passed over, to tune them after a drive. The maneuver card shows the lanes of the same point; on the run-up to an exit it shows the run-up's one row with the lanes to use in words (46).
- **Smooth follow and Mapbox tuned carry Large; Mapbox standard keeps its own zoom,** as the comparison style it is (26). Settings says so under the row.
- **Alternatives:**
  - A fixed higher zoom limit. It brings the whole town drive closer, not the approach to a turn, and the view jumps to it whenever only the car's position is framed.
  - A zoom that follows the speed. It has nothing to do with where a lane has to be chosen.
  - Going close on every wide freeway. It ends the long view ahead for no lane choice.
  - Mapbox's Electronic Horizon for lane counts. It is a beta whose pricing may change; the route's own data is free and says more.
  - Large as what a phone starts with. The owner chose Default, with Large one tap away.
- **Why:** the lanes are large exactly where a lane has to be chosen, and nowhere else.
- **Cost:** at a junction the view is only about 55 m wide, wider where the road bends well after the maneuver point; what comes after the turn is left to the Then chip (37). None of it has been on a drive yet; the numbers are in [CAMERA.md](CAMERA.md), Lane zoom.

## 39. Lane details on the map, no lane position

- **Chosen:** Settings, Guidance has a switch **Lane details on the map**, off unless turned on. It turns on Mapbox Standard's lane details: lane lines, the arrows painted in each lane, stop lines and crosswalks, where Mapbox has them, which is on major highways and in some cities.
- **The navigation SDK turns lane details off** at every style load. The app sets them after the style load, in a later message of the main thread, so nothing turns them off again and nothing fades out and in. It looks once a second during guidance and sets them again if something changed them; the debug log counts that. Only the public style setting is used.
- **The car is never drawn in a lane, and nothing moves its icon sideways.** Phone GPS is typically within about 5 m and a lane is 3.5 to 3.7 m wide. The navigator's position lies on the line of the road and has no lane. The public SDK has no lane geometry: the route says which lanes can be used, not where they are on the map. An icon drawn in the lane the route needs would show the driver in the right lane whether they are in it or not.
- **What the driver gets in its place:** the close view of the Large lane zoom (38), lane markings on the map where Mapbox has them, and the lane arrows in the maneuver card, also at lane choices where the route goes straight on. The switch's text says that the car shows where it is on the road, not which lane it is in.
- **While the switch is on, the route line stays thin close in,** at the width it has at zoom 16.5. The SDK's line grows to about a lane's width at the junction zoom and would cover a lane's markings and read as "this lane".
- **Off unless turned on,** because two things could not be checked without a device in an area that has lane details: whether the route line and the maneuver arrow, which have no height, hide under a bridge drawn in 3D, and the frame rate. The switch's text says the route line can hide under bridges. Turning it on for everyone later is a one-line change.
- **Alternatives:**
  - The SDK's own switch that stops it from turning lane details off. It is restricted to Mapbox's modules; calling it would claim a module that is not there.
  - Setting them within the style load. The SDK turns them off again, with a visible fade.
  - Moving the car's icon or the route line into a lane. There is no lane position to move it by.
- **Why:** public, reversible and honest about what the phone knows.
- **Cost:** the order of events the setting relies on is read from the SDK's source, not promised by it. Check it with each SDK upgrade ([ROADMAP.md](ROADMAP.md), watch list).

## 40. Start at once with older routes

- **Chosen (owner):** Start hands the previewed routes to the navigator at once, whatever their age. It never waits for a position or a request and shows no spinner. What is done about old routes happens inside the trip.
- **Three plans,** picked at Start by one rule in `:core-nav` (`StartFreshness`) from the time since the routes arrived and how far the car is from where they were requested:

  | Plan | When | Inside the trip |
  |---|---|---|
  | As previewed | Up to 5 minutes old, and the car within 100 m | Nothing |
  | Refresh | Older than 5 minutes, or the car moved more than 100 m | One refresh of traffic, times and closures; the roads stay the same |
  | Replan | Older than 30 minutes; or the refresh failed, or the routes cannot be refreshed | One route request from where the car is to the same place; its routes take the place of the old ones |

  - The car's position at Start is the map's puck. Without one nothing is measured and only the age counts. The location service's last answer is no second source: the app asks for it only for a route request, so at Start it still is the position the routes were requested from.
  - A simulated drive is always as previewed.
  - A car that moved gets a refresh, never a replan. A car that has left the route is put on a new one by the navigator itself, free and inside the trip, as on both drives; a second request would only race it. From the moment the navigator finds the car off the route, or puts other routes in place for any reason, the plan does nothing more.
  - The replan repeats the preview's request, to the same stop point with the same arrival target (28), from the navigator's position on the road, with the car's direction when it moves at 3 m/s or more. Of the fresh routes, the one with the most main roads of the route the driver started on goes first; with none in common the service's order stays.
  - The plan begins at the first progress whose position is no older than 30 s. The navigator can open a trip on the phone's last known position, minutes old, as on the second drive, and routes requested from there would start where the car no longer is. A replan after a failed refresh is skipped when the position is older.
- **The billing guard.** Mapbox bills a trip once, when its session starts. Routes given to the navigator inside a trip are compared with its latest progress: routes that arrive while it has none, as before the first progress of a trip, or that end more than 100 m from where the trip goes, start a second billed trip. An empty list ends guidance while the session runs on, which is billed by the hour.
  - So one pure rule (`InTripRefresh`, tested on the JVM) is asked before anything is done, and the engine only carries out its answer. Nothing happens before the first progress of the trip, after guidance ended or after a reroute. An empty answer is never set, and neither are routes worked out on the phone without network, which know less than the routes in use. A destination more than 100 m from the trip's is never set; the distance is measured the way the navigator measures it.
  - Fresh routes are set from inside a progress update for the route the trip started on, the one moment the navigator is sure to have a progress.
  - An answer that belongs to an earlier trip is dropped, and a request still under way is cancelled when guidance ends.
- **What the refresh's results mean.**
  - Success: done.
  - Failure, no network or a refusal: replan.
  - Not started, the navigator has no route it can refresh, such as one worked out on the phone: replan, since such routes are never refreshed later either.
  - Cancelled: the navigator drops a refresh on every change of its routes that is not itself a refresh (`RouteRefreshController`, Navigation 3.34.1). After a reroute or the end of guidance there is nothing left to do. But a change of the alternatives alone cancels it too, as when the car passes the point where one of them leaves the route, or the navigator finds new ones. The trip is then still on its old route, and the navigator's own refresh starts its 5 minutes over. So a refresh cancelled while the trip is on the route it started on is asked for once more, at the next progress update, where the same rule is asked again. A second cancel is followed by nothing.
- **Logged:** `start_plan` at Start with the plan, the age and the distance; `start_refresh` and `start_replan` with the result, and the reason when nothing was set; a cancelled refresh says whether it is asked for once more. Every route request the app sends is a `route_request` line that says whether it was sent inside a trip.
- **Only logged for now:** what the navigator's reroutes ask for (28).
- **Alternatives:**
  - Start waits up to 4 s for fresh routes. The owner chose to start at once.
  - Refresh the open preview on a timer. Each refresh is a billed request outside a trip, for a preview that may never be driven.
  - Request again on every Start. It doubles the requests for nothing when the preview is fresh.
- **Why:** the first drive started on routes 8 minutes old. Only a reroute a few seconds in brought fresh traffic; without it the arrival time would have stayed that old until the navigator's first own refresh, 5 minutes into the drive.
- **Cost:**
  - The drive begins on the old route; after a replan the route line and the first instruction can change a few seconds in.
  - The refresh is an experimental call of the navigation SDK, and the navigator's rule for "the same trip" is read from its source, not promised by it. Re-check both with each SDK upgrade.
  - The trip count in Settings follows the navigator's sessions (18). A second billed trip caused by swapped routes would not show there, only on Mapbox's statistics page, which is why the guard is a tested rule and not a habit.
  - The wait for a live position trusts the age the navigator gives its positions, which no drive log has shown yet. If that age were wrong the plan would never begin; the log would show it, with the age on each `progress` line and the plan closed as skipped at the end of the drive.
  - How often the navigator changes its alternatives in the first seconds of a trip cannot be read from its source. If it does so more than once while the refresh runs, the single repeat is used up and the refresh is lost as before.
  - None of it has been on a drive yet; 5 minutes, 100 m, 30 minutes, the 30 s of a live position and the single repeat are first settings.

## 41. Offline data along the drive

- **Chosen:** the phone saves the map and the road data of a drive before the connection is gone, in two ways, both into one tile store. A chip on the map says when the app is offline.
- **One tile store for the map and the navigator.** The engine makes Mapbox's tile store first, gives it to the Maps SDK before any map or offline manager exists, and puts it into the options every navigator is made from. Both SDKs read their options when an object is made, so the order is fixed in one place (`MapboxEngine.create`).
  - It is the store at the default location, the one each SDK used before by itself, so nothing saved so far is lost.
  - The map uses it as before (`READ_ONLY`, the SDK's default): it reads tile packs from the store and loads any other tile singly into its own cache.
  - Without a store set by the app, the SDK's predictive caching refuses map data.
- **During guidance, data is kept ahead of the car.** The navigation SDK's predictive caching (`PredictiveCacheController`) loads road data and the map around the car, along the route in use and around the destination, over whatever network the app has. It is set up with each navigator and has something to load only during guidance: the navigator learns where the car is, and which route it drives, only in a trip session. The distances are the app's own:

  | Data | Around the car | Along the route | Around the destination |
  |---|---|---|---|
  | Road data: map matching, speed limits, reroutes without a connection | 10 km | 3 km | 10 km |
  | Standard map, zoom 15 to 16 | 2 km | 1 km | 2 km |
  | Standard map, zoom 11 to 14 | 10 km | 5 km | 10 km |

  - The SDK's defaults are 20 km around the car, 5 km along the route and 50 km around the destination, for the road data and for the map at zoom 15 to 16.
  - Why smaller: all of it is loaded over mobile data while driving. The street map is kept only close to the car and the route, where the camera shows it. The town map and the road data reach further, for the view of the whole route and for a reroute. The drive ends at the destination, so 50 km around it would mostly be data for roads never driven.
  - Only the route in use counts, not its alternatives. Nothing is kept for search.
- **Before guidance, the street map along the previewed routes is downloaded.** When routes appear in the preview, one tile region is loaded: the Standard map from zoom 11 to 16 along the first 100 km of every previewed route, outward from where the routes start. The first preview after the app starts also loads the style's own files, which the Maps SDK keeps outside the tile store, so the map can open without a connection.
  - It is always the same region, and each new preview takes it over. A load still under way is ended then, and the log calls that `replaced`, not an error.
  - A load goes on after Start until it is done (44): a preview is often open for a few seconds only. It ends early, as `stopped` with `trip_end`, only when the drive ends first, by End or at the destination; a newer preview takes it over as before. During the drive it loads next to predictive caching, on the tile store's own threads. What was loaded stays. A preview closed without Start lets its load finish.
  - Tiles the phone has are taken as they are, also past their date. The style's files are loaded anew when past theirs: Mapbox's guide says a style saved this way is not brought up to date otherwise.
  - The store gets the routes as lines with a point about every 100 m, put together off the main thread. A route has a point every few meters, and the smallest tile asked for is 300 to 600 m wide.
  - Each route counts only for its first 100 km along the road (`PreviewDownload.MAX_ROUTE_METERS`). Mapbox refuses a region whole when it would take the phone past 750 tile packs, its squares of zoom 11 and 12 counted for each tileset of the style. Without a limit a long trip's preview would save nothing at all, not even its first kilometers, on Wi-Fi as on mobile data. The first 100 km are the ones driven first; during guidance predictive caching keeps the rest ahead. Counted by those squares, three routes of 100 km stay below the limit even for a style of four tilesets.
  - Map tiles only, as the map loads them itself when it shows a place. No route or search request is sent and no trip session starts.
- **On disk.** Tiles of the region stay as long as a preview's routes need them. Once a new preview's region no longer covers them, they belong to no region. By the SDK's description, what the store holds outside regions is trimmed once it passes 1 GB, the default; no overall limit is set. Whether tiles that fell out of the region are dropped at once or with that trimming, and whether what predictive caching loaded counts the same way, cannot be read from the SDK's published sources.
- **Cost: mobile data, no extra Mapbox charges.** Map tiles are covered by the Maps SDK's monthly user, and the navigator's requests during guidance are part of the trip. So each preview download, the tile region and the style pack, writes into the debug log how many bytes came over the network for it (`loadedBytes`), next to what the phone holds for it. Predictive caching has no such count in the SDK: the log has only its distances and its errors. The `device` line, once a minute during guidance, has all the bytes the app has received (`appRxBytes`, Wi-Fi included), so its growth over a drive is the most that guidance can have loaded.
- **Open, the owner's question: may the preview download use mobile data?** For now it runs on any network, so routes planned on the road are covered too. For the tile region it is one constant (`PreviewDownload.NETWORK`); the other value waits for a network that costs nothing, and a setting can take the constant's place. Three things belong to the same question:
  - How far along each route the download reaches: 100 km for now, a first setting. Some limit is needed whatever the answer, since a region past 750 tile packs is refused whole; the answer may still change its value, for example a shorter one on mobile data.
  - Tiles in the region are never refreshed by this path, since tiles past their date are accepted.
  - The style's own files do not follow the constant: the Maps SDK has no network option for a style pack. With the other value they would still load over mobile data, once in a run and again when past their date. Wi-Fi only therefore also needs the style pack started only on a network that costs nothing, decided in the app from the one network monitor (no second callback). Loading the pack with the region, through the tile descriptor's style pack options, might let the region's restriction cover it, but what that does cannot be read from the SDK's sources.
- **The chip.** Three seconds after the app loses its internet a chip says so, and it goes at once when the internet is back; the wait hides a handover from Wi-Fi to mobile data. Before a drive it is a sentence under the search bar that names the reason, with a Settings button when Android keeps the app off the network. During guidance it is a short tab, an icon and "Offline", at the end side of the band the Then chip hangs in (37), at most 30% of the card's width and without a button. Both forms are outside the measured panels, so nothing moves when the chip comes or goes. The sentence is left out where it would reach the strip of the Mapbox logo (25), as in a short window or with a very large font and the keyboard up. The compass sits below the band for the whole of guidance.
- **Alternatives:**
  - Offline regions the owner picks on a map and keeps until removed. They need a picker, a list with sizes and a way to update them, and they do not help on a route outside them. Still on the Future list in [ROADMAP.md](ROADMAP.md).
  - The SDK's default distances. More mobile data for the same drive.
  - A tile store in a directory of the app's own. It would start empty and leave what is cached behind.
  - `READ_AND_UPDATE`, where the map loads only whole tile packs through the store. Mapbox's guide suggests it for a map that cannot be panned freely; this one can.
  - Ending a preview's download at Start, the first choice. During a drive the routes not taken cost no mobile data, but a preview is often too short for the download to save anything.
  - Each route at its whole length. A long trip's region would be refused whole and save nothing.
  - The full sentence during guidance. It would need a second row under the card and more of the map.
- **Why:** the first drive lost its connection 37 s after Start and stayed offline until End. Nothing had been saved ahead, although the routes had been on screen on home Wi-Fi for 8 minutes, and nothing on the screen said why the map stopped loading ([DRIVES.md](DRIVES.md)).
- **Cost:**
  - Mobile data, by estimate tens of MB for a new area; the log has the preview's real numbers and an upper bound for a drive.
  - The Maps and Common native libraries now load when the app starts, with the tile store, not with the first map.
  - None of it has run on a phone. Whether Mapbox resolves the Standard style for a tile region, its 3D buildings included, shows only in the first `offline_region` line on the phone. The distances, the zooms and the 100 m are first settings.

## 42. Release through Google Play internal testing

- **Chosen:** the Play build reaches the phone through Google Play's internal testing track, and from the second release on CI publishes every green build of `main` there by itself.
- **Play App Signing with a key Google generates.** Google signs the APKs it delivers and keeps that key, the default for a new app. A leaked or lost key on the owner's side can then never stop updates.
- **The upload key.** A separate PKCS12 keystore with an RSA 2048 key, never the debug key (7). The owner keeps the upload key; CI has it as secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`). Locally the same four values with `RELEASE_KEYSTORE_FILE`, read like the debug key: all four or none, a partial set fails and names what is missing. Play Console can reset it; the app signing key stays.
- **Version code.** `scripts/version.sh` (tested by `scripts/test/version-test.sh`): run number × 100 + run attempt on CI, so every run and every re-run rises (GitHub allows at most 50 re-runs of a run); version name `0.1.<run>`. Only release builds take that code. Debug builds keep 1, so a build from Android Studio still installs over a CI build; a build on a computer is 1 and `0.1.0`. A base constant in the script must go above Play's highest code if the workflow is ever renamed, since run numbers then start again at 1.
- **R8 and resource shrinking off for now.** The Play build runs the same code as the debug builds that were driven, and its stack traces and the debug log read the same. R8 builds cleanly, but nothing proves it safe with the Mapbox SDKs on a phone (reflection, JNI names, plugins that Mapbox's rules do not keep), obfuscation renames the class names the debug log writes, and with R8 the Compose plugin wants one more artifact. Cost: by bundletool's estimate about 11 MB more per download and about 32 MB more on the phone. To be tried on the internal track, preferably without obfuscation.
- **The bundle.** Built by `scripts/ci.sh` only with the upload key, in its own Gradle run with both ABIs; every native library in it is checked for 16 KB alignment through the universal APK that AGP's bundletool makes from it. Published as the artifact `custommaps-release-<versionCode>-<commit>.aab` for 30 days: any signed-in GitHub user can download it, which is fine, since it holds no secret.
- **Each signing key is given only to the Gradle run that signs with it.** `scripts/ci.sh` checks both sets of four secrets by name before anything runs and then takes all of them out of the environment of every later process. The debug key is decoded for the debug build, the upload key right before the bundle's Gradle run, each into a private folder that is deleted when the script ends, also on a cancelled run (the workflow starts the script with `exec`, so GitHub's signals reach it, and it sets no INT or TERM trap, which bash would hold back until Gradle ends), and only that run is given the key's file and its three values in its environment; the script unsets the base64 values as soon as they are decoded. The tests of the CI scripts and the debug build, its unit tests and lint get no release value in their environment. This keeps the upload key out of whatever those steps log or keep, not away from hostile code in the build job:
  - `export -n` and `unset` change only what child processes inherit. `scripts/ci.sh`, the step's own process, keeps all eight values in its start environment (`/proc/<pid>/environ`), which any process of the job can read without any privilege.
  - The Gradle daemon and the Kotlin compile daemon that the debug run starts keep the debug key's three values in their environment and serve the bundle run too. That run gets nothing to sign with from them: the debug keystore file is deleted before it starts, and its base64 is never given to Gradle.
  - The bundle's own run still has every plugin of the release build, lint vital included.
  - Every job on GitHub's runners has passwordless sudo, so any code in the job can also read every secret of the job from the runner's memory.
  - Only the app bundle in a job of its own would keep the upload key away from the debug build (alternatives).
- **The project's own upload script, no Action and no plugin.** `scripts/play-upload.sh` uses only bash, curl, jq and openssl from the runner: a JWT signed with the service account key, an edit, the resumable upload, a completed release, the commit. The key, the JWT, the access token and the upload URL never reach a command line or the log, live only in a private temporary folder that is deleted on exit, also when the job is cancelled (the workflow starts the script with `exec`, and it sets no INT or TERM trap, like `scripts/ci.sh`; an unfinished edit is then deleted too), and are also masked on GitHub Actions. Its test runs in CI against a local stand-in for Google's servers, with a throwaway key, and needs no network.
- **Internal testing only.** The script accepts no other track. The publish job needs the build job (tests, lint, 16 KB check), runs only for `main`, one at a time, and publishes only once the service account key (`PLAY_SERVICE_ACCOUNT_JSON_BASE64`) is set; it skips a bundle that is not newer than the track's and never cancels a review in progress (`changesInReviewBehavior=ERROR_IF_IN_REVIEW`). The commit is never retried; when no clear answer to it comes back (none, or a 5xx), the script says that the release may already be out, not that nothing was published. No release notes: their language must match the store listing, and the release name is the version name.
- **The publish job runs no Gradle.** It takes the build job's bundle with `actions/download-artifact` (GitHub's own, pinned by commit, the bundle kept as it is).
- **The Google Play key is a secret of the GitHub environment `google-play`.** Only jobs that use an environment can read its secrets, and only the publish job uses this one, so the build job, which runs Gradle with all its plugins and libraries and holds the upload key, can never get the Play key. The environment's deployment branches are limited to `main` before the secret is added (a run that names a missing environment creates it without any rule), so a workflow changed on another branch cannot reach the key either. No repository secret of that name may exist: any job of any branch could read it. The publish job's runner holds the key for all of its steps, checkout and download included; only the upload step has it in its environment, and the job's first step sees only whether it is set and says so. This keeps a compromised build from stealing the Play key; it cannot keep one from changing the bundle the publish job uploads. Cost: each publish job adds a deployment of `google-play` to the repository page.
- **First release by hand.** Google's API works only for an app whose first release was rolled out in Play Console; the owner does that once with a CI bundle and adds the service account key after it. Until then the publish job only says that no key is set and stays green. With the key set too early the publish job fails with a plain explanation: the build job stays green, and the run shows the failed job.
- **What can hold up a release on internal testing.** Declarations on Play Console's App content page: the foreground service declaration for Mapbox's location service, with a video of a drive being started, filled in before the first automatic upload at the latest (a third-party report saw an upload to internal testing through the API refused without it); the advertising ID question, which third-party pages say blocks apps that target Android 13 and newer until answered (the app has no advertising ID: No); and from stage 2 background location. Google reviews that declaration only with a bundle that requests the permission, and an API release of such a bundle fails until Google has approved it. So the permission stays in the debug build while stage 2 is built; then one bundle with it is rolled out by hand in Play Console together with the declaration, Play updates pause during the review, which can take several weeks, and new builds come through the debug APK until it is approved.
- **Alternatives:**
  - A third-party Action (r0adkll/upload-google-play) or Gradle Play Publisher: someone else's code holding the Play key or running inside the Gradle build that holds the upload key, and one more dependency to keep up with.
  - Internal app sharing: no automatic updates, links that expire, and another signing key, so its installs and the track's cannot update each other.
  - The release secrets in an environment too: only with the app bundle built in a job of its own, since a run of another branch would otherwise lose its debug APK. That job would also keep the upload key away from the code of the debug build. Not needed while only the owner can push.
  - The Play key as a repository secret that only the publish job names: GitHub then hands it to no other job, but only the workflow file keeps the build job from naming it, and a workflow changed on any branch could read it.
  - Workload Identity Federation instead of a service account key: no long-lived key at all, but more setup and not documented by Google for the Play API. A later hardening step that changes only the sign-in part of the script.
- **Cost:** two apps on the phone (the Play app and the debug APK), each one Mapbox monthly active user with its own token. Production needs a closed test with 12 testers for 14 days first (personal developer account), the store listing, the content rating and the Data safety form ([ROADMAP.md](ROADMAP.md), stage 3).

## 43. Streets lighter than the ground at night

- **Chosen (the owner left it to the build):** the night map has a graphite ground (`colorLand` `#1A1D21`) and buildings one small step above it (`colorBuildings` `#282B31`), and every road class is one step lighter than before: streets `#4A4F57`, trunk roads `#5A5F67`, freeways `#6A6F77`. As configured, a street stands about 2 to 1 against the ground. Mapbox's own ground, which the app left as it was, was only a little darker than the streets on the phone.
- **Why:** close in, as Large goes, a street is a plain band, since the map's road data ends at zoom 16 and draws no markings outside the areas of lane details (39). A band that stands out from the ground reads as a road, and the median of a divided road as a median, not as a block of houses.
- **These are the night look's colors, and a first setting.** The night light and the monochrome theme change the configured colors on their way to the screen, in a way only the phone shows (a blue-grey street showed as a plain grey). The owner judges them on the phone; a day look sets its own.
- **Water and parks moved with the ground:** `colorWater` `#080B0E` and `colorGreenspace` `#090C0A`, each about 1.16 to 1 below the ground, about as far below it as before. Left as they were, they would have been as dark as the darker ground, and the monochrome theme takes their hue, so a river or a park would have shown only by its label.
- **Unchanged:** the road surface under lane details (`colorHdRoads`, the street's grey now, darker than a freeway's), labels, and the route's amber, which stands about 4 to 1 against a street.
- **Alternatives:**
  - Mapbox's own ground and the darker streets, as before: the streets hardly part from the ground close in.
  - A theme of the app's own (a color table for the whole basemap): one more file to keep, and every color of the map changes with it.
  - An aerial photo at the close view: planned separately, with its own switch.
- **Cost:** the other routes' grey (`#6F747C`) came close to a freeway's, so on a freeway another route showed mostly by its dark casing. Their grey is now `#9AA1AB`, about 2 to 1 against a freeway and 3 to 1 against a street. One commit to undo.

## 44. Fixes from the drive logs

- **Chosen:**
  - **A drive forgets where the last one ended.** When a drive ends and its routes are cleared, the engine resets the navigator's trip session (`MapboxNavigation.resetTripSession`). The navigator keeps its last position from one trip session to the next, and a later drive of the same run began there, often far away, and was rerouted for nothing. By the SDK's sources the reset starts and ends no trip session, sets no routes and changes none of what Mapbox bills by; the SDK calls it itself at the start of every simulated drive. The map before a drive shows the phone's own location and does not change.
  - **Behind it, a guard in the app.** Until a drive's own first position has come in, an update that still carries an older one is left alone by the camera, the drawn car, the card and its Then chip, the lane choices and the start plan (`StaleStart`). It is told by when its position was measured and whether it was replayed, never by where it is. A simulated drive's replay stamps its positions ahead of the phone's clock, so the last one of such a drive can seem newer than the first position of a real drive started soon after; it carries the replay's mark, which the phone's own positions do not, so while the drive's newest position is the phone's own, a marked one is an old one. Such updates are still logged, marked `staleStart`.
  - **The preview's download goes on after Start** (41 changed). A preview is often open for a few seconds only, so a download ended at Start rarely finished, and a phone that lost its network soon after drove without the map ahead. It now runs until it is done, a newer preview takes the region over, or the drive ends.
  - **The debug log keeps closed days compressed** (20 changed). The camera and guidance lines of the newer builds fill 50 MB in a few days. Days that are over are gzipped in the background, the limit counts what is stored, and **Share debug log** still writes each day as plain text.
  - **One summary line per drive**, numbers only, and a month's trip count that says since when it runs when the app was installed during the month.
  - **The start line on Android 15 and newer** looks for this process's record among Android's newest, by its pid or by when the app was bound, or takes it as the start completes. Android notes a launch before the process has a pid, so its newest record was often not this one's, and no line was written.
- **Alternatives:**
  - Only the guard in the app, without the reset: the navigator's own reroute would still act on the old position.
  - Taking every replayed position for an old one in a real drive: a real drive fed by a mock location app, as from an external receiver, has the same mark on all its positions, and every update of it would be left alone.
  - Resetting the navigator again at the start of a real drive that follows a simulated one, as the SDK itself does when it changes from a replay to real positions: one more navigator call at a start, for a case the mark already covers.
  - Telling old updates by the moment the trip session started: it would also drop the phone's newest position when the location service hands over one taken just before Start, every update of a start without satellites, and every update of a simulated drive, whose positions are stamped ahead of the phone's clock.
  - Ending the preview's download at Start, as before: less mobile data for routes not taken, but it rarely saved anything.
  - Keeping the log as plain text and raising the limit: the same days take far more room on the phone.
- **Cost:** a download that goes on into the drive also loads the routes not taken, up to the region's limit, over mobile data; sharing the log takes a moment longer while compressed days are written out; one more navigator call at the end of each drive. None of it has run on a phone yet.

## 45. The replay slider of the simulated drive

- **Chosen:** during a simulated drive, a small slider over the guidance bar shows how far the drive has got and moves it forward or back along the route, as the owner asked.
  - **The whole route in the replay.** The SDK's replay decodes the route 100 km at a time and drops what it has played when it takes the next part, so only the part in hand could be sought in. It now decodes the whole route at once, as its own documentation offers, and the replayer holds the drive from start to end: about 3 MB of positions for a few hundred km, decoded once at the start, on the main thread as each part was before.
  - **Each seek as Mapbox asks for a large change of position:** the replay stops, the navigator forgets where the car was (`resetTripSession`, which the SDK's replay itself calls at the start of every simulated drive), and the replay goes to the new place and plays on. The routes and the trip session stay as they are, and nothing is billed. Back is as good as forward.
  - **No reroute after a seek.** How the navigator treats a jump is in its native code, not in the SDK's sources, and a reroute would be a route request. So from a drive's first seek its reroutes are off until the drive ends. A simulated drive follows its route and needs none; a drive without a seek reroutes as before.
  - **The new place first.** The updates from before a seek, and from the moment the navigator knows no place, are left alone by the screen (`SeekHold`, logged with `replaySeek`); the drawn car and the camera go to the new place at once and let go of the road they were on, and the Then chip and the lane choices start over there.
  - The slider counts the replay's time, so the played part grows at a steady pace, as a video's does. It ends 5 s before the destination, so the drive still arrives by itself. The dot glides between the drive's positions in the draw phase only and stops when they stop.
- **Alternatives:**
  - Keeping the 100 km parts and pushing positions up to the target before a seek: the session's decoding is private, and what it has played is already dropped, so it could never go back.
  - Forward seeks only, without the reset: the navigator's handling of a jump either way is not documented, and Mapbox's own advice for a large change of position is the reset.
  - Seeks with reroutes left on: probably harmless, but a seek must never cause a route request, and nothing in the sources rules one out.
- **Cost:** a pause at the start of a very long simulated drive while the whole route is decoded, and some MB of memory for it; after a seek the simulated drive cannot be rerouted. None of it has run on a phone yet.

## 46. One approach per freeway exit

- **Chosen:** on a freeway, the last part of a step before its exit or fork is one approach, the run-up ([CAMERA.md](CAMERA.md), The run-up to a freeway exit). It came out of the drive on which the owner missed an exit: the close view went in, out and in again on the approach, the card's lane row changed at each point on the way, each row with its own distance and together a set of conflicting orders, and the close view showed only a few seconds of road.
  - **One move in, held to the gore, with one latch.** A run-up begins when the exit is within reach, the close view's window plus 60 s of driving and at least 1,500 m, and a point on the way where the lane at the exit's side leaves the route (an edge point) lies ahead, or a lane choice is inside its window, or a run-up ended less than 3 s ago. It holds whatever the speed does until the exit is no longer ahead. One latch in the engine serves the card and both cameras, so they cannot disagree, and a new route, a short time off the route, a seek and the gore of a fork right before take it up again. A turn right after the exit keeps the view until the junction view frames the turn.
  - **The card's one row with the composed lane and its exact words.** On a run-up the card shows the next edge point's lanes with one lane lit: the lane that leads through every point after it to the exit, counted from the exit's edge backward, taking lanes added between two edge points to be on the far side and lanes gone at the exit's edge. From the last edge point on, it shows the exit's own lanes. The lanes to use are in a few words, "Right lane", "2 right", "2nd from right" and the like, only where the counts leave no doubt; the words never count down, never name the far side and never say "keep right", and they show whole or not at all. Before and outside run-ups the card is as before.
  - **Lanes for car pools and buses dim** in every lane row, and never lit while another lane can be used.
  - **The tilt and a floor of 12 s.** With Large, Smooth follow tilts the close view up to 55°, and both cameras with Large keep at least 12 s of road above the car at the highest speed of the last 10 s. The owner answered yes to the stronger tilt on 2026-11-11. Mapbox tuned keeps the SDK's 45° with the same floor, so its lanes are a third to a half narrower at speed; Default and Mapbox standard move as before.
  - **Log only at a diverge.** The debug log gains what the card drew (`lane_row`), every lane of each lane point, where the phone's own position lay against the matched one (`match.raw`), and whether the car took or missed each diverge (`diverge_taken`, `missed_maneuver`). Nothing acts on them: the app starts no reroute (13, 40).
  - **No speech.** The owner answered no to a spoken lane hint on 2026-11-11. The words are on the card and for the screen reader; the app makes no sound.
  - **The route line unchanged.** Its widths (39), colours, the maneuver arrow and the order of the layers stay as they are. The owner asked that nothing on the map fade or be removed.
- **Alternatives:**
  - A longer time between lane choices that keeps the view in: the view would still go in and out wherever two points lie farther apart, and the card's rows would still change at each.
  - A run-up with no limit on its reach: on a long step points where the lane at the exit's side leaves are common far from the exit (in Mapbox's test routes about half the freeway points more than 1 km out), so the view would stay close for minutes and a split far ahead would join the exit's approach.
  - The floor of road ahead alone, without the run-up: more road in each close view, but still several of them.
  - The lane at the largest count from the edge among the points ahead, as a first draft had it: after an edge point the lanes count from a new edge, and that draft could light one of the next point's leaving lanes under unchanged words.
  - A row from the step's first banner, wherever the exit is: on a long step it would light the far lane for kilometres, or move both ways at every earlier exit.
  - Fading the route line close in: withdrawn; the owner wants nothing on the map faded or removed.
  - Speaking the lanes to use: the owner said no; a phone's voice would also be another product's, and one from the network could send the text away.
  - A reroute started by the app when the car stays on the freeway past its exit: it would race the navigator's own, which starts in native code, and a wrong one shows a wrong route at the moment the driver is right. The new lines first show whether a rule could find such a car sooner without false alarms.
- **Why:** one decision before an exit gets one approach, one row with one lit lane and its words, and enough road ahead to see the exit coming.
- **Cost:**
  - **The route line's lane means nothing.** The line still follows the road's centre line, which on a freeway runs along one of its lanes, and at the gore it still cuts across every lane; a run-up holds the close view, with its line about a lane wide, for up to about 85 s. Until the app draws the lanes itself, the lane the line lies in means nothing, and the card's words are the instruction. The next drive asks at each exit which of the two was followed.
  - Where lanes are added between two edge points, a row lights a more careful lane than needed and has no words.
  - One switch from the exit's own lanes to the run-up's row, at least about a minute and a half before the exit.
  - Off the route for longer than the camera's hold before the new route: two approaches.
  - The tilt draws more map far ahead: more data and frame time, no new bill.
  - The lane that serves a split right after the exit is not shown; that is for the app's own drawing of the lanes.
  - None of it has run on a phone yet.

## 47. Spare tokens, switched by hand

- **Chosen (owner, 2026-11-11):** up to five Mapbox public tokens on the phone, each with a name the driver gives it, one of them active, chosen by hand in **Settings → Mapbox access token**: spare tokens just in case, for example to start a trip on the main token rather than the test one. The first is Main; after the update the one token entered before becomes Main.
  - **Never switched by the app.** Nothing switches on an error, a refused token, a count or anything else, and nothing compares or adds up free tiers. Mapbox's terms do not allow using several accounts to stay within a free monthly tier or to pay less; the list is only for choosing by hand which token is used.
  - **Never during a trip.** While guidance runs the active token can be neither switched nor deleted, and no token added; Settings says why. A change that arrives all the same waits for the trip to end, so a trip session runs on one token from its start to its end. A switch while routes are previewed closes the preview, whose routes were asked for with the other token.
  - **A switch recreates the map,** as replacing the token did (16). It asks for nothing and starts no trip session by itself: the next search, route request and map tile go out with the new token.
  - **Stored as before** (6), in the one DataStore file that backups and transfers leave out: the list and the active token's id. The first read after the update moves the old token into the list in two writes, the list first and the old key last, so a process that dies in between loses nothing and adds nothing twice. When the file cannot be written then, as on a full disk, the old token is used as Main all the same, and the move is written once a write works.
  - **Trip sessions by token.** Each session in the trip log keeps an id the app gave the token (a random one, or a fixed one for the token moved from before the update, the same at every attempt of the move), never the token, and **Settings → Debug** counts the month's sessions under each token's name. Sessions of deleted tokens count together as "Deleted token", those logged before the update as "Before tokens were named". The debug log names the token in use only by its place in the list and its name.
- **Alternatives:**
  - Moving on to the next token when one is refused or a count is reached: that spreads usage over accounts, which Mapbox's terms forbid, and the owner does not want it.
  - Keeping a deleted token's name for its past sessions: the names would have to outlive the tokens somewhere else, and the trip log comes back from a backup without the tokens anyway, with ids that then name nothing.
  - Showing the Mapbox account each token belongs to, read from the token itself: left out. Its tests would need token-shaped fixtures that secret scanners flag, the account name is personal, and the driver names each token.
  - Handing a switched token over in the middle of a trip: every SDK reads the token with each request, so the rest of the session (reroutes, traffic updates, tiles) would go to the other token.
- **Cost:** tokens are entered on each phone and in each app (the Play app and the debug app keep their own lists). A build from before the list, installed over a newer one, finds no token. Five at most.

## 48. Day and night

- **Chosen (owner):** the app is light by day and keeps today's dark look at night, on the phone: a bright Mapbox map with white panels and dark ink by day, the dark map with graphite panels at night (15, 36). The car followed its host until stage 2's step 1b; since then the car's map shows this same look (50).
  - **Auto by the sun at the phone's position,** the default: the day look when the sun's centre is 2.5 degrees below the horizon or higher, the night look at 3.5 degrees below or lower, and in between the look on screen stays, so it cannot flicker. That is a little after sunset and a little before sunrise. After a switch by the sun nothing switches back for 10 minutes. The sun is worked out for the newest position the app has: the car's, then the location service's last known one, and without any the meridian of the phone's time zone, where the darkest of five latitudes on it decides, since a dark screen by day can be read and a light one at night blinds. A better position arriving later can switch once. **Day** and **Night** by hand in **Settings → Day and night**, at once, until Auto is picked again. The phone's own dark theme, battery saver and the car's setting are no inputs.
  - **During guidance a switch by the sun waits for a quiet moment:** never in a tunnel, never in a close view of a junction or a lane choice, never within 300 m or 20 s before a maneuver or 6 s after one. After 10 minutes of waiting, tunnels included, the window narrows to 150 m or 10 s before and 3 s after, never to nothing; a standing car takes the switch at once outside a tunnel and a close view ([CAMERA.md](CAMERA.md), Day and night during guidance). The driver's own choice goes at once.
  - **Coming back on screen,** the look goes at once; near a turn, in a tunnel or a close view only the basemap's theme, the one write that redraws its tiles, waits for the next quiet moment.
  - **The start without a flash:** the first frame waits, 300 ms at most, until the look is decided and the screen is composed in it; the map takes the look as it is made, so its first tiles are drawn in it; the window's background and the bar icons follow the look. The system splash stays graphite in both looks, and from Android 13 the app leaves no screenshot in Recents, which then shows a graphite tile for it: a screenshot taken by day would light up a dark car when the app comes back at night. Below Android 13 Android keeps its screenshot.
  - **The switch is config writes** on the style the map shows and the route line's colours, all in one go, never a style reload, no trip call and no request. The theme swap redraws the basemap's tiles once.
  - **On the screen:** every colour is a token with a day and a night value, and all 48 of Material's colour roles are set, so none of Material's own lavender shows. Two ambers: the bright one for fills in both looks, and for amber text, arrows and thin marks a dark amber `#8A5A00` by day (the bright one on white is 2 to 1). Numbers read at a glance are near-black by day, green ones a darker green of their own, and a step heavier in both looks. The lanes to take differ from the others by shape too, a heavier arrow and a short bar under the lane, since by colour alone they differ too little in both looks. The card right after a new route is dimmed less by day (60 %) than at night (45 %), so its road stays readable on white.
  - **The debug log** says when the look changed and why (`look`, `look_theme`, `look_settled`; [CAMERA.md](CAMERA.md), Logging), with no position, no sun angle, no time of the sun and no time zone. A switch by the sun outside guidance writes no line, since its time alone would be the local dusk.
  - **The owner's answers of 2026-11-11** to four questions, all as proposed: by day the places' icons in Mapbox's colours, not one grey; with lane details by day the dark asphalt with white markings, not Standard's light road where they are faint; the plates that a later build floats over the map white by day, like the cards; and the numbers read at a glance a step heavier. Each can be undone in one commit.
- **Alternatives:**
  - The phone's own dark theme: on Android it changes only once the screen goes off, so a drive started before sunset never sees it; battery saver forces it at noon; and it is a preference for the screen, not the light outside.
  - Clock times, such as light from 7 to 19: wrong with the seasons, near the poles and when travelling.
  - Swapping whole styles, as Mapbox's own navigation screens do: a full reload at every change.
  - A pale or faded day map: the owner rejected it (36).
  - A night built from overrides on Standard's default theme: today's night would change, and that needs the owner's word.
  - The phone's wallpaper colours (dynamic colour): the colours mean things the map cannot follow.
  - An animated change of colours: the whole screen would be composed again on every frame of it.
  - A light sensor: the look would flip under bridges and trees, and the tunnel hold covers the one real case.
  - The equator as the time zone's guess: day long after dark at higher latitudes.
  - A cap that ends the hold after a while: a long tunnel that ends just before a ramp would send the switch right at the ramp.
- **Cost:**
  - A switch costs a few milliseconds on the main thread and one recomposition of the screen, twice a day (estimate, not measured), and the theme swap a redraw of the basemap's tiles, which may leave it blank for a moment; the holds keep it away from turns.
  - A cold start may wait up to 300 ms for its first frame; by day a start shows the graphite splash, and a start from Recents a graphite frame, before the light screen.
  - Recents shows a graphite tile instead of the map.
  - With location off for a long time, a summer afternoon can stay dark until a position arrives; Day by hand helps.
  - Until stage 2's step 1b the phone and the car could show different looks for some minutes at dusk, each following its own; since then they show one (50).
  - Until the maneuver card gets its own gear for Settings, the look can be chosen only before Start and after arrival.
  - None of it has run on a phone yet.

## 49. Android Auto in steps, the car screen first

- **Chosen (owner, 2026-11-12):** stage 2 is built in steps, and the first is the car screen "just like on the phone but on the Android Auto screen; no separate screens needed now". Step 1 shows the app's own map in the car, with the same scene as the phone (31), and the running drive on it: the phone card's maneuver, arrow, words and lane row, the Then step while the phone shows it, the arrival time, time and distance left, and End. A drive is still started on the phone, and the car says so when asked. Search and the route preview in the car, Start in the car with its guard (33), the turn notifications in the car, resume after a crash, shared links, the log upload and the Desktop Head Unit profiles come in later steps ([STAGE-2.md](STAGE-2.md), Step 1).
  - **One content, two screens.** The routing card is made from what the phone's card shows: the same lane row (`CardLanes`), the same Then rule, the same words, distances in feet and miles as the phone rounds them, and the same arrow shapes, which now live in `:core-nav` for both screens. What the car shows can never disagree with the phone.
  - **The car home screen is a navigation template in every state,** so that a later step pushes its search and preview screens on it and every flow ends on it.
  - **Every call into or out of the car code is caught** (32): one exception on the main thread would end the phone's guidance with the car's.
- **Alternative:** the whole stage at once, as designed. It would put Start in the car, with its open start problem on Android 14 and 15 (33), before the owner had seen the car screen at all, and the log upload and links before either.
- **Why:** the owner can drive with the car screen now, through the internal testing build (42), while the parts that need the first device test wait for it.
- **Cost:** in the car the next drive is chosen and started on the phone, when parked. The car's puck before Start may be missing or old with the phone locked and location only while in use, until "Allow all the time" comes.

## 50. The car's map follows the phone's look

- **Chosen (owner, 2026-11-13):** the car's map shows the app's one look, the phone's: Auto by the sun at the phone's position, or Day or Night by hand in Settings, switched at the same moments on both screens (48). On the first test with the car screen the phone showed the night look while the car's map, following its host, showed the day look; the owner wants the car to follow the phone. The host's own day and night (`CarContext.isDarkMode`) colour only the images the app draws for the host's routing card, the maneuver icon and the lanes image, which sit on the host's card; the debug log keeps the host's mode beside the map's look (`car_look`).
  - **One look, one loop.** The look is decided once for the process (`LookController`). The phone's activity and the car's session each keep it deciding while they are started, one loop when both are, and the car's session starts it when the car's host started the process with no activity. Both maps take every change through the same path, so a basemap's theme that waits for a quiet moment of the drive waits on both, and the car's map is made once the first look is decided, with that look.
  - **The car's view counts while the phone's is not on screen.** While the phone's activity is not started, the car camera's close view holds a switch as the phone camera's does; and Settings open over the phone's map lets nothing go early while the car's screen shows the map.
- **Alternatives:**
  - The host's mode, as step 1 had it (36), with its two stages against tunnel flips: what Google's car quality guidelines ask for, but the two screens differ at dusk, and whenever the car's own setting or light sensor says otherwise.
  - The host's mode, with Day or Night chosen by hand on the phone as an override.
  - The darker of the two.
- **Cost:** Google's car quality guidelines expect a dark map when the car says night (MR-1): a car that says night while the sun at the phone's position is still above the band, as in a garage, shows the day map. Internal testing is not reviewed against the guidelines (42), so this choice is to be looked at again before any closed or open testing of the car screen. The car's map switches by the phone's rules, at a quiet moment of the drive, not with the car's lights.
