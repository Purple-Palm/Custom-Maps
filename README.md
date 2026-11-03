# Custom Maps

A personal navigation app for Android Auto, built on Mapbox. The phone app uses Jetpack Compose;
the car app uses the Android for Cars App Library with the navigation template.

**Status: stage 1, navigation on the phone.** The phone app shows a night map with your location,
finds addresses and places, offers routes with traffic and alternatives, and guides turn by turn
with lanes and arrival time. The car screen arrives in stage 2.

## Modules

| Module | Contents |
|---|---|
| `:core-nav` | Pure Kotlin: domain models (maneuvers, lanes, route progress, camera mode, distances) and the `NavigationEngine`, `MapRenderer`, `SearchProvider` and `GuidanceAudio` interfaces. No Android dependencies. |
| `:engine-mapbox` | Android library that implements those interfaces with the Mapbox Navigation SDK. Mapbox types never leave this module. |
| `:app` | The phone app: Compose UI, settings and the wiring of all modules. The car app service will live here too. |
| `:car` (stage 2) | Car screens built on the navigation template. |

Progress by stage is tracked in [docs/ROADMAP.md](docs/ROADMAP.md); design decisions and their
alternatives are recorded in [docs/DECISIONS.md](docs/DECISIONS.md).

## Requirements

- Android Studio Quail 4 (2026.1.4) or newer. Rabbit 1 and Rabbit 2 work too.
- JDK 21 to run Gradle. The JDK bundled with Android Studio is fine.
- Android SDK Platform 37 and Build-Tools 36.0.0. Android Studio offers to install them on the first sync.
- A phone with Android 8.0 or newer. The Desktop Head Unit needs Android 9 or newer.

## Mapbox access token

