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
  - The driver pastes a public token on first launch or in Settings; Settings shows it only masked. The typed value never enters saved instance state.
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
- This is not the Play release key (stage 3).

## 8. Continuous integration

- **Triggers and pinning:** GitHub Actions run on pushes to `main` and on demand. Every action is pinned to a commit SHA.
- **Where the logic lives:** the job only calls `scripts/ci.sh`, so build changes do not require editing the workflow file.
- **Checks:** build, unit tests, lint, and the 16 KB check, which covers both ELF LOAD alignment and zip alignment.
- **Artifact:** the debug APK is uploaded on every run and kept for 30 days.
- **Caching:** `setup-gradle` uses `cache-provider: basic`, which is open source with no extra terms. The default "enhanced" provider is proprietary. Cost: caches start cold after catalog changes.

## 9. No build-logic module yet

- **Chosen:** shared SDK levels live in the version catalog, and each module keeps a short `android {}` block.
- **Revisit:** move to convention plugins once modules repeat more configuration.

## 10. JUnit 6 (Jupiter) for all JVM tests

- **Chosen:** JUnit Platform in both JVM modules and Android local unit tests, with `useJUnitPlatform()` and the explicit `junit-platform-launcher` dependency that Gradle 9 requires.
- **Alternative:** JUnit 4, the AGP default.
- **Why:** parameterized and nested tests suit the camera scenarios planned for stage 4.

## 11. Packaging

- **ABIs:** only `arm64-v8a` (phones) and `x86_64` (emulator); 32-bit ABIs are dropped. The CI APK is arm64 only (`-Pcustommaps.abis=arm64-v8a`), about half the size, because it is meant for the phone.
- **Debug suffix:** debug builds use the application ID suffix `.debug`, so they install next to the Play build.
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

## 15. Night map from Mapbox Standard

- **Chosen:** Mapbox Standard with the night light preset, the monochrome theme and 3D buildings, and graphite road and water colours with amber routes. The configuration is applied when the style data loads, before the first tiles draw, so there is no daylight flash.
- **Alternative:** a custom style made in Mapbox Studio. It would have to be maintained in the Mapbox account, outside this repository.

## 16. One map per activity

- **Chosen:**
  - The map stays composed for the whole life of the activity. Settings opens on top of it.
  - The activity handles configuration changes such as resizing itself, so the map is not rebuilt for them.
  - A map created during a drive, after the app was reopened, draws the navigator's current route at once.
  - Replacing or removing the token recreates the activity, so a new map and navigator use the new token. Replacing it is blocked during guidance.
- **Why:** the Maps SDK releases a map only when its activity is destroyed, and rebuilding the navigator mid-drive would end the drive.

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
  - Every new trip session (its session id changes) is stored with its time and kind, up to 500 entries.
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
  - The oldest days are deleted past 50 MB. The log is never backed up or transferred. Clear deletes it together with the copies made for sharing.
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
- **Cost:** the bottom panels sit about 21 dp higher than before.

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
- **Cost:** Google Play asks for a background-location declaration, a video and a review (stage 3). A start guard still checks before every trip start, and a refused start goes back to the route preview instead of crashing.

## 34. Shared links and Google place pages

- **Chosen (owner):** coordinates in a link are used as they are. A Google Maps link without coordinates, which is what the Google Maps phone app shares, has its place page loaded once to read the exact coordinates; on by default, with a switch in Settings. The fallback is a Mapbox search near the place hint the link carries. A link that arrives during a drive offers to go there now, on the phone and in the car; the first answer on either screen wins.
- **Cost:** Google's terms do not allow reading its pages this way, and the page format can change at any time; the fallback keeps links working.

## 35. Trip logs go to a private GitHub repository

- **Chosen (owner):** each trip's debug log is uploaded when the trip ends, as one gzip-compressed JSON-lines file, to a private repository, with a fine-grained token that reaches only that repository (366 days, a reminder two weeks before it expires). Uploads run through WorkManager, so they survive network loss and process death; Settings has Upload now.
- **Guards:** the app refuses a public repository and classic tokens, never logs the token, and masks GitHub tokens in every log line.
- **Why:** the code repository is public, and the logs hold location history.

## 36. A bright day map in the car

- **Chosen (owner):** a bright, colourful day map by day and the existing dark map at night, following the car's day and night setting. The route stays amber.
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
  - **Large** is Default on the open road plus two close views. Before every turn the view goes onto the junction until a lane is 28 dp wide on the screen, about 4.4 mm. On a freeway, before a point where lanes leave the route, it goes in until a lane is 14 dp wide, with the tilt kept, about 25 s ahead. It comes back out after each.
- **Large is set by the lane's width on the screen, not by a zoom number,** so it looks the same at every latitude. It needs no tiles beyond those Default uses: Mapbox's tiles end at zoom 16, and a closer view draws them larger, online and offline.
- **The frame stays the navigation SDK's.** At a junction Large lets the SDK fit its own frame of the car and the junction closer and with less road after the turn. The camera takes that frame's zoom from where the SDK starts to frame the maneuver, and holds the open road at 16.35 itself.
- **Which freeway points count is one rule in `:core-nav`,** read from the lanes the route already carries, with no request: at least 3 lanes reach the point and lanes leave the route; where the route goes straight on, at least 2. A wide freeway where every lane goes on, and a single lane that turns into an exit, do not zoom, so the long freeway view the owner likes stays the rule. Both numbers are a first setting: every point with lanes is written to the debug log with its lane counts, counted or passed over, to tune them after a drive. The maneuver card shows the lanes of the same point.
- **Smooth follow and Mapbox tuned carry Large; Mapbox standard keeps its own zoom,** as the comparison style it is (26). Settings says so under the row.
- **Alternatives:**
  - A fixed higher zoom limit. It brings the whole town drive closer, not the approach to a turn, and the view jumps to it whenever only the car's position is framed.
  - A zoom that follows the speed. It has nothing to do with where a lane has to be chosen.
  - Going close on every wide freeway. It ends the long view ahead for no lane choice.
  - Mapbox's Electronic Horizon for lane counts. It is a beta whose pricing may change; the route's own data is free and says more.
  - Large as what a phone starts with. The owner chose Default, with Large one tap away.
- **Why:** the lanes are large exactly where a lane has to be chosen, and nowhere else.
- **Cost:** at a junction the view is only about 55 m wide; what comes after the turn is left to the Then chip (37). None of it has been on a drive yet; the numbers are in [CAMERA.md](CAMERA.md), Lane zoom.

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
