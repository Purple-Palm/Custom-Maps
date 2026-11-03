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
  - It is stored in a dedicated DataStore file. That file is excluded from cloud backup and device transfer, and a unit test keeps the backup rules in sync with the file path.
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
  - End and arrival stop the trip session first and clear the routes after that. Clearing routes while a session runs would switch it to free drive, which Mapbox bills.
  - A dedicated lifecycle owner keeps the navigator alive only for the length of a drive. When the app goes to the background mid-drive, guidance carries on with the trip notification.
- **Alternatives:** Mapbox's ready-made navigation UI, or free drive just to show the location. Both start billed sessions on their own.
- **Why:** trip sessions are what Mapbox bills for navigation, and the rules are testable on the JVM this way.

## 14. Location before guidance comes from the platform

- **Chosen:** before Start, the puck uses the Maps SDK's platform location provider; during guidance it switches to the navigator's map-matched location.
- **Alternative:** free drive for map-matched location at all times. It is billed as a trip.
- **Cost:** no snapping to roads before Start.

## 15. Night map from Mapbox Standard

- **Chosen:** Mapbox Standard with the night light preset, the monochrome theme and 3D buildings, and graphite road and water colours with amber routes. The configuration is applied when the style data loads, before the first tiles draw, so there is no daylight flash.
- **Alternative:** a custom style made in Mapbox Studio. It would have to be maintained in the Mapbox account, outside this repository.

## 16. One map per activity

- **Chosen:**
  - The map stays composed for the whole life of the activity. Settings opens on top of it.
  - The activity handles rotation and resizing itself, so the map is not rebuilt when the phone turns.
  - Replacing or removing the token recreates the activity, so a new map and navigator use the new token. Replacing it is blocked during guidance.
- **Why:** the Maps SDK releases a map only when its activity is destroyed, and rebuilding the navigator mid-drive would end the drive.

## 17. Search

- **Chosen:**
  - Search Box API, which covers addresses and places (businesses, points of interest), limited to the US and English.
  - Requests start 300 ms after typing stops, from two characters on; at most 8 suggestions.
  - The search engine is recreated when the token changes.
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