- The app needs a Mapbox **public** token (`pk.…`). Create one on the
  [Mapbox access tokens page](https://console.mapbox.com/account/access-tokens/); the default public scopes are enough.
- No build contains the token: not debug builds, not CI builds, not Play builds. Enter it in the
  app, either on first launch or later in **Settings → Mapbox access token**, where it can also be
  replaced or removed.
- The token is stored only on the phone, in the app's private storage. It is excluded from cloud
  backups and from device-to-device transfers, so after a reinstall or on a new phone it has to be
  entered again.
- Secret tokens (`sk.…`) are refused. Building the project needs no Mapbox token at all: the Mapbox
  Maven repository is public.

## Install a build from GitHub Actions

Every push to `main` builds a debug APK.

1. On GitHub, open **Actions → CI** and the latest successful run. Downloads need a signed-in
   GitHub account.
2. Under **Artifacts**, download `custommaps-debug-<run>-<commit>.apk`. It is the APK itself, not a zip.
3. Open the APK on the phone (download it there, or copy it over), and allow installing apps from
   that source when Android asks.
4. Launch Custom Maps and paste your Mapbox token.

Install newer builds over the installed one; do not uninstall first. Uninstalling deletes the
token, and every new installation counts as one more monthly active user at Mapbox. Updating in
place only works when all builds are signed with the same key, see the next section.
**Settings → About** shows the version and the commit of the installed build.

## Using the app

1. On first launch, paste the Mapbox token, then allow location access. The map centers on you.
   Routes and guidance need **precise** location; with approximate location only, the prompt at
   the bottom offers to switch. If Android has stopped asking, the prompt opens the app's system
   settings instead, where precise location can be turned on.
2. Type an address or a place name in the search field and pick a suggestion.
3. Tap **Directions**. Up to three routes appear: the chosen one in amber, turning orange and red
   where traffic is heavy, the alternatives in grey. Tap a route, on the map or in the list, to
   choose it.
4. Tap **Start**. On Android 13 and newer the app asks once to show notifications, for the trip
   notification. The maneuver card shows the next turn, its distance and the lanes; the bottom bar
   shows the arrival time, what is left, **Overview**/**Recenter** and **End**.
5. Guidance stops at the destination (**Done**) or with **End**. Both stop the trip session, and so
   does **End Navigation** in the trip notification.

The phone screen stays in portrait. If Mapbox refuses the token (mistyped or revoked), the map
stays empty and the app says so, with a button to Settings where the token can be replaced.

### Simulated drive

To test guidance without driving, turn on **Settings → Debug → Simulate drive**. **Start** then
becomes **Start simulation**: the position moves along the chosen route by itself, with the same
maneuver card, lanes and arrival. Each simulated drive is a trip session and is billed like a real
one; the debug section counts them with the rest.

### Debug log

The app records a detailed log on the phone, so a drive can be examined afterwards:

- every route request and answer, with each step: road, maneuver, lanes, distance, time and traffic;
- the route you picked, Start, End and arrival;
- every second of guidance: position, speed, the road you are on, the speed limit, the next
  maneuver and what is left;
- off-route moments and reroutes, searches, trip sessions and errors.

To hand it over, open **Settings → Debug → Share debug log** and pick an app in the share sheet,
for example Google Drive. Each day becomes one text file, `custom-maps-debug-<date>.txt`, with one
JSON event per line. The log never leaves the phone otherwise: it is kept out of backups and device
transfers, the oldest days are deleted once it passes 50 MB, and **Clear** deletes it together with
the copies made for sharing. It never contains the Mapbox token; token-shaped text is masked in
every line as a last safeguard.

### Backups

Android backs up only the app's settings and its trip session log. The token, the debug log and
the data the Mapbox SDKs keep on the phone (map and routing caches) stay out of cloud backups and
device transfers. The app keeps no search history.

## Shared debug signing key

Android installs an update over an existing app only if both are signed with the same key. Without
the setup below, every CI run signs the APK with a new temporary key, and each new build would have
to replace the app by uninstalling it first. This key is for debug builds only; the Play release
key is set up in stage 3.

1. Create the key. `keytool` ships with every JDK, including the one inside Android Studio
   (`<Android Studio>/jbr/bin/keytool`; on macOS
   `Android Studio.app/Contents/jbr/Contents/Home/bin/keytool`). It asks for a password twice:

   ```bash
   keytool -genkeypair -v -storetype PKCS12 -keystore custommaps-debug.p12 -alias custommaps-debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Custom Maps Debug"
   ```

   Keep `custommaps-debug.p12` outside the repository and the password in a password manager. In a
   PKCS12 keystore the key password is the same as the keystore password.

2. Encode the file as one line of base64.

   macOS, straight to the clipboard:

   ```bash
   base64 -i custommaps-debug.p12 | pbcopy
   ```

   Linux (copy the content of the file, then delete it):

   ```bash
   base64 -w 0 custommaps-debug.p12 > custommaps-debug.b64
   ```

   Windows PowerShell, straight to the clipboard:

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\custommaps-debug.p12")) | Set-Clipboard
   ```

3. On GitHub open the repository's **Settings → Secrets and variables → Actions → New repository
   secret** and create four secrets:

   | Name | Value |
   |---|---|
   | `DEBUG_KEYSTORE_BASE64` | the base64 text from step 2 |
   | `DEBUG_KEYSTORE_PASSWORD` | the password from step 1 |
   | `DEBUG_KEY_ALIAS` | `custommaps-debug` |
   | `DEBUG_KEY_PASSWORD` | the same password as `DEBUG_KEYSTORE_PASSWORD` |

4. Start a build: **Actions → CI → Run workflow**, or push a commit. From now on every APK is signed
   with this key. If a build signed with a temporary key is installed, uninstall it once and install
   the new one.

5. Optional: to let builds from Android Studio install over CI builds too, put the same four values
   into `local.properties` as shown in [local.properties.example](local.properties.example). Use an
   absolute path with forward slashes, also on Windows.

Set all four values or none: a partial setup fails the build with a message listing what is missing.

## Build and run locally

- Android Studio: open the project, wait for the Gradle sync, choose the `app` run configuration
  and your phone, then Run.
- Command line, with the phone connected over USB:

  ```bash
  ./gradlew installDebug
  ```

- Unit tests and lint:

  ```bash
  ./gradlew testDebugUnitTest :core-nav:test :app:lintDebug
  ```

- 16 KB page size check of the APK (Linux, needs `readelf`):

  ```bash
  scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/app-debug.apk
  ```

## Desktop Head Unit (DHU)

The car screens arrive in stage 2. Until then the DHU connects, but Android Auto does not list this
app yet. To prepare:

1. On the phone, enable the developer options and USB debugging, and update Android Auto. Then turn
   on Android Auto developer mode: open the Android Auto settings (on Android 10 and newer:
   **Settings → Apps → See all apps → Android Auto → Advanced → Additional settings in the app**),
   tap **Version and permission info** 10 times and confirm.
2. On the computer, install **Android Auto Desktop Head Unit Emulator** from Android Studio's
   **SDK Manager → SDK Tools**, or with `sdkmanager "extras;google;auto"`. It is installed into
   `<sdk>/extras/google/auto/`. On macOS and Linux make it executable with
   `chmod +x desktop-head-unit`. Linux also needs GLIBC 2.32 or newer and the `libc++1` and
   `libc++abi1` packages.
3. Connect the phone over USB and start the DHU from that directory in accessory mode (recommended):

   ```bash
   ./desktop-head-unit --usb
   ```

   Alternatively use ADB tunneling: in Android Auto's overflow menu choose **Start head unit
   server**, check that **Add new cars to Android Auto** is enabled, then run:

   ```bash
   adb forward tcp:5277 tcp:5277
   ```

   ```bash
   ./desktop-head-unit
   ```

4. Screen profiles for 800×480 and 1280×720 head units will be added under `dhu/` in stage 2 and
   used with `-c`, for example `./desktop-head-unit --usb -c /path/to/repo/dhu/800x480.ini`.
5. Switch between day and night with the `day` and `night` console commands, or Shift+N and Ctrl+N.

In a real car, a navigation app built with the Car App Library has to come from Google Play;
stage 3 sets up an internal testing track for that.

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push to `main` and on demand.
It builds the debug APK for arm64 phones (every current phone), runs the unit tests and lint, checks
16 KB page alignment and uploads the APK, unzipped, as an artifact that is kept for 30 days. Local
builds also include x86_64 for the emulator. The steps themselves live in
[`scripts/ci.sh`](scripts/ci.sh). CI needs no Mapbox token, and the debug signing secrets are optional.

## Mapbox usage

- Mapbox bills navigation by monthly active users and trips, with a free tier (see
  [mapbox.com/pricing](https://www.mapbox.com/pricing)). Free drive counts as a trip too.
- The app starts a trip session only when you press Start, once the navigator has the route, so a
  drive is one active guidance session and never a free drive first. It stops the session on
  arrival and on End; from stage 2 also when the car disconnects. Every start is logged, and **Settings → Debug** shows
  the count for the month, the latest starts and a link to the
  [Mapbox statistics page](https://console.mapbox.com/account/statistics/), which has the billed numbers.
- Route and search requests made outside a trip session are billed per request.
- Every installation counts as a user: install updates over the app instead of reinstalling it.

## Attribution

Maps from Mapbox must show the Mapbox logo and the © Mapbox © OpenStreetMap attribution, behind the
(i) button. This applies to every map made with Mapbox, free or paid, commercial or not. Mapbox
allows moving the logo but not resizing, recolouring or fading it. On the phone the logo and the (i)
button keep a fixed strip at the bottom left, above the navigation bar or the keyboard, and the
panels stay above it; the car screen gets the same rule in stage 2.
